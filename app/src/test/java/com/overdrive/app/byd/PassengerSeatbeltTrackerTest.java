package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PassengerSeatbeltTrackerTest {
    private static final int UNKNOWN = BydVehicleData.UNAVAILABLE;

    @Test
    public void emptySeatAtStartupNeverPublishesBuckled() {
        PassengerSeatbeltTracker tracker = new PassengerSeatbeltTracker();

        assertReading(tracker.resolveGetter(1, UNKNOWN, 100L), 1, UNKNOWN, false, false);
        assertReading(tracker.resolveGetter(1, UNKNOWN, 600L), 1, UNKNOWN, false, false);
        assertEquals("AWAITING_CONFIRMED_UNBUCKLED", tracker.diagnosticState());
    }

    @Test
    public void closedDoorUnbuckledArmsWhenOccupancyIsUnavailable() {
        PassengerSeatbeltTracker tracker = new PassengerSeatbeltTracker();

        assertTrue(tracker.onPassengerDoorState(true));
        assertReading(tracker.resolveGetter(1, UNKNOWN, 100L), 1, UNKNOWN, false, false);
        assertReading(tracker.resolveGetter(0, UNKNOWN, 200L), 0, 0, false, false);
        assertTrue(tracker.onPassengerDoorState(false));

        // Exact regression sequence: this trim has no occupancy reading, but after the door closes
        // a real unbuckled 0 must still arm the following buckle transition.
        assertReading(tracker.resolveGetter(0, UNKNOWN, 300L), 0, 0, true, false);
        assertReading(tracker.resolveGetter(1, UNKNOWN, 400L), 1, 1, true, false);
        assertEquals("TRACKING", tracker.diagnosticState());
    }

    @Test
    public void confirmedEmptySeatDoesNotArmUnavailableFallback() {
        PassengerSeatbeltTracker tracker = new PassengerSeatbeltTracker();

        assertReading(tracker.resolveGetter(0, 0, 100L), 0, 0, false, false);
        assertReading(tracker.resolveGetter(1, 0, 200L), 1, UNKNOWN, false, false);
        assertEquals("AWAITING_CONFIRMED_UNBUCKLED", tracker.diagnosticState());
    }

    @Test
    public void confirmedOccupiedUnbucklePreservesRealBuckleTransitions() {
        PassengerSeatbeltTracker tracker = new PassengerSeatbeltTracker();

        assertReading(tracker.resolveGetter(0, 1, 100L), 0, 0, true, false);
        assertReading(tracker.resolveGetter(1, 1, 200L), 1, 1, true, false);
        assertReading(tracker.resolveGetter(0, 1, 300L), 0, 0, true, false);

        // Door-open and confirmed-empty evidence both revoke trust for the next passenger.
        assertTrue(tracker.onPassengerDoorState(true));
        assertReading(tracker.resolveGetter(1, UNKNOWN, 400L), 1, UNKNOWN, false, false);
        assertTrue(tracker.onPassengerDoorState(false));
        assertReading(tracker.resolveGetter(0, 1, 500L), 0, 0, true, false);
        assertReading(tracker.resolveGetter(1, 0, 600L), 1, UNKNOWN, false, false);
    }

    @Test
    public void typedCallbackTemporarilyOverridesLaggingGetter() {
        PassengerSeatbeltTracker tracker = new PassengerSeatbeltTracker();

        assertTrue(tracker.recordCallback(1, 1_000L));
        assertReading(tracker.resolveGetter(0, UNKNOWN, 1_100L), 1, 1, true, true);
        assertReading(tracker.resolveGetter(1, UNKNOWN, 1_200L), 1, 1, true, true);

        // Once the getter converges, a later real unbuckle is no longer masked.
        assertReading(tracker.resolveGetter(0, UNKNOWN, 1_300L), 0, 0, true, false);
    }

    @Test
    public void callbackOverrideExpiresIfGetterNeverConverges() {
        PassengerSeatbeltTracker tracker = new PassengerSeatbeltTracker();

        assertTrue(tracker.recordCallback(1, 1_000L));
        assertReading(tracker.resolveGetter(0, UNKNOWN, 2_000L), 1, 1, true, true);
        assertReading(
                tracker.resolveGetter(
                        0,
                        UNKNOWN,
                        1_000L + PassengerSeatbeltTracker.CALLBACK_OVERRIDE_MS + 1L),
                0, 0, true, false);
    }

    @Test
    public void doorOpenClearsAStaleCallbackOverride() {
        PassengerSeatbeltTracker tracker = new PassengerSeatbeltTracker();

        assertTrue(tracker.recordCallback(1, 1_000L));
        assertTrue(tracker.onPassengerDoorState(true));
        assertReading(tracker.resolveGetter(1, UNKNOWN, 1_100L), 1, UNKNOWN, false, false);
        assertFalse(tracker.onPassengerDoorState(true));
    }

    private static void assertReading(
            PassengerSeatbeltTracker.Reading reading,
            int beltState,
            int automationState,
            boolean buckledGetterTrusted,
            boolean callbackAuthoritative) {
        assertEquals(beltState, reading.beltState);
        assertEquals(automationState, reading.automationState);
        assertEquals(buckledGetterTrusted, reading.buckledGetterTrusted);
        assertEquals(callbackAuthoritative, reading.callbackAuthoritative);
    }
}
