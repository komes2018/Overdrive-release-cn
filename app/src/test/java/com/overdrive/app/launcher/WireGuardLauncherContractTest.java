package com.overdrive.app.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.BuildConfig;
import com.overdrive.app.wireguard.WireGuardPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Regression coverage for the WireGuard (wgproxy) deployment and launch contract. */
public class WireGuardLauncherContractTest {

    @Test
    public void pathsLiveUnderTheWireGuardHome() {
        assertEquals("/data/local/tmp/.wireguard", WireGuardPaths.HOME);
        assertEquals(WireGuardPaths.HOME + "/wg0.conf", WireGuardPaths.CONFIG);
        assertEquals(WireGuardPaths.HOME + "/status.json", WireGuardPaths.STATUS);
        assertEquals(WireGuardPaths.HOME + "/proxy_enabled", WireGuardPaths.PROXY_FLAG);
        assertEquals(8541, WireGuardPaths.SOCKS_PORT);
        assertEquals(3, WireGuardPaths.RELOAD_EXIT_CODE);
    }

    @Test
    public void deploymentProbeRequiresBinaryAndCurrentAppVersion() {
        String command = WireGuardLauncher.deploymentStatusCommand();

        assertTrue(command.contains("test -x " + WireGuardPaths.BINARY));
        assertTrue(command.contains(WireGuardPaths.VERSION_FILE));
        assertTrue(command.contains(String.valueOf(BuildConfig.VERSION_CODE)));
        assertTrue(command.contains("printf 'current"));
        assertTrue(command.contains("printf 'stale"));
    }

    @Test
    public void launchCommandRestartsOnlyOnReloadExit() {
        String command = WireGuardLauncher.buildLaunchCommand(false);

        assertTrue(command.startsWith("nohup sh -c 'while :; do "));
        assertTrue(command.contains(WireGuardPaths.BINARY + " -config " + WireGuardPaths.CONFIG));
        assertTrue(command.contains("-socks 127.0.0.1:8541"));
        assertTrue(command.contains("-status " + WireGuardPaths.STATUS));
        assertTrue(command.contains("[ $? -eq 3 ] || break; done'"));
        assertTrue(command.endsWith("> " + WireGuardPaths.LOG + " 2>&1 &"));
        assertFalse(command.contains("-upstream"));
        assertFalse(command.contains("-expose"));
    }

    @Test
    public void dashboardIsExposedOnlyWhenOptedIn() {
        assertFalse(WireGuardLauncher.buildLaunchCommand(false, false).contains("-expose"));
        assertFalse(WireGuardLauncher.buildLaunchCommand(true, false).contains("-expose"));

        String command = WireGuardLauncher.buildLaunchCommand(false, true);
        assertTrue(command.contains(" -expose 8080=127.0.0.1:8080"));
        assertTrue(command.indexOf("-expose") < command.indexOf("[ $? -eq 3 ]"));
        assertTrue(WireGuardLauncher.buildLaunchCommand(true, true).contains("-upstream 127.0.0.1:8119 -expose"));
    }

    @Test
    public void exposeFlagIsStoredNextToTheProxyFlag() {
        assertEquals(WireGuardPaths.HOME + "/expose_dashboard", WireGuardPaths.EXPOSE_FLAG);
    }

    @Test
    public void launchCommandUsesSingBoxUpstreamWhenRequested() {
        String command = WireGuardLauncher.buildLaunchCommand(true);

        assertTrue(command.contains("-upstream 127.0.0.1:8119"));
        assertTrue(command.indexOf("-upstream") < command.indexOf("[ $? -eq 3 ]"));
    }

    @Test
    public void killCommandOnlyTouchesVerifiedWgproxyPids() {
        String command = WireGuardLauncher.buildKillPidsCommand(
                java.util.Arrays.asList("123", "4; reboot", "456"));

        assertTrue(command.contains("for pid in 123 456; do"));
        assertFalse(command.contains("reboot"));
        assertTrue(command.contains("/proc/$pid/cmdline"));
        assertTrue(command.contains("grep -q '/wgproxy'"));
        assertEquals(
                "echo no-safe-wgproxy-pid",
                WireGuardLauncher.buildKillPidsCommand(java.util.Collections.emptyList()));
    }

    @Test
    public void proxyFlagIsWorldReadable() {
        String command = WireGuardLauncher.buildProxyFlagCommand(true);

        assertTrue(command.contains("echo true > " + WireGuardPaths.PROXY_FLAG));
        assertTrue(command.contains("chmod 644 " + WireGuardPaths.PROXY_FLAG));
        assertTrue(command.contains("chmod 711 " + WireGuardPaths.HOME));
    }

