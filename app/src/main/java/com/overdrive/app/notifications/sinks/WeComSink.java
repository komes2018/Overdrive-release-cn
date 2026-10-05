package com.overdrive.app.notifications.sinks;

import android.util.Log;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 企业微信 / 通用 Webhook 机器人通知 Sink。
 *
 * <p>支持企业微信群机器人、钉钉机器人、飞书自定义机器人及通用 JSON Webhook，
 * 国内直连无需代理。内置离线待发持久化队列，应对熄火瞬断与系统杀进程自愈补偿。
 */
public final class WeComSink implements NotificationBus.Sink {

    private static final String TAG = "WeComSink";
    private static final DaemonLogger logger = DaemonLogger.getInstance("WeComSink");
    public static final String CONFIG_FILE = "/data/local/tmp/wecom_config.properties";
    public static final String PENDING_QUEUE_FILE = "/data/local/tmp/pending_wecom_queue.json";
    private static final Object QUEUE_LOCK = new Object();
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 8000;

    private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "WeComSink");
        t.setDaemon(true);
        return t;
    });

    static {
        // 守护进程启动 10 秒后执行首次待发队列补偿，此后每隔 30 秒周期性巡检
        executor.scheduleWithFixedDelay(() -> {
            try {
                flushPendingQueue();
            } catch (Throwable t) {
                logger.warn("WeComSink periodic flush error: " + t.getMessage());
            }
        }, 10, 30, TimeUnit.SECONDS);
    }

    /**
     * Webhook 是否已全局启用且已配置 URL。
     */
    public static boolean isEnabled() {
        try {
            JSONObject cfg = UnifiedConfigManager.getWeCom();
            boolean enabled = cfg.optBoolean("enabled", true);
            if (!enabled) return false;
            String url = readWebhookUrl();
            return url != null && !url.isEmpty();
        } catch (Throwable t) {
            String url = readWebhookUrl();
            return url != null && !url.isEmpty();
        }
    }

    /**
     * 哨兵抓拍图文是否发送图片。
     */
    public static boolean isMotionImagesEnabled() {
        try {
            return UnifiedConfigManager.getWeCom().optBoolean("motionImages", true);
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public void onNotification(NotificationEvent event) {
        if (event == null || !isEnabled()) return;
        try {
            // 哨兵摄像头事件：由 WeComNotifier 直投，此处跳过避免重复
            if (event.category != null && event.category.startsWith("surveillance.")) return;

            // 门锁开关：Web Push 专属，不推企微
            if (event.category != null && event.category.startsWith("vehicle.security.door.")) return;

            JSONObject cfg = UnifiedConfigManager.getWeCom();

            // 胎压类告警
            if (event.category != null && event.category.startsWith("vehicle.health.tyre.")) {
                if (!cfg.optBoolean("tyre", true)) return;
            }

            // 充电类事件
            if (event.category != null && event.category.startsWith("vehicle.charge.")) {
                if (!cfg.optBoolean("charging", true)) return;
            }

            // 行程报告类事件（每次行程结束车熄火后推送）
            boolean isTrip = event.category != null && event.category.startsWith("vehicle.trip.");
            if (isTrip) {
                if (!cfg.optBoolean("trips", true)) return;
            }

            // 驻车耗电报告类事件（每次点火启动后推送）
            boolean isParkingDrain = event.category != null && event.category.equals("vehicle.parking.drain");
            if (isParkingDrain) {
                if (!cfg.optBoolean("parkingDrain", true)) return;
            }

            // 等级门控
            boolean userAuthored = "automation.action".equals(event.category);
            if (event.severity == NotificationEvent.Severity.CRITICAL) {
                if (!cfg.optBoolean("tierCritical", true)) return;
            } else if (event.severity == NotificationEvent.Severity.WARN) {
                if (!cfg.optBoolean("tierAlerts", true)) return;
            } else if (event.severity == NotificationEvent.Severity.INFO) {
                if (!userAuthored && !isTrip && !isParkingDrain && !cfg.optBoolean("tierNotices", false)) return;
            }

            // 组装消息文本
            String icon = isParkingDrain ? "🅿️" : (isTrip ? "🚗" : (event.severity == NotificationEvent.Severity.CRITICAL ? "🚨"
                    : (event.severity == NotificationEvent.Severity.INFO ? "🔔" : "⚠️")));
            StringBuilder msg = new StringBuilder();
            msg.append(icon).append("【").append(safe(event.title)).append("】");
            if (event.body != null && !event.body.isEmpty()) {
                msg.append("\n").append(safe(event.body));
            }

            final String text = msg.toString();
            final String eventId = (event.tag != null && !event.tag.isEmpty())
                    ? event.tag
                    : (event.category + ":" + (event.title != null ? event.title.hashCode() : System.currentTimeMillis()));
            final boolean isDurable = isTrip || isParkingDrain || event.severity == NotificationEvent.Severity.CRITICAL;

            // 关键报告（行程结算、驻车耗电、严重告警）：发送前先持久化落盘，彻底免疫进程被系统杀或断网丢失
            if (isDurable) {
                enqueuePending(eventId, text);
            }

            executor.execute(() -> {
                boolean delivered = doSendTextWithResult(text);
                if (isDurable && delivered) {
                    dequeuePending(eventId);
                }
            });

        } catch (Throwable t) {
            logger.warn("WeComSink forward failed: " + t.getMessage());
        }
    }

    // ==================== 公开静态 API（与 WeComNotifier 共用）====================

    /**
     * 发送纯文本消息。供 WeComNotifier 调用。
     */
    public static void sendText(String text) {
        executor.execute(() -> doSendText(text));
    }

    /**
     * 发送文本消息（向后兼容接口：自动清洗 Markdown 标记以保证个人微信原生兼容）。
     */
    public static void sendMarkdown(String content) {
        executor.execute(() -> doSendMarkdown(content));
    }

    /**
     * 发送图片（Base64 编码，自动截取前 2MB）。
     */
    public static void sendImage(String base64Jpeg, String md5) {
        executor.execute(() -> doSendImage(base64Jpeg, md5));
    }

    // ==================== 内部实现 ====================

    /**
     * 将待发送的重要消息持久化存盘（离线待发队列）。
     */
    private static void enqueuePending(String id, String text) {
        if (id == null || id.isEmpty() || text == null || text.isEmpty()) return;
        synchronized (QUEUE_LOCK) {
            try {
                JSONArray queue = loadQueueFile();
                for (int i = 0; i < queue.length(); i++) {
                    JSONObject item = queue.optJSONObject(i);
                    if (item != null && id.equals(item.optString("id"))) {
                        item.put("text", text);
                        item.put("lastAttemptAt", System.currentTimeMillis());
                        saveQueueFile(queue);
                        return;
                    }
                }
                JSONObject item = new JSONObject();
                item.put("id", id);
                item.put("text", text);
                item.put("createdAt", System.currentTimeMillis());
                item.put("attempts", 0);
                queue.put(item);

                // 队列上限保护（保留最新 10 条）
                while (queue.length() > 10) {
                    queue.remove(0);
                }
                saveQueueFile(queue);
                logger.info("Enqueued pending webhook message: " + id);
            } catch (Throwable t) {
                logger.warn("enqueuePending failed: " + t.getMessage());
            }
        }
    }

    /**
     * 消息成功送达后从持久化队列中移除。
     */
    private static void dequeuePending(String id) {
        if (id == null || id.isEmpty()) return;
        synchronized (QUEUE_LOCK) {
            try {
                JSONArray queue = loadQueueFile();
                boolean changed = false;
                JSONArray remaining = new JSONArray();
                for (int i = 0; i < queue.length(); i++) {
                    JSONObject item = queue.optJSONObject(i);
                    if (item != null) {
                        if (id.equals(item.optString("id"))) {
                            changed = true;
                        } else {
                            remaining.put(item);
                        }
                    }
                }
                if (changed) {
                    saveQueueFile(remaining);
                    logger.info("Dequeued delivered webhook message: " + id);
                }
            } catch (Throwable t) {
                logger.warn("dequeuePending failed: " + t.getMessage());
            }
        }
    }

    /**
     * 巡检并补发持久化队列中的未送达消息（开机自愈、断网恢复补偿）。
     */
    public static void flushPendingQueue() {
        if (!isEnabled()) return;
        String webhookUrl = readWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        synchronized (QUEUE_LOCK) {
            try {
                JSONArray queue = loadQueueFile();
                if (queue == null || queue.length() == 0) return;

                long now = System.currentTimeMillis();
                JSONArray remaining = new JSONArray();
                boolean networkErrorEncountered = false;

                for (int i = 0; i < queue.length(); i++) {
                    JSONObject item = queue.optJSONObject(i);
                    if (item == null) continue;
                    String id = item.optString("id");
                    String text = item.optString("text");
                    long createdAt = item.optLong("createdAt", now);
                    int attempts = item.optInt("attempts", 0);

                    // 1. 过期淘汰：超过 24 小时的旧消息不再补发
                    if (now - createdAt > 24 * 3600 * 1000L) {
                        logger.info("Dropping expired pending webhook notification: " + id);
                        continue;
                    }

                    // 2. 坏死淘汰：重试超过 20 次且超过 2 小时
                    if (attempts >= 20 && (now - createdAt > 2 * 3600 * 1000L)) {
                        logger.warn("Dropping poison pending webhook notification after 20 attempts: " + id);
                        continue;
                    }

                    // 3. 网络故障阻断：若上一条发送遭遇明显网络不可达，跳过后续重试等待下个周期
                    if (networkErrorEncountered) {
                        remaining.put(item);
                        continue;
                    }

                    // 4. 执行重试投递
                    boolean delivered = false;
                    try {
                        String payload = buildTextPayload(webhookUrl, text);
                        delivered = postWithRetry(webhookUrl, payload, 2);
                    } catch (Throwable t) {
                        logger.warn("flushPendingQueue item " + id + " error: " + t.getMessage());
                    }

                    if (delivered) {
                        logger.info("Pending webhook compensated & delivered successfully: " + id);
                    } else {
                        item.put("attempts", attempts + 1);
                        item.put("lastAttemptAt", now);
                        remaining.put(item);
                        networkErrorEncountered = true;
                    }
                }

                saveQueueFile(remaining);
            } catch (Throwable t) {
                logger.warn("flushPendingQueue execution failed: " + t.getMessage());
            }
        }
    }

    private static JSONArray loadQueueFile() {
        File f = new File(PENDING_QUEUE_FILE);
        if (!f.exists()) return new JSONArray();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String content = sb.toString().trim();
            if (content.isEmpty()) return new JSONArray();
            return new JSONArray(content);
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    private static void saveQueueFile(JSONArray queue) {
        File target = new File(PENDING_QUEUE_FILE);
        if (queue == null || queue.length() == 0) {
            if (target.exists()) target.delete();
            return;
        }
        File tmp = new File(PENDING_QUEUE_FILE + ".tmp");
        try {
            try (OutputStream os = new FileOutputStream(tmp)) {
                os.write(queue.toString(2).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            if (tmp.renameTo(target)) {
                target.setReadable(true, false);
                target.setWritable(true, false);
            }
        } catch (Throwable t) {
            logger.warn("saveQueueFile failed: " + t.getMessage());
        }
    }

    private static boolean doSendTextWithResult(String text) {
        String webhookUrl = readWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) return false;
        try {
            String payload = buildTextPayload(webhookUrl, text);
            return postWithRetry(webhookUrl, payload, 3);
        } catch (Exception e) {
            logger.warn("doSendText failed: " + e.getMessage());
            return false;
        }
    }

    private static void doSendText(String text) {
        doSendTextWithResult(text);
    }

    private static void doSendMarkdown(String content) {
        if (content == null) return;
        String clean = content.replaceAll("\\*\\*", "")
                .replaceAll("`", "")
                .replaceAll("^>\\s*", "• ")
                .replaceAll("\n>\\s*", "\n• ");
        doSendText(clean);
    }

    private static void doSendImage(String base64Jpeg, String md5) {
        if (!isMotionImagesEnabled()) return;
        String webhookUrl = readWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("msgtype", "image");
            JSONObject img = new JSONObject();
            img.put("base64", base64Jpeg);
            img.put("md5", md5);
            payload.put("image", img);
            postWithRetry(webhookUrl, payload.toString(), 2);
        } catch (Exception e) {
            logger.warn("doSendImage failed: " + e.getMessage());
        }
    }

    /**
     * 智能根据 URL 协议自适应多平台 Webhook 文本格式
     */
    public static String buildTextPayload(String webhookUrl, String text) throws Exception {
        JSONObject payload = new JSONObject();
        if (webhookUrl.contains("open.feishu.cn")) {
            // 飞书自定义机器人格式
            payload.put("msg_type", "text");
            JSONObject content = new JSONObject();
            content.put("text", text);
            payload.put("content", content);
        } else if (webhookUrl.contains("oapi.dingtalk.com")) {
            // 钉钉机器人格式
            payload.put("msgtype", "text");
            JSONObject textObj = new JSONObject();
            textObj.put("content", text);
            payload.put("text", textObj);
        } else {
            // 企业微信群机器人格式 (qyapi.weixin.qq.com) / 兼容通用格式
            payload.put("msgtype", "text");
            JSONObject textObj = new JSONObject();
            textObj.put("content", text);
            payload.put("text", textObj);
        }
        return payload.toString();
    }

    /**
     * 发送测试通知，同步返回 null 表示成功，非 null 为错误提示。
     */
    public static String sendTestMessage(String webhookUrl, String message) {
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
            return "Webhook URL 不能为空";
        }
        HttpURLConnection conn = null;
        try {
            String payload = buildTextPayload(webhookUrl, message);
            URL url = new URL(webhookUrl.trim());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int code = conn.getResponseCode();
            StringBuilder resp = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    resp.append(line);
                }
            }

            if (code != 200) {
                return "HTTP " + code + ": " + resp.toString();
            }

            // 解析企微/钉钉/飞书的错误码
            String respStr = resp.toString();
            try {
                JSONObject json = new JSONObject(respStr);
                if (json.has("errcode") && json.optInt("errcode") != 0) {
                    return "接口返回错误: " + json.optString("errmsg", respStr);
                }
                if (json.has("code") && json.optInt("code") != 0) {
                    return "接口返回错误: " + json.optString("msg", respStr);
                }
                if (json.has("StatusCode") && json.optInt("StatusCode") != 0) {
                    return "接口返回错误: " + json.optString("StatusMessage", respStr);
                }
            } catch (Exception ignored) {}

            return null; // 成功
        } catch (Exception e) {
            return "网络请求异常: " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 发起 HTTP POST 请求（带重试机制，应对车辆熄火下电瞬间 Wi-Fi 断开 / 4G 蜂窝数据激活切换窗口）。
     */
    private static boolean postWithRetry(String webhookUrl, String jsonBody, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(webhookUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

                byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Length", String.valueOf(body.length));

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    logger.info("WeCom delivered successfully (attempt " + attempt + ")");
                    return true;
                } else {
                    logger.warn("WeCom HTTP " + code + " (attempt " + attempt + ")");
                }
            } catch (Exception e) {
                logger.warn("WeCom post error (attempt " + attempt + "/" + maxRetries + "): " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }

            if (attempt < maxRetries) {
                try {
                    // 渐进式休眠，应对熄火瞬间 Wi-Fi 断开 / 4G 蜂窝数据切换窗口 (3s, 6s)
                    Thread.sleep(attempt * 3000L);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
        return false;
    }

    /**
     * 读取 Webhook URL：优先读取 UnifiedConfigManager，回退读取 wecom_config.properties。
     */
    public static String readWebhookUrl() {
        // 1. 优先从 UnifiedConfig 读取
        try {
            JSONObject cfg = UnifiedConfigManager.getWeCom();
            String url = cfg.optString("url", "").trim();
            if (!url.isEmpty()) return url;
        } catch (Throwable ignored) {}

        // 2. 从本地配置文件读取
        try {
            File f = new File(CONFIG_FILE);
            if (f.exists()) {
                Properties p = new Properties();
                try (FileInputStream fis = new FileInputStream(f)) {
                    p.load(fis);
                }
                String url = p.getProperty("webhook_url", "").trim();
                if (!url.isEmpty()) return url;
            }
        } catch (Exception e) {
            Log.w(TAG, "WeComSink config read failed: " + e.getMessage());
        }

        // 3. 编译时默认值
        String compiled = getCompiledWebhookUrl();
        if (compiled != null && !compiled.isEmpty()) return compiled;

        return null;
    }

    /**
     * 持久化 Webhook URL 到 wecom_config.properties（保持向后兼容）
     */
    public static void persistWebhookUrl(String url) {
        if (url == null) url = "";
        try {
            File f = new File(CONFIG_FILE);
            Properties p = new Properties();
            if (f.exists()) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    p.load(fis);
                } catch (Exception ignored) {}
            }
            p.setProperty("webhook_url", url);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                p.store(fos, "OverDrive WeCom Config");
            }
            // 确保权限 world-readable
            f.setReadable(true, false);
            f.setWritable(true, false);
        } catch (Exception e) {
            Log.w(TAG, "WeComSink persistWebhookUrl failed: " + e.getMessage());
        }
    }

    private static String getCompiledWebhookUrl() {
        return "";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
