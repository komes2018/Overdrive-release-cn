package com.overdrive.app.remoteaccess;

import static com.overdrive.app.remoteaccess.ProximityEvaluator.Event.ARRIVAL;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.Event.DEPARTURE;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.Event.NONE;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.AWAY;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.NEAR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 18 Oct two-reading floor (S1): {@code ProximityEvaluator.MIN_CONFIRMING_SAMPLES = 2} is the hard
 * floor no configuration can go below, and the confirming count of the settings-derived evaluator.
 * Replays use the collector's 1 Hz connected cadence and the new defaults (2 s / 1 s arrival,
 * 6 s / 2 s departure). Median/dwell semantics and the away_to_near first-connection rule are
 * untouched ({@link ProximityEvaluatorTest}, {@link ProximityEvaluatorFirstConnectionDiagnosticTest}).
 */
public class ProximityEvaluatorConfirmingSamplesTest {
    private static final String PHONE = "AA:BB:CC:DD:EE:01";
    /** Thresholds that make the brief's -20 dBm door reading a Near reading and -40 dBm Away. */
    private static final int NEAR_THRESHOLD = -22, AWAY_THRESHOLD = -34;

    private static ProximityEvaluator.Snapshot read(ProximityEvaluator evaluator, long nowMs, int rssi) {
        return evaluator.observe(PHONE, rssi, nowMs, true, true);
    }

