package com.overdrive.app.remoteaccess;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class KeyFobSourceRecoveryTest {
    @Test public void unknownPowerIsValidProtocolUnknownAndNeverOff() {
        for (int unknown : new int[]{Integer.MIN_VALUE, -2, -1, 4, 255, 65535, Integer.MAX_VALUE})
            assertEquals(-1, KeyFobSourceRecovery.protocolPower(unknown));
        for (int known = 0; known <= 3; known++)
            assertEquals(known, KeyFobSourceRecovery.protocolPower(known));
    }

    @Test public void unavailablePowerHeartbeatIsAcceptedAndRevokesOldAway() throws Exception {
        KeyFobEventEvaluator evaluator = evaluator();
        assertTrue(evaluator.observe(observation(1, 1, 1, 4, 1_000, true, 0),
                1_000, true).armed);
        KeyFobEventEvaluator.Observation unavailable = observation(2, 2, 0, -1, 2_000, false,
                KeyFobSourceRecovery.protocolPower(Integer.MIN_VALUE));
        KeyFobEventEvaluator.Snapshot state = evaluator.observe(unavailable, 2_000, true);
        assertFalse(state.active);
        assertFalse(state.armed);
        assertFalse(state.arrival);
        assertEquals("receiver_unavailable", state.reason);
    }

    @Test public void lateSdkServiceGetsAnotherAttemptOnlyAfterBackoff() {
        KeyFobSourceRecovery recovery = new KeyFobSourceRecovery();
        assertEquals(KeyFobSourceRecovery.Decision.READY, recovery.decide(1_000, true, false));
        recovery.failed(1_000);
        assertEquals(KeyFobSourceRecovery.Decision.WAIT, recovery.decide(3_999, true, false));
        assertEquals(1L, recovery.retryInMs(3_999));
        assertEquals(KeyFobSourceRecovery.Decision.READY, recovery.decide(4_000, true, false));
    }

    @Test public void repeatedServiceFailureHasBoundedBackoffAndSuccessResetsIt() {
        KeyFobSourceRecovery recovery = new KeyFobSourceRecovery();
        long now = 1_000;
        long[] delays = {3_000, 6_000, 12_000, 24_000, 30_000, 30_000, 30_000};
        for (long expected : delays) {
            recovery.failed(now);
            assertEquals(expected, recovery.retryInMs(now));
            assertEquals(KeyFobSourceRecovery.Decision.WAIT,
                    recovery.decide(now + expected - 1, true, false));
            now += expected;
            assertEquals(KeyFobSourceRecovery.Decision.READY, recovery.decide(now, true, false));
        }
        recovery.recovered();
        assertEquals(0L, recovery.retryInMs(now));
        recovery.failed(now);
        assertEquals(3_000L, recovery.retryInMs(now));
    }

    @Test public void masterOffOrChangedRevisionRevokesBothReadyAndPendingRetry() {
        KeyFobSourceRecovery recovery = new KeyFobSourceRecovery();
        assertEquals(KeyFobSourceRecovery.Decision.REVOKED, recovery.decide(1_000, false, false));
        recovery.failed(1_000);
        assertEquals(KeyFobSourceRecovery.Decision.REVOKED, recovery.decide(2_000, false, false));
        assertEquals(KeyFobSourceRecovery.Decision.REVOKED, recovery.decide(4_000, false, false));
    }

    @Test public void pendingNativeCleanupNeverPermitsReplacementRegistration() {
        KeyFobSourceRecovery recovery = new KeyFobSourceRecovery();
        assertEquals(KeyFobSourceRecovery.Decision.CLEANUP_REQUIRED,
                recovery.decide(1_000, true, true));
        recovery.failed(1_000);
        assertEquals(KeyFobSourceRecovery.Decision.CLEANUP_REQUIRED,
                recovery.decide(100_000, true, true));
        assertEquals(KeyFobSourceRecovery.Decision.REVOKED,
                recovery.decide(100_000, false, true));
        assertEquals(KeyFobSourceRecovery.Decision.READY,
                recovery.decide(100_000, true, false));
    }

    @Test public void interruptedWorkerCannotStartReadyRecovery() {
        KeyFobSourceRecovery recovery = new KeyFobSourceRecovery();
        try {
            Thread.currentThread().interrupt();
            assertEquals(KeyFobSourceRecovery.Decision.REVOKED, recovery.decide(1_000, true, false));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void clockRegressionCannotBypassBackoff() {
        KeyFobSourceRecovery recovery = new KeyFobSourceRecovery();
        recovery.failed(10_000);
        assertEquals(3_000L, recovery.retryInMs(9_000));
        assertEquals(KeyFobSourceRecovery.Decision.WAIT, recovery.decide(9_000, true, false));
    }

    @Test public void recoveredFirstNearDoesNotChangeDefaultAwayToNearBehavior() throws Exception {
        KeyFobEventEvaluator evaluator = evaluator();
        evaluator.observe(observation(1, 1, 1, 4, 1_000, true, 0), 1_000, true);
        evaluator.observe(observation(2, 2, 0, -1, 2_000, false, -1), 2_000, true);
        KeyFobEventEvaluator.Snapshot firstNear = evaluator.observe(
                observation(3, 3, 1, 8, 5_000, true, 0), 5_000, true);
        assertFalse(firstNear.arrival);
        assertEquals("NEAR", firstNear.state);
        evaluator.observe(observation(4, 3, 2, 4, 6_000, true, 0), 6_000, true);
        assertTrue(evaluator.observe(observation(5, 3, 3, 8, 7_000, true, 0), 7_000, true).arrival);
    }

    @Test public void explicitNearModeStillAcceptsFreshNearAfterRecovery() throws Exception {
        KeyFobEventEvaluator evaluator = new KeyFobEventEvaluator(
                ProximityEvaluator.UnlockTrigger.NEAR, 1, false);
        evaluator.observe(observation(1, 1, 0, -1, 1_000, false, -1), 1_000, true);
        assertTrue(evaluator.observe(observation(2, 2, 1, 8, 4_000, true, 0), 4_000, true).arrival);
    }

    private static KeyFobEventEvaluator evaluator() {
        return new KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR, 1, false);
    }

    private static KeyFobEventEvaluator.Observation observation(long serial, long generation,
            long sequence, int mask, long now, boolean active, int power) throws Exception {
        return KeyFobEventEvaluator.Observation.parse(new JSONObject()
                .put("revision", 1L).put("instanceId", "fob-source").put("observationSequence", serial)
                .put("sourceGeneration", generation).put("eventSequence", sequence).put("rawMask", mask)
                .put("eventObservedAtElapsedMs", sequence == 0 ? -1L : now)
                .put("runtime", new JSONObject().put("active", active).put("registered", active)
                        .put("tableRegistered", active)
                        .put("reason", active ? "listening" : "receiver_unavailable"))
                .put("scope", new JSONObject().put("power", power).put("driverLock", active ? 2 : -1)
                        .put("observedAtElapsedMs", now)));
    }
}
