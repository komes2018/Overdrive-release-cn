package com.overdrive.app.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Pins the three autostart reliability fixes that do not run on desktop Android. */
public class AutoStartEnablerReliabilityContractTest {

    @Test
    public void waitsForBindResetsListAndNeverClicksTheRow() throws Exception {
        String setup = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/overlay/SetupGuideDialog.java");
        String enabler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/AutoStartEnabler.java");

        assertTrue(setup.contains("runAutoStartWhenServiceReady"));
        assertTrue(setup.contains("AUTOSTART_SERVICE_WAIT_MS"));
        assertTrue(setup.contains("button.postDelayed("));
        assertTrue(setup.contains(
                "BydDataCacheWhitelist.applyViaDaemonWhenReady()"));
        assertFalse(setup.contains(
                "BydDataCacheWhitelist.applyViaDaemon()"));
        assertTrue(enabler.contains("ACTION_SCROLL_BACKWARD"));
        assertTrue(enabler.contains("rewindToStart(root)"));
        assertTrue(enabler.contains("return tapCenter(sw);"));
        assertFalse(enabler.contains("ACTION_CLICK on clickable ancestor"));

        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");
        assertTrue(daemon.contains(
                "android.hardware.bydauto.startup.BYDAutoStartupAppDevice"));
        String helper = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDeviceHelper.java");
        assertTrue(helper.contains(
                "Boolean.TRUE.equals(getter.invoke(device, packageName))"));
        assertTrue(daemon.contains("&& startupApp"));
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
