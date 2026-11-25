package com.overdrive.app.remoteaccess;

import static com.overdrive.app.remoteaccess.ProximityEvaluator.Event.ARRIVAL;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.Event.DEPARTURE;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.Event.NONE;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.AWAY;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.NEAR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * R4 diagnostic (18 Oct): in away_to_near mode a phone's first confirmed Near cannot unlock (no
 * confirmed Away in its history). The evaluator now flags exactly that one confirming observation
 * with {@code firstConnectionNoPriorAway}; the daemon turns it into one
 * {@code reason=first_connection_no_prior_away} log line. Decisions, states and events are unchanged.
 */
public class ProximityEvaluatorFirstConnectionDiagnosticTest {
    private static final String PHONE = "AA:BB:CC:DD:EE:01";

    private static ProximityEvaluator evaluator(ProximityEvaluator.UnlockTrigger trigger) {
        return new ProximityEvaluator(new ProximityEvaluator.Config(-12, -24, 1, 4_000, 5_000, trigger));
    }

    private static ProximityEvaluator.Snapshot read(ProximityEvaluator evaluator, long time, int rssi) {
        return evaluator.observe(PHONE, rssi, time, true, true);
    }

    @Test
    public void firstConfirmedNearWithoutPriorAwayIsFlaggedExactlyOnceAndStillNeverUnlocks() {
        ProximityEvaluator evaluator = evaluator(ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR);
        ProximityEvaluator.Snapshot first = read(evaluator, 0, -8);
        ProximityEvaluator.Snapshot second = read(evaluator, 2_000, -8);
        assertFalse(first.firstConnectionNoPriorAway);
        assertFalse(second.firstConnectionNoPriorAway);
        ProximityEvaluator.Snapshot confirmed = read(evaluator, 4_000, -8);
        assertEquals(NEAR, confirmed.state);
        assertEquals(NONE, confirmed.event);
        assertFalse(confirmed.armed);
        assertFalse(confirmed.arrivalCandidate);
        assertEquals("stable_near", confirmed.reason);
        assertTrue(confirmed.firstConnectionNoPriorAway);
        // Only the confirming observation carries the flag; stable Near reads and status reads do not.
        for (long time = 5_000; time <= 15_000; time += 1_000) {
            ProximityEvaluator.Snapshot stable = read(evaluator, time, -8);
            assertEquals(NEAR, stable.state);
            assertFalse(stable.firstConnectionNoPriorAway);
            assertFalse(evaluator.snapshot(time + 500).firstConnectionNoPriorAway);
        }
        // A confirmed Away arms the phone; the next confirmed Near is a real arrival, not flagged.
        ProximityEvaluator.Snapshot away = null;
        for (long time = 16_000; time <= 20_000; time += 2_000) away = read(evaluator, time, -30);
        assertEquals(AWAY, away.state);
        assertEquals(DEPARTURE, away.event);
        assertTrue(away.armed);
        assertFalse(away.firstConnectionNoPriorAway);
        ProximityEvaluator.Snapshot arrival = null;
        for (long time = 21_000; time <= 25_000; time += 2_000) arrival = read(evaluator, time, -8);
        assertEquals(NEAR, arrival.state);
        assertEquals(ARRIVAL, arrival.event);
        assertFalse(arrival.firstConnectionNoPriorAway);
    }

    @Test
    public void nearModeNeverFlagsBecauseTheFirstNearAlreadyUnlocks() {
        ProximityEvaluator evaluator = evaluator(ProximityEvaluator.UnlockTrigger.NEAR);
        read(evaluator, 0, -8);
        read(evaluator, 2_000, -8);
        ProximityEvaluator.Snapshot confirmed = read(evaluator, 4_000, -8);
        assertEquals(NEAR, confirmed.state);
        assertEquals(ARRIVAL, confirmed.event);
        assertFalse(confirmed.firstConnectionNoPriorAway);
    }

    @Test
    public void separateArrivalConfirmationFlagsTheSameSingleObservation() {
        // The production configuration (separate arrival window) reaches Near through its own branch.
        ProximityEvaluator evaluator = new ProximityEvaluator(new ProximityEvaluator.Config(
                -27, -29, 15_000, 4_000, 5_000, 4_000, 2_000, ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR));
        int flagged = 0;
        ProximityEvaluator.Snapshot last = null;
        for (long time = 0; time <= 10_000; time += 1_000) {
            last = read(evaluator, time, -20);
            if (last.firstConnectionNoPriorAway) {
                flagged++;
                assertEquals(NEAR, last.state);
                assertEquals(NONE, last.event);
                assertEquals("stable_near", last.reason);
            }
        }
        assertEquals(1, flagged);
        assertEquals(NEAR, last.state);
        assertFalse(last.armed);
        // A reconnect after a lost signal is again a first connection without a prior Away: flagged once more.
        ProximityEvaluator.Snapshot lost = evaluator.observe(PHONE, null, 20_000, false, true);
        assertEquals("disconnected", lost.reason);
        assertFalse(lost.firstConnectionNoPriorAway);
        flagged = 0;
        for (long time = 21_000; time <= 30_000; time += 1_000)
            if (read(evaluator, time, -20).firstConnectionNoPriorAway) flagged++;
        assertEquals(1, flagged);
        // Away confirmations are never flagged, in either confirmation branch.
        for (long time = 31_000; time <= 45_000; time += 1_000)
            assertFalse(read(evaluator, time, -40).firstConnectionNoPriorAway);
    }
}
