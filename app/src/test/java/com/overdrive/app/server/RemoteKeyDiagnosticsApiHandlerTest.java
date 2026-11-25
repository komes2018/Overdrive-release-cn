package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Drives {@link RemoteKeyDiagnosticsApiHandler}'s GET/POST branches through the package-private
 * {@code Config} seam, so no live {@code UnifiedConfigManager} or Android {@code Context} is
 * needed. The handler reports validation and save failures through {@code HttpResponse}, which
 * answers HTTP 200 with {@code {"success":false,...}}; these tests assert the rejection via that
 * body and prove the config is not persisted (sibling vehicle keys are left to the merge write).
 */
public class RemoteKeyDiagnosticsApiHandlerTest {

    private DaemonLogger.Config previousLogConfig;

    @Before public void muteLogger() {
        previousLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false).withFileLog(false).withStdoutLog(false));
    }

    @After public void restoreLogger() {
        DaemonLogger.configure(previousLogConfig);
    }

    @Test public void getReturnsBothSettingsAndStatusFields() throws Exception {
        FakeConfig cfg = new FakeConfig();
        cfg.frameFormat = "dynamic";
        cfg.mcuReadyGate = false;
        cfg.localRailEnabled = true;
        cfg.mcuStatus = 10;
        String response = get(cfg);
        assertTrue(response.contains("\"success\":true"));
        assertTrue(response.contains("\"frameFormat\":\"dynamic\""));
        assertTrue(response.contains("\"mcuReadyGate\":false"));
        assertTrue(response.contains("\"localRailEnabled\":true"));
        assertTrue(response.contains("\"mcuStatus\":10"));
        assertEquals(0, cfg.saveCalls);
    }

    @Test public void postRejectsFrameFormatThatIsNotFixedOrDynamic() throws Exception {
        FakeConfig cfg = new FakeConfig();
        String response = post(cfg, "{\"frameFormat\":\"canfd\"}");
        assertTrue(response.contains("\"success\":false"));
        assertTrue(response.contains("frameFormat must be"));
        assertEquals(0, cfg.saveCalls);
    }

    @Test public void postRejectsFrameFormatOfTheWrongType() throws Exception {
        FakeConfig cfg = new FakeConfig();
        String response = post(cfg, "{\"frameFormat\":3}");
        assertTrue(response.contains("\"success\":false"));
        assertTrue(response.contains("frameFormat must be"));
        assertEquals(0, cfg.saveCalls);
    }

    @Test public void postRejectsNonBooleanMcuReadyGate() throws Exception {
        FakeConfig cfg = new FakeConfig();
        String response = post(cfg, "{\"mcuReadyGate\":\"true\"}");
        assertTrue(response.contains("\"success\":false"));
        assertTrue(response.contains("mcuReadyGate must be a boolean"));
        assertEquals(0, cfg.saveCalls);
    }

    @Test public void postRejectsEmptyOrNonObjectBody() throws Exception {
        FakeConfig cfg = new FakeConfig();
        assertTrue(post(cfg, null).contains("\"success\":false"));
        assertTrue(post(cfg, "").contains("\"success\":false"));
        assertTrue(post(cfg, "[\"frameFormat\"]").contains("\"success\":false"));
        assertTrue(post(cfg, "\"frameFormat\"").contains("\"success\":false"));
        assertTrue(post(cfg, "{}").contains("\"success\":false")); // object with nothing to update
        assertEquals(0, cfg.saveCalls);
    }

    @Test public void postPartialUpdatePersistsOnlyTheProvidedKey() throws Exception {
        // Only frameFormat is sent: the patch must carry only remoteKeyFrameFormat, so the
        // UnifiedConfigManager per-key merge leaves the gate flag and other vehicle keys untouched.
        FakeConfig formatOnly = new FakeConfig();
        String response = post(formatOnly, "{\"frameFormat\":\"dynamic\"}");
        assertTrue(response.contains("\"success\":true"));
        assertEquals(1, formatOnly.saveCalls);
        assertTrue(formatOnly.lastPatch.has("remoteKeyFrameFormat"));
        assertEquals("dynamic", formatOnly.lastPatch.getString("remoteKeyFrameFormat"));
        assertFalse(formatOnly.lastPatch.has("remoteKeyMcuReadyGateDisabled"));

        // Only mcuReadyGate is sent: the patch must carry only the inverted stored flag.
        FakeConfig gateOnly = new FakeConfig();
        post(gateOnly, "{\"mcuReadyGate\":false}");
        assertEquals(1, gateOnly.saveCalls);
        assertTrue(gateOnly.lastPatch.has("remoteKeyMcuReadyGateDisabled"));
        assertTrue(gateOnly.lastPatch.getBoolean("remoteKeyMcuReadyGateDisabled"));
        assertFalse(gateOnly.lastPatch.has("remoteKeyFrameFormat"));
    }

    @Test public void postSaveFailureReturnsAnError() throws Exception {
        FakeConfig cfg = new FakeConfig();
        cfg.saveResult = false;
        String response = post(cfg, "{\"frameFormat\":\"dynamic\"}");
        assertTrue(response.contains("\"success\":false"));
        assertTrue(response.contains("Failed to save"));
        assertEquals(1, cfg.saveCalls);
    }

    private static String get(RemoteKeyDiagnosticsApiHandler.Config cfg) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RemoteKeyDiagnosticsApiHandler.handleGet(out, cfg);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String post(RemoteKeyDiagnosticsApiHandler.Config cfg, String body)
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RemoteKeyDiagnosticsApiHandler.handlePost(out, body, cfg);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static final class FakeConfig implements RemoteKeyDiagnosticsApiHandler.Config {
        String frameFormat = "fixed";
        boolean mcuReadyGate = true;
        boolean localRailEnabled = true;
        int mcuStatus = -1;
        boolean saveResult = true;
        int saveCalls;
        JSONObject lastPatch;

        public String frameFormat() { return frameFormat; }
        public boolean mcuReadyGate() { return mcuReadyGate; }
        public boolean localRailEnabled() { return localRailEnabled; }
        public int mcuStatus() { return mcuStatus; }

        public boolean save(JSONObject patch) {
            saveCalls++;
            lastPatch = patch;
            return saveResult;
        }
    }
}
