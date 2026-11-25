package com.overdrive.app.remoteaccess;

import static com.overdrive.app.remoteaccess.ProximityEvaluator.Event.ARRIVAL;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.Event.DEPARTURE;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.Event.NONE;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.AWAY;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.NEAR;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.TRANSITION;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.UNKNOWN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Exercises actual approach/departure sequences, freshness, and noisy signal boundaries. */
public class ProximityEvaluatorTest {
    private static final String PHONE = "AA:BB:CC:DD:EE:01";
    private static final String OTHER_PHONE = "AA:BB:CC:DD:EE:02";

    private static ProximityEvaluator evaluator() {
        return new ProximityEvaluator(new ProximityEvaluator.Config(-12, -24, 1, 4_000, 5_000));
    }

    private static ProximityEvaluator nearEvaluator() {
        return new ProximityEvaluator(new ProximityEvaluator.Config(-12, -24, 1, 4_000, 5_000,
                ProximityEvaluator.UnlockTrigger.NEAR));
    }

    private static ProximityEvaluator.Snapshot read(ProximityEvaluator evaluator, long time, int rssi) {
        return evaluator.observe(PHONE, rssi, time, true, true);
    }

    private static ProximityEvaluator.Snapshot stable(ProximityEvaluator evaluator, long start, int rssi) {
        assertEquals(NONE, read(evaluator, start, rssi).event);
        assertEquals(NONE, read(evaluator, start + 2_000, rssi).event);
        return read(evaluator, start + 4_000, rssi);
    }

    @Test
    public void startingNearSeedsPresenceWithoutArrivalOrArm() {
        ProximityEvaluator evaluator = evaluator();
        ProximityEvaluator.Snapshot near = stable(evaluator, 0, -8);
        assertEquals(NEAR, near.state);
        assertEquals(NONE, near.event);
        assertFalse(near.armed);
        assertEquals(NONE, evaluator.snapshot(5_000).event);
    }

    @Test
    public void nearModeInitialPresenceArrivesOnlyAfterFreshDwellAndOnlyOnce() {
        ProximityEvaluator evaluator = nearEvaluator();
        assertEquals(NONE, read(evaluator, 0, -8).event);
        assertEquals(NONE, read(evaluator, 2_000, -8).event);
        assertEquals(NONE, evaluator.snapshot(4_000).event);
        ProximityEvaluator.Snapshot near = read(evaluator, 4_000, -8);
        assertEquals(NEAR, near.state);
        assertEquals(ARRIVAL, near.event);
        assertFalse(near.armed);
        for (long time = 6_000; time <= 20_000; time += 2_000)
            assertEquals(NONE, read(evaluator, time, -8).event);
    }

    @Test
    public void nearModeDisconnectIsNotAnActionAndReconnectNeedsFreshNearDwell() {
        ProximityEvaluator evaluator = nearEvaluator();
        assertEquals(ARRIVAL, stable(evaluator, 0, -8).event);
        ProximityEvaluator.Snapshot disconnected =
                evaluator.observe(PHONE, null, 6_000, false, true);
        assertEquals(UNKNOWN, disconnected.state);
        assertEquals(NONE, disconnected.event);
        assertEquals(ARRIVAL, stable(evaluator, 8_000, -8).event);
        assertEquals(NONE, read(evaluator, 14_000, -8).event);
    }

    @Test
    public void nearModeUnknownOrIneligibleDataClearsAllPendingDwell() {
        for (Integer invalid : new Integer[] {null, -128, 21}) {
            ProximityEvaluator evaluator = nearEvaluator();
            read(evaluator, 0, -8);
            read(evaluator, 2_000, -8);
            ProximityEvaluator.Snapshot unknown =
                    evaluator.observe(PHONE, invalid, 4_000, true, true);
            assertEquals(UNKNOWN, unknown.state);
            assertEquals(NONE, unknown.event);
            assertEquals(ARRIVAL, stable(evaluator, 6_000, -8).event);
        }
        ProximityEvaluator evaluator = nearEvaluator();
        read(evaluator, 0, -8);
        read(evaluator, 2_000, -8);
        assertEquals(NONE, evaluator.observe(PHONE, -8, 4_000, true, false).event);
        assertEquals(UNKNOWN, evaluator.snapshot(4_000).state);
        assertEquals(ARRIVAL, stable(evaluator, 6_000, -8).event);
    }

