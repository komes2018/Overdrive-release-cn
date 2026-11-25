package com.overdrive.app.remoteaccess;

/** Retry decisions for the existing fob worker; owns no resources or vehicle commands. */
final class KeyFobSourceRecovery {
    static final long MIN_RETRY_MS = 3_000L;
    static final long MAX_RETRY_MS = 30_000L;

    enum Decision { REVOKED, CLEANUP_REQUIRED, WAIT, READY }

    private long failedAtMs = -1L;
    private long retryDelayMs;
    private int failures;

    static int protocolPower(int power) {
        return power >= 0 && power <= 3 ? power : -1;
    }

    Decision decide(long nowMs, boolean consented, boolean cleanupPending) {
        if (!consented || Thread.currentThread().isInterrupted()) return Decision.REVOKED;
        if (cleanupPending) return Decision.CLEANUP_REQUIRED;
        return retryInMs(nowMs) > 0L ? Decision.WAIT : Decision.READY;
    }

    void failed(long nowMs) {
        failedAtMs = nowMs;
        failures = Math.min(5, failures + 1);
        retryDelayMs = Math.min(MAX_RETRY_MS, MIN_RETRY_MS << (failures - 1));
    }

    void recovered() {
        failedAtMs = -1L;
        retryDelayMs = 0L;
        failures = 0;
    }

    long retryInMs(long nowMs) {
        if (failedAtMs < 0L || retryDelayMs == 0L) return 0L;
        if (nowMs < failedAtMs) return retryDelayMs;
        long age = nowMs - failedAtMs;
        return age < retryDelayMs ? retryDelayMs - age : 0L;
    }
}
