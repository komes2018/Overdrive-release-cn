package com.overdrive.app.byd;

import android.content.Context;
import android.os.SystemClock;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;

/**
 * Local remote-key commands: the body controller's key-fob command frame written through
 * {@code BYDAutoManager.setBuffer(1034, 0xAA000004, frame)}. No BYD cloud involved.
 * Accepted only when setBuffer returns exactly 0.
 */
public final class RemoteKeyControl {

    private static final DaemonLogger logger = DaemonLogger.getInstance("RemoteKeyControl");

    public static final int CODE_POWER_ON = 1;
    public static final int CODE_UNLOCK = 5;
    public static final int CODE_LOCK = 7;
    public static final int CODE_POWER_OFF = 9;
    public static final int CODE_FIND_CAR = 10;
    public static final int CODE_FLASH_LIGHTS = 22;

    private static final int DEVICE_REMOTE_KEY = 1034;
    private static final int FID_REMOTE_KEY_COMMAND = 0xAA000004;
    private static final int CODE_OFFSET = 22;

    // Experimental dynamic 73-byte frame inputs (feature IDs cross-checked against
    // docs/manager-study/vehicle-controls.md, row "Local non-CANFD"). Read-only getBuffer feeds;
    // the write still goes through the one setBuffer(1034, 0xAA000004, …) call below.
    private static final int FID_DYNAMIC_SEED = 0x99000005;    // on DEVICE_REMOTE_KEY (1034)
    private static final int DEVICE_DYNAMIC_KEY = 1027;
    private static final int FID_DYNAMIC_KEY = 0x9900021a;     // on DEVICE_DYNAMIC_KEY (1027)
    private static final String FRAME_FORMAT_FIXED = "fixed";
    private static final String FRAME_FORMAT_DYNAMIC = "dynamic";
    private static final byte[] FRAME_TEMPLATE = {
            0x5E, 0x02, 0x14, 0x00, (byte) 0xFE, 0x14, 0x2E, (byte) 0x82, 0x5C, (byte) 0xF1,
            0x78, 0x50, 0x44, (byte) 0xA2, (byte) 0xB9, (byte) 0xCA, 0x15, (byte) 0xA3,
            (byte) 0xEE, (byte) 0xBC, (byte) 0xD4, 0x7B, 0x00, 0x00, 0x00, 0x00
    };
    private static final RemoteKeySequence SEQUENCES = new RemoteKeySequence(
            new RemoteKeySequence.Clock() {
                public long elapsedMs() { return SystemClock.elapsedRealtime(); }
                public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
            });

    /** Why this thread's last frame was refused, for callers whose log is in another process. */
    private static final ThreadLocal<String> LAST_FAILURE = new ThreadLocal<>();
    private static final ThreadLocal<LocalActuationAbort> LAST_ABORT = new ThreadLocal<>();

    private RemoteKeyControl() {}

    /** Returns and clears this thread's last refusal reason, or null. */
    public static String takeLastFailure() {
        String reason = LAST_FAILURE.get();
        LAST_FAILURE.remove();
        return reason;
    }

    /** Get-and-clear of a terminal write admission refusal, distinct from a normal HAL refusal. */
    public static LocalActuationAbort takeLastAbort() {
        LocalActuationAbort reason = LAST_ABORT.get();
        LAST_ABORT.remove();
        return reason;
    }

    /** The app-process bridge preserves the refusal kind as well as its diagnostic message. */
    public static void noteAbort(LocalActuationAbort reason) {
        if (reason == null) LAST_ABORT.remove();
        else {
            LAST_ABORT.set(reason);
            refuse(reason.getMessage());
        }
    }

    public static boolean lock(Context ctx) { return send(ctx, CODE_LOCK); }

    public static boolean findCar(Context ctx) { return send(ctx, CODE_FIND_CAR); }

    public static boolean flashLights(Context ctx) { return send(ctx, CODE_FLASH_LIGHTS); }

    public static boolean powerOn(Context ctx) { return send(ctx, CODE_POWER_ON); }

    /**
     * Power-on for a car confirmed off: a power-off frame first, because the car can ignore
     * power-on until it has seen one. Inside the hold-off a repeat press sends power-on alone,
     * so it cannot power-cycle a car that is already coming on.
     */
    public static boolean powerOnFromOff(Context ctx) {
        return SEQUENCES.powerOnFromOff(new Port(ctx, null));
    }

    public static boolean powerOff(Context ctx) { return send(ctx, CODE_POWER_OFF); }