    @Test
    public void nearModeStaleStatusNeverArrivesAndRestartsConfirmation() {
        ProximityEvaluator evaluator = nearEvaluator();
        assertEquals(ARRIVAL, stable(evaluator, 0, -8).event);
        ProximityEvaluator.Snapshot stale = evaluator.snapshot(9_001);
        assertEquals(UNKNOWN, stale.state);
        assertEquals(NONE, stale.event);
        assertEquals(ARRIVAL, stable(evaluator, 10_000, -8).event);
        assertEquals(NONE, evaluator.snapshot(14_001).event);
    }

    @Test
    public void nearModeDuplicatesAndStatusReadsCannotConfirmInitialPresence() {
        ProximityEvaluator evaluator = nearEvaluator();
        read(evaluator, 0, -8);
        for (int i = 0; i < 10; i++) {
            ProximityEvaluator.Snapshot duplicate = read(evaluator, 0, -8);
            assertEquals(NONE, duplicate.event);
            assertEquals(1, duplicate.candidateSamples); // a duplicate never counts as a reading
        }
        assertEquals(NONE, evaluator.snapshot(3_000).event);
        assertEquals(1, evaluator.snapshot(3_000).candidateSamples);
        // The second real reading before the dwell: not yet; at the dwell: arrival (18 Oct floor: two readings).
        assertEquals(NONE, read(evaluator, 3_000, -8).event);
        assertEquals(NONE, evaluator.snapshot(4_000).event);
        assertEquals(ARRIVAL, read(evaluator, 4_000, -8).event);
    }

    @Test
    public void nearModeDeadbandDoesNotRepeatArrivalAndDepartureStillRequiresConfirmedAway() {
        ProximityEvaluator evaluator = nearEvaluator();
        assertEquals(ARRIVAL, stable(evaluator, 0, -8).event);
        assertEquals(NONE, read(evaluator, 6_000, -18).event);
        assertEquals(NONE, read(evaluator, 8_000, -8).event);
        assertEquals(DEPARTURE, stable(evaluator, 10_000, -30).event);
        assertEquals(ARRIVAL, stable(evaluator, 16_000, -8).event);
    }

    @Test
    public void nearModeMissingDataWhileFarNeverCountsAsDeparture() {
        ProximityEvaluator evaluator = nearEvaluator();
        assertEquals(ARRIVAL, stable(evaluator, 0, -8).event);
        read(evaluator, 6_000, -30);
        assertEquals(NONE, evaluator.observe(PHONE, null, 8_000, false, true).event);
        ProximityEvaluator.Snapshot away = stable(evaluator, 10_000, -30);
        assertEquals(AWAY, away.state);
        assertEquals(NONE, away.event);
        assertTrue(away.armed);
        assertEquals(ARRIVAL, stable(evaluator, 16_000, -8).event);
    }

    @Test
    public void nearModeMedianRejectsAnIsolatedStrongSignal() {
        ProximityEvaluator evaluator = new ProximityEvaluator(
                new ProximityEvaluator.Config(-12, -24, 15_000, 4_000, 5_000,
                        ProximityEvaluator.UnlockTrigger.NEAR));
        stable(evaluator, 0, -30);
        ProximityEvaluator.Snapshot outlier = read(evaluator, 6_000, 20);
        assertEquals(AWAY, outlier.state);
        assertEquals(-30.0, outlier.medianRssi, 0.0);
        assertEquals(NONE, outlier.event);
    }

