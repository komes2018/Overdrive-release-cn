package com.overdrive.app.notifications.sinks;

import android.util.Log;

import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 企业微信群机器人 Webhook 通知 Sink。
 *
 * <p>与 TelegramSink 过滤策略完全对齐，走相同的 WARN/CRITICAL 门控，
 * 但直接 HTTP POST 到企微 API（国内直连，无需代理/VPN）。
 *
 * <h3>配置文件</h3>
 * 在车机上执行：
 * <pre>
 *   echo "webhook_url=https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=你的KEY" \
 *       > /data/local/tmp/wecom_config.properties
 * </pre>
 *
 * <h3>支持的通知类型（车→手机）</h3>
 * <ul>
 *   <li>🚨 CRITICAL：充电故障、胎压告警、SOH 异常、守护进程崩溃、低电量</li>
 *   <li>⚠️ WARN：充电完成（满电）、胎压偏低</li>
 *   <li>🔔 automation.action：用户自定义自动化触发通知</li>
 * </ul>
 *
 * <h3>排除项（与 TelegramSink 一致）</h3>
 * <ul>
 *   <li>surveillance.*：摄像头哨兵事件由 WeComNotifier 直接投递，此处跳过避免重发</li>
 *   <li>vehicle.security.door.*：门锁开关仅 Web Push，不推企微</li>
 *   <li>INFO 级别常规遥测（充电开始/结束等）：不推送</li>
 * </ul>
 */
public final class WeComSink implements NotificationBus.Sink {

    private static final String TAG = "WeComSink";
    private static final String CONFIG_FILE = "/data/local/tmp/wecom_config.properties";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 8000;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WeComSink");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void onNotification(NotificationEvent event) {
        if (event == null) return;
        try {
            // 哨兵摄像头事件：由 WeComNotifier 直投，此处跳过避免重复
            if (event.category != null && event.category.startsWith("surveillance.")) return;

            // 门锁开关：Web Push 专属，不推企微
            if (event.category != null && event.category.startsWith("vehicle.security.door.")) return;

            // 胎压类告警：单独检查开关（企微 wecom 暂复用 Telegram tyre 开关）
            if (event.category != null && event.category.startsWith("vehicle.health.tyre.")) {
                try {
                    if (!com.overdrive.app.telegram.config.UnifiedTelegramConfig.isTyreAlerts()) return;
                } catch (Exception ignored) {}
            }

            // 只推 WARN/CRITICAL，以及用户自定义自动化通知
            boolean userAuthored = "automation.action".equals(event.category);
            if (event.severity == NotificationEvent.Severity.INFO && !userAuthored) return;

            // 组装消息文本
            String icon = event.severity == NotificationEvent.Severity.CRITICAL ? "🚨"
                    : (event.severity == NotificationEvent.Severity.INFO ? "🔔" : "⚠️");
            StringBuilder msg = new StringBuilder();
            msg.append(icon).append("【").append(safe(event.title)).append("】");
            if (event.body != null && !event.body.isEmpty()) {
                msg.append("\n").append(safe(event.body));
            }

            final String text = msg.toString();
            executor.execute(() -> sendText(text));

        } catch (Throwable t) {
            Log.w(TAG, "WeComSink forward failed: " + t.getMessage());
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
     * 企微限制：图片 Base64 不超过 2MB，文件不超过 20MB。
     */
    public static void sendImage(String base64Jpeg, String md5) {
        executor.execute(() -> doSendImage(base64Jpeg, md5));
    }

    // ==================== 内部实现 ====================

    private static void doSendText(String text) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("msgtype", "text");
            JSONObject textObj = new JSONObject();
            textObj.put("content", text);
            payload.put("text", textObj);
            post(payload.toString());
        } catch (Exception e) {
            Log.e(TAG, "doSendText failed: " + e.getMessage());
        }
    }

    private static void doSendMarkdown(String content) {
        if (content == null) return;
        // 企微机器人发送 markdown 时，手机微信个人端会显示“暂不支持此消息类型，点击前往企业微信查看”。
        // 将其轻量清洗为原生 text 格式，确保微信客户端直接可见。
        String clean = content.replaceAll("\\*\\*", "")
                .replaceAll("`", "")
                .replaceAll("^>\\s*", "• ")
                .replaceAll("\n>\\s*", "\n• ");
        doSendText(clean);
    }

    private static void doSendImage(String base64Jpeg, String md5) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("msgtype", "image");
            JSONObject img = new JSONObject();
            img.put("base64", base64Jpeg);
            img.put("md5", md5);
            payload.put("image", img);
            post(payload.toString());
        } catch (Exception e) {
            Log.e(TAG, "doSendImage failed: " + e.getMessage());
        }
    }

    /**
     * 读取 Webhook URL 并发起 HTTP POST。
     */
    private static void post(String jsonBody) {
        String webhookUrl = readWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            Log.w(TAG, "WeComSink: webhook_url 未配置，跳过推送。" +
                    "请执行: echo \"webhook_url=https://qyapi.weixin.qq.com/...\" > " + CONFIG_FILE);
            return;
        }
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
            if (code != 200) {
                Log.w(TAG, "WeComSink HTTP " + code);
            } else {
                Log.d(TAG, "WeComSink delivered OK");
            }
        } catch (Exception e) {
            Log.e(TAG, "WeComSink post error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 从配置文件读取 webhook_url，每次调用实时读取（支持热更新）。
     */
    static String readWebhookUrl() {
        // 1. 先尝试从设备配置文件读取（热更新，不用重新编译）
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

        // 2. 编译时内置（可选，留空则必须配置文件）
        String compiled = getCompiledWebhookUrl();
        if (compiled != null && !compiled.isEmpty()) return compiled;

        return null;
    }

    /**
     * 编译时内置 Webhook URL（避免明文可以留空，运行时用配置文件覆盖）。
     * 如果你不介意 APK 内包含 key，直接填写；否则留空用配置文件。
     */
    private static String getCompiledWebhookUrl() {
        // 留空：依赖 /data/local/tmp/wecom_config.properties
        return "";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