    @Test
    public void runningFastPathCannotSkipDeploymentAndStampIsWrittenLast()
            throws Exception {
        String source = read(
                "app/src/main/java/com/overdrive/app/launcher/WireGuardLauncher.kt");

        int launch = source.indexOf("fun launch(");
        int deployment = source.indexOf("isDeploymentCurrent {", launch);
        int pids = source.indexOf("getPids {", launch);
        int redeploy = source.indexOf("redeployRunning(pids, callback)", launch);
        assertTrue(launch >= 0);
        assertTrue(deployment > launch && deployment < pids);
        assertTrue(redeploy > pids);

        // The launcher refuses to run without a stored config
        int configCheck = source.indexOf("hasConfig { configured ->", launch);
        assertTrue(configCheck > launch && configCheck < deployment);

        int install = source.indexOf("private fun install(");
        int next = source.indexOf("private fun launchInstalled(", install);
        String body = source.substring(install, next);
        int copy = body.indexOf("cp -f");
        int chmod = body.indexOf("chmod 755");
        int move = body.indexOf("mv -f");
        int stamp = body.indexOf("printf '$expectedVersion");
        assertTrue(copy >= 0 && copy < chmod);
        assertTrue(chmod < move);
        assertTrue(move < stamp);
    }

    @Test
    public void configContentsAreNeverLogged() throws Exception {
        String source = read(
                "app/src/main/java/com/overdrive/app/launcher/WireGuardLauncher.kt");

        assertFalse(source.contains("cat ${WireGuardPaths.CONFIG}"));
    }

    @Test
    public void telegramDirectLaunchFailsClosedWithoutConfigOrOnStalePayload()
            throws Exception {
        String source = read(
                "app/src/main/java/com/overdrive/app/daemon/telegram/DaemonCommandHandler.java");
        int wgCase = source.indexOf("case \"wireguard\":");
        int config = source.indexOf("WireGuardPaths.CONFIG", wgCase);
        int deployment = source.indexOf("deploymentStatusCommand()", wgCase);
        int stale = source.indexOf(
                "WireGuard binary is stale; waiting for app-side redeployment",
                deployment);
        int launch = source.indexOf("buildLaunchCommand(", wgCase);

        assertTrue(wgCase >= 0);
        assertTrue(config > wgCase && config < deployment);
        assertTrue(stale > deployment && stale < launch);
        assertTrue(source.substring(stale, launch).contains("return false;"));
    }

    @Test
    public void everyLaunchPathReadsTheOptInAndFailsClosed() throws Exception {
        String telegram = read(
                "app/src/main/java/com/overdrive/app/daemon/telegram/DaemonCommandHandler.java");
        String launcher = read(
                "app/src/main/java/com/overdrive/app/launcher/WireGuardLauncher.kt");
        String api = read("app/src/main/java/com/overdrive/app/server/WireGuardApiHandler.java");
        int wgCase = telegram.indexOf("case \"wireguard\":");

        assertTrue(telegram.indexOf("WireGuardStore.isDashboardExposed()", wgCase) > wgCase);
        assertTrue(launcher.contains("buildLaunchCommand(useUpstream, expose)"));
        // An unreadable flag must mean "off".
        int onError = launcher.indexOf("callback(false)", launcher.indexOf("private fun readExposeFlag("));
        assertTrue(onError > 0);
        assertTrue(api.contains("\"/api/wireguard/expose\""));
    }

    @Test
    public void stopSignalsCompletionThroughOnStopped() throws Exception {
        String source = read(
                "app/src/main/java/com/overdrive/app/launcher/WireGuardLauncher.kt");
        int killAll = source.indexOf("private fun killAll(");
        int next = source.indexOf("private fun getPids(", killAll);
        String body = source.substring(killAll, next);

        assertTrue(source.contains("fun onStopped()"));
        assertTrue(body.contains("callback.onStopped()"));
        assertFalse(body.contains("onStarted"));
    }

    @Test
    public void wireGuardSurfaceIsJwtOnlyAndBootPathsNeedAConfig() throws Exception {
        String server = read("app/src/main/java/com/overdrive/app/server/HttpServer.java");
        String startup = read(
                "app/src/main/java/com/overdrive/app/ui/daemon/DaemonStartupManager.kt");

        assertTrue(server.contains("pathOnly.equals(\"/wireguard.html\")"));
        assertTrue(server.contains("pathOnly.startsWith(\"/api/wireguard/\")"));
        assertTrue(server.contains("remoteDevRequest || genAiRequest || wireGuardRequest"));
        assertTrue(startup.contains("ifWireGuardConfigured {"));
        int health = startup.indexOf("if (type == DaemonType.WIREGUARD_TUNNEL) {");
        assertTrue(health > 0 && startup.indexOf("ifWireGuardConfigured", health) > health);
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