    @Test
    public void stableAwayThenNearArrivesOnceAndNeedsAnotherDepartureToRearm() {
        ProximityEvaluator evaluator = evaluator();
        ProximityEvaluator.Snapshot away = stable(evaluator, 0, -30);
        assertEquals(AWAY, away.state);
        assertEquals(NONE, away.event);
        assertTrue(away.armed);
        assertEquals(ARRIVAL, stable(evaluator, 6_000, -8).event);
        for (long time = 12_000; time <= 20_000; time += 2_000) {
            ProximityEvaluator.Snapshot near = read(evaluator, time, -9);
            assertEquals(NONE, near.event);
            assertFalse(near.armed);
        }
        assertEquals(DEPARTURE, stable(evaluator, 22_000, -30).event);
        assertEquals(ARRIVAL, stable(evaluator, 28_000, -8).event);
    }

    @Test
    public void startupNearCanDepartOnlyThroughStableFarReadings() {
        ProximityEvaluator evaluator = evaluator();
        stable(evaluator, 0, -8);
        ProximityEvaluator.Snapshot firstFar = read(evaluator, 6_000, -30);
        assertEquals(NONE, firstFar.event);
        assertEquals(NEAR, firstFar.state);
        assertEquals(NONE, read(evaluator, 8_000, -30).event);
        assertEquals(DEPARTURE, read(evaluator, 10_000, -30).event);
    }

    @Test
    public void deadbandJitterHoldsNearAndInterruptsPendingDeparture() {
        ProximityEvaluator evaluator = evaluator();
        stable(evaluator, 0, -8);
        read(evaluator, 6_000, -30);
        ProximityEvaluator.Snapshot middle = read(evaluator, 8_000, -18);
        assertEquals(NEAR, middle.state);
        assertEquals(UNKNOWN, middle.candidate);
        assertFalse(middle.armed);
        assertEquals(NONE, read(evaluator, 10_000, -30).event);
        assertEquals(NONE, read(evaluator, 12_000, -30).event);
        assertEquals(NONE, read(evaluator, 14_000, -18).event);
        assertFalse(evaluator.snapshot(14_000).armed);
    }

    @Test
    public void nullAndInvalidReadingsClearHistoryAndApproachArmImmediately() {
        for (Integer invalid : new Integer[] {null, -128, 21}) {
            ProximityEvaluator evaluator = evaluator();
            stable(evaluator, 0, -30);
            ProximityEvaluator.Snapshot missing =
                    evaluator.observe(PHONE, invalid, 6_000, true, true);
            assertEquals(UNKNOWN, missing.state);
            assertEquals(NONE, missing.event);
            assertFalse(missing.armed);
            assertNull(missing.rawRssi);
            assertEquals(0, missing.sampleCount);
            assertEquals(NONE, stable(evaluator, 8_000, -8).event);
        }
    }

    @Test
    public void disconnectIsNeverDepartureAndReconnectNearIsNeverArrival() {
        ProximityEvaluator evaluator = evaluator();
        stable(evaluator, 0, -8);
        ProximityEvaluator.Snapshot disconnected =
                evaluator.observe(PHONE, null, 6_000, false, true);
        assertEquals(UNKNOWN, disconnected.state);
        assertEquals(NONE, disconnected.event);
        assertEquals(NONE, stable(evaluator, 8_000, -8).event);

        ProximityEvaluator armed = evaluator();
        stable(armed, 0, -30);
        armed.observe(PHONE, null, 6_000, false, true);
        assertEquals(NONE, stable(armed, 8_000, -8).event);
    }

    @Test
    public void staleSnapshotRevokesArmWithoutTimerGeneratedActions() {
        ProximityEvaluator evaluator = evaluator();
        stable(evaluator, 0, -30);
        assertTrue(evaluator.snapshot(9_000).armed);
        ProximityEvaluator.Snapshot stale = evaluator.snapshot(9_001);
        assertEquals(UNKNOWN, stale.state);
        assertEquals("stale_signal", stale.reason);
        assertEquals(NONE, stale.event);
        assertFalse(stale.armed);
        assertEquals(NONE, stable(evaluator, 10_000, -8).event);
    }

    @Test
    public void aGapRevokesArmEvenWithoutIntermediateSnapshot() {
        ProximityEvaluator evaluator = evaluator();
        stable(evaluator, 0, -30);
        assertEquals(NONE, stable(evaluator, 10_000, -8).event);
        assertEquals(NEAR, evaluator.snapshot(14_000).state);
    }

