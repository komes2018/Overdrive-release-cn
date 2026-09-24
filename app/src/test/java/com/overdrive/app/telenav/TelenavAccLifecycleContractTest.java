package com.overdrive.app.telenav;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class TelenavAccLifecycleContractTest {

    @Test
    public void telenavUsesSharedAccEdgeAndStopsWhenParked() throws IOException {
        String manager = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/telenav/DeferredNavManager.kt");
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");

        assertFalse(manager.contains("AccMonitorController"));
        assertFalse(manager.contains("startPolling()"));
        assertTrue(manager.contains(
                "ProcessBuilder(\"am\", \"force-stop\", TELENAV_PACKAGE)"));
        assertTrue(daemon.contains(
                "DeferredNavManager.onAccStateChanged(accIsOff);"));
        assertFalse(daemon.contains("DeferredNavManager.start();"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
