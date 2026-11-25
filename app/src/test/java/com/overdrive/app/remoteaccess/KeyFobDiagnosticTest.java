package com.overdrive.app.remoteaccess;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class KeyFobDiagnosticTest {
    private JSONObject settings() throws Exception {
        return new JSONObject().put("enabled", true).put("keyFobEnabled", true)
                .put("unlockEnabled", false).put("dryRun", true)
                .put("unlockTrigger", "away_to_near").put("revision", 3L);
    }
    private KeyFobEventEvaluator.Observation reading(int mask, long eventSequence, long eventAt)
            throws Exception {
        return KeyFobEventEvaluator.Observation.parse(new JSONObject().put("revision", 3L)
                .put("observationSequence", 1L).put("sourceGeneration", 2L)
                .put("eventSequence", eventSequence).put("eventObservedAtElapsedMs", eventAt)
                .put("rawMask", mask).put("instanceId", "PRIVATE-PHONE-ADDRESS-TOKEN")
                .put("runtime", new JSONObject().put("active", true).put("registered", true)
                        .put("tableRegistered", true).put("reason", "listening"))
                .put("scope", new JSONObject().put("power", 0).put("driverLock", 2)
                        .put("observedAtElapsedMs", 1000L)));
    }
    @Test public void registeredHeartbeatWithNoNativeEventIsVisible() throws Exception {
        KeyFobEventEvaluator.Observation reading = reading(-1, 0L, -1L);
        KeyFobEventEvaluator evaluator =
                new KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR, 3L);
        String diagnostic = KeyFobDiagnostic.describe(
                reading, evaluator.observe(reading, 1000L, true), settings(), "accepted", 1000L);
        assertTrue(diagnostic.contains("observation=accepted"));
        assertTrue(diagnostic.contains("registered=true tableRegistered=true"));
        assertTrue(diagnostic.contains("nativeEventKnown=false rawMask=-1"));
        assertTrue(diagnostic.contains("state=UNKNOWN healthy=true fresh=false armed=false event=NONE"));
        assertTrue(diagnostic.contains("newness=heartbeat scopeAgeMs=0 eventAgeMs=-1"));
    }
    @Test public void freshNearWithoutAwaySeedShowsWhyThereWasNoArrival() throws Exception {
        KeyFobEventEvaluator.Observation reading = reading(8, 1L, 1000L);
        KeyFobEventEvaluator evaluator =
                new KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR, 3L);
        String diagnostic = KeyFobDiagnostic.describe(
                reading, evaluator.observe(reading, 1000L, true), settings(), "accepted", 1000L);
        assertTrue(diagnostic.contains("rawMask=8 state=NEAR"));
        assertTrue(diagnostic.contains("fresh=true armed=false event=NONE"));
        assertTrue(diagnostic.contains("unlockEnabled=false dryRun=true mode=away_to_near revision=3"));
        assertFalse(diagnostic.contains("PRIVATE"));
    }
    @Test public void actionableNearAndPolicyAreLoggedWithoutIdentity() throws Exception {
        KeyFobEventEvaluator.Observation reading = reading(8, 1L, 1000L);
        KeyFobEventEvaluator evaluator =
                new KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger.NEAR, 3L);
        String diagnostic = KeyFobDiagnostic.describe(
                reading, evaluator.observe(reading, 1000L, true),
                settings().put("unlockTrigger", "near"), "accepted", 1100L);
        assertTrue(diagnostic.contains("event=ARRIVAL"));
        assertTrue(diagnostic.contains("sourceGeneration=2 observationSequence=1 eventSequence=1"));
        assertTrue(diagnostic.contains("eventAgeMs=100"));
        assertFalse(diagnostic.contains("instanceId"));
        assertFalse(diagnostic.contains("PRIVATE"));
    }
    @Test public void stableHeartbeatsLogPeriodicallyAndChangesLogImmediately() {
        assertFalse(KeyFobDiagnostic.shouldLog("same", "same", 31000L, 30000L, false));
        assertTrue(KeyFobDiagnostic.shouldLog("same", "same", 31000L, 31000L, false));
        assertTrue(KeyFobDiagnostic.shouldLog("old", "new", 31000L, 1001L, false));
        assertTrue(KeyFobDiagnostic.shouldLog("same", "same", 31000L, 1001L, true));
        assertEquals(30000L, KeyFobDiagnostic.HEARTBEAT_LOG_MS);
    }
    @Test public void agesAndNativeCountersDoNotMakeAStableHeartbeatLookChanged() throws Exception {
        KeyFobEventEvaluator.Observation reading = reading(-1, 0L, -1L);
        KeyFobEventEvaluator evaluator =
                new KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR, 3L);
        KeyFobEventEvaluator.Snapshot evaluated = evaluator.observe(reading, 1000L, true);
        String key = KeyFobDiagnostic.changeKey(reading, evaluated, settings(), "accepted");
        assertFalse(key.contains("AgeMs"));
        assertFalse(key.contains("Sequence"));
        assertNotEquals(KeyFobDiagnostic.describe(reading, evaluated, settings(), "accepted", 1000L),
                KeyFobDiagnostic.describe(reading, evaluated, settings(), "accepted", 1100L));
    }
    @Test public void rejectedEnvelopeReasonsExposeNoRawValuesOrMessages() {
        assertEquals("invalid_power", KeyFobDiagnostic.rejectionReason(
                new IllegalArgumentException("power is out of range: PRIVATE")));
        assertEquals("replayed_observation", KeyFobDiagnostic.rejectionReason(
                new IllegalArgumentException("Key fob observation was already consumed")));
        assertEquals("invalid_envelope", KeyFobDiagnostic.rejectionReason(
                new IllegalArgumentException("PRIVATE-PHONE-TOKEN")));
    }
}
