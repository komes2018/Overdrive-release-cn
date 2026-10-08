package com.overdrive.app.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.wireguard.WireGuardPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the WireGuard SOCKS listener's place in the proxy selection. */
public class ProxyHelperWireGuardContractTest {

    @Test
    public void wireGuardFlagIsParsedLikeTheTailscaleFlag() {
        // The launcher writes the flag with `echo true > file`
        assertTrue(ProxyHelper.isProxyEnabledValue("true\n"));
        assertEquals(
                "/data/local/tmp/.wireguard/proxy_enabled",
                WireGuardPaths.PROXY_FLAG);
    }

    @Test
    public void probeOrderIsTailscaleThenWireGuardThenSingBox() throws Exception {
        String source = read("app/src/main/java/com/overdrive/app/mqtt/ProxyHelper.java");

        int tailscale = source.indexOf("if (probePort(TAILSCALE_PROXY_PORT))");
        int wireGuard = source.indexOf("probePort(WIREGUARD_PROXY_PORT)");
        int singBox = source.indexOf("probePort(PROXY_PORT)", wireGuard);

        assertTrue(tailscale >= 0);
        assertTrue(wireGuard > tailscale);
        assertTrue(singBox > wireGuard);
    }

    @Test
    public void wireGuardPortSpeaksSocksOnly() throws Exception {
        String source = read("app/src/main/java/com/overdrive/app/mqtt/ProxyHelper.java");

        assertTrue(source.contains("private static final int WIREGUARD_PROXY_PORT = 8541;"));
        assertTrue(source.contains(
                "proxyPort == TAILSCALE_PROXY_PORT || proxyPort == WIREGUARD_PROXY_PORT"));
    }

    @Test
    public void failClosedRouteNamesTheExpectedPort() throws Exception {
        String source = read("app/src/main/java/com/overdrive/app/mqtt/ProxyHelper.java");

        int failClosed = source.indexOf("public static Proxy getFailClosedHttpProxy()");
        int expected = source.indexOf("getExpectedProxyPort()", failClosed);
        assertTrue(failClosed >= 0 && expected > failClosed);
        assertTrue(source.contains(
                "return isFlagEnabled(PROXY_ENABLED_FILE) || isFlagEnabled(WIREGUARD_PROXY_ENABLED_FILE);"));
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
