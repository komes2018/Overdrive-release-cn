package com.overdrive.app.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** GPS → session fields: keep old fixes, label them honestly, refuse cache-loaded ones. */
public class ParkingLocationCaptureTest {

    private static ParkingSession apply(ParkingEnvironment.GpsFix fix, long nowElapsed, long nowWall) {
        ParkingSession s = new ParkingSession();
        ParkingLocationCapture.apply(s, fix, nowElapsed, nowWall);
        return s;
    }

    @Test
    public void freshFixUsesMonotonicAge() {
        ParkingSession s = apply(new ParkingEnvironment.GpsFix(3.1, 101.6, 8f, 100_000L, 0L, false),
                130_000L, 5_000_000L);
        assertTrue(s.hasFix());
        assertEquals(30_000L, s.fixAgeMs);
        assertEquals(ParkingSession.GPS_FRESH, s.gpsQuality);
        assertEquals(8f, s.accuracyM, 0f);
    }

    @Test
    public void tenMinuteOldFixIsKeptButStale() {
        ParkingSession s = apply(new ParkingEnvironment.GpsFix(3.1, 101.6, 8f, 100_000L, 0L, false),
                100_000L + 10 * 60_000L, 5_000_000L);
        assertTrue("an old fix is exactly the garage-entrance answer", s.hasFix());
        assertEquals(ParkingSession.GPS_STALE, s.gpsQuality);
    }

    @Test
    public void twoMinuteOldFixIsRecent() {
        ParkingSession s = apply(new ParkingEnvironment.GpsFix(3.1, 101.6, 8f, 100_000L, 0L, false),
                100_000L + 2 * 60_000L, 5_000_000L);
        assertEquals(ParkingSession.GPS_RECENT, s.gpsQuality);
    }

    @Test
    public void wallClockFallbackWhenNoMonotonicStamp() {
        ParkingSession s = apply(new ParkingEnvironment.GpsFix(3.1, 101.6, 8f, 0L, 4_000_000L, false),
                999L, 4_000_000L + 45_000L);
        assertEquals(45_000L, s.fixAgeMs);
        assertEquals(ParkingSession.GPS_FRESH, s.gpsQuality);
    }

    @Test
    public void cacheLoadedOrZeroFixIsUnknown() {
        ParkingSession cached = apply(new ParkingEnvironment.GpsFix(3.1, 101.6, 8f, 0L, 0L, true), 1L, 1L);
        assertFalse(cached.hasFix());
        assertTrue(cached.fixFromCache);
        assertEquals(ParkingSession.GPS_UNKNOWN, cached.gpsQuality);

        ParkingSession zero = apply(new ParkingEnvironment.GpsFix(0.0, 0.0, 8f, 10L, 10L, false), 20L, 20L);
        assertFalse(zero.hasFix());
        assertEquals(ParkingSession.GPS_UNKNOWN, zero.gpsQuality);

        ParkingSession none = apply(null, 20L, 20L);
        assertFalse(none.hasFix());
        assertFalse(none.fixFromCache);
    }

    @Test
    public void qualityTable() {
        assertEquals(ParkingSession.GPS_UNKNOWN, ParkingSession.qualityFor(false, false, 10));
        assertEquals(ParkingSession.GPS_UNKNOWN, ParkingSession.qualityFor(true, true, 10));
        assertEquals(ParkingSession.GPS_STALE, ParkingSession.qualityFor(true, false, -1));
        assertEquals(ParkingSession.GPS_FRESH, ParkingSession.qualityFor(true, false, 59_999));
        assertEquals(ParkingSession.GPS_RECENT, ParkingSession.qualityFor(true, false, 60_000));
        assertEquals(ParkingSession.GPS_STALE, ParkingSession.qualityFor(true, false, 5 * 60_000));
    }
}
