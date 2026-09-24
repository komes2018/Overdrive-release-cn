package com.overdrive.app.power;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Cross-process "the vehicle was just switched on" hint.
 *
 * <p>BYD's unambiguous ignition broadcasts ({@code com.byd.action.ACC_ON},
 * {@code com.byd.action.IGN_ON}) are delivered to the APP process (BootReceiver).
 * The process that owns the parked panel — {@code acc_sentry_daemon}, a bare
 * {@code app_process} with no manifest — cannot receive broadcasts at all. On
 * DiLink 5 its only ACC source is the car_service power-mode probe, and a running
 * car reads {@code 10=PowerMode DisPlay on} there: the field-validated grammar
 * classifies that as a WEAK observation, because a parked cloud wake or a deep-sleep
 * resume produces the same row. {@code AccMonitor.admitDiLink5AccObservation}
 * therefore refuses to turn a confirmed parked state into ACC ON on that reading
 * alone, and until the driver shifts out of P (driving telemetry is the only other
 * strong source) the daemon stays in sentry mode with the panel dark.
 *
 * <p>This file is the independent second source that rule asks for. The app
 * process writes the time of the last ignition broadcast into its own external
 * files directory (the same directory the UID-2000 daemons already write recordings
 * to, so both sides can reach it without ADB or the CameraDaemon IPC socket — which
 * is DOWN during an onOnly park). The daemon's heartbeat reads it and passes
 * {@code independentIgnitionEvidence=true} into the admission rule, which then
 * admits a weak IVI-awake reading as ON. A strong OFF is never overridden by the hint.
 *
 * <p>Clock domain: {@link SystemClock#elapsedRealtime()}, which every process on the
 * device shares and which no RTC correction can move. These head units routinely
 * step the wall clock at ignition (GPS/NTP), which would make a wall-clock hint read
 * either older than the park or from the future. The wall clock is carried only as a
 * loose sanity bound so a hint from a PREVIOUS boot (elapsed time restarts at zero)
 * cannot masquerade as fresh.
 *
 * <p>Fail-safe in every direction: if the broadcast never fires on a given
 * firmware, the file is never written and behaviour is unchanged; if the daemon
 * cannot read the path, the hint is simply absent. A hint is honoured only while
 * younger than {@link #MAX_AGE_MS} and only if it post-dates the daemon's current
 * sentry entry, so the driver turning the car OFF again after an ignition edge
 * cannot resurrect a stale ON.
 */
public final class IgnitionBroadcastHint {

    /** File name inside the app's external files directory. */
    public static final String FILE_NAME = "acc_on_broadcast_hint";

    /**
     * How long an ignition broadcast stays admissible. Generous enough to cover
     * the onOnly relaunch of acc_sentry_daemon (~55 s after the ACC-ON edge:
     * BootReceiver recovery → +45 s core daemons → +10 s acc_sentry), bounded so
     * a hint can never outlive the drive it belongs to.
     */
    public static final long MAX_AGE_MS = 90_000L;

    /**
     * Wall-clock sanity window. Elapsed time restarts at every boot, so a hint from
     * the previous boot could look "fresh" in elapsed terms; its wall stamp will be
     * far older than this. Loose enough to survive an RTC step at ignition.
     */
    static final long WALL_SANITY_MS = 10L * 60_000L;

    private IgnitionBroadcastHint() {}

    /** Parsed hint. */
    public static final class Stamp {
        public final long elapsedMs;
        public final long wallMs;

        Stamp(long elapsedMs, long wallMs) {
            this.elapsedMs = elapsedMs;
            this.wallMs = wallMs;
        }
    }

    // ── App process ────────────────────────────────────────────────────────

    /**
     * Record that an unambiguous ignition broadcast just arrived. Best-effort and
     * off the caller's thread: BroadcastReceiver.onReceive runs on the main looper
     * and emulated-storage writes go through FUSE.
     */
    public static void publishAsync(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        final Context target = app != null ? app : context;
        try {
            Thread t = new Thread(() -> publish(target), "IgnitionHint-publish");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
            // Nothing to do — the hint is advisory.
        }
    }

    /**
     * Synchronous write of "{@code <elapsedMs> <wallMs>}"; tmp + rename so a reader
     * never sees a torn value. The tmp name is per-thread: ACC_ON and IGN_ON can
     * arrive back to back and must not rename each other's partial write.
     */
    static boolean publish(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return false;
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            File target = new File(dir, FILE_NAME);
            File tmp = new File(dir, FILE_NAME + ".tmp." + Thread.currentThread().getId());
            String value = SystemClock.elapsedRealtime() + " " + System.currentTimeMillis();
            try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                out.write(value.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                out.getFD().sync();
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
                return false;
            }
            try {
                // The UID-2000 daemon is the reader.
                target.setReadable(true, false);
            } catch (Throwable ignored) {}
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── Daemon process ─────────────────────────────────────────────────────

    /**
     * True when an ignition broadcast younger than {@link #MAX_AGE_MS} exists and
     * post-dates {@code notBeforeElapsedMs} (the daemon's current sentry-entry time
     * in elapsed-realtime terms; 0 if it never entered sentry in this process).
     */
    public static boolean isFresh(long notBeforeElapsedMs) {
        return isFresh(readStamp(), notBeforeElapsedMs,
                SystemClock.elapsedRealtime(), System.currentTimeMillis());
    }

    /** Pure freshness rule, separated so it can be reasoned about without I/O. */
    static boolean isFresh(Stamp stamp, long notBeforeElapsedMs,
            long nowElapsedMs, long nowWallMs) {
        if (stamp == null || stamp.elapsedMs <= 0L) return false;
        long age = nowElapsedMs - stamp.elapsedMs;
        if (age < 0L || age > MAX_AGE_MS) return false;
        if (Math.abs(nowWallMs - stamp.wallMs) > WALL_SANITY_MS) return false;
        return stamp.elapsedMs > notBeforeElapsedMs;
    }

    /** Last published stamp, or null when absent/unreadable. */
    static Stamp readStamp() {
        for (File candidate : daemonCandidates()) {
            try {
                if (!candidate.isFile()) continue;
                byte[] raw = Files.readAllBytes(candidate.toPath());
                String value = new String(raw, StandardCharsets.US_ASCII).trim();
                if (value.isEmpty() || value.length() > 48) continue;
                String[] parts = value.split("\\s+");
                if (parts.length != 2) continue;
                return new Stamp(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
            } catch (Throwable ignored) {
                // Try the next path spelling.
            }
        }
        return null;
    }

    /**
     * The app's external files directory as seen from a UID-2000 daemon. The app
     * resolves it via Context.getExternalFilesDir; from a bare app_process that
     * call is not reliable, so spell the two equivalent mount views explicitly.
     */
    private static File[] daemonCandidates() {
        String rel = "/Android/data/" + com.overdrive.app.BuildConfig.APPLICATION_ID
                + "/files/" + FILE_NAME;
        return new File[] {
            new File("/storage/emulated/0" + rel),
            new File("/sdcard" + rel),
        };
    }
}