    /**
     * Unlock is a two-frame sequence. Success requires BOTH frames: a half-completed sequence
     * leaves the doors locked, so reporting it as success would echo a state the car is not in.
     */
    public static boolean unlock(Context ctx) {
        return SEQUENCES.unlock(new Port(ctx, null));
    }

    /**
     * {@link #unlock} confirmed on the driver-door read when it reads locked beforehand: a
     * sequence the car ignores is sent once more, and a door still locked after that is refused.
     * A null collector, or a door that doesn't read locked, is plain {@link #unlock}.
     */
    public static boolean unlockConfirmed(Context ctx, BydDataCollector readback) {
        return SEQUENCES.unlockConfirmed(new Port(ctx, readback), readback != null);
    }

    /** The newest door or power intent sent through this rail. */
    static long doorIntent() { return SEQUENCES.doorIntent(); }

    /** A door write from another local rail, ordered with these frames; null when superseded. */
    static RemoteKeySequence.IntentWrite writeAsDoorIntent(
            long expected, java.util.function.IntSupplier write) {
        return SEQUENCES.writeAsDoorIntent(expected, write);
    }

    /** Whether this thread's last local write was stopped: cancelled, expired or blocked. */
    static boolean hasPendingAbort() { return LAST_ABORT.get() != null; }