    @Test
    public void statusReadsCannotCompleteDwellOrEmitArrival() {
        ProximityEvaluator evaluator = evaluator();
        stable(evaluator, 0, -30);
        read(evaluator, 6_000, -8);
        for (long time = 7_000; time <= 10_000; time += 1_000) {
            assertEquals(NONE, evaluator.snapshot(time).event);
            assertEquals(AWAY, evaluator.snapshot(time).stableState);
            assertEquals(1, evaluator.snapshot(time).candidateSamples); // status reads are not readings
        }
        // The second real reading completes the dwell (18 Oct floor: two readings).
        assertEquals(ARRIVAL, read(evaluator, 10_000, -8).event);
    }

    @Test
    public void duplicateAndOlderSamplesCannotBuildStabilityOrRefreshFreshness() {
        ProximityEvaluator evaluator = evaluator();
        stable(evaluator, 0, -30);
        ProximityEvaluator.Snapshot first = read(evaluator, 6_000, -8);
        assertEquals(1, first.candidateSamples);
        for (int i = 0; i < 10; i++) {
            ProximityEvaluator.Snapshot duplicate = read(evaluator, 6_000, -8);
            assertEquals(NONE, duplicate.event);
            assertEquals(1, duplicate.candidateSamples);
            assertEquals("duplicate_sample", duplicate.reason);
        }
        ProximityEvaluator.Snapshot older = read(evaluator, 5_000, -8);
        assertEquals(NONE, older.event);
        assertEquals(1, older.candidateSamples);
        assertEquals("out_of_order", older.reason);
        assertEquals(UNKNOWN, evaluator.snapshot(11_001).state);
    }

    @Test
    public void switchingPhonesOrVehicleGateResetsWithoutSyntheticActions() {
        ProximityEvaluator evaluator = evaluator();
        stable(evaluator, 0, -30);
        ProximityEvaluator.Snapshot changed =
                evaluator.observe(OTHER_PHONE, -8, 6_000, true, true);
        assertFalse(changed.armed);
        evaluator.observe(OTHER_PHONE, -8, 8_000, true, true);
        assertEquals(NONE, evaluator.observe(OTHER_PHONE, -8, 10_000, true, true).event);
        ProximityEvaluator.Snapshot blocked =
                evaluator.observe(OTHER_PHONE, -8, 12_000, true, false);
        assertEquals(UNKNOWN, blocked.state);
        assertEquals(NONE, blocked.event);
        assertEquals(0, blocked.sampleCount);
    }

    @Test
    public void addressFormattingDoesNotCauseFalseSwitchOrLoseArm() {
        ProximityEvaluator evaluator = evaluator();
        stable(evaluator, 0, -30);
        assertTrue(evaluator.observe("  aa:bb:cc:dd:ee:01  ", -30, 6_000, true, true).armed);
        assertEquals(ARRIVAL, stable(evaluator, 8_000, -8).event);
    }

    @Test
    public void medianRejectsAnIsolatedStrongOutlierAndEvictsOldSamples() {
        ProximityEvaluator evaluator = new ProximityEvaluator(
                new ProximityEvaluator.Config(-12, -24, 15_000, 4_000, 5_000));
        stable(evaluator, 0, -30);
        ProximityEvaluator.Snapshot spike = read(evaluator, 6_000, 20);
        assertEquals(-30.0, spike.medianRssi, 0.0);
        assertEquals(AWAY, spike.state);
        assertEquals(NONE, spike.event);
        read(evaluator, 8_000, -30);
        read(evaluator, 10_000, -30);
        read(evaluator, 12_000, -30);
        read(evaluator, 14_000, -30);
        ProximityEvaluator.Snapshot evicted = read(evaluator, 16_000, -30);
        assertEquals(8, evicted.sampleCount);
        assertEquals(-30.0, evicted.medianRssi, 0.0);
    }

