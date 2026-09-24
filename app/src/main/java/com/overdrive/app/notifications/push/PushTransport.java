package com.overdrive.app.notifications.push;

import com.overdrive.app.mqtt.ProxyHelper;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * POSTs an encrypted Web Push payload to a subscription endpoint.
 *
 * <p>Honors the sing-box proxy via {@link ProxyHelper#getHttpProxy()} so push
 * traffic respects whatever proxy mode the user has configured — same
 * pattern as ABRP, BYD cloud, and MQTT.
 */
public final class PushTransport {

    public static final class Result {
        public final int status;
        public final String body;
        /** Server-suggested wait before retrying, in seconds. -1 = unspecified. */
        public final int retryAfterSeconds;

        public Result(int status, String body) {
            this(status, body, -1);
        }

        public Result(int status, String body, int retryAfterSeconds) {
            this.status = status;
            this.body = body;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public boolean expired() { return status == 404 || status == 410; }
        public boolean ok() { return status >= 200 && status < 300; }
        /** True for transient failures worth retrying (5xx, 408, 429). */
        public boolean transientFailure() {
            return status >= 500 || status == 408 || status == 429;
        }
    }

    private PushTransport() {}

    public static Result send(String endpoint, String vapidJwt, String vapidPubKeyB64Url,
                              byte[] aes128gcmBody, int ttlSeconds) throws Exception {
        // Proxy first, ONE direct retry on an IO-level proxied failure. The
        // probe behind getHttpProxy() prefers the Tailscale SOCKS listener,
        // which on most cars is tailnet-only: public push endpoints (FCM,
        // Mozilla, etc.) are unreachable THROUGH it while the listener probes
        // healthy — without the retry, every push died for as long as it was
        // up. The encrypted body is a fixed byte[] so re-sending is safe.
        java.net.Proxy proxy = ProxyHelper.getHttpProxy();
        boolean viaProxy = proxy != null
                && proxy.type() != java.net.Proxy.Type.DIRECT;
        try {
            return sendVia(proxy, endpoint, vapidJwt, vapidPubKeyB64Url,
                    aes128gcmBody, ttlSeconds);
        } catch (java.io.IOException first) {
            if (!viaProxy) throw first;
            ProxyHelper.invalidateCache();
            return sendVia(java.net.Proxy.NO_PROXY, endpoint, vapidJwt,
                    vapidPubKeyB64Url, aes128gcmBody, ttlSeconds);
        }
    }

    private static Result sendVia(java.net.Proxy route, String endpoint, String vapidJwt,
                                  String vapidPubKeyB64Url, byte[] aes128gcmBody,
                                  int ttlSeconds) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(route);
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("Content-Encoding", "aes128gcm");
            conn.setRequestProperty("TTL", String.valueOf(ttlSeconds));
            conn.setRequestProperty("Authorization",
                    "vapid t=" + vapidJwt + ", k=" + vapidPubKeyB64Url);
            conn.setFixedLengthStreamingMode(aes128gcmBody.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(aes128gcmBody);
            }

            int status = conn.getResponseCode();
            String responseBody = "";
            try {
                java.io.InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                if (is != null) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > 4096) break; // cap log size
                    }
                    responseBody = new String(out.toByteArray(), "UTF-8");
                }
            } catch (Exception ignored) {}

            // Connectivity hiccup — ask ProxyHelper to re-probe next call.
            // (Same discipline as BydCloudMqttSubscriber on connection errors.)
            if (status >= 500 || status == 408 || status == 429) {
                ProxyHelper.invalidateCache();
            }

            int retryAfter = -1;
            String hdr = conn.getHeaderField("Retry-After");
            if (hdr != null) {
                try { retryAfter = Math.max(0, Integer.parseInt(hdr.trim())); }
                catch (NumberFormatException ignored) {}
            }
            return new Result(status, responseBody, retryAfter);
        } finally {
            conn.disconnect();
        }
    }
}