    /**
     * Opt-out for the whole local rail: {@code vehicle.localRemoteKeyDisabled = true} sends every
     * one of these commands back to its cloud leg. Present because the frame's fixed bytes are not
     * confirmed across models — a car that reacts wrongly to it needs a one-key escape, not a
     * rebuild. Absent or malformed config leaves the rail enabled.
     */
    public static boolean isEnabled() {
        try {
            return !com.overdrive.app.config.UnifiedConfigManager.getVehicle()
                    .optBoolean("localRemoteKeyDisabled", false);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * MCU readiness gate kill switch: {@code vehicle.remoteKeyMcuReadyGateDisabled = true} skips
     * the status read / wake / wait before door and power frames. Default (absent or malformed)
     * is gate ON. Read like {@link #isEnabled()} so the two switches can never disagree about how
     * config failures are treated.
     */
    public static boolean isMcuReadyGateEnabled() {
        try {
            return !com.overdrive.app.config.UnifiedConfigManager.getVehicle()
                    .optBoolean("remoteKeyMcuReadyGateDisabled", false);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Live controller (MCU) status for diagnostics and the readiness gate: 1 or 10 = awake,
     * -1 = unavailable. Read-only; nothing is written.
     */
    public static int readMcuStatus(Context ctx) {
        try {
            if (ctx != null) com.overdrive.app.power.McuPowerHal.ensureAppContext(ctx);
            return com.overdrive.app.power.McuPowerHal.readMcuStatus();
        } catch (Throwable unavailable) {
            return RemoteKeySequence.MCU_STATUS_UNAVAILABLE;
        }
    }

    /**
     * Whether this platform exposes the command path at all. Reflection only — resolves the
     * manager and looks the method up; nothing is written and no command is sent.
     */
    public static boolean isAvailable(Context ctx) {
        if (ctx == null) return false;
        if (!isEnabled()) return false;
        try {
            Object manager = ctx.getSystemService("auto");
            if (manager == null) return false;
            manager.getClass().getMethod("setBuffer", int.class, int.class, byte[].class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Legacy alias retained for existing DiLink 3/4 callers; delegates to {@link #send(Context, int)}. */
    public static boolean sendKey(Context ctx, int code) { return send(ctx, code); }

    /** Legacy alias retained for existing DiLink 3/4 callers; delegates to {@link #send(Context, int)}. */
    public static boolean sendKeyDirect(Context ctx, int code) { return send(ctx, code); }

    private static boolean send(Context ctx, int code) {
        return SEQUENCES.send(new Port(ctx, null), code);
    }

    static byte[] buildFrame(int code) {
        byte[] frame = FRAME_TEMPLATE.clone();
        frame[CODE_OFFSET] = (byte) code;
        return frame;
    }

    /**
     * Selected remote-key command format: {@code "fixed"} (the default 26-byte frame, also for an
     * absent, unknown or malformed value) or {@code "dynamic"} (the experimental 73-byte frame).
     * Validated strictly — only the exact string {@code "dynamic"} selects the dynamic format.
     */
    public static String frameFormat() {
        try {
            return normalizeFrameFormat(com.overdrive.app.config.UnifiedConfigManager.getVehicle()
                    .optString("remoteKeyFrameFormat", FRAME_FORMAT_FIXED));
        } catch (Throwable t) {
            return FRAME_FORMAT_FIXED;
        }
    }

    /** Strict validation: only the exact string {@code "dynamic"} selects dynamic; else fixed. */
    static String normalizeFrameFormat(String raw) {
        return FRAME_FORMAT_DYNAMIC.equals(raw) ? FRAME_FORMAT_DYNAMIC : FRAME_FORMAT_FIXED;
    }

    /**
     * The command frame for {@code code} in the given format, or null (with a refusal recorded)
     * when a dynamic frame cannot build. Package-private and {@code Context}-free so the
     * dispatch-level test can build a frame for either format from an injected manager object.
     */
    static byte[] resolveFrame(Object manager, int code, boolean dynamic) {
        return dynamic ? buildDynamicFrame(manager, code) : buildFrame(code);
    }

    /**
     * Experimental dynamic 73-byte frame from live vendor buffers. Missing, short or null buffers
     * are an ordinary refusal (returns null; the write then returns false and the cloud fallback
     * stays eligible) — never a silent fall back to the fixed frame.
     */
    static byte[] buildDynamicFrame(Object manager, int code) {
        byte[] seed = RemoteKeyDynamicFrame.normalizeBuffer(
                readVendorBuffer(manager, DEVICE_REMOTE_KEY, FID_DYNAMIC_SEED),
                RemoteKeyDynamicFrame.SEED_LENGTH);
        if (seed == null) {
            refuse("code=" + code + " refused: dynamic frame inputs unavailable (seed)");
            return null;
        }
        byte[] key = RemoteKeyDynamicFrame.normalizeBuffer(
                readVendorBuffer(manager, DEVICE_DYNAMIC_KEY, FID_DYNAMIC_KEY),
                RemoteKeyDynamicFrame.KEY_LENGTH);
        if (key == null) {
            refuse("code=" + code + " refused: dynamic frame inputs unavailable (key)");
            return null;
        }
        long epochSeconds = System.currentTimeMillis() / 1000L;
        return RemoteKeyDynamicFrame.build(seed, key, epochSeconds, readUnlockUuid(), code);
    }

    /**
     * Write a built frame through the single vendor {@code setBuffer(1034, 0xAA000004, frame)} call
     * and accept it only when the return code is exactly 0. A HAL exception becomes an ordinary
     * refusal; this method performs no admission check (the dynamic re-admit runs, and may abort,
     * before it is called). Package-private for the dispatch-level test.
     */
    static boolean writeFrameToManager(Object manager, Method setBuffer, int code, byte[] frame) {
        try {
            Object rc = setBuffer.invoke(manager, DEVICE_REMOTE_KEY, FID_REMOTE_KEY_COMMAND, frame);
            boolean accepted = rc instanceof Integer && ((Integer) rc).intValue() == 0;
            logger.info("code=" + code + " rc=" + rc + " accepted=" + accepted);
            if (!accepted) LAST_FAILURE.set("code=" + code + " rc=" + rc);
            return accepted;
        } catch (Throwable failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            return refuse("code=" + code + " failed: " + cause.getClass().getSimpleName()
                    + (cause.getMessage() != null ? " " + cause.getMessage() : ""));
        }
    }

    private static byte[] readVendorBuffer(Object manager, int device, int feature) {
        try {
            Method getBuffer = manager.getClass().getMethod("getBuffer", int.class, int.class);
            Object result = getBuffer.invoke(manager, device, feature);
            return result instanceof byte[] ? (byte[]) result : null;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** Optional 16-byte UUID from {@code sys.cloud.unlock_uuid} (32 hex chars); null if absent. */
    private static byte[] readUnlockUuid() {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method get = systemProperties.getMethod("get", String.class, String.class);
            String raw = (String) get.invoke(null, "sys.cloud.unlock_uuid", "");
            if (raw == null) return null;
            String hex = raw.trim().replace("-", "");
            if (hex.length() != 32) return null;
            byte[] uuid = new byte[16];
            for (int i = 0; i < 16; i++) {
                uuid[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return uuid;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static final class Port implements RemoteKeySequence.Port {
        private final Context ctx;
        private final BydDataCollector readback;
        /** Command format resolved once per command, so a format change can't flip mid-command. */
        private Boolean dynamicFormat;

        Port(Context ctx, BydDataCollector readback) {
            this.ctx = ctx;
            this.readback = readback;
        }

        public void begin() {
            LAST_FAILURE.remove();
            LAST_ABORT.remove();
        }

        public void clearFailure() { LAST_FAILURE.remove(); }

        public RemoteKeySequence.PreparedFrame prepare(int code) {
            if (ctx == null) {
                refuse("code=" + code + " refused: no context");
                return null;
            }
            if (!isEnabled()) {
                logger.info("code=" + code + " skipped: local rail disabled by config");
                LAST_FAILURE.set("code=" + code + " skipped: local rail disabled by config");
                return null;
            }
            try {
                Object manager = ctx.getSystemService("auto");
                if (manager == null) {
                    refuse("code=" + code + " refused: auto service unavailable");
                    return null;
                }
                Method setBuffer = manager.getClass()
                        .getMethod("setBuffer", int.class, int.class, byte[].class);
                return () -> {
                    if (!isEnabled()) {
                        return RemoteKeyControl.refuse(
                                "code=" + code + " skipped: local rail disabled by config");
                    }
                    // Build the frame inside the write, after the readiness gate and the lease /
                    // motion gates: the dynamic path reads live vendor buffers and refuses (an
                    // ordinary refusal, so cloud fallback stays eligible) when they are missing or
                    // malformed. It never silently falls back to the fixed frame.
                    boolean dynamic = isDynamicFormat();
                    byte[] frame = resolveFrame(manager, code, dynamic);
                    if (frame == null) return false;
                    // The dynamic build (two getBuffer reads, a system property, CRC and DES) opened
                    // a fresh cancellation window after writeFrame's admission check, so re-admit
                    // immediately before the write. checkLease/checkMotion throw LocalActuationAbort,
                    // which must propagate OUT of this lambda — not be swallowed by the HAL-failure
                    // catch inside writeFrameToManager — so RemoteKeySequence.writeFrame turns it
                    // into Write.ABORTED via port.abort. The fixed path adds no checks and stays
                    // byte- and call-identical.
                    if (dynamic) {
                        checkLease();
                        checkMotion(code);
                    }
                    return writeFrameToManager(manager, setBuffer, code, frame);
                };
            } catch (NoSuchMethodException absent) {
                refuse("code=" + code + " refused: setBuffer absent on this platform");
                return null;
            }
        }

        public void checkLease() {
            if (VehicleActuatorBridge.isRemoteKeyRequestCancelled()) {
                throw new LocalActuationAbort(LocalActuationAbort.Kind.CANCELLED,
                        "remote-key request cancelled");
            }
            if (VehicleActuatorBridge.isRemoteKeyRequestExpired()) {
                throw new LocalActuationAbort(LocalActuationAbort.Kind.REQUEST_EXPIRED,
                        "remote-key request expired, revoked or vehicle mode changed");
            }
        }

        public void checkMotion(int code) {
            if (code != CODE_LOCK && code != CODE_UNLOCK) return;
            boolean blocked;
            try {
                blocked = com.overdrive.app.byd.routing.DrivingSafetyGuard.isActionBlocked(
                        com.overdrive.app.byd.routing.DrivingSafetyGuard.GUARD_DOOR_LOCKS);
            } catch (Throwable unavailable) {
                blocked = true;
            }
            if (blocked) {
                throw new LocalActuationAbort(LocalActuationAbort.Kind.BLOCKED_DRIVING,
                        "not confirmed parked");
            }
        }

        public boolean confirmedOff() {
            return com.overdrive.app.monitor.AccMonitor.isAccStateAuthoritative()
                    && !com.overdrive.app.monitor.AccMonitor.isAccOn();
        }

        public int readDoor() {
            return readback == null ? BydDataCollector.DOOR_STATE_INVALID
                    : readback.readDoorLockState();
        }

        public void refuse(String reason) { RemoteKeyControl.refuse(reason); }
        public void abort(LocalActuationAbort reason) { noteAbort(reason); }
        public void warn(String message) { logger.warn(message); }
        public void log(String message) { logger.info(message); }

        // MCU readiness gate. Status is a read-only HAL getter; the wake goes through
        // McuPowerHal. Both fail soft: -1 / false make the gate proceed.
        public int readControllerStatus() { return readMcuStatus(ctx); }

        public boolean wakeController() {
            try {
                if (ctx != null) com.overdrive.app.power.McuPowerHal.ensureAppContext(ctx);
                return com.overdrive.app.power.McuPowerHal.requestMcuWake();
            } catch (Throwable cannotWake) {
                return false;
            }
        }

        public boolean readinessGateEnabled() { return isMcuReadyGateEnabled(); }

        private boolean isDynamicFormat() {
            if (dynamicFormat == null) {
                dynamicFormat = FRAME_FORMAT_DYNAMIC.equals(frameFormat());
            }
            return dynamicFormat;
        }
    }

    private static boolean refuse(String reason) {
        logger.warn(reason);
        LAST_FAILURE.set(reason);
        return false;
    }
}
