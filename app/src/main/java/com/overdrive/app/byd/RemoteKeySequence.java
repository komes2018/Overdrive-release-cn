package com.overdrive.app.byd;

/** The remote-key sequence policy, independent of Android and the vendor manager. */
final class RemoteKeySequence {
    static final int POWER_ON = 1;
    static final int UNLOCK = 5;
    static final int LOCK = 7;
    static final int POWER_OFF = 9;
    static final long UNLOCK_REPEAT_GAP_MS = 500L;
    static final long POWER_ON_PRIMER_GAP_MS = 2_000L;
    static final long POWER_ON_PRIMER_HOLDOFF_MS = 60_000L;
    static final long UNLOCK_CONFIRM_MS = 4_000L;
    static final long UNLOCK_CONFIRM_POLL_MS = 250L;
    private static final int DOOR_UNLOCK = 1;
    private static final int DOOR_LOCK = 2;

    // MCU readiness gate (default ON). Field-observed "awake" status values; -1 means the HAL
    // gave no status (SL7 stubs, missing device). Poll cadence and ceiling for the wake wait.
    static final int MCU_STATUS_ACTIVE = 1;
    static final int MCU_STATUS_ACTIVE_ALT = 10;
    static final int MCU_STATUS_UNAVAILABLE = -1;
    static final long MCU_READY_POLL_MS = 250L;
    static final long MCU_READY_MAX_MS = 1_500L;

    interface Clock {
        long elapsedMs();
        void sleep(long millis) throws InterruptedException;
    }

    interface PreparedFrame { boolean write(); }

    interface Port {
        void begin();
        void clearFailure();
        PreparedFrame prepare(int code);
        void checkLease();
        void checkMotion(int code);
        boolean confirmedOff();
        int readDoor();
        void refuse(String reason);
        void abort(LocalActuationAbort reason);
        void warn(String message);

        // MCU readiness gate hooks. Defaulted so every existing implementer and test fake keeps
        // compiling and reads as "no controller status, cannot wake, gate on" — i.e. the gate
        // fails open and changes nothing until a Port actually wires these.
        /** Controller (MCU) status; 1 or 10 = awake, -1 = unavailable. Default: unavailable. */
        default int readControllerStatus() { return MCU_STATUS_UNAVAILABLE; }
        /** Request a one-shot controller wake; true only when the wake was issued. */
        default boolean wakeController() { return false; }
        /** False disables the readiness gate entirely (kill switch). Default: enabled. */
        default boolean readinessGateEnabled() { return true; }
        /** Informational one-liner for the gate outcome. Default: no-op. */
        default void log(String message) {}
    }

    /** Per-sequence latch so the readiness gate runs once, before the first frame written. */
    private static final class SequenceGate { boolean done; }

    private enum Write { ACCEPTED, REFUSED, ABORTED, PRIMER_NO_LONGER_NEEDED }

    /** Complete sequences share the lock; readback windows deliberately do not hold it. */
    private final Object sendLock = new Object();
    private final Clock clock;
    private long lastPrimedPowerOnElapsedMs;
    /**
     * A newer door or power intent cancels an older unlock retry, including a refused lock.
     * Horn/flash do not determine the door's desired state. Guarded by sendLock.
     */
    private long doorIntentGeneration;

    RemoteKeySequence(Clock clock) { this.clock = clock; }

    /** A door write from another local rail: the intent it registered and its return code. */
    static final class IntentWrite {
        final long intent;
        final int rc;

        IntentWrite(long intent, int rc) {
            this.intent = intent;
            this.rc = rc;
        }
    }

    /** The newest door or power intent. */
    long doorIntent() {
        synchronized (sendLock) {
            return doorIntentGeneration;
        }
    }

    /**
     * A door write from another local rail, serialized with the frames and registered as the newest
     * intent. Null, with nothing written, once {@code expected} is no longer the newest.
     */
    IntentWrite writeAsDoorIntent(long expected, java.util.function.IntSupplier write) {
        synchronized (sendLock) {
            if (doorIntentGeneration != expected) return null;
            long intent = ++doorIntentGeneration;
            return new IntentWrite(intent, write.getAsInt());
        }
    }

    boolean send(Port port, int code) {
        port.begin();
        synchronized (sendLock) {
            if (affectsPendingUnlock(code)) doorIntentGeneration++;
            return writeFrame(port, code, false, new SequenceGate()) == Write.ACCEPTED;
        }
    }

