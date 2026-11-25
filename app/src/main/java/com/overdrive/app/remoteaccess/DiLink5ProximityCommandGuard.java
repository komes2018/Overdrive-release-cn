package com.overdrive.app.remoteaccess;

import android.os.SystemClock;
import com.overdrive.app.byd.DiLink5LockRail;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.remote.RemoteDevViewBridgeAuth;
import com.overdrive.app.util.DaemonHttpClient;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** One bounded, authenticated check at an automated Di5 lock write; no worker or timer. */
public final class DiLink5ProximityCommandGuard {
    private static final int MAX_BYTES = 8192;
    public static final String EXTRA = "bridge_proximity_command";

    private DiLink5ProximityCommandGuard() {}

    public static final class Scope {
        public final String token, action, modeGeneration;
        public final long revision, expiresAt;
        /**
         * Optional DI5 gate evidence the daemon selected for this command (the actuation rail's
         * tag, app-domain state, SDK read time, raw value): all four present or all absent. The app
         * selects its write path from {@link #gateRailOrNull()}; absent (older daemon, manual
         * command) means today's typed path.
         */
        public final String gateRail;
        public final int gateState;
        public final long gateObservedElapsedMs;
        public final int gateRaw;

        public Scope(String token, String action, String modeGeneration, long revision, long expiresAt) {
            this(token, action, modeGeneration, revision, expiresAt, null, 0, 0L, 0);
        }

        public Scope(String token, String action, String modeGeneration, long revision, long expiresAt,
                     String gateRail, int gateState, long gateObservedElapsedMs, int gateRaw) {
            if (token == null || !token.matches("[a-f0-9-]{36}")
                    || (!"lock".equals(action) && !"unlock".equals(action))
                    || modeGeneration == null || modeGeneration.isEmpty()
                    || modeGeneration.length() > 1024 || revision < 0L || expiresAt <= 0L)
                throw new IllegalArgumentException("Invalid proximity command scope");
            boolean gatePresent = gateRail != null;
            boolean gateValid = gatePresent
                    ? DiLink5LockRail.fromTag(gateRail) != null
                            && (gateState == 1 || gateState == 2) && gateObservedElapsedMs > 0L
                    : gateState == 0 && gateObservedElapsedMs == 0L;
            if (!gateValid) throw new IllegalArgumentException("Invalid proximity command gate evidence");
            this.token = token;
            this.action = action;
            this.modeGeneration = modeGeneration;
            this.revision = revision;
            this.expiresAt = expiresAt;
            this.gateRail = gateRail;
            this.gateState = gateState;
            this.gateObservedElapsedMs = gateObservedElapsedMs;
            this.gateRaw = gateRaw;
        }

        public JSONObject toJson() throws Exception {
            JSONObject value = new JSONObject().put("token", token).put("action", action)
                    .put("modeGeneration", modeGeneration).put("revision", revision)
                    .put("expiresAtElapsedMs", expiresAt);
            if (gateRail != null) {
                value.put("gateRail", gateRail).put("gateState", gateState)
                        .put("gateObservedElapsedMs", gateObservedElapsedMs).put("gateRaw", gateRaw);
            }
            return value;
        }

        public static Scope parse(String encoded) throws Exception {
            JSONObject value = new JSONObject(encoded);
            int gateKeys = (value.has("gateRail") ? 1 : 0) + (value.has("gateState") ? 1 : 0)
                    + (value.has("gateObservedElapsedMs") ? 1 : 0) + (value.has("gateRaw") ? 1 : 0);
            if (gateKeys == 0) {
                return new Scope(value.getString("token"), value.getString("action"),
                        value.getString("modeGeneration"), exact(value.get("revision")),
                        exact(value.get("expiresAtElapsedMs")));
            }
            if (gateKeys != 4) throw new IllegalArgumentException("Invalid proximity command gate evidence");
            return new Scope(value.getString("token"), value.getString("action"),
                    value.getString("modeGeneration"), exact(value.get("revision")),
                    exact(value.get("expiresAtElapsedMs")), value.getString("gateRail"),
                    exactInt(value.get("gateState")), exact(value.get("gateObservedElapsedMs")),
                    exactInt(value.get("gateRaw")));
        }