    @Test
    public void evenSampleMedianAndInclusiveThresholdsAreExplicit() {
        // minStableSamples=1 is raised to the two-reading floor (18 Oct): a zone needs two readings.
        ProximityEvaluator evaluator = new ProximityEvaluator(
                new ProximityEvaluator.Config(-12, -24, 15_000, 0, 5_000, 1));
        assertEquals(TRANSITION, read(evaluator, 0, -24).state);
        assertEquals(AWAY, read(evaluator, 1_000, -24).state);
        read(evaluator, 2_000, 0);
        ProximityEvaluator.Snapshot even = read(evaluator, 3_000, 0);
        assertEquals(-12.0, even.medianRssi, 0.0);
        assertEquals(NEAR, even.candidate);
        assertEquals(NONE, even.event);
        ProximityEvaluator.Snapshot arrived = read(evaluator, 4_000, 0);
        assertEquals(ARRIVAL, arrived.event);
        assertEquals(NEAR, arrived.state);
    }

    @Test
    public void resetReleasesHistoryAndAllowsASeparateMonotonicSession() {
        ProximityEvaluator evaluator = evaluator();
        stable(evaluator, 100_000, -30);
        evaluator.reset();
        ProximityEvaluator.Snapshot empty = evaluator.snapshot(0);
        assertEquals(UNKNOWN, empty.state);
        assertNull(empty.address);
        assertEquals(-1, empty.freshnessMs);
        assertEquals(0, empty.sampleCount);
        assertFalse(empty.armed);
        assertEquals(NONE, stable(evaluator, 2_000, -8).event);
    }

    @Test
    public void rawRangeEndpointsAreAccepted() {
        ProximityEvaluator evaluator = new ProximityEvaluator(
                new ProximityEvaluator.Config(-12, -24, 1, 0, 5_000, 1));
        assertEquals(TRANSITION, read(evaluator, 0, -127).state);
        assertEquals(AWAY, read(evaluator, 1_000, -127).state);
        assertEquals(NONE, read(evaluator, 2_000, 20).event);
        assertEquals(ARRIVAL, read(evaluator, 3_000, 20).event);
    }

    private static ProximityEvaluator fastEvaluator(ProximityEvaluator.UnlockTrigger trigger) {
        return new ProximityEvaluator(new ProximityEvaluator.Config(
                -12, -24, 15_000, 4_000, 5_000, 4_000, 2_000, trigger));
    }

    private static void longAwayHistory(ProximityEvaluator evaluator) {
        for (long time = 0; time <= 15_000; time += 1_000)
            assertEquals(NONE, read(evaluator, time, -30).event);
        assertEquals(AWAY, evaluator.snapshot(15_000).state);
    }

    @Test
    public void fastArrivalUsesThreeFreshNearReadingsWithoutWaitingForAwayMedian() {
        ProximityEvaluator evaluator = fastEvaluator(ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR);
        longAwayHistory(evaluator);
        ProximityEvaluator.Snapshot first = read(evaluator, 16_000, -8);
        assertEquals(NONE, first.event);
        assertEquals(NEAR, first.candidate);
        assertFalse(first.departureEligible);
        assertEquals(NONE, read(evaluator, 17_000, -8).event);
        ProximityEvaluator.Snapshot arrival = read(evaluator, 18_000, -8);
        assertEquals(ARRIVAL, arrival.event);
        assertEquals(NEAR, arrival.state);
        assertEquals(3, arrival.nearSampleCount);
        assertEquals(2_000, arrival.nearStableForMs);
        assertEquals(-8.0, arrival.arrivalMedianRssi, 0.0);
        assertEquals(-30.0, arrival.departureMedianRssi, 0.0);
        assertEquals(-8.0, arrival.medianRssi, 0.0);
        assertEquals("arrival", arrival.filterKind);
        assertEquals(NONE, read(evaluator, 19_000, -8).event);
    }

    @Test
    public void fastInitialNearRetainsModeChoiceAndDoesNotRepeatWithinVisit() {
        for (ProximityEvaluator.UnlockTrigger trigger : ProximityEvaluator.UnlockTrigger.values()) {
            ProximityEvaluator evaluator = fastEvaluator(trigger);
            assertEquals(NONE, read(evaluator, 0, -8).event);
            assertEquals(NONE, read(evaluator, 1_000, -8).event);
            assertEquals(trigger == ProximityEvaluator.UnlockTrigger.NEAR ? ARRIVAL : NONE,
                    read(evaluator, 2_000, -8).event);
            assertEquals(NONE, read(evaluator, 3_000, -8).event);
        }
    }

