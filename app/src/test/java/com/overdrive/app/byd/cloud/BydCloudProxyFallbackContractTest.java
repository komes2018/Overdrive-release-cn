package com.overdrive.app.byd.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.eclipse.paho.mqttv5.client.MqttClientException;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.junit.Test;

/**
 * Guards the BYD-cloud proxy fallback contract.
 *
 * <p>The local proxy probe (ProxyHelper) is a blind loopback TCP connect: it
 * cannot tell a general-egress proxy (sing-box, Tailscale fronting an exit
 * node) from the tailnet-only Tailscale SOCKS listener that the MQTT settings
 * expose for private brokers. BYD's cloud endpoints are public internet, so
 * when the tailnet-only listener wins the probe, every cloud operation used
 * to fail for as long as the listener was up — the listener itself probes
 * healthy, so cache invalidation could never deselect it. Both cloud legs
 * must therefore fall back:
 *
 * <ul>
 *   <li>HTTP transport (login, commands, realtime polling, DI5 parked
 *       heartbeat): the OkHttp proxy selector returns a route CHAIN — proxy
 *       first, DIRECT second — so OkHttp falls through in the same call and
 *       its route database keeps traffic off the dead leg afterwards.</li>
 *   <li>EMQ realtime subscriber (Paho has no route chain): one explicit
 *       direct retry after a transport-level proxied connect failure, with
 *       the winning route preferred on later attempts.</li>
 * </ul>
 */
public class BydCloudProxyFallbackContractTest {

    // ── HTTP transport: proxy route chain ────────────────────────────────

    @Test
    public void routeChainIsDirectOnlyWithoutAProxy() {
        assertEquals(
                java.util.Collections.singletonList(Proxy.NO_PROXY),
                BydCloudTransport.proxyRouteChain(Proxy.NO_PROXY));
        assertEquals(
                java.util.Collections.singletonList(Proxy.NO_PROXY),
                BydCloudTransport.proxyRouteChain(null));
    }

    @Test
    public void routeChainTriesProxyFirstThenFallsBackDirect() {
        Proxy tailscaleSocks = new Proxy(Proxy.Type.SOCKS,
                InetSocketAddress.createUnresolved("127.0.0.1", 8539));
        List<Proxy> chain = BydCloudTransport.proxyRouteChain(tailscaleSocks);
        assertEquals(2, chain.size());
        assertEquals(tailscaleSocks, chain.get(0));
        assertEquals(Proxy.NO_PROXY, chain.get(1));

        Proxy singBoxHttp = new Proxy(Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved("127.0.0.1", 8119));
        chain = BydCloudTransport.proxyRouteChain(singBoxHttp);
        assertEquals(2, chain.size());
        assertEquals(singBoxHttp, chain.get(0));
        assertEquals(Proxy.NO_PROXY, chain.get(1));
    }

    // ── EMQ subscriber: transport-failure classification ─────────────────