    /** The settings-derived configuration with the new defaults. */
    private static ProximityEvaluator defaultsEvaluator() {
        try {
            JSONObject defaults = RemoteAccessSettings.defaults().put("nearThreshold", NEAR_THRESHOLD)
                    .put("awayThreshold", AWAY_THRESHOLD);
            return new ProximityEvaluator(RemoteAccessSettings.evaluatorConfig(defaults));
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Away history at 1 Hz long enough to confirm Away and arm an away_to_near arrival. */
    private static long awayHistory(ProximityEvaluator evaluator) {
        long t = 0;
        for (; t <= 8_000; t += 1_000) assertEquals(NONE, read(evaluator, t, -40).event);
        ProximityEvaluator.Snapshot away = evaluator.snapshot(t);
        assertEquals(AWAY, away.state);
        assertTrue(away.armed);
        return t;
    }

    @Test
    public void theFloorIsTwoAndNoConfigurationGoesBelowIt() {
        assertEquals(2, ProximityEvaluator.MIN_CONFIRMING_SAMPLES);
        assertEquals(2, new ProximityEvaluator.Config(-12, -24, 15_000, 4_000, 5_000, 1).minStableSamples);
        assertEquals(2, new ProximityEvaluator.Config(-12, -24, 15_000, 4_000, 5_000).minStableSamples);
        assertEquals(2, new ProximityEvaluator.Config(-12, -24, 6_000, 2_000, 5_000, 2_000, 1_000,
                ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR).minStableSamples);
        // Asking for more is still honoured.
        assertEquals(5, new ProximityEvaluator.Config(-12, -24, 15_000, 4_000, 5_000, 5).minStableSamples);
        assertEquals(2, RemoteAccessSettings.evaluatorConfig(RemoteAccessSettings.defaults()).minStableSamples);
    }

    @Test
    public void oneNearReadingNeverYieldsAnArrivalCandidateEvenAtTheFloorTimings() {
        ProximityEvaluator evaluator = defaultsEvaluator();
        long t = awayHistory(evaluator);
        ProximityEvaluator.Snapshot first = read(evaluator, t + 1_000, -20);
        assertEquals(NONE, first.event);
        assertFalse(first.arrivalCandidate);
        assertEquals(NEAR, first.candidate);
        assertEquals(1, first.nearSampleCount);
        assertEquals("stabilizing_near", first.reason);
        // Even a status read two seconds later, with the dwell long satisfied, adds nothing without a reading.
        ProximityEvaluator.Snapshot aged = evaluator.snapshot(t + 3_000);
        assertEquals(NONE, aged.event);
        assertFalse(aged.arrivalCandidate);
        // With a zero dwell and a one-sample request the floor still holds for a plain evaluator too.
        ProximityEvaluator bare = new ProximityEvaluator(new ProximityEvaluator.Config(
                NEAR_THRESHOLD, AWAY_THRESHOLD, 1, 0, 5_000, 1));
        long armed = awayHistory(bare);
        assertEquals(NONE, read(bare, armed + 1_000, -20).event);
        assertEquals(ARRIVAL, read(bare, armed + 2_000, -20).event);
    }

    @Test
    public void arrivalAtOneHertzConfirmsOnTheSecondNearReadingWithinTwoSeconds() {
        ProximityEvaluator evaluator = defaultsEvaluator();
        long t = awayHistory(evaluator);
        long firstNear = t + 1_000;
        assertEquals(NONE, read(evaluator, firstNear, -20).event);
        ProximityEvaluator.Snapshot second = read(evaluator, firstNear + 1_000, -20);
        assertEquals(ARRIVAL, second.event);
        assertTrue(second.arrivalCandidate);
        assertEquals(NEAR, second.state);
        assertEquals(2, second.nearSampleCount);
        assertTrue(second.nearStableForMs >= 1_000);
        // Candidate at ~2 s after the last Away reading; a 1 Hz collector with a little jitter still
        // keeps two readings inside the 2 s window.
        ProximityEvaluator jitter = defaultsEvaluator();
        long start = awayHistory(jitter) + 1_000;
        assertEquals(NONE, read(jitter, start, -20).event);
        assertEquals(ARRIVAL, read(jitter, start + 1_050, -20).event);
    }

    @Test
    public void departureAtOneHertzConfirmsAFewSecondsAfterTheSignalDropsAndNeverOnOneAwayReading() {
        ProximityEvaluator evaluator = defaultsEvaluator();
        long t = awayHistory(evaluator);
        // Arrive and stay Near for a while.
        read(evaluator, t + 1_000, -20);
        assertEquals(ARRIVAL, read(evaluator, t + 2_000, -20).event);
        long now = t + 2_000;
        for (int i = 0; i < 8; i++) assertEquals(NONE, read(evaluator, now += 1_000, -20).event);
        // Walk away: readings drop below Away once a second.
        long firstAway = now + 1_000;
        long departedAt = -1;
        int awayReadings = 0;
        for (long when = firstAway; when <= firstAway + 10_000 && departedAt < 0; when += 1_000) {
            awayReadings++;
            ProximityEvaluator.Snapshot s = read(evaluator, when, -40);
            if (s.event == DEPARTURE) departedAt = when;
            else assertFalse(s.lockCandidate);
            // The floor: never on the first Away reading of a candidate run.
            if (s.event == DEPARTURE) assertTrue(s.candidateSamples == 0 && awayReadings >= 2);
        }
        assertTrue("departure confirmed", departedAt > 0);
        long afterDrop = departedAt - firstAway;
        assertTrue("candidate at ~4-8 s after the drop, was " + afterDrop, afterDrop >= 3_000 && afterDrop <= 8_000);
        ProximityEvaluator.Snapshot departed = evaluator.snapshot(departedAt);
        assertEquals(AWAY, departed.state);
        assertTrue(departed.armed);
    }

    @Test
    public void departureNeedsTwoAwayReadingsEvenWithAZeroDwell() {
        // Short median window and no dwell: the median flips on the first Away reading, the floor
        // still demands a second one.
        ProximityEvaluator evaluator = new ProximityEvaluator(new ProximityEvaluator.Config(
                NEAR_THRESHOLD, AWAY_THRESHOLD, 1, 0, 5_000, 2_000, 0,
                ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR));
        long t = awayHistory(evaluator);
        read(evaluator, t + 1_000, -20);
        assertEquals(ARRIVAL, read(evaluator, t + 2_000, -20).event);
        ProximityEvaluator.Snapshot firstAway = read(evaluator, t + 3_000, -40);
        assertEquals(NONE, firstAway.event);
        assertEquals(AWAY, firstAway.candidate);
        assertEquals(1, firstAway.candidateSamples);
        assertFalse(firstAway.lockCandidate);
        ProximityEvaluator.Snapshot secondAway = read(evaluator, t + 4_000, -40);
        assertEquals(DEPARTURE, secondAway.event);
        assertTrue(secondAway.lockCandidate);
    }
}
