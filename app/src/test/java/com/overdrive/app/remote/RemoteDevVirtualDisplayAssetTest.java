package com.overdrive.app.remote;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RemoteDevVirtualDisplayAssetTest {

    @Test
    public void preferredLiveStreamUsesAPrivatePacedVirtualDisplay() throws Exception {
        String host = read("src/main/java/com/overdrive/app/remote/RemoteDevVirtualDisplay.kt");
        String controller = read("src/main/java/com/overdrive/app/remote/RemoteDevViewController.kt");
        String stream = read("src/main/java/com/overdrive/app/server/RemoteDevViewWebSocketStream.java");

        assertTrue(host.contains("createVirtualDisplay("));
        assertTrue(host.contains("VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY"));
        assertTrue(host.contains("ImageReader.newInstance("));
        assertTrue(host.contains("LOGICAL_WIDTH = 1920"));
        assertTrue(host.contains("STREAM_WIDTH = 960"));
        assertTrue(host.contains("ImageReader.newInstance(\n            LOGICAL_WIDTH,\n            LOGICAL_HEIGHT"));
        assertTrue(host.contains("Rect(0, 0, LOGICAL_WIDTH, LOGICAL_HEIGHT)"));
        assertTrue(host.contains("STREAM_FRAME_INTERVAL_MS = 100L"));
        assertTrue(host.contains("RemoteMainActivity::class.java"));
        assertFalse(host.contains("MediaProjection"));
        assertFalse(host.contains("captureLayers"));
        assertTrue(controller.contains("RemoteDevVirtualDisplay.latestFrame()"));
        assertTrue(controller.contains("if (!format.equals(\"png\""));
        assertTrue(stream.contains("STREAM_POLL_INTERVAL_MS = 80L"));
        assertTrue(stream.contains("sourceSequence != lastSourceSequence"));
    }

    @Test
    public void blockedVirtualActivityLaunchUsesAGatedPhysicalWindowCompatibilityPath()
            throws Exception {
        String host = read("src/main/java/com/overdrive/app/remote/RemoteDevVirtualDisplay.kt");
        String controller = read("src/main/java/com/overdrive/app/remote/RemoteDevViewController.kt");
        String bridge = read("src/main/java/com/overdrive/app/remote/RemoteDevViewBridgeService.kt");
        String api = read("src/main/java/com/overdrive/app/server/RemoteDevViewApiHandler.java");

        int launchGate = host.indexOf("if (!session.isActivityLaunchAllowed())");
        int remoteDisplayBind = host.indexOf(
            "RemoteDevViewController.bindRemoteDisplay(session.displayId)");
        assertTrue(launchGate >= 0);
        assertTrue(remoteDisplayBind > launchGate);
        assertTrue(host.contains("isActivityStartAllowedOnDisplay("));
        assertTrue(host.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.Q"));
        assertTrue(host.contains("error is SecurityException"));
        assertTrue(host.contains("COMPATIBILITY_BACKEND_NAME"));
        assertTrue(host.contains("physicalCompatibilityStatus()"));
        assertFalse(host.contains("Intent(application, MainActivity::class.java)"));
        assertFalse(host.contains("D50F_LC"));
        assertFalse(host.contains("\"2606\""));

        assertTrue(controller.contains("physicalMainActivity"));
        assertTrue(controller.contains("activity is MainActivity"));
        assertTrue(controller.contains("activity !is RemoteMainActivity"));
        assertTrue(controller.contains("activityDisplay == Display.DEFAULT_DISPLAY"));
        assertTrue(controller.contains("isAttachedToWindow"));
        assertTrue(controller.contains("RemoteDevVirtualDisplay.isCompatibilityMode()"));

        assertTrue(bridge.contains("response.put(\"captureBackend\", launch.backend)"));
        assertTrue(bridge.contains("RemoteDevVirtualDisplay.currentBackendName()"));
        assertTrue(bridge.contains("RemoteDevVirtualDisplay.isCompatibilityMode()"));
        assertTrue(api.contains("ready.metadata.optBoolean(\"compatibilityMode\", false)"));
        assertTrue(api.contains("response.put(\"physicalDisplayChanged\", compatibilityMode)"));
    }

    @Test
    public void remoteActivityIsIsolatedFromPhysicalStartupAndExplicitlyTornDown() throws Exception {
        String manifest = read("src/main/AndroidManifest.xml");
        String main = read("src/main/java/com/overdrive/app/ui/MainActivity.kt");
        String bridge = read("src/main/java/com/overdrive/app/remote/RemoteDevViewBridgeService.kt");
        String client = read("src/main/java/com/overdrive/app/server/RemoteDevViewBridgeClient.java");
        String api = read("src/main/java/com/overdrive/app/server/RemoteDevViewApiHandler.java");

        assertTrue(manifest.contains("com.overdrive.app.ui.RemoteMainActivity"));
        assertTrue(manifest.contains("android:exported=\"false\""));
        assertTrue(manifest.contains("android:taskAffinity=\"com.overdrive.app.remote_dev\""));
        assertTrue(main.contains("this is RemoteMainActivity"));
        assertTrue(main.contains("if (!remoteDevSession) runDaemonStartup"));
        assertTrue(main.contains("if (!remoteDevSession) startStatusOverlay"));
        assertTrue(bridge.contains("\"stop\" ->"));
        assertTrue(bridge.contains("RemoteDevVirtualDisplay.stop()"));
        assertTrue(client.contains("new JSONObject().put(\"command\", \"stop\")"));
        assertTrue(api.contains("remote-dev-session-reaper"));
        assertTrue(api.contains("!SESSIONS.hasActiveSession()"));
    }

    @Test
    public void transientBlackFramesAreHeldUnlessTheRemoteWindowIsSecure() throws Exception {
        String host = read("src/main/java/com/overdrive/app/remote/RemoteDevVirtualDisplay.kt");
        String controller = read("src/main/java/com/overdrive/app/remote/RemoteDevViewController.kt");

        assertTrue(host.contains("isNearlyBlack(output)"));
        assertTrue(host.contains("latestFrame.get() != null"));
        assertTrue(host.contains("!RemoteDevViewController.isRemoteWindowSecure()"));
        assertTrue(controller.contains("WindowManager.LayoutParams.FLAG_SECURE"));
        assertTrue(controller.contains("rootDisplayId == activityDisplayId"));
    }

    private static String read(String moduleRelativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        Path fromModule = current.resolve(moduleRelativePath);
        if (Files.exists(fromModule)) {
            return normalizeNewlines(Files.readAllBytes(fromModule));
        }
        Path fromRepository = current.resolve("app").resolve(moduleRelativePath);
        return normalizeNewlines(Files.readAllBytes(fromRepository));
    }

    private static String normalizeNewlines(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
