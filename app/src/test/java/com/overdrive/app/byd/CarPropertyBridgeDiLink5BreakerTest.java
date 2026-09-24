package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure-logic coverage for the DiLink 5 bridge resolution guard. The policy
 * must never open on a car whose provider merely starts late (transient
 * failures / a short run of rejections), and must open only after a sustained
 * run of the specific rejection signature.
 */
public class CarPropertyBridgeDiLink5BreakerTest {

    @Test
    public void thirtyConsecutiveHardRejectionsTripThenBackOffWithDoubling() {
        CarPropertyBridge.DiLink5BreakerState s =
                new CarPropertyBridge.DiLink5BreakerState();
        long now = 100_000L;
        // 29 rejections: still closed.
        for (int i = 0; i < CarPropertyBridge.DiLink5BreakerState.DILINK5_BREAKER_TRIP_FAILURES - 1; i++) {
            assertFalse(s.recordHardFailure(now + i * 1_000L));
            assertFalse(s.isOpen(now + i * 1_000L));
        }
        // 30th trips for the initial 60s window.
        long tripAt = now + 29_000L;
        assertTrue(s.recordHardFailure(tripAt));
        assertTrue(s.isOpen(tripAt));
        assertTrue(s.isOpen(tripAt + CarPropertyBridge.DiLink5BreakerState.DILINK5_BREAKER_INITIAL_OPEN_MS - 1L));
        assertFalse(s.isOpen(tripAt + CarPropertyBridge.DiLink5BreakerState.DILINK5_BREAKER_INITIAL_OPEN_MS));
        assertEquals(1, s.trips);

        // Second trip doubles the open window to 120s.
        long second = tripAt + 61_000L;
        for (int i = 0; i < CarPropertyBridge.DiLink5BreakerState.DILINK5_BREAKER_TRIP_FAILURES - 1; i++) {
            assertFalse(s.recordHardFailure(second + i * 1_000L));
        }
        long secondTripAt = second + 29_000L;
        assertTrue(s.recordHardFailure(secondTripAt));
        assertTrue(s.isOpen(secondTripAt + 119_999L));
        assertFalse(s.isOpen(secondTripAt + 120_000L));
        assertEquals(2, s.trips);
    }

    @Test
    public void openWindowIsCappedAtThirtyMinutes() {
        CarPropertyBridge.DiLink5BreakerState s =
                new CarPropertyBridge.DiLink5BreakerState();
        long t = 0L;
        long lastOpenMs = 0L;
        for (int trip = 0; trip < 12; trip++) {
            for (int i = 0; i < CarPropertyBridge.DiLink5BreakerState.DILINK5_BREAKER_TRIP_FAILURES - 1; i++) {
                s.recordHardFailure(t);
            }
            assertTrue(s.recordHardFailure(t));
            lastOpenMs = s.openUntilElapsedMs - t;
            t = s.openUntilElapsedMs + 1L;
        }
        assertEquals(CarPropertyBridge.DiLink5BreakerState.DILINK5_BREAKER_MAX_OPEN_MS, lastOpenMs);
    }

    @Test
    public void successAndTransientFailuresNeverAccumulateTowardATrip() {
        CarPropertyBridge.DiLink5BreakerState s =
                new CarPropertyBridge.DiLink5BreakerState();
        // A late-starting provider on a healthy car: rejections interleaved
        // with a DeadObjectException-style transient reset the run.
        for (int i = 0; i < 20; i++) s.recordHardFailure(i);
        s.recordTransientFailure();
        for (int i = 0; i < 29; i++) assertFalse(s.recordHardFailure(100 + i));
        // A single success fully resets the state and restores the 60s window.
        s.recordSuccess();
        assertEquals(0, s.consecutiveHardFailures);
        assertFalse(s.isOpen(1_000_000L));
        assertEquals(CarPropertyBridge.DiLink5BreakerState.DILINK5_BREAKER_INITIAL_OPEN_MS, s.currentOpenMs);
        assertEquals(0, s.trips);
    }

    @Test
    public void killSwitchAcceptsOnlyExplicitOffValues() {
        assertTrue(CarPropertyBridge.DiLink5KillSwitch.isEnabled(null));
        assertTrue(CarPropertyBridge.DiLink5KillSwitch.isEnabled(""));
        assertTrue(CarPropertyBridge.DiLink5KillSwitch.isEnabled("1"));
        assertTrue(CarPropertyBridge.DiLink5KillSwitch.isEnabled("true"));
        assertFalse(CarPropertyBridge.DiLink5KillSwitch.isEnabled("0"));
        assertFalse(CarPropertyBridge.DiLink5KillSwitch.isEnabled(" false "));
        assertFalse(CarPropertyBridge.DiLink5KillSwitch.isEnabled("OFF"));
    }
}
