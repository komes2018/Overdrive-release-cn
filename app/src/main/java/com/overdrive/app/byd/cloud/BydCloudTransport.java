package com.overdrive.app.byd.cloud;

import com.overdrive.app.byd.cloud.crypto.BydCryptoUtils;
import com.overdrive.app.byd.cloud.crypto.EnvelopeCodec;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * HTTP transport for BYD cloud API.
 * 
 * Handles Bangcle envelope wrapping/unwrapping and cookie management.
 * 
 * Port of: Niek/BYD-re/client.js (postSecure function)
 */
public final class BydCloudTransport {

    private static final String TAG = "BydCloudTransport";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=UTF-8");

    private final BydCloudConfig config;
    private final EnvelopeCodec codec;
    private final OkHttpClient httpClient;
    private final ConcurrentMap<Thread, Call> activeCalls = new ConcurrentHashMap<>();

    public BydCloudTransport(BydCloudConfig config, EnvelopeCodec codec) {
        this.config = config;
        this.codec = codec;

        // Simple in-memory cookie jar (no external dependency needed)
        final Map<String, List<Cookie>> cookieStore = new HashMap<>();
        CookieJar cookieJar = new CookieJar() {
            @Override
            public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                cookieStore.put(url.host(), new ArrayList<>(cookies));
            }

            @Override
            public List<Cookie> loadForRequest(HttpUrl url) {
                List<Cookie> cookies = cookieStore.get(url.host());
                return cookies != null ? cookies : Collections.emptyList();
            }
        };

        // China stack talks directly to domestic BYD servers (dilinksuperappserver-cn.byd.auto);
        // never route through local proxies (e.g. Tailscale SOCKS on 8539).
        java.net.Proxy proxy = config.isChinaRegion()
                ? java.net.Proxy.NO_PROXY
                : com.overdrive.app.mqtt.ProxyHelper.getHttpProxy();
        logger.info("BYD Cloud transport: baseUrl=" + config.getBaseUrl()
                + " isChina=" + config.isChinaRegion()
                + " proxy=" + (proxy.equals(java.net.Proxy.NO_PROXY) ? "direct" : proxy.address()));

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .cookieJar(cookieJar)
                .proxy(proxy)
                .build();
    }

    /**
     * Send a secure POST request with Bangcle envelope wrapping.
     * 
     * @param endpoint API endpoint path (e.g., "/app/account/login")
     * @param outerPayload The outer JSON payload to encrypt and send
     * @return Decoded outer response JSON
     * @throws IOException on network or protocol errors
     */
    public JSONObject postSecure(String endpoint, JSONObject outerPayload) throws IOException {
        throwIfRequestCancelled();
        // Encode the outer payload into a Bangcle envelope
        String requestEnvelope = codec.encodeEnvelope(outerPayload.toString());

        // Wrap in {"request": "<envelope>"}
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("request", requestEnvelope);
        } catch (Exception e) {
            throw new IOException("Failed to build request body", e);
        }

        String url = config.getBaseUrl() + endpoint;
        logger.debug("POST " + endpoint);

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestBody.toString(), JSON_TYPE))
                .addHeader("accept-encoding", "identity")
                .addHeader("content-type", "application/json; charset=UTF-8")
                .addHeader("user-agent", config.getUserAgent());
        // China stack carries three extra headers (BYD-re client.js / pyBYD transport).
        if (config.isChinaRegion()) {
            requestBuilder
                    .addHeader("version", BydCloudConfig.CN_APP_INNER_VERSION)
                    .addHeader("platform", "ANDROID")
                    .addHeader("BrandFlag", BydCloudConfig.CN_BRAND_FLAG);
        }
        Request request = requestBuilder.build();

        Thread owner = Thread.currentThread();
        Call call = httpClient.newCall(request);
        activeCalls.put(owner, call);
        try {
            // A router timeout can race the Call registration above. Re-check
            // after publishing the Call so a timeout cannot leave a newly
            // registered request free to dispatch.
            try {
                throwIfRequestCancelled();
            } catch (InterruptedIOException cancelled) {
                call.cancel();
                throw cancelled;
            }
            try (Response response = call.execute()) {
                logger.debug("  HTTP " + response.code() + " " + endpoint);
                if (!response.isSuccessful()) {
                    logger.warn("  HTTP error " + response.code() + " " + endpoint);
                    throw new IOException("HTTP " + response.code() + " " + endpoint);
                }

                String bodyText = response.body() != null ? response.body().string() : "";
                logger.debug("  Response length: " + bodyText.length() + " bytes");
                JSONObject body;
                try {
                    body = new JSONObject(bodyText);
                } catch (Exception e) {
                    logger.warn("  Invalid JSON response from " + endpoint + " (length=" + bodyText.length() + ")");
                    throw new IOException("Invalid JSON response from " + endpoint);
                }

                String responseEnvelope = body.optString("response", "");
                if (responseEnvelope.isEmpty()) {
                    logger.warn("  Missing 'response' field in body from " + endpoint);
                    throw new IOException("Missing response payload for " + endpoint);
                }

                // Decode the Bangcle envelope
                logger.debug("  Decoding Bangcle envelope (" + responseEnvelope.length() + " chars)");
                String decodedText = codec.decodeEnvelope(responseEnvelope);

                // Handle edge case where decoded text starts with "F{" or "F["
                String normalized = decodedText;
                if (normalized.startsWith("F{") || normalized.startsWith("F[")) {
                    normalized = normalized.substring(1);
                }

                try {
                    JSONObject parsed = new JSONObject(normalized);
                    String code = parsed.optString("code", "?");
                    String message = parsed.optString("message", "");
                    logger.debug("  Decoded: code=" + code + " message=" + message);
                    return parsed;
                } catch (Exception e) {
                    logger.warn("  Decoded response is not valid JSON from " + endpoint);
                    throw new IOException("Decoded response is not JSON from " + endpoint);
                }
            }
        } finally {
            activeCalls.remove(owner, call);
        }
    }

    /** Cancel the one request currently owned by a router worker thread. */
    public void cancelCallForThread(Thread owner) {
        if (owner == null) return;
        Call call = activeCalls.get(owner);
        if (call != null) {
            logger.info("Cancelling timed-out BYD cloud HTTP call");
            call.cancel();
        }
    }

    private static void throwIfRequestCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("BYD cloud request cancelled");
        }
    }

    /**
     * Decrypt a respondData hex string using the given content key.
     */
    public static JSONObject decryptRespondData(String respondDataHex, String contentKeyHex) {
        String plain = BydCryptoUtils.aesDecryptUtf8(respondDataHex, contentKeyHex);
        try {
            return new JSONObject(plain);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse decrypted respondData", e);
        }
    }
}
