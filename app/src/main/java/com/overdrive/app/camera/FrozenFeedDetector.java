package com.overdrive.app.camera;

/**
 * Frozen-feed detector for the parked (ACC-OFF) pipeline.
 *
 * <p>Field finding (DI5 head unit, default camera mode, 2026-09-21): a few
 * minutes after ACC OFF the AVM HAL can keep queuing buffers at full rate whose
 * CONTENT never changes — the camera/ISP side went dark while the AVM SoC
 * re-emits its last frame. Nothing notices: the frame-stall watchdog only sees
 * "no buffers" (buffers keep coming), and the motion pipeline sees a perfectly
 * still scene and stays quiet. The parked recording then shows one image all
 * night.
 *
 * <p>Detection: a cheap 64-bit FNV-1a hash over a strided sample of the
 * downscaled RGB frame the surveillance engine already receives on the CPU.
 * A live sensor practically never produces two BIT-IDENTICAL downscaled frames
 * (sensor noise survives the GPU downscale, which is deterministic for
 * identical input); a repeated gralloc buffer is identical by construction. So
 * "N consecutive sampled frames with the same hash" separates a frozen feed
 * from a genuinely static scene with no threshold tuning.
 *
 * <p>Cost: one hash every {@link #sampleIntervalMs} (default 2 s), reading
 * every {@link #STRIDE}-th byte — ~7 KB touched per sample on a 640×480×3
 * frame. Zero allocations on the hot path.
 *
 * <p>Pure Java (no Android imports) so the episode state machine is
 * unit-testable on the JVM. The caller decides what to do on
 * {@link Listener#onFrozenFeed}; this class only detects and logs.
 */
public final class FrozenFeedDetector {

    /** Sample every N-th byte of the frame — plenty for bit-identity. */
    static final int STRIDE = 97;  // prime, so it doesn't lock onto row structure

    /** Default cadence between hashed samples. */
    public static final long DEFAULT_SAMPLE_INTERVAL_MS = 2_000L;

    /**
     * Consecutive identical SAMPLES (after the first) before an episode is
     * declared. 5 identical samples at the 2 s cadence = 10 s of provably
     * repeated frames — far beyond any encoder/HAL duplication burst.
     */
    public static final int DEFAULT_IDENTICAL_SAMPLES_TO_TRIP = 5;

    /** Callback surface. Invoked synchronously from {@link #observe}. */
    public interface Listener {
        /**
         * A frozen-feed episode BEGINS (fires once per episode).
         *
         * @param identicalSamples how many consecutive identical samples tripped it
         * @param frozenForMs      wall time since the first identical sample
         */
        void onFrozenFeed(int identicalSamples, long frozenForMs);

        /** The episode ENDS — a sample with different content arrived. */
        void onFeedRecovered(long frozenForMs);
    }

    private final long sampleIntervalMs;
    private final int identicalSamplesToTrip;
    private final Listener listener;

    private long lastSampleAtMs = Long.MIN_VALUE;
    private long lastHash;
    private boolean haveHash;
    private int identicalStreak;
    private long streakStartMs;
    private boolean episodeActive;
    private long episodesTotal;

    public FrozenFeedDetector(Listener listener) {
        this(listener, DEFAULT_SAMPLE_INTERVAL_MS, DEFAULT_IDENTICAL_SAMPLES_TO_TRIP);
    }

    public FrozenFeedDetector(
            Listener listener, long sampleIntervalMs, int identicalSamplesToTrip) {
        this.listener = listener;
        this.sampleIntervalMs = Math.max(1L, sampleIntervalMs);
        this.identicalSamplesToTrip = Math.max(1, identicalSamplesToTrip);
    }

    /**
     * Feed one frame. Cheap no-op unless {@link #sampleIntervalMs} elapsed
     * since the last hashed sample. Not thread-safe by design — call from the
     * single thread that owns the frame buffers.
     *
     * @param frame the downscaled RGB frame (read-only; never retained)
     * @param nowMs caller's clock (testable)
     */
    public void observe(byte[] frame, long nowMs) {
        if (frame == null || frame.length == 0) return;
        if (lastSampleAtMs != Long.MIN_VALUE
                && nowMs - lastSampleAtMs < sampleIntervalMs) {
            return;
        }
        lastSampleAtMs = nowMs;
        long hash = hash(frame);
        if (haveHash && hash == lastHash) {
            if (identicalStreak == 0) streakStartMs = nowMs;
            identicalStreak++;
            if (!episodeActive && identicalStreak >= identicalSamplesToTrip) {
                episodeActive = true;
                episodesTotal++;
                safeOnFrozen(identicalStreak, nowMs - streakStartMs);
            }
        } else {
            if (episodeActive) {
                episodeActive = false;
                safeOnRecovered(nowMs - streakStartMs);
            }
            identicalStreak = 0;
        }
        lastHash = hash;
        haveHash = true;
    }

    /**
     * Reset all episode state (camera reopen, ACC transition, arm/disarm) so a
     * stale pre-transition hash can never seed the next session's comparison.
     */
    public void reset() {
        lastSampleAtMs = Long.MIN_VALUE;
        haveHash = false;
        identicalStreak = 0;
        episodeActive = false;
    }

    /** True while a frozen-feed episode is active. */
    public boolean isFrozen() {
        return episodeActive;
    }

    /** Total episodes since construction (diagnostics). */
    public long episodesTotal() {
        return episodesTotal;
    }

    static long hash(byte[] frame) {
        long h = 0xcbf29ce484222325L;            // FNV-1a 64 offset basis
        for (int i = 0; i < frame.length; i += STRIDE) {
            h ^= (frame[i] & 0xffL);
            h *= 0x100000001b3L;                 // FNV-1a 64 prime
        }
        // Fold the length in so a resize is never "identical".
        h ^= frame.length;
        h *= 0x100000001b3L;
        return h;
    }

    private void safeOnFrozen(int samples, long forMs) {
        try {
            if (listener != null) listener.onFrozenFeed(samples, forMs);
        } catch (Throwable ignored) {
            // Detection must never disturb the frame path.
        }
    }

    private void safeOnRecovered(long forMs) {
        try {
            if (listener != null) listener.onFeedRecovered(forMs);
        } catch (Throwable ignored) {
        }
    }
}
