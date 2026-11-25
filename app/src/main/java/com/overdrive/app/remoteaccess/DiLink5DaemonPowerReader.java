package com.overdrive.app.remoteaccess;

import android.app.Application;
import android.os.Build;
import android.os.SystemClock;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.remote.RemoteDevViewBridgeAuth;
import com.overdrive.app.util.DaemonHttpClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * On-demand vehicle power read through the shell-owned daemon.
 *
 * <p>The app UID cannot perform the daemon's DiLink 5 car-service dump, and some
 * newer default-mode firmwares deny its bodywork getter. This reader uses
 * the existing authenticated loopback relay, never starts a worker or timer,
 * and keeps only a short process-local result for concurrent native callers.
 */
public final class DiLink5DaemonPowerReader {
    public static final long MAX_SAMPLE_AGE_MS = 3_000L;
    static final long CACHE_TTL_MS = 1_000L;
    static final int MAX_RESPONSE_BYTES = 8_192;
    private static final long TRANSPORT_TIMEOUT_MS = 2_000L;
    private static final String APP_PROCESS = "com.overdrive.app";
    private static final Reader READER = new Reader(
            SystemClock::elapsedRealtime,
            DiLink5DaemonPowerReader::currentScope,
            DiLink5DaemonPowerReader::exchange,
            RemoteDevViewBridgeAuth::sign,
            envelope -> RemoteDevViewBridgeAuth.verify(envelope).request,
            () -> UUID.randomUUID().toString());

    private DiLink5DaemonPowerReader() {}

    public static int readNow() {
        return READER.readNow();
    }

