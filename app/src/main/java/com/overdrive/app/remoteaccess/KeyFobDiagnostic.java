package com.overdrive.app.remoteaccess;

import org.json.JSONObject;

/** Anonymized fob relay diagnostics. Called only by an existing observation; owns no resources. */
public final class KeyFobDiagnostic {
    public static final long HEARTBEAT_LOG_MS = 30_000L;
    private KeyFobDiagnostic() {}

    public static String changeKey(KeyFobEventEvaluator.Observation reading,
                                   KeyFobEventEvaluator.Snapshot evaluated, JSONObject settings,
                                   String disposition) {
        return "observation=" + code(disposition)
                + " enabled=" + settings.optBoolean("enabled")
                + " keyFobEnabled=" + settings.optBoolean("keyFobEnabled")
                + " unlockEnabled=" + settings.optBoolean("unlockEnabled")
                + " dryRun=" + settings.optBoolean("dryRun", true)
                + " mode=" + code(settings.optString("unlockTrigger"))
                + " revision=" + settings.optLong("revision", -1L)
                + " observationRevision=" + reading.revision
                + " power=" + reading.power + " driverLock=" + reading.driverLock
                + " sourceActive=" + reading.active + " registered=" + reading.registered
                + " tableRegistered=" + reading.tableRegistered
                + " nativeEventKnown=" + (reading.eventSequence > 0L)
                + " rawMask=" + reading.mask
                + " state=" + (evaluated == null ? "NOT_EVALUATED" : evaluated.state)
                + " healthy=" + (evaluated != null && evaluated.active)
                + " fresh=" + (evaluated != null && evaluated.fresh)
                + " armed=" + (evaluated != null && evaluated.armed)
                + " event=" + (evaluated == null ? "NONE" : evaluated.event.name())
                + " reason=" + code(evaluated == null ? disposition : evaluated.reason);
    }

    public static String describe(KeyFobEventEvaluator.Observation reading,
                                  KeyFobEventEvaluator.Snapshot evaluated, JSONObject settings,
                                  String disposition, long now) {
        return changeKey(reading, evaluated, settings, disposition)
                + " sourceGeneration=" + reading.generation
                + " observationSequence=" + reading.serial
                + " eventSequence=" + reading.eventSequence
                + " newness=" + code(evaluated == null ? "none" : evaluated.newness)
                + " scopeAgeMs=" + age(now, reading.scopeElapsed)
                + " eventAgeMs=" + age(now, reading.eventElapsed);
    }

    public static boolean shouldLog(String previous, String current, long nextPeriodicAt,
                                    long now, boolean actionableEvent) {
        return actionableEvent || !current.equals(previous) || now >= nextPeriodicAt;
    }

    public static String rejectionReason(IllegalArgumentException rejected) {
        String message = rejected.getMessage();
        if (message != null && message.startsWith("power ")) return "invalid_power";
        if (message != null && message.startsWith("driverLock ")) return "invalid_driver_lock";
        if (message != null && message.contains("already consumed")) return "replayed_observation";
        if (message != null && message.contains("timestamp")) return "invalid_timestamp";
        return "invalid_envelope";
    }

    private static String code(String value) {
        return value != null && value.matches("[a-z_]{1,64}") ? value : "unknown";
    }
    private static long age(long now, long observed) {
        return observed < 0L || observed > now ? -1L : now - observed;
    }
}