    boolean powerOnFromOff(Port port) {
        port.begin();
        synchronized (sendLock) {
            doorIntentGeneration++;
            SequenceGate gate = new SequenceGate();
            if (lastPrimedPowerOnElapsedMs > 0L && clock.elapsedMs()
                    - lastPrimedPowerOnElapsedMs < POWER_ON_PRIMER_HOLDOFF_MS) {
                return writeFrame(port, POWER_ON, false, gate) == Write.ACCEPTED;
            }
            Write primer = writeFrame(port, POWER_OFF, true, gate);
            if (primer == Write.ABORTED) return false;
            if (primer == Write.ACCEPTED) {
                if (!sleep(port, POWER_ON_PRIMER_GAP_MS,
                        "code=" + POWER_ON + " not sent: interrupted after the power-off primer")) {
                    return false;
                }
            } else if (primer == Write.REFUSED) {
                port.warn("power-on: power-off primer refused — sending power-on alone");
            }
            boolean accepted = writeFrame(port, POWER_ON, false, gate) == Write.ACCEPTED;
            if (accepted) lastPrimedPowerOnElapsedMs = clock.elapsedMs();
            return accepted;
        }
    }

    boolean unlock(Port port) {
        port.begin();
        synchronized (sendLock) {
            doorIntentGeneration++;
            return unlockLocked(port, new SequenceGate());
        }
    }

    boolean unlockConfirmed(Port port, boolean hasReadback) {
        port.begin();
        SequenceGate gate = new SequenceGate();
        if (!hasReadback || readDoor(port) != DOOR_LOCK) {
            synchronized (sendLock) {
                doorIntentGeneration++;
                return unlockLocked(port, gate);
            }
        }
        long intent;
        synchronized (sendLock) {
            intent = ++doorIntentGeneration;
            if (!unlockLocked(port, gate)) return false;
        }
        for (int attempt = 1; ; attempt++) {
            Boolean landed = awaitDriverDoorUnlocked(port);
            synchronized (sendLock) {
                // Preserve accepted/unknown/superseded semantics, without reasserting old intent.
                if (landed == null || landed || doorIntentGeneration != intent) return true;
                if (attempt == 2) {
                    port.refuse("code=" + UNLOCK + " accepted twice, driver door still locked");
                    return false;
                }
                port.warn("unlock: accepted but the driver door still reads locked — sending again");
                // Re-gate the retry with a fresh SequenceGate: the gate shared by the first
                // sequence may be stale after the up-to-4 s confirmation window, so the retry's
                // first frame re-runs the readiness gate (one status read when the MCU is awake).
                // Its own second frame shares this fresh gate, so the 500 ms repeat never re-gates.
                if (!unlockLocked(port, new SequenceGate())) return false;
            }
        }
    }

    private boolean unlockLocked(Port port, SequenceGate gate) {
        if (writeFrame(port, UNLOCK, false, gate) != Write.ACCEPTED) return false;
        if (!sleep(port, UNLOCK_REPEAT_GAP_MS,
                "unlock: interrupted before the second frame — sequence incomplete")) return false;
        if (writeFrame(port, UNLOCK, false, gate) != Write.ACCEPTED) {
            port.warn("unlock: second frame refused — sequence incomplete");
            return false;
        }
        return true;
    }

    private Write writeFrame(Port port, int code, boolean needsOffProof, SequenceGate gate) {
        port.clearFailure();
        try {
            checkLease(port);
            PreparedFrame frame = port.prepare(code);
            checkLease(port);
            if (frame == null) return Write.REFUSED;
            // Manager/method lookup may block. Admission belongs after it, under sendLock.
            if (needsOffProof && !port.confirmedOff()) {
                checkLease(port);
                return Write.PRIMER_NO_LONGER_NEEDED;
            }
            // MCU readiness gate: once per sequence, immediately before the first frame actually
            // written, for door/power codes only. A disabled rail / missing auto service never
            // reaches here (frame was null), so a wake is never triggered for them.
            if (!gate.done && gatesReadiness(code)) {
                gate.done = true;
                awaitControllerReady(port, code);
            }
            port.checkMotion(code);
            checkLease(port);
            return frame.write() ? Write.ACCEPTED : Write.REFUSED;
        } catch (LocalActuationAbort stopped) {
            port.abort(stopped);
            return Write.ABORTED;
        } catch (Throwable failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            port.refuse("code=" + code + " failed: " + cause.getClass().getSimpleName()
                    + (cause.getMessage() != null ? " " + cause.getMessage() : ""));
            return Write.REFUSED;
        }
    }

    private static void checkLease(Port port) {
        if (Thread.currentThread().isInterrupted()) {
            throw new LocalActuationAbort(
                    LocalActuationAbort.Kind.CANCELLED, "remote-key command cancelled");
        }
        port.checkLease();
        if (Thread.currentThread().isInterrupted()) {
            throw new LocalActuationAbort(
                    LocalActuationAbort.Kind.CANCELLED, "remote-key command cancelled");
        }
    }

