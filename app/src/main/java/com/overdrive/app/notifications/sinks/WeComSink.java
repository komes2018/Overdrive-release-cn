package com.overdrive.app.notifications.sinks;

import android.util.Log;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 企业微信 / 通用 Webhook 机器人通知 Sink。
 *
 * <p>支持企业微信群机器人、钉钉机器人、飞书自定义机器人及通用 JSON Webhook，
 * 国内直连无需代理。
 */
public final class WeComSink implements NotificationBus.Sink {

    private static final String TAG = "WeComSink";
    public static final String CONFIG_FILE = "/data/local/tmp/wecom_config.properties";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 8000;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WeComSink");
        t.setDaemon(true);
        return t;
    });

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

            // 等级门控
            boolean userAuthored = "automation.action".equals(event.category);
            if (event.severity == NotificationEvent.Severity.CRITICAL) {
                if (!cfg.optBoolean("tierCritical", true)) return;
            } else if (event.severity == NotificationEvent.Severity.WARN) {
                if (!cfg.optBoolean("tierAlerts", true)) return;
            } else if (event.severity == NotificationEvent.Severity.INFO) {
                if (!userAuthored && !cfg.optBoolean("tierNotices", false)) return;
            }

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
     */
    public static void sendImage(String base64Jpeg, String md5) {
        executor.execute(() -> doSendImage(base64Jpeg, md5));
    }

    // ==================== 内部实现 ====================

    private static void doSendText(String text) {
        String webhookUrl = readWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) return;
        try {
            String payload = buildTextPayload(webhookUrl, text);
            post(webhookUrl, payload);
        } catch (Exception e) {
            Log.e(TAG, "doSendText failed: " + e.getMessage());
        }
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
            post(webhookUrl, payload.toString());
        } catch (Exception e) {
            Log.e(TAG, "doSendImage failed: " + e.getMessage());
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
     * 发起 HTTP POST 请求。
     */
    private static void post(String webhookUrl, String jsonBody) {
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
