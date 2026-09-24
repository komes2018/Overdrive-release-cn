package com.overdrive.app.power;

/**
 * 12 V guard for the DiLink 5 parked keep-alive lease.
 *
 * <p>Deliberately independent of {@link BatteryVoltageMonitorV2}: that monitor's
 * live samples piggyback {@code BydDataCollector}'s OTA hub and its own start is
 * delayed 35 s after sentry entry, and its 12.0/12.5 V thresholds decide MCU
 * sleep/wake — a different decision from "may this feature keep spending 12 V".
 * The lease feeds this guard directly from {@code BYDAutoOtaDevice
 * .getBatteryPowerVoltage()} on its own 10 s tick.
 *
 * <p>Rules (all from the review of the v1 proposal):
 * <ul>
 *   <li>a valid first sample is required before anything may be held;</li>
 *   <li>a sample older than the configured maximum age blocks new asserts;</li>
 *   <li>{@code cutoffSamples} CONSECUTIVE readings at or below the cutoff trip
 *       the guard (debounce);</li>
 *   <li>once tripped the guard stays LATCHED until ACC ON or confirmed external
 *       charging — never an automatic resume on voltage rebound, which would
 *       oscillate as surface charge recovers.</li>
 * </ul>
 *
 * <p>Pure Java, single-threaded by contract (the lease serializes calls).
 */
public final class Di5KeepAliveVoltageGuard {

    public enum Decision {
        /** Fresh sample above the cutoff and not latched: holds may run. */
        ALLOW,
        /** No valid sample has ever been received. */
        NO_SAMPLE,
        /** Last sample is older than the configured maximum age. */
        STALE,
        /** Cutoff tripped; latched until {@link #releaseLatch(String)}. */
        CUTOFF_LATCHED
    }

    private double cutoffVoltage;
    private int cutoffSamples;
    private long maxAgeMs;

    private boolean haveSample;
    private double lastVoltage;
    private long lastSampleAtMs = Long.MIN_VALUE;
    private int consecutiveLow;
    private boolean latched;
    private String latchReason;
    private long latchedAtMs;

    public Di5KeepAliveVoltageGuard(double cutoffVoltage, int cutoffSamples, long maxAgeMs) {
        reconfigure(cutoffVoltage, cutoffSamples, maxAgeMs);
    }

    /** Apply new thresholds without losing sample history or the latch. */
    public void reconfigure(double cutoffVoltage, int cutoffSamples, long maxAgeMs) {
        this.cutoffVoltage = cutoffVoltage;
        this.cutoffSamples = Math.max(1, cutoffSamples);
        this.maxAgeMs = Math.max(1L, maxAgeMs);
    }

    /**
     * Feed one reading. Non-finite or non-positive readings are treated as
     * "no sample" for freshness purposes (they do not refresh the timestamp) and
     * never count towards the cutoff.
     *
     * @return true when this sample tripped the cutoff (edge, not level)
     */
    public boolean observe(double volts, long nowMs) {
        if (Double.isNaN(volts) || Double.isInfinite(volts) || volts <= 0.0) {
            return false;
        }
        haveSample = true;
        lastVoltage = volts;
        lastSampleAtMs = nowMs;
        if (latched) {
            return false;
        }
        if (volts <= cutoffVoltage) {
            consecutiveLow++;
            if (consecutiveLow >= cutoffSamples) {
                latched = true;
                latchedAtMs = nowMs;
                latchReason = "12V " + volts + " V <= " + cutoffVoltage
                        + " V for " + consecutiveLow + " consecutive samples";
                return true;
            }
        } else {
            consecutiveLow = 0;
        }
        return false;
    }

    /** Current verdict for the lease. */
    public Decision evaluate(long nowMs) {
        if (latched) return Decision.CUTOFF_LATCHED;
        if (!haveSample) return Decision.NO_SAMPLE;
        if (nowMs - lastSampleAtMs > maxAgeMs) return Decision.STALE;
        return Decision.ALLOW;
    }

    /**
     * Clear the latch. Only ACC ON or confirmed external charging may call this;
     * a voltage rebound must not.
     */
    public void releaseLatch(String reason) {
        latched = false;
        latchReason = null;
        latchedAtMs = 0L;
        consecutiveLow = 0;
    }

    /** Forget sample history (e.g. a new parked session). Keeps the latch. */
    public void resetSamples() {
        haveSample = false;
        lastVoltage = 0.0;
        lastSampleAtMs = Long.MIN_VALUE;
        consecutiveLow = 0;
    }

    public boolean isLatched() { return latched; }
    public String latchReason() { return latchReason; }
    public long latchedAtMs() { return latchedAtMs; }
    public boolean hasSample() { return haveSample; }
    public double lastVoltage() { return lastVoltage; }
    public long lastSampleAtMs() { return lastSampleAtMs; }
    public int consecutiveLowSamples() { return consecutiveLow; }
    public double cutoffVoltage() { return cutoffVoltage; }
    public int cutoffSamples() { return cutoffSamples; }

    /** Age of the last sample in ms, or -1 when no sample exists. */
    public long sampleAgeMs(long nowMs) {
        return haveSample ? Math.max(0L, nowMs - lastSampleAtMs) : -1L;
    }
}