    private boolean sleep(Port port, long millis, String reason) {
        try {
            clock.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            port.abort(new LocalActuationAbort(LocalActuationAbort.Kind.CANCELLED, reason));
            return false;
        }
    }

    private Boolean awaitDriverDoorUnlocked(Port port) {
        long deadline = clock.elapsedMs() + UNLOCK_CONFIRM_MS;
        boolean readLocked = false;
        while (true) {
            int state = readDoor(port);
            if (state == DOOR_UNLOCK) return Boolean.TRUE;
            if (state == DOOR_LOCK) readLocked = true;
            if (clock.elapsedMs() >= deadline) return readLocked ? Boolean.FALSE : null;
            try {
                clock.sleep(UNLOCK_CONFIRM_POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null; // Both frames were accepted; keep the established unknown contract.
            }
        }
    }

    private static int readDoor(Port port) {
        try {
            return port.readDoor();
        } catch (Throwable unavailable) {
            return 0;
        }
    }

    private static boolean affectsPendingUnlock(int code) {
        // Retain power's conservative supersession of a pending door retry.
        return code == LOCK || code == UNLOCK || code == POWER_ON || code == POWER_OFF;
    }

    private static boolean gatesReadiness(int code) {
        // Door and power frames only. Horn/flash (FIND_CAR, FLASH_LIGHTS) never wake the MCU.
        return code == LOCK || code == UNLOCK || code == POWER_ON || code == POWER_OFF;
    }

    /**
     * MCU readiness gate. The body controller drops door/power frames the HAL accepts while the
     * MCU is asleep, so a sleeping MCU is woken and briefly polled before the first frame. The
     * gate always fails open: an unavailable status, a refused wake, or a timeout all proceed —
     * the existing cloud fallback is the backstop. Only a cancelled/revoked lease or an interrupt
     * stops the write, via {@link LocalActuationAbort} (no frame is written).
     */
    private void awaitControllerReady(Port port, int code) {
        if (!port.readinessGateEnabled()) {
            port.log("remote-key: mcuStatus=n/a ready=false wake=false waitedMs=0 outcome=disabled");
            return;
        }
        checkLease(port);
        int status = readControllerStatus(port);
        if (status == MCU_STATUS_ACTIVE || status == MCU_STATUS_ACTIVE_ALT) {
            logGate(port, status, true, false, 0L, "ready");
            return;
        }
        if (status == MCU_STATUS_UNAVAILABLE) {
            logGate(port, status, false, false, 0L, "unavailable");
            return;
        }
        // Re-admit immediately before the wake WRITE. The status read above (plus the manager and
        // scheduling work before the gate) opened a window after the caller's last motion check,
        // and the wake — and, when the voltage monitor runs in-process, its sentry wake — is a
        // vehicle-side write that must not fire for a car that is no longer confirmed parked. A
        // block here aborts with no wake and no frame (through writeFrame's LocalActuationAbort
        // catch). Ready/unavailable return above and never reach this point, so their call sequence
        // and the one-checkMotion-per-frame timing invariant are unchanged.
        port.checkMotion(code);
        checkLease(port);
        boolean woke = wakeController(port);
        if (!woke) {
            logGate(port, status, false, false, 0L, "skipped");
            return;
        }
        long waited = 0L;
        int polled = status;
        while (waited < MCU_READY_MAX_MS) {
            try {
                clock.sleep(MCU_READY_POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new LocalActuationAbort(LocalActuationAbort.Kind.CANCELLED,
                        "remote-key command cancelled while waiting for the MCU");
            }
            waited += MCU_READY_POLL_MS;
            checkLease(port);
            polled = readControllerStatus(port);
            if (polled == MCU_STATUS_ACTIVE || polled == MCU_STATUS_ACTIVE_ALT) {
                logGate(port, polled, true, true, waited, "ready");
                return;
            }
        }
        port.warn("remote-key: MCU not confirmed ready after " + waited
                + "ms — proceeding (cloud fallback is the backstop)");
        logGate(port, polled, false, true, waited, "timeout");
    }

    private static int readControllerStatus(Port port) {
        try {
            return port.readControllerStatus();
        } catch (Throwable unavailable) {
            return MCU_STATUS_UNAVAILABLE;
        }
    }

    private static boolean wakeController(Port port) {
        try {
            return port.wakeController();
        } catch (Throwable cannotWake) {
            return false;
        }
    }

    private static void logGate(Port port, int status, boolean ready, boolean wake,
            long waitedMs, String outcome) {
        port.log("remote-key: mcuStatus=" + status + " ready=" + ready + " wake=" + wake
                + " waitedMs=" + waitedMs + " outcome=" + outcome);
    }
}