        /** The daemon-selected gate rail, or null when the scope carries no gate evidence. */
        public DiLink5LockRail gateRailOrNull() {
            return DiLink5LockRail.fromTag(gateRail);
        }

        public boolean locallyCurrent() {
            try {
                JSONObject settings = RemoteAccessSettings.read();
                return locallyAllowed(this, settings, SystemClock.elapsedRealtime(),
                        DiLink5Platform.isSelected()
                                && DiLink5Platform.matchesActiveModeGeneration(modeGeneration),
                        UnifiedConfigManager.isBluetoothKeepAliveEnabled(),
                        UnifiedConfigManager.isVehicleOnOnlyMode());
            } catch (Throwable unavailable) { return false; }
        }
    }

    static boolean locallyAllowed(Scope scope, JSONObject settings, long now,
                                  boolean modeCurrent, boolean bluetoothEnabled, boolean onOnly) {
        return scope != null && now >= 0L && now < scope.expiresAt && modeCurrent && !onOnly
                && bluetoothEnabled && settings.optBoolean("enabled")
                && !settings.optBoolean("dryRun", true)
                && settings.optLong("revision", -1L) == scope.revision
                && settings.optBoolean("unlock".equals(scope.action) ? "unlockEnabled" : "lockEnabled");
    }

    public static boolean sameEpisode(long originalSequence, long currentSequence,
                                      long lastStateChangeSequence) {
        return originalSequence > 0L && currentSequence >= originalSequence
                && lastStateChangeSequence <= originalSequence;
    }

    /** A held NEAR median cannot authorize a delayed write after the latest signal weakened. */
    public static boolean latestArrivalReadingAgrees(
            Object rawRssi, int nearThreshold, boolean connected) {
        if (!connected || !(rawRssi instanceof Number)) return false;
        double raw = ((Number) rawRssi).doubleValue();
        return Double.isFinite(raw) && raw == Math.rint(raw)
                && raw >= -127 && raw <= 20 && raw >= nearThreshold;
    }

    public static boolean confirmBeforeWrite(Scope scope) {
        HttpURLConnection connection = null;
        try {
            if (scope == null || !scope.locallyCurrent()) return false;
            long requestedAt = SystemClock.elapsedRealtime();
            String requestId = UUID.randomUUID().toString();
            JSONObject request = scope.toJson().put("command", "remoteAccessActuationCheck")
                    .put("requestId", requestId);
            byte[] bytes = RemoteDevViewBridgeAuth.sign(request).getBytes(StandardCharsets.UTF_8);
            connection = DaemonHttpClient.open("/api/remote-access/relay", "POST", 400, 750);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (SystemClock.elapsedRealtime() - requestedAt > 1000L
                            || output.size() + count > MAX_BYTES) return false;
                    output.write(buffer, 0, count);
                }
                JSONObject response = RemoteDevViewBridgeAuth.verify(
                        new String(output.toByteArray(), StandardCharsets.UTF_8)).request;
                return validReply(scope, response, requestId, requestedAt, SystemClock.elapsedRealtime())
                        && scope.locallyCurrent();
            }
        } catch (Throwable unavailable) { return false; }
        finally { if (connection != null) connection.disconnect(); }
    }

    static boolean validReply(Scope scope, JSONObject response, String requestId,
                              long requestedAt, long now) {
        try {
            long observed = exact(response.get("observedAtElapsedMs"));
            return Boolean.TRUE.equals(response.opt("allowed"))
                    && "remoteAccessActuationResponse".equals(response.opt("command"))
                    && requestId.equals(response.opt("requestId"))
                    && scope.token.equals(response.opt("token"))
                    && scope.action.equals(response.opt("action"))
                    && scope.modeGeneration.equals(response.opt("modeGeneration"))
                    && exact(response.get("revision")) == scope.revision
                    && observed >= requestedAt && observed <= now && now - observed <= 250L
                    && now < scope.expiresAt;
        } catch (Exception malformed) { return false; }
    }

    private static long exact(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Expected integer");
        return new java.math.BigDecimal(value.toString()).longValueExact();
    }

    private static int exactInt(Object value) {
        long exact = exact(value);
        if (exact < Integer.MIN_VALUE || exact > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Expected int");
        return (int) exact;
    }
}
