package com.overdrive.app.parking;

/**
 * Turns the daemon's last GPS reading into the session's location fields.
 *
 * <p>Unlike the recorder's geo tag ({@code GeoSnapshot}, which REJECTS a fix
 * older than five minutes so a parked clip never inherits yesterday's
 * address), a parking session WANTS the last fix even when it is old: after
 * a long underground descent the entrance fix from ten minutes ago is exactly
 * the "where did I park" answer, as long as it is labelled honestly. So this
 * stores the fix together with its age and a quality label, and only refuses a
 * fix that was restored from the on-disk cache (previous drive / boot — its age
 * is unknowable) or is (0,0).
 */
public final class ParkingLocationCapture {

    private ParkingLocationCapture() {}

    /**
     * @param nowElapsedMs monotonic clock now, same basis as {@code fix.fixElapsedMs}
     * @param nowMs        wall clock now, same basis as {@code fix.lastUpdateMs}
     */
    public static void apply(ParkingSession s, ParkingEnvironment.GpsFix fix,
                             long nowElapsedMs, long nowMs) {
        if (s == null) return;
        if (fix == null || !fix.hasLocation() || fix.loadedFromCache) {
            s.lat = Double.NaN;
            s.lng = Double.NaN;
            s.accuracyM = 0f;
            s.fixAgeMs = -1L;
            s.fixFromCache = fix != null && fix.loadedFromCache;
            s.gpsQuality = ParkingSession.GPS_UNKNOWN;
            return;
        }
        long age;
        if (fix.fixElapsedMs > 0L && fix.fixElapsedMs <= nowElapsedMs) {
            // Monotonic basis: immune to a wrong RTC at cold boot and to the
            // sidecar's 1 Hz keep-alive re-sends (which refresh lastUpdate but
            // not the fix time).
            age = nowElapsedMs - fix.fixElapsedMs;
        } else if (fix.lastUpdateMs > 0L) {
            age = Math.max(0L, nowMs - fix.lastUpdateMs);
        } else {
            age = -1L;
        }
        s.lat = fix.lat;
        s.lng = fix.lng;
        s.accuracyM = fix.accuracyM;
        s.fixAgeMs = age;
        s.fixFromCache = false;
        s.gpsQuality = ParkingSession.qualityFor(true, false, age);
    }
}