    @Test
    public void socketLevelFailuresTriggerTheFallbackLeg() {
        // IOException anywhere in the cause chain (SOCKS refusal, TLS reset).
        assertTrue(BydCloudMqttSubscriber.isTransportLevelConnectFailure(
                new SocketException("SOCKS: Host unreachable")));
        assertTrue(BydCloudMqttSubscriber.isTransportLevelConnectFailure(
                new MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION,
                        new SocketException("Connection reset"))));
        assertTrue(BydCloudMqttSubscriber.isTransportLevelConnectFailure(
                new RuntimeException("wrapper",
                        new MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION,
                                new UnknownHostException("dilink emq host")))));

        // Paho client-side connect codes without an IOException cause.
        assertTrue(BydCloudMqttSubscriber.isTransportLevelConnectFailure(
                new MqttException(MqttClientException.REASON_CODE_CLIENT_TIMEOUT)));
        assertTrue(BydCloudMqttSubscriber.isTransportLevelConnectFailure(
                new MqttException(MqttClientException.REASON_CODE_SERVER_CONNECT_ERROR)));
        assertTrue(BydCloudMqttSubscriber.isTransportLevelConnectFailure(
                new MqttException(MqttClientException.REASON_CODE_CONNECTION_LOST)));
    }

    @Test
    public void brokerRejectionsMustNotBurnASecondConnect() {
        // MQTT v5 CONNACK rejections (bad credentials / not authorized) fail
        // identically on every route — no fallback leg.
        assertFalse(BydCloudMqttSubscriber.isTransportLevelConnectFailure(
                new MqttException(134))); // bad user name or password
        assertFalse(BydCloudMqttSubscriber.isTransportLevelConnectFailure(
                new MqttException(135))); // not authorized
        assertFalse(BydCloudMqttSubscriber.isTransportLevelConnectFailure(
                new RuntimeException("Login failed: token expired")));
        assertFalse(BydCloudMqttSubscriber.isTransportLevelConnectFailure(null));
    }

    // ── Source pins: the fallback wiring itself ──────────────────────────

    @Test
    public void httpTransportSelectorReturnsTheChainWithDirectFallback()
            throws IOException {
        String transport = read(
                "app/src/main/java/com/overdrive/app/byd/cloud/BydCloudTransport.java");
        String helper = read(
                "app/src/main/java/com/overdrive/app/mqtt/ProxyHelper.java");

        // Canonical chain lives in ProxyHelper (shared with AppUpdater).
        assertTrue(helper.contains("public static java.util.List<Proxy> proxyRouteChain(Proxy selected)"));
        assertTrue(helper.contains("chain.add(Proxy.NO_PROXY);"));
        // The selector must consult the chain, not a single frozen proxy.
        assertTrue(transport.contains("return proxyRouteChain("));
        assertTrue(transport.contains(
                "com.overdrive.app.mqtt.ProxyHelper.proxyRouteChain(selected)"));
        assertFalse(transport.contains("return Collections.singletonList(\n"
                + "                            com.overdrive.app.mqtt.ProxyHelper.getHttpProxy());"));
    }

    @Test
    public void everyPublicInternetConsumerCarriesTheDirectFallback()
            throws IOException {
        // Simple OkHttp clients share ProxyHelper.chainProxySelector().
        String helper = read(
                "app/src/main/java/com/overdrive/app/mqtt/ProxyHelper.java");
        assertTrue(helper.contains(
                "public static java.net.ProxySelector chainProxySelector()"));
        assertTrue(helper.contains("return proxyRouteChain(getHttpProxy());"));

        assertTrue(read("app/src/main/java/com/overdrive/app/weather/WeatherTemperature.java")
                .contains(".proxySelector(ProxyHelper.chainProxySelector())"));
        assertTrue(read("app/src/main/java/com/overdrive/app/analytics/AnalyticsPinger.kt")
                .contains(".proxySelector(com.overdrive.app.mqtt.ProxyHelper.chainProxySelector())"));
        assertTrue(read("app/src/main/java/com/overdrive/app/roadsense/sync/CloudflareEdgeSyncProvider.kt")
                .contains(".proxySelector(com.overdrive.app.mqtt.ProxyHelper.chainProxySelector())"));

        // Map clients keep their own selector but must return the chain.
        assertTrue(read("app/src/main/java/com/overdrive/app/navmap/nav/MapNetworking.kt")
                .contains("ProxyHelper.proxyRouteChain(ProxyHelper.getHttpProxy()).toMutableList()"));

        // Telegram daemon: frozen chain per client rebuild (getGlobalProxy may
        // shell out — must never run per-request), proxy leg still first.
        assertTrue(read("app/src/main/java/com/overdrive/app/daemon/TelegramBotDaemon.java")
                .contains("com.overdrive.app.mqtt.ProxyHelper.proxyRouteChain(proxy)"));

        // HttpURLConnection users: one explicit direct retry on proxied IO failure.
        assertTrue(read("app/src/main/java/com/overdrive/app/geo/GeocodingResolver.java")
                .contains("nominatimAttempt(lat, lng, locale, java.net.Proxy.NO_PROXY)"));
        assertTrue(read("app/src/main/java/com/overdrive/app/notifications/push/PushTransport.java")
                .contains("return sendVia(java.net.Proxy.NO_PROXY, endpoint, vapidJwt,"));
    }

    @Test
    public void failClosedPrivacyConsumersMustNeverGainADirectFallback()
            throws IOException {
        // GenAI and community sync intentionally fail closed when proxy-only
        // routing is expected — "harmonizing" them onto the fallback chain
        // would silently leak privacy-sensitive traffic onto a direct dial.
        String genai = read(
                "app/src/main/java/com/overdrive/app/genai/GenAiRuntime.java");
        String community = read(
                "app/src/main/java/com/overdrive/app/community/sync/CommunitySyncProvider.kt");

        assertTrue(genai.contains("ProxyHelper.getFailClosedHttpProxy()"));
        assertFalse(genai.contains("chainProxySelector()"));
        assertFalse(genai.contains("proxyRouteChain("));
        assertTrue(community.contains(".proxy(com.overdrive.app.mqtt.ProxyHelper.getFailClosedHttpProxy())"));
        assertFalse(community.contains("chainProxySelector()"));
        assertFalse(community.contains("proxyRouteChain("));
    }

    @Test
    public void appUpdaterMetadataClientUsesTheSameChain() throws IOException {
        String updater = read(
                "app/src/main/java/com/overdrive/app/updater/AppUpdater.java");

        // GitHub metadata fetches must re-evaluate the route per call and
        // carry the DIRECT fallback; a frozen builder.proxy(...) wedges every
        // update check while a tailnet-only proxy listener is up (the
        // streaming APK download already had its own manual proxy→direct
        // retry — the metadata client used to die before reaching it).
        assertTrue(updater.contains(
                "com.overdrive.app.mqtt.ProxyHelper.proxyRouteChain("));
        assertFalse(updater.contains("builder.proxy(proxy);\n"
                + "            Log.d(TAG, \"AppUpdater HTTP via proxy \""));
    }

    @Test
    public void emqSubscriberRetriesTheOtherRouteOnceAndRemembersTheWinner()
            throws IOException {
        String subscriber = read(
                "app/src/main/java/com/overdrive/app/byd/cloud/BydCloudMqttSubscriber.java");

        // Fallback leg: same attempt, opposite route, fresh client.
        assertTrue(subscriber.contains(
                "mc = connectClientViaRoute(brokerUri, clientId, opts, !firstViaProxy);"));
        // Gate: only transport-level failures may retry; CONNACK rejections rethrow.
        assertTrue(subscriber.contains(
                "if (!proxyActive || !isTransportLevelConnectFailure(first))"));
        // The winning route is preferred on later attempts (reconnects and the
        // 25-min session refresh must not re-pay a dead proxied leg each time).
        assertTrue(subscriber.contains("preferDirectEmqRoute = firstViaProxy;"));
        assertTrue(subscriber.contains(
                "boolean firstViaProxy = proxyActive && !preferDirectEmqRoute;"));
        // Failed candidates are closed by the route helper, never leaked to GC.
        assertTrue(subscriber.contains("closeClientQuietly(candidate);"));
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path direct = current.resolve(relativePath);
            if (Files.isRegularFile(direct)) {
                return new String(
                        Files.readAllBytes(direct),
                        StandardCharsets.UTF_8);
            }
            Path fromModule =
                    current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule),
                        StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
