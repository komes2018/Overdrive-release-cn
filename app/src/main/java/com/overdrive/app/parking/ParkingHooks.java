package com.overdrive.app.parking;

import com.overdrive.app.surveillance.Actor;

import java.io.File;
import java.util.List;

/**
 * Static entry points the rest of the daemon calls into so the Parking
 * Intelligence feature can observe the ACC / lock / surveillance lifecycle
 * WITHOUT any of those callers depending on the feature being enabled.
 *
 * <p><b>Zero cost when disabled.</b> Every hook is a single volatile read of
 * {@link #listener} followed by an early return. {@link ParkingController}
 * installs itself only from {@code start()} (feature enabled) and removes
 * itself in {@code stop()}, so with the master switch off the hook sites cost
 * a null check and nothing else — no allocation, no locking, no threads.
 *
 * <p><b>Never blocks the caller.</b> Several hook sites run on hot or
 * lock-holding paths (the surveillance stop tail holds
 * {@code recordingLifecycleLock}; the baseline mutators run under the
 * baseline monitor; the ACC edge runs inside its transition lease). The
 * installed listener therefore MUST hand off to its own executor and return
 * immediately. Every dispatch here is wrapped in {@code catch (Throwable)} so
 * a defect in the feature can never break recording, arming or ACC handling
 * — the same posture the engine takes for its automation / overlay hooks.
 */
public final class ParkingHooks {

    /** Contract the controller implements. All methods must be non-blocking. */
    public interface Listener {
        void onAccOff(long transitionGeneration);
        void onAccOn(long transitionGeneration);
        /** Doors unlocked while parked (lock arm mode; owner returning). */
        void onUnlockWhileParked();
        /**
         * A sentry event segment was finalized and its sidecar write dispatched.
         *
         * @param mp4            the segment file (may not have a .json yet — the
         *                       sidecar is written asynchronously)
         * @param actors         event actors as handed to the timeline writer
         * @param segmentStartMs wall-clock start of this segment's window
         * @param finalSegment   true for the event's last segment (publish path)
         */
        void onEventFinalized(File mp4, List<Actor> actors, long segmentStartMs,
                              boolean finalSegment);
        /** Session id to stamp into a sidecar being written now, or null. */
        String currentSessionId();
    }

    private static volatile Listener listener;

    private ParkingHooks() {}

    /** Install (or clear with null) the single listener. */
    public static void setListener(Listener l) {
        listener = l;
    }

    /** True when the feature is running and observing. */
    public static boolean isActive() {
        return listener != null;
    }

    public static void onAccOff(long transitionGeneration) {
        Listener l = listener;
        if (l == null) return;
        try { l.onAccOff(transitionGeneration); } catch (Throwable ignored) {}
    }

    public static void onAccOn(long transitionGeneration) {
        Listener l = listener;
        if (l == null) return;
        try { l.onAccOn(transitionGeneration); } catch (Throwable ignored) {}
    }

    public static void onUnlockWhileParked() {
        Listener l = listener;
        if (l == null) return;
        try { l.onUnlockWhileParked(); } catch (Throwable ignored) {}
    }

    public static void onEventFinalized(File mp4, List<Actor> actors,
                                        long segmentStartMs, boolean finalSegment) {
        Listener l = listener;
        if (l == null) return;
        try { l.onEventFinalized(mp4, actors, segmentStartMs, finalSegment); }
        catch (Throwable ignored) {}
    }

    /** @return the open session id for sidecar stamping, or null when disabled/none. */
    public static String currentSessionId() {
        Listener l = listener;
        if (l == null) return null;
        try { return l.currentSessionId(); } catch (Throwable t) { return null; }
    }
}