    static boolean isAppProcess() {
        try {
            return APP_PROCESS.equals(Application.getProcessName());
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static Scope currentScope() {
        if (!isAppProcess()) return null;
        boolean bodywork = !DiLink5Platform.isSelected();
        if (bodywork && (Build.VERSION.SDK_INT < 31 || Build.VERSION.SDK_INT > 34)) return null;
        String generation = DiLink5Platform.currentActiveModeGeneration();
        if (generation == null || generation.isEmpty()) return null;
        return new Scope(generation, RemoteAccessSettings.read().optLong("revision", -1L), bodywork);
    }

    private static String exchange(String envelope) throws Exception {
        HttpURLConnection connection = null;
        long deadline = SystemClock.elapsedRealtime() + TRANSPORT_TIMEOUT_MS;
        try {
            byte[] bytes = envelope.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_RESPONSE_BYTES)
                throw new IllegalArgumentException("Power request is too large");
            connection = DaemonHttpClient.open("/api/remote-access/relay", "POST", 500, 1500);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK)
                throw new IllegalStateException("Power response was refused");
            if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES)
                throw new IllegalArgumentException("Power response is too large");
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                while (true) {
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining <= 0L)
                        throw new IllegalStateException("Power response timed out");
                    connection.setReadTimeout((int) Math.min(1500L, remaining));
                    int count = input.read(buffer);
                    if (count < 0) break;
                    if (output.size() + count > MAX_RESPONSE_BYTES)
                        throw new IllegalArgumentException("Power response is too large");
                    output.write(buffer, 0, count);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    interface Clock { long elapsedRealtime(); }
    interface ScopeSource { Scope current(); }
    interface Transport { String exchange(String signedRequest) throws Exception; }
    interface Signer { String sign(JSONObject request) throws Exception; }
    interface Verifier { JSONObject verify(String envelope) throws Exception; }
    interface RequestIdSource { String next(); }

    static final class Scope {
        final String modeGeneration;
        final long revision;
        final boolean bodywork;

        Scope(String modeGeneration, long revision) {
            this(modeGeneration, revision, false);
        }

        Scope(String modeGeneration, long revision, boolean bodywork) {
            this.modeGeneration = modeGeneration;
            this.revision = revision;
            this.bodywork = bodywork;
        }

        String requestCommand() {
            return bodywork ? "remoteAccessBodyworkPowerRequest" : "remoteAccessPowerRequest";
        }

        String responseCommand() {
            return bodywork ? "remoteAccessBodyworkPowerResponse" : "remoteAccessPowerResponse";
        }

        boolean valid() {
            return modeGeneration != null && !modeGeneration.isEmpty() && revision >= 0L;
        }

        boolean same(Scope other) {
            return other != null && bodywork == other.bodywork && revision == other.revision
                    && modeGeneration.equals(other.modeGeneration);
        }
    }

    /** Serializes concurrent callers without introducing another background lane. */
    static final class Reader {
        private final Clock clock;
        private final ScopeSource scopes;
        private final Transport transport;
        private final Signer signer;
        private final Verifier verifier;
        private final RequestIdSource ids;
        private Scope cachedScope;
        private long cachedAtMs = -1L;
        private long observedAtMs = -1L;
        private int cachedPower = BydVehicleData.UNAVAILABLE;

        Reader(Clock clock, ScopeSource scopes, Transport transport,
               Signer signer, Verifier verifier, RequestIdSource ids) {
            this.clock = clock;
            this.scopes = scopes;
            this.transport = transport;
            this.signer = signer;
            this.verifier = verifier;
            this.ids = ids;
        }

        synchronized int readNow() {
            Scope requestedScope = null;
            long started = -1L;
            try {
                if (Thread.currentThread().isInterrupted()) {
                    clear();
                    return BydVehicleData.UNAVAILABLE;
                }
                requestedScope = scopes.current();
                if (requestedScope == null || !requestedScope.valid()) {
                    clear();
                    return BydVehicleData.UNAVAILABLE;
                }
                started = clock.elapsedRealtime();
                if (Thread.currentThread().isInterrupted()) {
                    clear();
                    return BydVehicleData.UNAVAILABLE;
                }
                if (requestedScope.same(cachedScope) && cachedAtMs >= 0L
                        && started >= cachedAtMs && started - cachedAtMs < CACHE_TTL_MS
                        && (cachedPower == BydVehicleData.UNAVAILABLE
                            || fresh(observedAtMs, started))) {
                    return cachedPower;
                }
                // Retire the previous OFF before transport or authentication can fail.
                clear();
                String requestId = ids.next();
                if (requestId == null || requestId.isEmpty())
                    return BydVehicleData.UNAVAILABLE;
                JSONObject request = new JSONObject()
                        .put("command", requestedScope.requestCommand())
                        .put("requestId", requestId)
                        .put("modeGeneration", requestedScope.modeGeneration)
                        .put("revision", requestedScope.revision);
                String signedRequest = signer.sign(request);
                if (Thread.currentThread().isInterrupted())
                    return BydVehicleData.UNAVAILABLE;
                String response = transport.exchange(signedRequest);
                if (response == null
                        || response.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES)
                    throw new IllegalArgumentException("Power response is missing or too large");
                JSONObject payload = verifier.verify(response);
                long completed = clock.elapsedRealtime();
                if (!requestedScope.same(scopes.current()))
                    return BydVehicleData.UNAVAILABLE;
                int power = validate(payload, requestId, requestedScope, started, completed);
                if (Thread.currentThread().isInterrupted())
                    return BydVehicleData.UNAVAILABLE;
                cachedScope = requestedScope;
                cachedAtMs = completed;
                observedAtMs = integral(payload.get("observedAtElapsedMs"));
                cachedPower = power;
                return power;
            } catch (Throwable unavailable) {
                clear();
                if (Thread.currentThread().isInterrupted())
                    return BydVehicleData.UNAVAILABLE;
                // A failed query is UNKNOWN, and shares the same short retry
                // budget so multiple phones cannot queue repeated failed I/O.
                try {
                    long completed = clock.elapsedRealtime();
                    if (requestedScope != null && requestedScope.valid()
                            && requestedScope.same(scopes.current()) && completed >= started) {
                        cachedScope = requestedScope;
                        cachedAtMs = completed;
                    }
                } catch (Throwable ignored) {
                    clear();
                }
                return BydVehicleData.UNAVAILABLE;
            }
        }

        private void clear() {
            cachedScope = null;
            cachedAtMs = -1L;
            observedAtMs = -1L;
            cachedPower = BydVehicleData.UNAVAILABLE;
        }
    }

    static int validate(JSONObject payload, String requestId, Scope scope,
                        long requestedAtMs, long nowMs) throws Exception {
        if (payload == null || !scope.responseCommand().equals(payload.opt("command"))
                || !Boolean.TRUE.equals(payload.opt("success"))
                || !requestId.equals(payload.opt("requestId"))
                || !scope.modeGeneration.equals(payload.opt("modeGeneration"))
                || integral(payload.get("revision")) != scope.revision)
            throw new SecurityException("Power response scope does not match");
        long observed = integral(payload.get("observedAtElapsedMs"));
        if (observed < requestedAtMs || !fresh(observed, nowMs))
            throw new SecurityException("Power response is stale");
        long power = integral(payload.get("power"));
        if (power != BydVehicleData.UNAVAILABLE
                && (power < 0L || power > (scope.bodywork ? 3L : 1L)))
            throw new IllegalArgumentException("Power response is unavailable");
        return (int) power;
    }

    private static boolean fresh(long observedAtMs, long nowMs) {
        return observedAtMs >= 0L && observedAtMs <= nowMs
                && nowMs - observedAtMs <= MAX_SAMPLE_AGE_MS;
    }

    private static long integral(Object value) {
        if (!(value instanceof Number))
            throw new IllegalArgumentException("Power response value must be an integer");
        try {
            return new BigDecimal(value.toString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IllegalArgumentException("Power response value must be an integer", invalid);
        }
    }
}
