package com.overdrive.app.ui.daemon;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards Wi-Fi auto-enable user intent and hotspot suppression ownership. */
public class WifiAutoEnableSettingWiringTest {

    @Test
    public void daemonsPagePersistsThePositiveSettingOffTheUiThread() throws Exception {
        String layout = readRepositoryFile("app/src/main/res/layout/fragment_daemons.xml");
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/DaemonsFragment.kt");

        assertTrue(layout.contains("@+id/rowWifiAutoEnable"));
        assertTrue(layout.contains("@+id/swWifiAutoEnable"));
        assertTrue(fragment.contains("Executors.newSingleThreadExecutor"));
        assertTrue(fragment.contains("wifiSettingsWorker.execute"));
        assertTrue(fragment.contains("UnifiedConfigManager.isWifiAutoEnableEnabled()"));
        assertTrue(fragment.contains(
                "DaemonHttpClient.open(\"/api/keymap/fire\", \"POST\""));
        assertTrue(fragment.contains(".put(\"kind\", \"radio\")"));
        assertTrue(fragment.contains(".put(\"radio\", \"wifi\")"));
        assertTrue(fragment.contains(".put(\"enable\", enabled)"));
    }

    @Test
    public void hotspotMarkerAndUserIntentRemainIndependent() throws Exception {
        String config = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt");
        String hotspot = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/network/HotspotManager.kt");
        String accSentry = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");

        assertTrue(config.contains("userOff || hotspotOwnsSuppression"));
        assertTrue(hotspot.contains(
                "updateHotspot(mapOf(\"suppressedByHotspot\" to true))"));
        assertFalse(hotspot.contains("setWifiKeepAliveSuppressed("));
        assertFalse(accSentry.contains("setWifiKeepAliveSuppressed(false)"));
        assertTrue(accSentry.contains(
                "return cleared && !com.overdrive.app.config.UnifiedConfigManager"));
    }

    @Test
    public void everyWifiEnablePathUsesEffectiveSuppression() throws Exception {
        for (String relativePath : new String[]{
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java",
                "app/src/main/java/com/overdrive/app/daemon/SentryDaemon.java",
                "app/src/main/java/com/overdrive/app/launcher/ServiceLauncher.kt"}) {
            assertTrue(readRepositoryFile(relativePath)
                    .contains("UnifiedConfigManager.isWifiKeepAliveSuppressed()"));
        }
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }

            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
