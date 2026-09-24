package com.overdrive.app.daemon;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Regression contract for PR #284: process-local ACC state must not fabricate
 * an OFF snapshot before AccSentryDaemon has received an authoritative value.
 */
public class AccSentryPowerLevelContractTest {

    @Test
    public void legacySnapshotAlwaysReadsHalAndDiLink5UsesDedicatedProbe()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");

        int read = source.indexOf("private static int readPowerLevel()");
        int readEnd = source.indexOf(
                "private static void applyHeartbeatPowerLevel(", read);
        assertTrue(read >= 0);
        assertTrue(readEnd > read);
        String readBody = source.substring(read, readEnd);

        assertFalse(readBody.contains("AccMonitor.isAccOn()"));
        assertFalse(readBody.contains("isAccStateAuthoritative()"));
        assertTrue(readBody.contains(
                "\"android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice\""));
        assertTrue(readBody.contains("getMethod(\"getPowerLevel\")"));

        int platformBranch = source.indexOf("if (isDilink5CameraMode()) {");
        int daemonLoop = source.indexOf(
                "log(\"Daemon running, entering persistence loop...\")",
                platformBranch);
        assertTrue(platformBranch >= 0);
        assertTrue(daemonLoop > platformBranch);
        String startup = source.substring(platformBranch, daemonLoop);
        assertTrue(startup.contains("startDiLink5AccStateHeartbeat();"));
        assertTrue(startup.contains(
                "startBodyworkListenerRegistrationSupervisor(context);"));

        int d5Heartbeat = source.indexOf(
                "private static synchronized void startDiLink5AccStateHeartbeat()");
        int notify = source.indexOf(
                "private static void notifyAccState(", d5Heartbeat);
        assertTrue(d5Heartbeat >= 0);
        assertTrue(notify > d5Heartbeat);
        String heartbeat = source.substring(d5Heartbeat, notify);
        assertTrue(heartbeat.contains(
                "probeDiLink5AccOnForTransition("));
        assertTrue(heartbeat.contains("!current.sentryMode"));
        assertFalse(heartbeat.contains("requestPowerLevelSnapshot("));
    }

    private static String readRepositoryFile(String relativePath)
            throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) {
                return new String(
                        Files.readAllBytes(candidate),
                        StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("Repository file not found: " + relativePath);
    }
}
