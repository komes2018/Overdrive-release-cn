package com.overdrive.app.server;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.notifications.sinks.WeComSink;

import org.json.JSONObject;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * REST API handler for WeCom / Webhook delivery settings.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET  /api/notifications/wecom       — Get current webhook settings</li>
 *   <li>POST /api/notifications/wecom       — Save webhook settings</li>
 *   <li>POST /api/notifications/wecom/test  — Send a test notification</li>
 * </ul>
 */
public final class WeComApiHandler {

    private WeComApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path;
        int q = cleanPath.indexOf('?');
        if (q >= 0) cleanPath = cleanPath.substring(0, q);

        if (cleanPath.equals("/api/notifications/wecom") || cleanPath.equals("/api/webhook/config")) {
            if ("GET".equalsIgnoreCase(method)) {
                handleGet(out);
                return true;
            } else if ("POST".equalsIgnoreCase(method)) {
                handlePost(out, body);
                return true;
            }
        }

        if ((cleanPath.equals("/api/notifications/wecom/test") || cleanPath.equals("/api/webhook/test"))
                && "POST".equalsIgnoreCase(method)) {
            handleTest(out, body);
            return true;
        }

        return false;
    }

    private static void handleGet(OutputStream out) throws Exception {
        UnifiedConfigManager.forceReload();
        JSONObject cfg = UnifiedConfigManager.getWeCom();
        String url = cfg.optString("url", "").trim();
        if (url.isEmpty()) {
            String fallback = WeComSink.readWebhookUrl();
            if (fallback != null) url = fallback;
        }

        boolean enabled = cfg.optBoolean("enabled", !url.isEmpty());
        boolean tierNotices = cfg.optBoolean("tierNotices", false);
        boolean tierAlerts = cfg.optBoolean("tierAlerts", true);
        boolean tierCritical = cfg.optBoolean("tierCritical", true);
        boolean motionImages = cfg.optBoolean("motionImages", true);
        boolean charging = cfg.optBoolean("charging", true);
        boolean tyre = cfg.optBoolean("tyre", true);

        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("enabled", enabled);
        resp.put("url", url);
        resp.put("hasUrl", !url.isEmpty());
        resp.put("tierNotices", tierNotices);
        resp.put("tierAlerts", tierAlerts);
        resp.put("tierCritical", tierCritical);
        resp.put("motionImages", motionImages);
        resp.put("charging", charging);
        resp.put("tyre", tyre);

        HttpResponse.sendJson(out, resp.toString());
    }

    private static void handlePost(OutputStream out, String body) throws Exception {
        JSONObject resp = new JSONObject();
        try {
            JSONObject req = new JSONObject(body == null ? "{}" : body);
            Map<String, Object> values = new HashMap<>();

            if (req.has("enabled")) values.put("enabled", req.getBoolean("enabled"));
            if (req.has("url")) {
                String newUrl = req.getString("url").trim();
                values.put("url", newUrl);
                WeComSink.persistWebhookUrl(newUrl);
            }
            if (req.has("tierNotices")) values.put("tierNotices", req.getBoolean("tierNotices"));
            if (req.has("tierAlerts")) values.put("tierAlerts", req.getBoolean("tierAlerts"));
            if (req.has("tierCritical")) values.put("tierCritical", req.getBoolean("tierCritical"));
            if (req.has("motionImages")) values.put("motionImages", req.getBoolean("motionImages"));
            if (req.has("charging")) values.put("charging", req.getBoolean("charging"));
            if (req.has("tyre")) values.put("tyre", req.getBoolean("tyre"));

            UnifiedConfigManager.setWeComValues(values);

            resp.put("success", true);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
            HttpResponse.sendJson(out, resp.toString());
        }
    }

    private static void handleTest(OutputStream out, String body) throws Exception {
        JSONObject resp = new JSONObject();
        try {
            JSONObject req = new JSONObject(body == null ? "{}" : body);
            String targetUrl = req.optString("url", "").trim();
            if (targetUrl.isEmpty()) {
                targetUrl = WeComSink.readWebhookUrl();
            }

            if (targetUrl == null || targetUrl.isEmpty()) {
                resp.put("success", false);
                resp.put("error", "未配置 Webhook 地址");
                HttpResponse.sendJson(out, resp.toString());
                return;
            }

            String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            String testMsg = "🔔【OverDrive 测试通知】\n"
                    + "企业微信 Webhook 通道测试成功！\n"
                    + "• 发送时间：" + now + "\n"
                    + "• 目标端点：已成功接收并响应\n"
                    + "• 当前环境：车机后台已就绪";

            String error = WeComSink.sendTestMessage(targetUrl, testMsg);
            if (error == null) {
                resp.put("success", true);
                resp.put("message", "测试消息发送成功");
            } else {
                resp.put("success", false);
                resp.put("error", error);
            }
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
            HttpResponse.sendJson(out, resp.toString());
        }
    }
}
