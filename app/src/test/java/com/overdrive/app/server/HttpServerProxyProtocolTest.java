package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.Test;

public class HttpServerProxyProtocolTest {

    @Test
    public void parsesValidIpv4AndIpv6Sources() {
        assertEquals(
                "100.64.10.20",
                HttpServer.proxyProtocolV1Source(
                        "PROXY TCP4 100.64.10.20 100.90.80.70 54321 8080"));
        assertEquals(
                "fd7a:115c:a1e0::1234",
                HttpServer.proxyProtocolV1Source(
                        "PROXY TCP6 fd7a:115c:a1e0::1234 fd7a:115c:a1e0::5678 54321 8080"));
    }

    @Test
    public void rejectsUntrustedOrMalformedPreambles() {
        assertNull(HttpServer.proxyProtocolV1Source(
                "GET /api/settings/unified HTTP/1.1"));
        assertNull(HttpServer.proxyProtocolV1Source("PROXY UNKNOWN"));
        assertNull(HttpServer.proxyProtocolV1Source(
                "PROXY TCP4 peer.example 100.90.80.70 54321 8080"));
        assertNull(HttpServer.proxyProtocolV1Source(
                "PROXY TCP4 100.64.10.20 100.90.80.70 0 8080"));
        assertNull(HttpServer.proxyProtocolV1Source(
                "PROXY TCP4 100.64.10.20 100.90.80.70 54321 70000"));
        assertNull(HttpServer.proxyProtocolV1Source(
                "PROXY TCP4 100.64.10.20 100.90.80.70 54321"));
    }

    @Test
    public void trustedProxySourceCannotBeOverriddenByForwardedHeader() {
        assertEquals(
                "100.64.10.20",
                HttpServer.forwardedForValue(
                        true, "100.64.10.20", "127.0.0.1"));
        assertEquals(
                "203.0.113.9",
                HttpServer.forwardedForValue(
                        false, null, "203.0.113.9"));
    }

    @Test
    public void loopbackBypassRemainsLocalOnly() throws Exception {
        InetSocketAddress loopback = new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), 54321);

        assertTrue(AuthMiddleware.checkAuth(
                "/api/settings/unified", null, null,
                new ByteArrayOutputStream(), loopback, false));

        ByteArrayOutputStream proxiedResponse = new ByteArrayOutputStream();
        assertFalse(AuthMiddleware.checkAuth(
                "/api/settings/unified", null, null,
                proxiedResponse, loopback, true));
        assertTrue(proxiedResponse.toString("UTF-8").contains("401 Unauthorized"));
    }
}