    @Test
    public void fastArrivalRejectsAnIsolatedSpikeAndDeadbandInterruptsConfirmation() {
        ProximityEvaluator evaluator = fastEvaluator(ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR);
        longAwayHistory(evaluator);
        assertEquals(NONE, read(evaluator, 16_000, 20).event);
        assertFalse(read(evaluator, 17_000, -30).nearCandidate);
        assertEquals(NONE, read(evaluator, 18_000, -8).event);
        assertEquals(NONE, read(evaluator, 19_000, -8).event);
        ProximityEvaluator.Snapshot interrupted = read(evaluator, 20_000, -18);
        assertEquals(NONE, interrupted.event);
        assertEquals(0, interrupted.nearSampleCount);
        assertFalse(interrupted.departureEligible);
        assertEquals(NONE, read(evaluator, 21_000, -8).event);
        assertEquals(NONE, read(evaluator, 22_000, -8).event);
        assertEquals(ARRIVAL, read(evaluator, 23_000, -8).event);
    }

    @Test
    public void fastConfirmedNearCannotDepartOnDeadbandUsingOldAwayHistory() {
        ProximityEvaluator evaluator = fastEvaluator(ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR);
        longAwayHistory(evaluator);
        read(evaluator, 16_000, -8);
        read(evaluator, 17_000, -8);
        assertEquals(ARRIVAL, read(evaluator, 18_000, -8).event);
        ProximityEvaluator.Snapshot firstDeadband = read(evaluator, 19_000, -18);
        assertEquals(-30.0, firstDeadband.departureMedianRssi, 0.0);
        for (long time = 20_000; time <= 27_000; time += 1_000) {
            ProximityEvaluator.Snapshot current = read(evaluator, time, -18);
            assertEquals(NONE, current.event);
            assertEquals(NEAR, current.state);
            assertEquals(UNKNOWN, current.candidate);
            assertFalse(current.departureEligible);
        }
    }

    @Test
    public void fastArrivalStillRequiresFourSecondFreshAwayDeparture() {
        ProximityEvaluator evaluator = fastEvaluator(ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR);
        longAwayHistory(evaluator);
        read(evaluator, 16_000, -8);
        read(evaluator, 17_000, -8);
        assertEquals(ARRIVAL, read(evaluator, 18_000, -8).event);
        for (long time = 19_000; time < 23_000; time += 1_000)
            assertEquals(NONE, read(evaluator, time, -30).event);
        ProximityEvaluator.Snapshot departed = read(evaluator, 23_000, -30);
        assertEquals(DEPARTURE, departed.event);
        assertTrue(departed.departureEligible);
        assertEquals("departure", departed.filterKind);
    }

    @Test
    public void fastNearCountsOnlyReadingsInsideItsWindowAndNeedsTwoOfThem() {
        ProximityEvaluator evaluator = fastEvaluator(ProximityEvaluator.UnlockTrigger.NEAR);
        assertEquals(NONE, read(evaluator, 0, -8).event);
        // A reading past the 4 s window restarts the run: one reading inside, not two.
        ProximityEvaluator.Snapshot restarted = read(evaluator, 4_500, -8);
        assertEquals(NONE, restarted.event);
        assertEquals(1, restarted.nearSampleCount);
        // Two readings inside the window, the 2 s confirmation not yet reached.
        ProximityEvaluator.Snapshot insufficient = read(evaluator, 6_000, -8);
        assertEquals(NONE, insufficient.event);
        assertEquals(2, insufficient.nearSampleCount);
        // Readings inside the window over the confirmation time: arrival (18 Oct floor: two readings).
        assertEquals(ARRIVAL, read(evaluator, 6_500, -8).event);
        // Two readings inside the window over the confirmation time are enough on their own.
        ProximityEvaluator two = fastEvaluator(ProximityEvaluator.UnlockTrigger.NEAR);
        assertEquals(NONE, read(two, 0, -8).event);
        assertEquals(ARRIVAL, read(two, 3_000, -8).event);
    }

