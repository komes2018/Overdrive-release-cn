package com.overdrive.app.remoteaccess;

/**
 * One fresh instrument search at a time. Clock values are original Android
 * elapsed-realtime observations; a heartbeat never advances an observation.
 */
final class KeyFobPresenceCycle {
    static final long DEFAULT_INTERVAL_MS = 10_000;
    static final long TIMEOUT_MS = 6_000;
    private final long intervalMs;

    KeyFobPresenceCycle() { this(DEFAULT_INTERVAL_MS); }

    /** Call spacing derived from the configured locking window (B2); the default is ten seconds. */
    KeyFobPresenceCycle(long intervalMs) {
        if (intervalMs <= 0) throw new IllegalArgumentException("Invalid search interval");
        this.intervalMs = intervalMs;
    }

    static final class Snapshot {
        final String state, status;
        final long generation, observationId, cycleId, observedMs, startedMs;
        final boolean pending, pausedLocked;

        Snapshot(KeyFobPresenceCycle owner) {
            state = owner.state;
            status = owner.pausedLocked ? "paused_locked"
                    : owner.pending ? "pending" : owner.unavailable ? "unavailable" : "idle";
            generation = owner.generation;
            observationId = owner.observationId;
            cycleId = owner.completedCycleId;
            observedMs = owner.observedMs;
            startedMs = owner.completedStartedMs;
            pending = owner.pending;
            pausedLocked = owner.pausedLocked;
        }
    }

    private long generation = 1, nextCycleId, currentCycleId;
    private long observationId, completedCycleId, observedMs = -1, completedStartedMs = -1;
    private long startedMs = -1, nextStartMs;
    private int baseline;
    private boolean pending, fresh, unavailable, requestAccepted, pausedLocked;
    private String candidateState;
    private long candidateMs;
    private String state = "unknown";

    synchronized boolean due(long now) {
        return !pausedLocked && !pending && now >= nextStartMs;
    }

    synchronized long begin(long now, int baseline) {
        if (!due(now)) return 0;
        currentCycleId = ++nextCycleId;
        startedMs = now;
        nextStartMs = now + intervalMs;
        this.baseline = normalizeBaseline(baseline);
        fresh = false;
        requestAccepted = false;
        candidateState = null;
        candidateMs = -1;
        unavailable = false;
        pending = true;
        return currentCycleId;
    }

    synchronized boolean observe(long expectedGeneration, long cycleId, int raw, long now) {
        if (!matches(expectedGeneration, cycleId)) return false;
        if (now < startedMs || now >= startedMs + TIMEOUT_MS)
            return complete("unknown", now < startedMs ? startedMs
                    : startedMs + TIMEOUT_MS, true);
        if (raw == 0) {
            // Vendor "search in progress" only arms freshness. It is not absence.
            fresh = true;
            // A renewed search retires a result held during the setter call.
            candidateState = null;
            candidateMs = -1;
            return false;
        }
        // An unavailable cache cannot prove that the first result is fresh.
        // In that case only a post-request progress callback arms this query.
        if (baseline >= 0 && raw != baseline) fresh = true;
        if (!fresh) return false;
        if (raw == 1) return known("present", now);
        if (raw == 2 || raw == 3) return known("absent", now);
        return complete("unknown", now, true);
    }

    /** A synchronous SDK callback cannot make a rejected setter a known result. */
    synchronized boolean acceptRequest(long expectedGeneration, long cycleId, long now) {
        if (!matches(expectedGeneration, cycleId)) return false;
        if (now < startedMs || now >= startedMs + TIMEOUT_MS)
            return complete("unknown", now < startedMs ? startedMs
                    : startedMs + TIMEOUT_MS, true);
        requestAccepted = true;
        return candidateState != null && complete(candidateState, candidateMs, false);
    }

    synchronized void baseline(long expectedGeneration, long cycleId, int raw) {
        if (matches(expectedGeneration, cycleId)) baseline = normalizeBaseline(raw);
    }

    /** Preparation time does not shorten either the callback bound or call spacing. */
    synchronized boolean requestStarted(long expectedGeneration, long cycleId, long now) {
        if (!matches(expectedGeneration, cycleId)) return false;
        startedMs = now;
        nextStartMs = now + intervalMs;
        fresh = requestAccepted = false;
        candidateState = null;
        candidateMs = -1;
        return true;
    }

    synchronized boolean fail(long expectedGeneration, long cycleId, long now) {
        return matches(expectedGeneration, cycleId)
                && complete("unknown", Math.max(startedMs, now), true);
    }

    synchronized boolean expire(long now) {
        return pending && now >= startedMs + TIMEOUT_MS
                && complete("unknown", startedMs + TIMEOUT_MS, true);
    }

    synchronized long wakeInMs(long now) {
        long deadline = pending ? startedMs + TIMEOUT_MS : nextStartMs;
        return Math.max(1, deadline - now);
    }

    synchronized void reset() {
        generation++;
        clearResult();
        pausedLocked = false;
        nextStartMs = 0;
    }

    /** A new mode fence retires evidence without shortening native call spacing. */
    synchronized void resetForModeChange() {
        generation++;
        clearResult();
        pausedLocked = false;
    }

    /** Locked parking retires results once without shortening native call spacing. */
    synchronized boolean pauseLocked() {
        if (pausedLocked) return false;
        generation++;
        clearResult();
        pausedLocked = true;
        return true;
    }

    synchronized void resume() {
        pausedLocked = false;
    }

    static boolean shouldPause(int power, int driverLock) {
        return power == 0 && driverLock == 2;
    }

    private void clearResult() {
        pending = fresh = unavailable = requestAccepted = false;
        candidateState = null;
        candidateMs = -1;
        state = "unknown";
        observationId = completedCycleId = 0;
        observedMs = completedStartedMs = startedMs = -1;
    }

    synchronized Snapshot snapshot() { return new Snapshot(this); }
    synchronized long currentCycleId() { return currentCycleId; }
    synchronized long intervalMs() { return intervalMs; }

    private boolean matches(long expectedGeneration, long cycleId) {
        return pending && generation == expectedGeneration && currentCycleId == cycleId;
    }

    private static int normalizeBaseline(int raw) {
        return raw >= 0 && raw <= 3 ? raw : -1;
    }

    private boolean known(String value, long now) {
        if (requestAccepted) return complete(value, now, false);
        // Native delivery can change while the synchronous setter is running.
        // Its successful receipt must publish the latest callback, not the first.
        candidateState = value;
        candidateMs = now;
        return false;
    }

    private boolean complete(String next, long now, boolean failed) {
        state = next;
        observedMs = now;
        completedStartedMs = startedMs;
        completedCycleId = currentCycleId;
        observationId++;
        pending = false;
        unavailable = failed;
        return true;
    }
}
