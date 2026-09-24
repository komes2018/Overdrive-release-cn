package com.overdrive.app.parking;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One parked period: opened at ACC-off, closed at return. Plain mutable
 * record; persistence in {@link ParkingStore}, JSON shape for the API in
 * {@link #toJson()}.
 */
public final class ParkingSession {

    /** GPS quality label shown to the user. */
    public static final String GPS_FRESH = "FRESH";      // fix < 60 s old
    public static final String GPS_RECENT = "RECENT";    // fix < 5 min old
    public static final String GPS_STALE = "STALE";      // older — probably a garage
    public static final String GPS_UNKNOWN = "UNKNOWN";  // no usable fix

    /** Why the session closed. */
    public static final String END_DOOR_OPEN = "door_open";
    public static final String END_UNLOCK = "unlock";
    public static final String END_ACC_ON = "acc_on";
    /** drive_away end trigger: the gear left P after ACC-on. */
    public static final String END_DRIVE_AWAY = "drive_away";
    public static final String END_RECOVERED = "recovered";
    public static final String END_SUPERSEDED = "superseded";
    /** The user switched the feature off while parked. */
    public static final String END_DISABLED = "disabled";

    /**
     * Wall-clock floor (2020-01-01T00:00Z). A head unit that cold-boots without
     * an RTC reading stamps 1970 until GPS/network time arrives; timestamps
     * below this are not trusted for durations or age-based retention.
     */
    public static final long CLOCK_FLOOR_MS = 1_577_836_800_000L;

    /** Sentry state observed for this park (why there may be no camera data). */
    public static final String SENTRY_ARMED = "armed";
    public static final String SENTRY_LOCK_WAIT = "lock_wait";
    public static final String SENTRY_PIPELINE_DOWN = "pipeline_down";
    public static final String SENTRY_SUPPRESSED_SAFE_ZONE = "suppressed_safe_zone";
    public static final String SENTRY_SUPPRESSED_SCHEDULE = "suppressed_schedule";
    public static final String SENTRY_SURVEILLANCE_OFF = "surveillance_off";
    public static final String SENTRY_VEHICLE_ON_ONLY = "vehicle_on_only";
    public static final String SENTRY_UNKNOWN = "unknown";

    public static final String SIGNAGE_PENDING = "pending";
    public static final String SIGNAGE_DONE = "done";
    public static final String SIGNAGE_SKIPPED = "skipped";
    public static final String SIGNAGE_UNAVAILABLE = "unavailable";

    public String sessionId;
    public long startedMs;
    public long endedMs;              // 0 = open
    public long transitionGeneration;
    public String endTrigger;

    public double lat = Double.NaN;
    public double lng = Double.NaN;
    public float accuracyM;
    public long fixAgeMs = -1;
    public boolean fixFromCache;
    public String gpsQuality = GPS_UNKNOWN;

    public String placeShort;
    public String placeDisplay;
    public String placeSource;
    public String safeZone;
    public String sentryState = SENTRY_UNKNOWN;

    public long arrivedSnapshotMs;
    public boolean arrivedSnapshotOk;
    public long returnedSnapshotMs;
    public boolean returnedSnapshotOk;
    public int rectifyStrength;

    public String signageJson;        // v2 result blob, may be null
    public String signageState = SIGNAGE_PENDING;

    public boolean notifiedStarted;
    public boolean notifiedEnded;

    public int eventCount;
    public int neighbourCount;
    public long createdMs;

    // Energy bookends at open and at a GENUINE return close (acc_on / door_open
    // / unlock / drive_away / disabled — never a recovered or superseded repair
    // close, whose "now" belongs to a different moment). NaN = no trustworthy
    // reading was available at that bookend.
    //
    // Two channels, same as the trips page: the BMS remaining energy (kWh, the
    // direct measurement — trips' kwhStart/kwhEnd) is the primary source; the
    // HV state of charge is the fallback, because the polled SoC getter is
    // INTEGER on most trims (whole-percent steps ≈ 0.6–0.8 kWh) and a short
    // park then reads as a flat 0.
    public double startSocPercent = Double.NaN;
    public double endSocPercent = Double.NaN;
    public double startRemainKwh = Double.NaN;
    public double endRemainKwh = Double.NaN;
    /** The pack ended fuller than it started: the car charged while parked. */
    public boolean chargedWhileParked;
    /** SoC-derived estimate |ΔSoC| × nominal × SOH, kWh, frozen at close; NaN unknown. */
    public double energyEstKwh = Double.NaN;

    /** Energy source labels in the JSON / UI. */
    public static final String ENERGY_SRC_BMS = "bms";
    public static final String ENERGY_SRC_SOC = "soc";
    /** Below this a BMS delta is meter jitter, not a charge or a draw (trips' MIN_SESSION_KWH). */
    public static final double MIN_ENERGY_KWH = 0.05;
    /** Below this a SoC-only delta is the integer gauge's quantization, not a change. */
    public static final double MIN_SOC_DELTA_PERCENT = 0.5;

    public boolean isOpen() { return endedMs <= 0; }

    public boolean hasSocBookends() {
        return !Double.isNaN(startSocPercent) && !Double.isNaN(endSocPercent);
    }

    public boolean hasKwhBookends() {
        return !Double.isNaN(startRemainKwh) && !Double.isNaN(endRemainKwh);
    }

    /** Either channel has both ends. */
    public boolean hasEnergyBookends() {
        return hasSocBookends() || hasKwhBookends();
    }

    /** Signed SoC change across the park (end − start); NaN without both bookends. */
    public double socDeltaPercent() {
        return hasSocBookends() ? endSocPercent - startSocPercent : Double.NaN;
    }

    /**
     * Signed net energy change across the park, kWh (end − start: negative =
     * consumed, positive = charged). BMS pair first; else the SoC estimate with
     * the SoC delta's sign; NaN when neither can answer.
     */
    public double energyDeltaKwh() {
        if (hasKwhBookends()) return endRemainKwh - startRemainKwh;
        double soc = socDeltaPercent();
        if (!Double.isNaN(energyEstKwh) && !Double.isNaN(soc) && soc != 0) {
            return Math.signum(soc) * energyEstKwh;
        }
        return Double.NaN;
    }

    /** {@link #ENERGY_SRC_BMS}, {@link #ENERGY_SRC_SOC}, or null when no energy figure exists. */
    public String energySource() {
        if (hasKwhBookends()) return ENERGY_SRC_BMS;
        if (!Double.isNaN(energyEstKwh) && hasSocBookends()) return ENERGY_SRC_SOC;
        return null;
    }

    /**
     * Whether the change is above the source's noise floor and worth showing:
     * a BMS delta beats {@link #MIN_ENERGY_KWH}; a SoC-only delta beats one
     * quantization step. A flat reading is hidden rather than shown as "0".
     */
    public boolean hasMeasurableEnergyChange() {
        if (hasKwhBookends()) return Math.abs(endRemainKwh - startRemainKwh) >= MIN_ENERGY_KWH;
        double soc = socDeltaPercent();
        return !Double.isNaN(soc) && Math.abs(soc) >= MIN_SOC_DELTA_PERCENT;
    }

    public boolean hasFix() { return !Double.isNaN(lat) && !Double.isNaN(lng); }

    /** False when the start was stamped by an unset (1970) clock: durations are meaningless. */
    public boolean clockTrusted() { return startedMs >= CLOCK_FLOOR_MS; }

    public long durationMs(long nowMs) {
        long end = endedMs > 0 ? endedMs : nowMs;
        return Math.max(0L, end - startedMs);
    }

    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("sessionId", sessionId);
            j.put("startedMs", startedMs);
            if (endedMs > 0) j.put("endedMs", endedMs);
            j.put("open", isOpen());
            if (!clockTrusted()) j.put("clockUntrusted", true);
            if (endTrigger != null) j.put("endTrigger", endTrigger);
            if (hasFix()) {
                JSONObject g = new JSONObject();
                g.put("lat", lat);
                g.put("lng", lng);
                if (accuracyM > 0f) g.put("accuracy", (double) accuracyM);
                if (fixAgeMs >= 0) g.put("fixAgeMs", fixAgeMs);
                g.put("fromCache", fixFromCache);
                g.put("quality", gpsQuality);
                j.put("gps", g);
            } else {
                JSONObject g = new JSONObject();
                g.put("quality", GPS_UNKNOWN);
                j.put("gps", g);
            }
            if (placeShort != null || placeDisplay != null) {
                JSONObject p = new JSONObject();
                if (placeShort != null) p.put("short", placeShort);
                if (placeDisplay != null) p.put("displayName", placeDisplay);
                if (placeSource != null) p.put("source", placeSource);
                j.put("place", p);
            }
            if (safeZone != null) j.put("safeZone", safeZone);
            j.put("sentryState", sentryState);
            JSONObject snaps = new JSONObject();
            snaps.put("arrivedOk", arrivedSnapshotOk);
            if (arrivedSnapshotMs > 0) snaps.put("arrivedMs", arrivedSnapshotMs);
            snaps.put("returnedOk", returnedSnapshotOk);
            if (returnedSnapshotMs > 0) snaps.put("returnedMs", returnedSnapshotMs);
            j.put("snapshots", snaps);
            j.put("rectifyStrength", rectifyStrength);
            j.put("signageState", signageState);
            if (signageJson != null && !signageJson.isEmpty()) {
                try { j.put("signage", new JSONObject(signageJson)); }
                catch (Exception e) { j.put("signageRaw", signageJson); }
            }
            j.put("eventCount", eventCount);
            j.put("neighbourCount", neighbourCount);
            j.put("createdMs", createdMs);
            // JSONObject.put(String, double) rejects NaN, so every value is guarded.
            if (!Double.isNaN(startSocPercent) || !Double.isNaN(endSocPercent)
                    || !Double.isNaN(startRemainKwh) || !Double.isNaN(endRemainKwh)) {
                JSONObject e = new JSONObject();
                if (!Double.isNaN(startSocPercent)) e.put("startSoc", startSocPercent);
                if (!Double.isNaN(endSocPercent)) e.put("endSoc", endSocPercent);
                if (!Double.isNaN(startRemainKwh)) e.put("startKwh", startRemainKwh);
                if (!Double.isNaN(endRemainKwh)) e.put("endKwh", endRemainKwh);
                if (hasSocBookends()) e.put("socDelta", socDeltaPercent());
                if (hasEnergyBookends()) {
                    e.put("charged", chargedWhileParked);
                    e.put("measurable", hasMeasurableEnergyChange());
                    double kwh = energyDeltaKwh();
                    if (!Double.isNaN(kwh)) e.put("kwh", kwh);
                    String src = energySource();
                    if (src != null) e.put("source", src);
                    if (!Double.isNaN(energyEstKwh)) e.put("estKwh", energyEstKwh);
                }
                j.put("energy", e);
            }
        } catch (Exception ignored) {}
        return j;
    }

    /** Classify a fix age into the user-facing quality label. */
    public static String qualityFor(boolean hasFix, boolean fromCache, long fixAgeMs) {
        if (!hasFix || fromCache) return GPS_UNKNOWN;
        if (fixAgeMs < 0) return GPS_STALE;
        if (fixAgeMs < 60_000L) return GPS_FRESH;
        if (fixAgeMs < 5L * 60_000L) return GPS_RECENT;
        return GPS_STALE;
    }

    /** Human-readable list helper used by the API for compact rows. */
    public static JSONArray toJsonArray(java.util.List<ParkingSession> list) {
        JSONArray arr = new JSONArray();
        if (list == null) return arr;
        for (ParkingSession s : list) arr.put(s.toJson());
        return arr;
    }
}