    @Test
    public void fastNearHonorsCustomDwellLongerThanItsRecentSampleWindow() {
        ProximityEvaluator evaluator = new ProximityEvaluator(new ProximityEvaluator.Config(
                -12, -24, 15_000, 4_000, 5_000, 4_000, 6_000,
                ProximityEvaluator.UnlockTrigger.NEAR));
        for (long time = 0; time < 6_000; time += 1_000)
            assertEquals(NONE, read(evaluator, time, -8).event);
        assertEquals(ARRIVAL, read(evaluator, 6_000, -8).event);
    }

    @Test
    public void fastNearDuplicatesStatusAndUnknownCannotCompleteConfirmation() {
        ProximityEvaluator evaluator = fastEvaluator(ProximityEvaluator.UnlockTrigger.NEAR);
        read(evaluator, 0, -8);
        for (int i = 0; i < 5; i++) {
            ProximityEvaluator.Snapshot duplicate = read(evaluator, 0, -8);
            assertEquals(NONE, duplicate.event);
            assertEquals(1, duplicate.nearSampleCount); // duplicates never count as readings
        }
        assertEquals(NONE, evaluator.snapshot(1_500).event);
        assertEquals(1, evaluator.snapshot(1_500).nearSampleCount);
        assertEquals(NONE, read(evaluator, 1_500, -8).event); // second reading, dwell not reached
        assertEquals(NONE, evaluator.observe(PHONE, null, 3_000, true, true).event);
        assertEquals(NONE, read(evaluator, 4_000, -8).event);
        assertEquals(NONE, read(evaluator, 5_000, -8).event);
        assertEquals(ARRIVAL, read(evaluator, 6_000, -8).event);
        assertEquals(UNKNOWN, evaluator.snapshot(11_001).state);
        assertEquals(NONE, read(evaluator, 12_000, -8).event);
        assertEquals(NONE, read(evaluator, 13_000, -8).event);
        assertEquals(ARRIVAL, read(evaluator, 14_000, -8).event);
    }

    @Test
    public void fastNearWindowGapRestartsConfirmationEvenBeforeGlobalStaleness() {
        ProximityEvaluator evaluator = fastEvaluator(ProximityEvaluator.UnlockTrigger.NEAR);
        read(evaluator, 0, -8);
        read(evaluator, 1_000, -8);
        assertEquals(NONE, read(evaluator, 5_500, -8).event);
        assertEquals(NONE, read(evaluator, 6_500, -8).event);
        assertEquals(ARRIVAL, read(evaluator, 7_500, -8).event);
    }

    @Test
    public void fastAwayToNearDoesNotCarryArrivalArmThroughDisconnectOrVehicleIneligibility() {
        for (boolean connected : new boolean[] {false, true}) {
            ProximityEvaluator evaluator = fastEvaluator(ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR);
            longAwayHistory(evaluator);
            ProximityEvaluator.Snapshot revoked = evaluator.observe(PHONE, null, 16_000,
                    connected, !connected);
            assertEquals(UNKNOWN, revoked.state);
            assertFalse(revoked.armed);
            assertEquals(NONE, read(evaluator, 17_000, -8).event);
            assertEquals(NONE, read(evaluator, 18_000, -8).event);
            assertEquals(NONE, read(evaluator, 19_000, -8).event);
            assertEquals(NEAR, evaluator.snapshot(19_000).state);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void overlappingThresholdsAreRejected() {
        new ProximityEvaluator.Config(-24, -12, 15_000, 4_000, 5_000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unboundedWindowIsRejected() {
        new ProximityEvaluator.Config(-12, -24, Long.MAX_VALUE, 4_000, 5_000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void absentUnlockTriggerIsRejectedByExplicitConstructor() {
        new ProximityEvaluator.Config(-12, -24, 15_000, 4_000, 5_000,
                (ProximityEvaluator.UnlockTrigger) null);
    }
}
