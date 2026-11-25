package com.overdrive.app.remoteaccess;

import static com.overdrive.app.remoteaccess.ProximityEvaluator.Event.DEPARTURE;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.Event.NONE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.overdrive.app.remoteaccess.SnappyLocksPolicyTest.PresenceFixture;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 20 Oct snappy locks (C5): with {@code keyFobLockSamples} 1 (the default) the first clean completed
 * Absent search confirms key absence; 2-3 need that many consecutive results and still span the window;
 * Present, unknown and stale results reset exactly as before; the original two-argument constructor
 * keeps the window-derived rule. Style of {@link KeyFobPresenceEvaluatorTest}.
 */
public class KeyFobPresenceEvaluatorLockSamplesTest {

    @Test
    public void oneResultConfirmsAbsenceOnTheFirstCleanAbsentSearch() throws Exception {
        PresenceFixture f = new PresenceFixture(5_000, 1);
        KeyFobPresenceEvaluator.Snapshot first = f.read("absent", 100);
        assertEquals(DEPARTURE, first.event);
        assertEquals("physical_key_absence_confirmed", first.reason);
        assertTrue(first.departureKnown);
        assertTrue(first.lockCandidate);
        assertEquals(1, first.requiredSamples);
        assertEquals(1, first.sampleCount);
        assertEquals(5_000L, first.windowMs);
        assertEquals(5_000L, first.pollIntervalMs);
        // One departure per absence; the next completed Absent search only renews the candidate.
        KeyFobPresenceEvaluator.Snapshot next = f.read("absent", 5_100);
        assertEquals(NONE, next.event);
        assertTrue(next.lockCandidate);
        // A Present result clears it; the next clean Absent result confirms again.
        KeyFobPresenceEvaluator.Snapshot present = f.read("present", 10_100);
        assertFalse(present.departureKnown);
        assertEquals(0, present.sampleCount);
        assertEquals(DEPARTURE, f.read("absent", 15_100).event);
    }

    @Test
    public void twoOrThreeResultsNeedThatManyConsecutiveAbsentSearches() throws Exception {
        PresenceFixture two = new PresenceFixture(5_000, 2);
        KeyFobPresenceEvaluator.Snapshot first = two.read("absent", 100);
        assertEquals(NONE, first.event);
        assertEquals("confirming_absence", first.reason);
        assertEquals(2, first.requiredSamples);
        assertFalse(first.departureKnown);
        assertFalse(first.lockCandidate);
        assertEquals(DEPARTURE, two.read("absent", 5_100).event);
        PresenceFixture three = new PresenceFixture(5_000, 3);
        assertEquals(NONE, three.read("absent", 100).event);
        assertEquals(NONE, three.read("absent", 5_100).event);
        assertEquals(DEPARTURE, three.read("absent", 10_100).event);
        // 2-3 still span a longer window: the greater of the setting and window / spacing + 1 ...
        PresenceFixture wide = new PresenceFixture(30_000, 2);
        assertEquals(4, wide.policy.current(0).requiredSamples);
        assertEquals(10_000L, wide.policy.current(0).pollIntervalMs);
        // ... while one result ignores the window length, which then only sets the cadence.
        assertEquals(1, new PresenceFixture(30_000, 1).policy.current(0).requiredSamples);
    }

    @Test
    public void presentUnknownAndStaleResultsStillReset() throws Exception {
        PresenceFixture f = new PresenceFixture(5_000, 2);
        assertEquals(1, f.read("absent", 100).sampleCount);
        KeyFobPresenceEvaluator.Snapshot present = f.read("present", 5_100);
        assertEquals(0, present.sampleCount);
        assertEquals("physical_key_present", present.reason);
        assertEquals(NONE, f.read("absent", 10_100).event);
        assertEquals(DEPARTURE, f.read("absent", 15_100).event);
        // An unavailable search is never absence and restarts the count.
        PresenceFixture u = new PresenceFixture(5_000, 2);
        u.read("absent", 100);
        KeyFobPresenceEvaluator.Snapshot unknown = u.read("unknown", 5_100);
        assertEquals(0, unknown.sampleCount);
        assertEquals("physical_key_search_unavailable", unknown.reason);
        assertEquals(NONE, u.read("absent", 10_100).event);
        assertEquals(DEPARTURE, u.read("absent", 15_100).event);
        // With one result, an unknown revokes a confirmed absence; the next clean Absent confirms again.
        PresenceFixture one = new PresenceFixture(5_000, 1);
        assertEquals(DEPARTURE, one.read("absent", 100).event);
        assertFalse(one.read("unknown", 5_100).departureKnown);
        assertEquals(DEPARTURE, one.read("absent", 10_100).event);
        // A search that used the whole query budget is stale and never counts, even with one result.
        PresenceFixture slow = new PresenceFixture(5_000, 1);
        KeyFobPresenceEvaluator.Snapshot stale = slow.read("absent", 10_000, KeyFobPresenceEvaluator.MAX_QUERY_MS);
        assertEquals(NONE, stale.event);
        assertEquals("presence_result_stale", stale.reason);
        assertFalse(stale.departureKnown);
        assertEquals(0, stale.sampleCount);
        // A confirmed absence expires with its result (one missed search cycle is tolerated, two are not).
        PresenceFixture gap = new PresenceFixture(5_000, 1);
        assertEquals(DEPARTURE, gap.read("absent", 100).event);
        long expired = 100 + KeyFobPresenceEvaluator.FAST_RESULT_FRESH_MS + 1;
        gap.aliveUntil(expired);
        assertFalse(gap.policy.allowsLock(expired));
    }

