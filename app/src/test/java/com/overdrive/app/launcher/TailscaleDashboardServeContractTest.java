package com.overdrive.app.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class TailscaleDashboardServeContractTest {

    @Test
    public void dashboardUsesProxyProtocolServeAndKeepsTheExistingUrl() throws Exception {
        String launcher = read(
                "app/src/main/java/com/overdrive/app/launcher/TailscaleLauncher.kt");

        assertEquals(
                "serve --bg --proxy-protocol=1 --tcp=8080 tcp://127.0.0.1:8080",
                TailscaleLauncher.secureDashboardServeArgs());
        assertTrue(launcher.contains("\"http://$ip:$DASHBOARD_PORT\""));
        assertFalse(launcher.contains("callback(\"http://${output.trim()}:8080\")"));
    }

    @Test
    public void httpsUsesStandardServeAndRequiresVerifiedState() throws Exception {
        String launcher = read(
                "app/src/main/java/com/overdrive/app/launcher/TailscaleLauncher.kt");
        String controller = read(
                "app/src/main/java/com/overdrive/app/ui/daemon/TailscaleController.kt");
        String layout = read(
                "app/src/main/res/layout/dialog_tailscale_settings.xml");

        assertEquals(
                "serve --bg --https=443 http://127.0.0.1:8080",
                TailscaleLauncher.secureHttpsServeArgs());
        assertEquals(
                "serve --https=443 off",
                TailscaleLauncher.disableHttpsServeArgs());

        assertTrue(launcher.contains("cmd = \"serve status --json\""));
        assertTrue(launcher.contains("CertDomains"));
        assertTrue(launcher.contains("optString(\"DNSName\", \"\")"));
        assertTrue(launcher.contains("HttpsServeOwnership.LEGACY_OWNED"));
        assertTrue(launcher.contains("disableLegacyHttpsServeArgs()"));
        assertTrue(launcher.contains(
                "after.ownership == HttpsServeOwnership.OWNED"));
        assertTrue(launcher.contains(
                "after.domain.equals(certDomain, ignoreCase = true)"));
        assertTrue(launcher.contains(
                "withdrawOwnedHttpsServe(after) { cleanup ->"));
        assertFalse(launcher.contains("cmd = \"serve --bg $HTTP_PORT\""));

        assertTrue(controller.contains("fun saveHttpsSettings("));
        assertTrue(layout.contains("@+id/switchTailscaleHttps"));
    }

    @Test
    public void failedSecureServeCannotFallBackToRawUserspaceForwarding() throws Exception {
        String launcher = read(
                "app/src/main/java/com/overdrive/app/launcher/TailscaleLauncher.kt");

        assertEquals(
                "serve --bg --tcp=8080 tcp://127.0.0.1:1",
                TailscaleLauncher.denyDashboardServeArgs());
        assertTrue(launcher.contains(
                "buildKillFingerprintCommand(daemonFingerprint)"));
        assertTrue(launcher.contains(
                "currentFingerprint != daemonFingerprint"));

        String controller = read(
                "app/src/main/java/com/overdrive/app/ui/daemon/TailscaleController.kt");
        assertTrue(controller.contains(
                "TailscaleLauncher.invalidateDashboardLifecycle()"));
    }

    @Test
    public void everyDirectStartPathInstallsTheSharedGuard() throws Exception {
        String telegram = read(
                "app/src/main/java/com/overdrive/app/daemon/telegram/DaemonCommandHandler.java");
        assertTrue(telegram.contains(
                "TailscaleLauncher\n                        .buildDashboardServeGuardScript()"));
        assertTrue(telegram.contains(
                "tailscaleGuardStarted = startTailscaleDashboardGuard(ctx)"));

        String guard = String.join(
                "\n", TailscaleLauncher.buildDashboardServeGuardScript());
        assertTrue(guard.contains(TailscaleLauncher.secureDashboardServeArgs()));
        assertTrue(guard.contains(TailscaleLauncher.denyDashboardServeArgs()));
        assertTrue(guard.contains(TailscaleLauncher.secureHttpsServeArgs()));
        assertTrue(guard.contains("HTTPS_FILE="));
        assertTrue(guard.contains("\"BackendState\""));
        assertTrue(guard.contains("kill -9 \"$PID\""));
        assertTrue(guard.contains(
                "if [ \"$LATEST_PIDS\" != \"$CURRENT_PIDS\" ]; then"));
        assertTrue(guard.contains("WAIT_TRIES=0"));
    }

    @Test
    public void startupWatchRetriesBeforeTheDaemonBecomesVisible() throws Exception {
        String launcher = read(
                "app/src/main/java/com/overdrive/app/launcher/TailscaleLauncher.kt");

        assertTrue(launcher.contains("sawDaemon = false"));
        assertTrue(launcher.contains(
                "if (sawDaemon || attempt >= DASHBOARD_SERVE_WATCH_ATTEMPTS)"));
        assertTrue(launcher.contains(
                "dashboardServeWatchGeneration.compareAndSet(generation, -1L)"));
    }

    @Test
    public void sharedGuardHasValidPosixShellSyntax() throws Exception {
        Path script = Files.createTempFile("tailscale-dashboard-guard", ".sh");
        try {
            Files.write(
                    script,
                    TailscaleLauncher.buildDashboardServeGuardScript(),
                    StandardCharsets.UTF_8);
            Process check = new ProcessBuilder(
                    "/bin/sh", "-n", script.toString())
                    .redirectErrorStream(true)
                    .start();
            byte[] output = check.getInputStream().readAllBytes();
            int exitCode = check.waitFor();
            assertEquals(
                    new String(output, StandardCharsets.UTF_8),
                    0,
                    exitCode);
        } finally {
            Files.deleteIfExists(script);
        }
    }

    @Test
    public void proxyPreambleDisablesOnlyTheLoopbackFallback() throws Exception {
        String server = read("app/src/main/java/com/overdrive/app/server/HttpServer.java");

        assertTrue(server.contains("boolean hasTunnelHeaders = hasProxyProtocol;"));
        assertTrue(server.contains("String forwardedFor = proxySourceAddress;"));
        assertTrue(server.contains("lower.startsWith(\"x-forwarded-for:\")"));
        assertTrue(server.contains("lower.startsWith(\"tailscale-user-login:\")"));
        assertTrue(server.contains("client.getInetAddress().isLoopbackAddress()"));
    }

    private static String read(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
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
