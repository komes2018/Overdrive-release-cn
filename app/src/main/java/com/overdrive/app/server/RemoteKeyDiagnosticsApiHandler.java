package com.overdrive.app.server;

import com.overdrive.app.byd.RemoteKeyControl;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.OutputStream;

/**
 * Remote-key diagnostics API — the experimental command-format selector and the MCU readiness
 * gate toggle, plus a read-only status snapshot. Two settings, both under the {@code vehicle}
 * section:
 * <ul>
 *   <li>{@code remoteKeyFrameFormat} = {@code "fixed"} (default) | {@code "dynamic"}</li>
 *   <li>{@code remoteKeyMcuReadyGateDisabled} = boolean (default false → gate ON). Exposed to the
 *       UI as {@code mcuReadyGate} (enabled), i.e. the inverse of the stored flag.</li>
 * </ul>
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/debug/remote-key} — current format, gate state, live MCU status, rail enabled</li>
 *   <li>{@code POST /api/debug/remote-key} — persist a full or partial update, validated strictly</li>
 * </ul>
 *
 * <p>Security: routed from {@code HttpServer.routeToHandlers}, so it inherits the exact same
 * {@code AuthMiddleware.checkAuth} gate as every other {@code /api/*} settings route — a verified
 * JWT for LAN/tunnel callers, the loopback same-device safety net otherwise. It adds no listening
 * socket or port and weakens no existing auth. Input is validated strictly: the format must be one
 * of the two enum strings and the gate flag must be a real JSON boolean, or the request is rejected.
 */
public class RemoteKeyDiagnosticsApiHandler {

    private static final String TAG = "RemoteKeyDiagnosticsApiHandler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String PATH = "/api/debug/remote-key";

    public static boolean handle(String method, String path, String body, OutputStream out)
            throws Exception {
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
        if (!pathOnly.equals(PATH)) {
            return false;
        }
        if ("GET".equals(method)) {
            handleGet(out, PRODUCTION);
            return true;
        }
        if ("POST".equals(method)) {
            handlePost(out, body, PRODUCTION);
            return true;
        }
        return false;
    }

    /**
     * Config read/write seam. Production delegates to {@link RemoteKeyControl} and
     * {@link UnifiedConfigManager}; the unit test injects a fake so the handler's GET/POST branches
     * run without a live config store or an Android {@code Context}.
     */
    interface Config {
        String frameFormat();
        boolean mcuReadyGate();
        boolean localRailEnabled();
        int mcuStatus();
        /** Persist the {@code vehicle}-section patch (per-key merge); true on success. */
        boolean save(JSONObject patch);
    }

    private static final Config PRODUCTION = new Config() {
        public String frameFormat() { return RemoteKeyControl.frameFormat(); }
        public boolean mcuReadyGate() { return RemoteKeyControl.isMcuReadyGateEnabled(); }
        public boolean localRailEnabled() { return RemoteKeyControl.isEnabled(); }
        public int mcuStatus() {
            return RemoteKeyControl.readMcuStatus(CameraDaemon.getAppContext());
        }
        public boolean save(JSONObject patch) {
            return UnifiedConfigManager.runUnderConfigLock(
                    () -> UnifiedConfigManager.setVehicle(patch));
        }
    };

    static void handleGet(OutputStream out, Config config) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("frameFormat", config.frameFormat());
        response.put("mcuReadyGate", config.mcuReadyGate());
        response.put("localRailEnabled", config.localRailEnabled());
        response.put("mcuStatus", config.mcuStatus());
        HttpResponse.sendJson(out, response.toString());
    }

    static void handlePost(OutputStream out, String body, Config config) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendJsonError(out, "Request body is required");
            return;
        }
        JSONObject incoming;
        try {
            incoming = (JSONObject) new JSONTokener(body).nextValue();
        } catch (Exception malformed) {
            HttpResponse.sendJsonError(out, "Request body must be a JSON object");
            return;
        }

        JSONObject patch = new JSONObject();

        if (incoming.has("frameFormat")) {
            Object raw = incoming.opt("frameFormat");
            if (!(raw instanceof String)) {
                HttpResponse.sendJsonError(out, "frameFormat must be \"fixed\" or \"dynamic\"");
                return;
            }
            String format = (String) raw;
            if (!format.equals("fixed") && !format.equals("dynamic")) {
                HttpResponse.sendJsonError(out, "frameFormat must be \"fixed\" or \"dynamic\"");
                return;
            }
            patch.put("remoteKeyFrameFormat", format);
        }

        if (incoming.has("mcuReadyGate")) {
            Object raw = incoming.opt("mcuReadyGate");
            if (!(raw instanceof Boolean)) {
                HttpResponse.sendJsonError(out, "mcuReadyGate must be a boolean");
                return;
            }
            // UI sends "gate enabled"; the stored flag is the inverse ("…Disabled").
            patch.put("remoteKeyMcuReadyGateDisabled", !((Boolean) raw));
        }

        if (patch.length() == 0) {
            HttpResponse.sendJsonError(out, "Nothing to update");
            return;
        }

        boolean saved = config.save(patch);
        if (!saved) {
            HttpResponse.sendJsonError(out, "Failed to save remote-key diagnostics settings");
            return;
        }
        logger.info("Remote-key diagnostics updated: " + patch);

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("frameFormat", config.frameFormat());
        response.put("mcuReadyGate", config.mcuReadyGate());
        response.put("localRailEnabled", config.localRailEnabled());
        response.put("mcuStatus", config.mcuStatus());
        HttpResponse.sendJson(out, response.toString());
    }
}
