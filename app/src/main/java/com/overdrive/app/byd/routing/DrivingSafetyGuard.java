package com.overdrive.app.byd.routing;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.VehicleActuatorBridge;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.monitor.AccMonitor;
import com.overdrive.app.monitor.GearMonitor;
import com.overdrive.app.monitor.GpsMonitor;
import com.overdrive.app.util.DaemonHttpClient;

import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Decides whether a motion-sensitive vehicle action is safe to run right now.
 *
 * <p>Split into a pure decision function ({@link #isBlocked}) and a live wrapper
 * ({@link #isMovementBlocked()}) so the policy remains unit-testable without Android/HAL
 * dependencies. Mis-gating here means either allowing a physical action while moving or
 * breaking normal parked remote control.
 *
 * <p>Callers ask only the raw fact ({@code isMovementBlocked(): boolean}) — this class
 * knows nothing about {@link VehicleCommandRouter.VehicleCommand} or risk tiers, so it
 * can gate both the router's dispatch path and the unrelated screen/media endpoints in
 * {@code VehicleControlApiHandler} without either needing to know about the other.
 */
public final class DrivingSafetyGuard {

    private DrivingSafetyGuard() {}

    public static final String GUARD_DOOR_LOCKS = "doorLocks";
    public static final String GUARD_TRUNK = "trunk";
    public static final String GUARD_MIRROR_FOLD = "mirrorFold";
    public static final String GUARD_POSITIONING = "positioning";
    public static final String GUARD_HEADLIGHT_OFF = "headlightOff";
    public static final String GUARD_DISPLAY_BRIGHTNESS = "displayBrightness";
    public static final String GUARD_DISPLAY_POWER = "displayPower";
    public static final String GUARD_SCREEN_MEDIA = "screenMedia";

    private static final String[] GUARD_KEYS = {
            GUARD_DOOR_LOCKS,
            GUARD_TRUNK,
            GUARD_MIRROR_FOLD,
            GUARD_POSITIONING,
            GUARD_HEADLIGHT_OFF,
            GUARD_DISPLAY_BRIGHTNESS,
            GUARD_DISPLAY_POWER,
            GUARD_SCREEN_MEDIA
    };

    /**
     * Gear reads P but speed is still above this, we still treat it as moving
     * ("rolling in Park"). ~0.5 m/s (TripDetector's GPS threshold) converted to
     * km/h with a small margin.
     */
    private static final double PARKED_SPEED_THRESHOLD_KMH = 2.0;

    /** How stale a GearMonitor observation can be before we fail closed. */
    private static final long GEAR_FRESHNESS_MS = 1000L;
    private static final long GPS_SPEED_MAX_AGE_MS = 5_000L;
    private static final float GPS_SPEED_MAX_ACCURACY_M = 20.0f;
    private static final double GPS_SPEED_MAX_KMH = 300.0;

    enum GearReading { PARK, NOT_PARK, UNKNOWN }

    public static boolean isKnownGuard(String key) {
        if (key == null) return false;
        for (String candidate : GUARD_KEYS) {
            if (candidate.equals(key)) return true;
        }
        return false;
    }

    /**
     * Per-action policy. Missing, malformed, and unknown settings all keep the
     * guard enabled so an old/corrupt config cannot silently remove protection.
     */
    static boolean isGuardEnabled(JSONObject settings, String key) {
        if (key == null || !isKnownGuard(key)) return true;
        Object value = settings != null ? settings.opt(key) : null;
        return !(value instanceof Boolean) || ((Boolean) value).booleanValue();
    }

    public static boolean isGuardEnabled(String key) {
        try {
            return isGuardEnabled(UnifiedConfigManager.getDrivingSafety(), key);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** Complete settings snapshot for the API/UI, including default-on values. */
    public static JSONObject getGuardSettings() {
        JSONObject configured = null;
        try {
            configured = UnifiedConfigManager.getDrivingSafety();
        } catch (Throwable ignored) {
        }
        JSONObject resolved = new JSONObject();
        for (String key : GUARD_KEYS) {
            try {
                resolved.put(key, isGuardEnabled(configured, key));
            } catch (Exception ignored) {
            }
        }
        return resolved;
    }

    /** True only when this action's guard is enabled and the live vehicle state blocks it. */
    public static boolean isActionBlocked(String key) {
        return isGuardEnabled(key) && isMovementBlocked();
    }

    /** Machine-readable live reason for a blocked movement gate, or {@code null} when allowed. */
    public static String getMovementBlockReason() {
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            boolean scoped = VehicleActuatorBridge.hasDiLink5RequestScope();
            Boolean scopedAccOn = scoped
                    ? VehicleActuatorBridge.currentDiLink5RequestAccOn() : null;
            boolean scopedStateValid = scoped
                    && scopedAccOn != null
                    && !VehicleActuatorBridge.isDiLink5RequestExpired();
            return blockReasonDiLink5(
                    resolveGear(),
                    scopedAccOn != null
                            ? scopedAccOn.booleanValue() : AccMonitor.isAccOn(),
                    scoped ? scopedStateValid : AccMonitor.isAccStateAuthoritative(),
                    scoped ? scopedStateValid : AccMonitor.isAccStateFreshForSafety(),
                    resolveSpeedKmh());
        }
        boolean accAuthoritative = AccMonitor.isAccStateAuthoritative();
        if (!accAuthoritative) return "state_unknown";
        if (!AccMonitor.isAccOn()) return null;
        return blockReason(resolveGear(), true, true, resolveSpeedKmh());
    }

    /**
     * IVI reboot may proceed with unavailable speed only after the movement gate
     * has already confirmed authoritative ACC state and Park. All other unknown
     * or moving states remain blocked.
     */
    public static String getIviRebootBlockReason() {
        return iviRebootBlockReason(getMovementBlockReason());
    }

    static String iviRebootBlockReason(String movementBlockReason) {
        return "speed_unknown".equals(movementBlockReason)
                ? null : movementBlockReason;
    }

    /**
     * App-process final-boundary check against the daemon's authoritative vehicle state.
     * Network/config failures fail closed.
     */
    public static boolean isActionBlockedViaDaemon(String key) {
        if (!isKnownGuard(key)) return true;
        HttpURLConnection connection = null;
        try {
            connection = DaemonHttpClient.open(
                    "/api/vehicle/driving-safety/" + key, "GET", 750, 1000);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return true;
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONObject response = new JSONObject(body.toString());
            return !isDaemonResponseUnblocked(response, key);
        } catch (Throwable ignored) {
            return true;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static boolean isDaemonResponseUnblocked(JSONObject response, String key) {
        return response != null
                && key != null
                && Boolean.TRUE.equals(response.opt("success"))
                && key.equals(response.opt("guard"))
                && Boolean.FALSE.equals(response.opt("blocked"));
    }

    /**
     * Pure policy — no Android, no singletons, no side effects. Package-visible
     * so tests can exercise it directly.
     *
     * <p>Fail-closed by design: every ambiguous case (unauthoritative ACC, unknown
     * gear, missing speed) returns {@code true} (blocked). This is a deliberate
     * deviation from this codebase's usual "degrade gracefully" convention for
     * not-yet-ready subsystems — for a safety gate, defaulting an unknown state to
     * "allow" would defeat the point of the gate.
     */
    static boolean isBlocked(GearReading gear, boolean accOn, boolean accAuthoritative, double speedKmh) {
        return blockReason(gear, accOn, accAuthoritative, speedKmh) != null;
    }

    static String blockReason(
            GearReading gear, boolean accOn, boolean accAuthoritative, double speedKmh) {
        if (accAuthoritative && !accOn) return null;
        if (!accAuthoritative) return "state_unknown";
        if (gear == GearReading.UNKNOWN) return "gear_unknown";
        if (gear != GearReading.PARK) return "not_park";
        if (Double.isNaN(speedKmh)) return "speed_unknown";
        return speedKmh > PARKED_SPEED_THRESHOLD_KMH ? "moving" : null;
    }

    static String blockReasonDiLink5(
            GearReading gear,
            boolean accOn,
            boolean accAuthoritative,
            boolean accFresh,
            double speedKmh) {
        if (gear == GearReading.NOT_PARK) return "not_park";
        if (!Double.isNaN(speedKmh) && speedKmh > PARKED_SPEED_THRESHOLD_KMH) {
            return "moving";
        }
        if (!accAuthoritative || !accFresh) return "state_unknown";
        if (!accOn) return null;
        if (gear == GearReading.UNKNOWN) return "gear_unknown";
        if (Double.isNaN(speedKmh)) return "speed_unknown";
        return null;
    }

    /** Live wrapper — reads AccMonitor / GearMonitor / BydDataCollector singletons. */
    public static boolean isMovementBlocked() {
        return getMovementBlockReason() != null;
    }

    private static GearReading resolveGear() {
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            return gearReading(BydDataCollector.getInstance().readGearNow());
        }
        GearMonitor gm = GearMonitor.getInstance();
        if (gm == null) return GearReading.UNKNOWN;
        if (!gm.isRunning()) return GearReading.UNKNOWN;
        long age = SystemClock.elapsedRealtime() - gm.getLastUpdateTime();
        if (age >= 0 && age < GEAR_FRESHNESS_MS) {
            return gearReading(gm.getCurrentGear());
        }
        return GearReading.UNKNOWN;
    }

    static GearReading gearReading(int gear) {
        if (gear == GearMonitor.GEAR_P) return GearReading.PARK;
        return gear > GearMonitor.GEAR_P && gear <= GearMonitor.GEAR_S
                ? GearReading.NOT_PARK : GearReading.UNKNOWN;
    }

    private static double resolveSpeedKmh() {
        double vehicleSpeedKmh =
                BydDataCollector.getInstance().readCurrentSpeedKmh();
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            return vehicleSpeedKmh;
        }
        try {
            GpsMonitor.GpsFixSnapshot fix =
                    GpsMonitor.getInstance().getFixSnapshot();
            long ageMs = fix.fixElapsedMs > 0L
                    ? SystemClock.elapsedRealtime() - fix.fixElapsedMs
                    : System.currentTimeMillis() - fix.lastUpdate;
            return preferFreshGpsSpeedKmh(
                    vehicleSpeedKmh,
                    fix.speed,
                    fix.accuracy,
                    ageMs,
                    fix.loadedFromCache);
        } catch (Throwable ignored) {
            return vehicleSpeedKmh;
        }
    }

    static double preferFreshGpsSpeedKmh(
            double vehicleSpeedKmh,
            float gpsSpeedMps,
            float gpsAccuracyM,
            long gpsAgeMs,
            boolean gpsLoadedFromCache) {
        double gpsSpeedKmh = gpsSpeedMps * 3.6;
        if (gpsLoadedFromCache
                || Float.isNaN(gpsSpeedMps)
                || Float.isInfinite(gpsSpeedMps)
                || gpsSpeedMps < 0.0f
                || Float.isNaN(gpsAccuracyM)
                || Float.isInfinite(gpsAccuracyM)
                || gpsAccuracyM <= 0.0f
                || gpsAccuracyM > GPS_SPEED_MAX_ACCURACY_M
                || gpsAgeMs < 0L
                || gpsAgeMs > GPS_SPEED_MAX_AGE_MS
                || gpsSpeedKmh > GPS_SPEED_MAX_KMH) {
            return vehicleSpeedKmh;
        }
        return gpsSpeedKmh;
    }
}
