package com.overdrive.app.server;

import com.overdrive.app.launcher.AppLauncher;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Installed-app enumeration + launch, shared by the automation "open app"
 * action and the key-mapping app picker.
 *
 * <ul>
 *   <li>GET  /api/apps/list   — {@code {success, apps:[{package,label}]}} launchable apps, sorted by label.</li>
 *   <li>POST /api/apps/launch — body {@code {package, split?, secondaryPackage?}} →
 *       launch one app, or a deterministic two-app split pair when both
 *       {@code split=true} and {@code secondaryPackage} are supplied. This is the endpoint
 *       an automation {@code ApiAction} and the keymap {@code openApp} action target
 *       (allowlisted in {@link HttpServer}).</li>
 * </ul>
 */
public final class AppsApiHandler {
    private static final DaemonLogger logger = DaemonLogger.getInstance("AppsApiHandler");

    private AppsApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        int q = path.indexOf('?');
        String cleanPath = q >= 0 ? path.substring(0, q) : path;

        if (cleanPath.equals("/api/apps/list") && method.equals("GET")) {
            handleList(out);
            return true;
        }
        if (cleanPath.equals("/api/apps/launch") && method.equals("POST")) {
            handleLaunch(out, body);
            return true;
        }
        return false;
    }

    private static void handleList(OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        try {
            JSONArray apps = AppLauncher.listLaunchableApps();
            resp.put("success", true);
            resp.put("apps", apps);
        } catch (Throwable t) {
            logger.warn("apps/list failed: " + t.getMessage());
            resp.put("success", false);
            resp.put("error", t.getMessage());
            resp.put("apps", new JSONArray());
        }
        HttpResponse.sendJson(out, resp.toString());
    }

    private static void handleLaunch(OutputStream out, String body) throws Exception {
        JSONObject resp = new JSONObject();
        try {
            JSONObject req = new JSONObject(body == null ? "{}" : body);
            String pkg = req.optString("package", null);
            if (pkg == null || pkg.trim().isEmpty()) {
                resp.put("success", false);
                resp.put("error", "Missing package");
                HttpResponse.sendJson(out, resp.toString());
                return;
            }
            // Optional split-screen dock: {"package":..,"split":true}. Defaults to
            // false (normal full-screen launch) so existing callers are unaffected.
            boolean split = req.optBoolean("split", false);
            boolean pairRequested = req.has("secondaryPackage");
            String secondaryPkg = req.optString("secondaryPackage", null);
            if (pairRequested && (secondaryPkg == null || secondaryPkg.trim().isEmpty())) {
                resp.put("success", false);
                resp.put("error", "Missing secondaryPackage");
                HttpResponse.sendJson(out, resp.toString());
                return;
            }
            if (pairRequested && !split) {
                resp.put("success", false);
                resp.put("error", "secondaryPackage requires split=true");
                HttpResponse.sendJson(out, resp.toString());
                return;
            }
            if (pairRequested && pkg.trim().equals(secondaryPkg.trim())) {
                resp.put("success", false);
                resp.put("error", "Split-screen apps must be different");
                HttpResponse.sendJson(out, resp.toString());
                return;
            }
            boolean ok = pairRequested
                    ? AppLauncher.launchSplitPair(pkg, secondaryPkg)
                    : AppLauncher.launch(pkg, split);
            resp.put("success", ok);
            if (!ok) {
                resp.put("error", pairRequested
                        ? "Could not launch split-screen app pair"
                        : "Could not launch " + pkg);
            }
        } catch (Throwable t) {
            logger.warn("apps/launch failed: " + t.getMessage());
            resp.put("success", false);
            resp.put("error", t.getMessage());
        }
        HttpResponse.sendJson(out, resp.toString());
    }
}