    @Test
    public void constructorBoundsAndTheOldSignatureKeepTheWindowRule() {
        assertEquals(1, KeyFobPresenceEvaluator.MIN_LOCK_SAMPLES);
        assertEquals(3, KeyFobPresenceEvaluator.MAX_LOCK_SAMPLES);
        assertEquals(1, KeyFobPresenceEvaluator.requiredAbsentSamples(5_000, 5_000, 1));
        assertEquals(1, KeyFobPresenceEvaluator.requiredAbsentSamples(300_000, 10_000, 1));
        assertEquals(2, KeyFobPresenceEvaluator.requiredAbsentSamples(5_000, 5_000, 2));
        assertEquals(3, KeyFobPresenceEvaluator.requiredAbsentSamples(5_000, 5_000, 3));
        assertEquals(2, KeyFobPresenceEvaluator.requiredAbsentSamples(10_000, 10_000, 2));
        assertEquals(4, KeyFobPresenceEvaluator.requiredAbsentSamples(30_000, 10_000, 2));
        assertEquals(4, KeyFobPresenceEvaluator.requiredAbsentSamples(30_000, 10_000, 3));
        // The two-argument constructor is the original window-derived rule (two at 10 s and at 5 s).
        assertEquals(2, new KeyFobPresenceEvaluator(7, 10_000).current(0).requiredSamples);
        assertEquals(2, new KeyFobPresenceEvaluator(7, 5_000).current(0).requiredSamples);
        assertEquals(4, new KeyFobPresenceEvaluator(7, 30_000).current(0).requiredSamples);
        for (int invalid : new int[] {0, 4, -1}) {
            try {
                new KeyFobPresenceEvaluator(7, 5_000, invalid);
                fail("lockSamples " + invalid);
            } catch (IllegalArgumentException expected) {
                assertEquals("Invalid physical-key absence result count", expected.getMessage());
            }
        }
        // Invalid windows are still rejected with the original message.
        try {
            new KeyFobPresenceEvaluator(7, 12_000, 1);
            fail("window 12000");
        } catch (IllegalArgumentException expected) {
            assertEquals("Invalid physical-key absence window", expected.getMessage());
        }
    }

    @Test
    public void theHandlerConstructionFromDefaultSettingsIsFiveSecondsAndOneResult() throws Exception {
        for (boolean advanced : new boolean[] {false, true}) {
            JSONObject effective = RemoteAccessSettings.effective(
                    RemoteAccessSettings.defaults().put("advancedMode", advanced));
            // Exactly the arguments reset() passes.
            KeyFobPresenceEvaluator built = new KeyFobPresenceEvaluator(effective.optLong("revision"),
                    effective.optLong("keyFobLockWindowMs", 10_000), RemoteAccessSettings.keyFobLockSamples(effective));
            KeyFobPresenceEvaluator.Snapshot snapshot = built.current(0);
            assertEquals(5_000L, snapshot.windowMs);
            assertEquals(5_000L, snapshot.pollIntervalMs);
            assertEquals(1, snapshot.requiredSamples);
        }
        // Normal mode forces one result even when three are saved; Advanced honours the choice.
        JSONObject saved = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(), new JSONObject()
                .put(RemoteAccessSettings.KEY_FOB_LOCK_SAMPLES_KEY, 3).put("keyFobLockWindowMs", 20_000));
        assertEquals(1, RemoteAccessSettings.keyFobLockSamples(RemoteAccessSettings.effective(saved)));
        JSONObject advanced = RemoteAccessSettings.effective(new JSONObject(saved.toString()).put("advancedMode", true));
        KeyFobPresenceEvaluator custom = new KeyFobPresenceEvaluator(0,
                advanced.optLong("keyFobLockWindowMs", 10_000), RemoteAccessSettings.keyFobLockSamples(advanced));
        assertEquals(3, custom.current(0).requiredSamples);
        assertEquals(10_000L, custom.current(0).pollIntervalMs);
    }
}
