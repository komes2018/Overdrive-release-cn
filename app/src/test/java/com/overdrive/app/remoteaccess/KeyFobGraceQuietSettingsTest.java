package com.overdrive.app.remoteaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * S4 (18 Oct): the B4 relock grace and the A2 post-lock quiet period take their duration from the
 * caller (the {@code relockGraceMs} / {@code postLockQuietMs} settings, 10 s by default) while the
 * evaluators' 15 s constants remain the defaults of the original signatures.
 */
public class KeyFobGraceQuietSettingsTest {
    private static final long UNLOCKED_AT = 100_000;

    @Test
    public void relockGraceUsesTheSuppliedDurationAndTheOldSignatureKeepsFifteenSeconds() {
        long grace = RemoteAccessSettings.RELOCK_GRACE_MS_DEFAULT;
        assertEquals(10_000L, grace);
        // Inside the supplied grace without a door edge: held; at the boundary: released.
        assertTrue(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", UNLOCKED_AT, -1, UNLOCKED_AT + 9_999, grace));
        assertFalse(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", UNLOCKED_AT, -1, UNLOCKED_AT + 10_000, grace));
        // The original four-argument form is the 15 s constant, unchanged.
        assertTrue(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", UNLOCKED_AT, -1, UNLOCKED_AT + 14_999));
        assertFalse(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", UNLOCKED_AT, -1, UNLOCKED_AT + 15_000));
        assertEquals(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", UNLOCKED_AT, -1, UNLOCKED_AT + 12_000),
                KeyFobPresenceEvaluator.relockGraceBlocks("unlock", UNLOCKED_AT, -1, UNLOCKED_AT + 12_000,
                        KeyFobPresenceEvaluator.RELOCK_GRACE_MS));
        // A door edge after the unlock releases the grace whatever its length; one before does not.
        assertFalse(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", UNLOCKED_AT, UNLOCKED_AT + 2_000, UNLOCKED_AT + 3_000, 30_000));
        assertTrue(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", UNLOCKED_AT, UNLOCKED_AT - 1, UNLOCKED_AT + 3_000, 30_000));
        // Only a prior unlock arms it; a clock regression blocks; a non-positive grace never blocks.
        assertFalse(KeyFobPresenceEvaluator.relockGraceBlocks("lock", UNLOCKED_AT, -1, UNLOCKED_AT + 1_000, grace));
        assertFalse(KeyFobPresenceEvaluator.relockGraceBlocks("", -1, -1, UNLOCKED_AT, grace));
        assertTrue(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", UNLOCKED_AT, -1, UNLOCKED_AT - 1, grace));
        assertFalse(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", UNLOCKED_AT, -1, UNLOCKED_AT + 1, 0));
        // The settings range maps straight onto the predicate.
        assertTrue(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", UNLOCKED_AT, -1, UNLOCKED_AT + 2_999, RemoteAccessSettings.GRACE_MS_MIN));
        assertFalse(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", UNLOCKED_AT, -1, UNLOCKED_AT + 3_000, RemoteAccessSettings.GRACE_MS_MIN));
        assertTrue(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", UNLOCKED_AT, -1, UNLOCKED_AT + 29_999, RemoteAccessSettings.GRACE_MS_MAX));
    }

    @Test
    public void postLockQuietUsesTheSuppliedDurationAndTheOldSignatureKeepsFifteenSeconds() throws Exception {
        // 10 s quiet (seed at 1 s → quiet until 11 s): a lone detection at 10 s is quiet, at 11.001 s an
        // arrival. Heartbeats keep the receiver healthy (a 5 s gap would clear the seed as before).
        KeyFobEventEvaluator ten = healthy();
        assertTrue(ten.seedAbsence(1_000, 10_000));
        heartbeat(ten, 2, 4_000);
        heartbeat(ten, 3, 8_000);
        KeyFobEventEvaluator.Snapshot quiet = read(ten, 4, 1, 8, 10_000);
        assertEquals(ProximityEvaluator.Event.NONE, quiet.event);
        assertEquals("post_lock_quiet", quiet.reason);
        KeyFobEventEvaluator later = healthy();
        assertTrue(later.seedAbsence(1_000, 10_000));
        heartbeat(later, 2, 4_000);
        heartbeat(later, 3, 8_000);
        assertTrue(read(later, 4, 1, 8, 11_001).arrival);
        // The one-argument form keeps the 15 s constant: 11 s is still quiet, 16 s is an arrival.
        KeyFobEventEvaluator fifteen = healthy();
        assertTrue(fifteen.seedAbsence(1_000));
        heartbeat(fifteen, 2, 4_000);
        heartbeat(fifteen, 3, 8_000);
        KeyFobEventEvaluator.Snapshot stillQuiet = read(fifteen, 4, 1, 8, 11_001);
        assertEquals("post_lock_quiet", stillQuiet.reason);
        assertFalse(stillQuiet.arrival);
        KeyFobEventEvaluator fifteenLater = healthy();
        assertTrue(fifteenLater.seedAbsence(1_000));
        heartbeat(fifteenLater, 2, 4_000);
        heartbeat(fifteenLater, 3, 8_000);
        heartbeat(fifteenLater, 4, 12_000);
        assertTrue(read(fifteenLater, 5, 1, 8, 16_000).arrival);
        // The shortest setting (3 s) and a non-positive value (no window) are honoured; refusal rules are unchanged.
        KeyFobEventEvaluator three = healthy();
        assertTrue(three.seedAbsence(1_000, RemoteAccessSettings.GRACE_MS_MIN));
        assertTrue(read(three, 2, 1, 8, 4_001).arrival);
        KeyFobEventEvaluator none = healthy();
        assertTrue(none.seedAbsence(1_000, 0));
        assertTrue(read(none, 2, 1, 8, 1_500).arrival);
        KeyFobEventEvaluator cold = new KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR, 7, true);
        assertFalse(cold.seedAbsence(1_000, 10_000));
    }

    private static KeyFobEventEvaluator healthy() throws Exception {
        KeyFobEventEvaluator evaluator = new KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR, 7, true);
        JSONObject sample = json(1, 0, -1, 100);
        sample.getJSONObject("scope").put("driverLock", 1);
        evaluator.observe(KeyFobEventEvaluator.Observation.parse(sample), 100, true);
        return evaluator;
    }

    /** A heartbeat with no native event and a fresh locked scope at {@code now}, keeping the receiver healthy. */
    private static void heartbeat(KeyFobEventEvaluator evaluator, long serial, long now) throws Exception {
        JSONObject sample = json(serial, 0, -1, -1);
        sample.getJSONObject("scope").put("driverLock", 2).put("observedAtElapsedMs", now);
        evaluator.observe(KeyFobEventEvaluator.Observation.parse(sample), now, true);
    }

    private static KeyFobEventEvaluator.Snapshot read(KeyFobEventEvaluator evaluator, long serial, long event,
                                                      int mask, long elapsed) throws Exception {
        return evaluator.observe(KeyFobEventEvaluator.Observation.parse(json(serial, event, mask, elapsed)), elapsed, true);
    }

    private static JSONObject json(long serial, long event, int mask, long elapsed) throws Exception {
        return new JSONObject().put("revision", 7L).put("instanceId", "source-a")
                .put("sourceGeneration", 1L).put("observationSequence", serial)
                .put("eventSequence", event).put("rawMask", mask)
                .put("eventObservedAtElapsedMs", event == 0 ? -1L : elapsed)
                .put("runtime", new JSONObject().put("active", true).put("registered", true)
                        .put("tableRegistered", true).put("reason", "listening"))
                .put("scope", new JSONObject().put("power", 0).put("driverLock", 2)
                        .put("observedAtElapsedMs", elapsed));
    }
}
