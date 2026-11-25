package com.overdrive.app.remoteaccess;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native event policy tests; no Android services, vehicle reads or commands. */
public class KeyFobEventEvaluatorTest {
    private KeyFobEventEvaluator evaluator(String mode) {
        return new KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger.fromSetting(mode), 7);
    }
    private JSONObject json(long serial, long event, int mask, long elapsed) throws Exception {
        return new JSONObject().put("revision", 7L).put("instanceId", "source-a")
                .put("sourceGeneration", 1L).put("observationSequence", serial)
                .put("eventSequence", event).put("rawMask", mask)
                .put("eventObservedAtElapsedMs", event == 0 ? -1L : elapsed)
                .put("runtime", new JSONObject().put("active", true).put("registered", true)
                        .put("tableRegistered", true).put("reason", "listening"))
                .put("scope", new JSONObject().put("power", 0).put("driverLock", 2)
                        .put("observedAtElapsedMs", elapsed));
    }
    private KeyFobEventEvaluator.Observation observation(long serial, long event, int mask, long elapsed)
            throws Exception {
        return KeyFobEventEvaluator.Observation.parse(json(serial, event, mask, elapsed));
    }
    private KeyFobEventEvaluator.Snapshot read(KeyFobEventEvaluator evaluator, long serial, long event,
                                             int mask, long elapsed) throws Exception {
        return evaluator.observe(observation(serial, event, mask, elapsed), elapsed, true);
    }
    private void rejected(Runnable action) {
        try { action.run(); fail("Expected strict observation rejection"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void measuredAwayIgnoreNearBurstProducesOneArrival() throws Exception {
        KeyFobEventEvaluator e = evaluator("away_to_near");
        assertTrue(read(e, 1, 1, 4, 100).armed);
        assertTrue(read(e, 2, 2, 0, 101).armed);
        KeyFobEventEvaluator.Observation arrival = observation(3, 3, 8, 102);
        assertTrue(e.observe(arrival, 102, true).arrival);
        assertTrue(e.decisionStillValid(arrival, 103));
        assertFalse(read(e, 4, 4, 0, 104).arrival);
        assertTrue(e.decisionStillValid(arrival, 105));
        assertFalse(read(e, 5, 5, 8, 106).arrival);
        assertFalse(read(e, 6, 5, 8, 106).arrival);
    }

    @Test public void quietHealthyMinutesPreserveHistoricalAwayWithoutFreshState() throws Exception {
        KeyFobEventEvaluator e = evaluator("away_to_near");
        read(e, 1, 1, 4, 100);
        long serial = 1;
        for (long now = 2100; now <= 122100; now += 2000) {
            JSONObject heartbeat = json(++serial, 1, 4, 100);
            heartbeat.getJSONObject("scope").put("observedAtElapsedMs", now);
            KeyFobEventEvaluator.Snapshot s = e.observe(KeyFobEventEvaluator.Observation.parse(heartbeat), now, true);
            assertTrue(s.armed);
            assertFalse(s.arrival);
            if (now > 5100) {
                assertFalse(s.fresh);
                assertEquals("UNKNOWN", s.state);
                assertEquals("AWAY", s.lastKnownState);
            }
        }
        assertTrue(read(e, ++serial, 2, 8, 122200).arrival);
    }

    @Test public void quietDetectedStateIsNotCurrentPresence() throws Exception {
        KeyFobEventEvaluator e = evaluator("near");
        read(e, 1, 1, 8, 100);
        JSONObject heartbeat = json(2, 1, 8, 100);
        heartbeat.getJSONObject("scope").put("observedAtElapsedMs", 6000);
        // This test keeps the receiver healthy before the event becomes old.
        JSONObject intermediate = json(2, 1, 8, 100);
        intermediate.getJSONObject("scope").put("observedAtElapsedMs", 3000);
        e.observe(KeyFobEventEvaluator.Observation.parse(intermediate), 3000, true);
        heartbeat.put("observationSequence", 3);
        KeyFobEventEvaluator.Snapshot s = e.observe(KeyFobEventEvaluator.Observation.parse(heartbeat), 6000, true);
        assertTrue(s.active);
        assertFalse(s.fresh);
        assertEquals("UNKNOWN", s.state);
        assertEquals("NEAR", s.lastKnownState);
        assertEquals(5900L, s.toJson(6000, "near").getLong("lastValidEventAgeMs"));
        assertFalse(s.arrival);
    }

    @Test public void defaultModeDoesNotUnlockFromStartupNear() throws Exception {
        KeyFobEventEvaluator e = evaluator("away_to_near");
        assertFalse(read(e, 1, 1, 8, 100).arrival);
        assertFalse(read(e, 2, 2, 8, 101).arrival);
        assertTrue(read(e, 3, 3, 4, 102).armed);
        assertTrue(read(e, 4, 4, 8, 103).arrival);
    }

    @Test public void nearModeAllowsFirstFreshEventOncePerEpisode() throws Exception {
        KeyFobEventEvaluator e = evaluator("near");
        assertTrue(read(e, 1, 1, 8, 100).arrival);
        assertFalse(read(e, 2, 2, 8, 101).arrival);
        assertFalse(read(e, 3, 3, 0, 102).arrival);
        assertFalse(read(e, 4, 4, 8, 103).arrival);
        read(e, 5, 5, 4, 104);
        assertTrue(read(e, 6, 6, 8, 105).arrival);
    }

    @Test public void unknownAndInvalidEventsClearAwaySeedAndActionToken() throws Exception {
        for (int mask : new int[] {12, -1, 1, 255}) {
            KeyFobEventEvaluator e = evaluator("away_to_near");
            read(e, 1, 1, 4, 100);
            KeyFobEventEvaluator.Snapshot unknown = read(e, 2, 2, mask, 101);
            assertFalse(unknown.armed);
            assertEquals("UNKNOWN", unknown.state);
            assertFalse(read(e, 3, 3, 8, 102).arrival);
        }
    }

    @Test public void staleOrFutureNativeEventCannotUnlockOrArm() throws Exception {
        for (long eventTime : new long[] {0, 10001}) {
            KeyFobEventEvaluator e = evaluator("near");
            JSONObject sample = json(1, 1, 8, eventTime);
            sample.getJSONObject("scope").put("observedAtElapsedMs", 10000);
            KeyFobEventEvaluator.Snapshot s = e.observe(KeyFobEventEvaluator.Observation.parse(sample), 10000, true);
            assertFalse(s.arrival);
            assertFalse(s.fresh);
            assertEquals("UNKNOWN", s.lastKnownState);
        }
    }

    @Test public void scopePowerLockAndSubscriptionHealthMustAllBeValid() throws Exception {
        for (String invalid : new String[] {"power", "driverLock", "active", "registered", "tableRegistered"}) {
            KeyFobEventEvaluator e = evaluator("near");
            JSONObject sample = json(1, 1, 8, 100);
            if ("power".equals(invalid)) sample.getJSONObject("scope").put(invalid, 1);
            else if ("driverLock".equals(invalid)) sample.getJSONObject("scope").put(invalid, 1);
            else sample.getJSONObject("runtime").put(invalid, false);
            KeyFobEventEvaluator.Snapshot s = e.observe(KeyFobEventEvaluator.Observation.parse(sample), 100, true);
            assertFalse(s.active);
            assertFalse(s.arrival);
            assertFalse(s.armed);
        }
    }

    @Test public void staleScopeAndHeartbeatGapRevokeHistoricalAway() throws Exception {
        KeyFobEventEvaluator e = evaluator("away_to_near");
        read(e, 1, 1, 4, 100);
        assertFalse(read(e, 2, 2, 8, 6000).arrival);
        e = evaluator("away_to_near");
        read(e, 1, 1, 4, 100);
        JSONObject stale = json(2, 2, 8, 6000);
        stale.getJSONObject("scope").put("observedAtElapsedMs", 100);
        assertFalse(e.observe(KeyFobEventEvaluator.Observation.parse(stale), 6000, true).arrival);
    }

    @Test public void scopeExpiresAccordingToItsOwnTimestamp() throws Exception {
        KeyFobEventEvaluator e = evaluator("away_to_near");
        JSONObject sample = json(1, 1, 4, 4000);
        sample.getJSONObject("scope").put("observedAtElapsedMs", 0);
        assertTrue(e.observe(KeyFobEventEvaluator.Observation.parse(sample), 4000, true).active);
        assertFalse(e.current(5001).active);
        assertFalse(e.current(5001).armed);
    }

    @Test public void generationChangeCannotReuseHistoricalAwayOrCachedNearHeartbeat() throws Exception {
        KeyFobEventEvaluator e = evaluator("away_to_near");
        read(e, 1, 1, 4, 100);
        JSONObject restart = json(2, 0, -1, 101).put("sourceGeneration", 2L);
        assertFalse(e.observe(KeyFobEventEvaluator.Observation.parse(restart), 101, true).armed);
        JSONObject near = json(3, 1, 8, 102).put("sourceGeneration", 2L);
        assertFalse(e.observe(KeyFobEventEvaluator.Observation.parse(near), 102, true).arrival);
    }

    @Test public void actionTokenIsRevokedByAwayUnknownDisableAndReceiverGeneration() throws Exception {
        for (String revoke : new String[] {"away", "unknown", "disabled", "generation"}) {
            KeyFobEventEvaluator e = evaluator("near");
            KeyFobEventEvaluator.Observation arrival = observation(1, 1, 8, 100);
            assertTrue(e.observe(arrival, 100, true).arrival);
            JSONObject next = json(2, 2, "away".equals(revoke) ? 4 : "unknown".equals(revoke) ? 12 : 8, 101);
            if ("generation".equals(revoke)) next = json(2, 0, -1, 101).put("sourceGeneration", 2L);
            e.observe(KeyFobEventEvaluator.Observation.parse(next), 101, !"disabled".equals(revoke));
            assertFalse(e.decisionStillValid(arrival, 102));
        }
    }

    @Test public void wrongRevisionAndDisabledConsentNeverProduceArrival() throws Exception {
        for (boolean disabled : new boolean[] {false, true}) {
            KeyFobEventEvaluator e = evaluator("near");
            JSONObject sample = json(1, 1, 8, 100).put("revision", disabled ? 7L : 6L);
            KeyFobEventEvaluator.Snapshot s = e.observe(KeyFobEventEvaluator.Observation.parse(sample), 100, !disabled);
            assertFalse(s.arrival);
            assertFalse(s.active);
        }
    }

    @Test public void replayOutOfOrderAndAlteredHeartbeatAreRejected() throws Exception {
        KeyFobEventEvaluator e = evaluator("near");
        KeyFobEventEvaluator.Observation initial = observation(1, 1, 8, 100);
        e.observe(initial, 100, true);
        rejected(() -> e.observe(initial, 100, true));
        KeyFobEventEvaluator.Observation changed = observation(2, 1, 4, 100);
        rejected(() -> e.observe(changed, 101, true));
        read(e, 2, 2, 4, 101);
        KeyFobEventEvaluator.Observation old = observation(3, 1, 8, 100);
        rejected(() -> e.observe(old, 102, true));
    }

    @Test public void retiredAppInstanceCannotReappearAsNewAndArm() throws Exception {
        KeyFobEventEvaluator e = evaluator("near");
        read(e, 1, 1, 4, 100);
        JSONObject newer = json(1, 0, -1, 101).put("instanceId", "source-b");
        e.observe(KeyFobEventEvaluator.Observation.parse(newer), 101, true);
        KeyFobEventEvaluator.Observation retired = observation(2, 2, 8, 102);
        rejected(() -> e.observe(retired, 102, true));
    }

    @Test public void freshArrivalTokenExpiresDespiteHeartbeatAndRepeatedNativeNear() throws Exception {
        KeyFobEventEvaluator e = evaluator("near");
        KeyFobEventEvaluator.Observation initial = observation(1, 1, 8, 100);
        e.observe(initial, 100, true);
        read(e, 2, 2, 8, 4000);
        read(e, 3, 3, 8, 6000);
        assertFalse(e.decisionStillValid(initial, 6000));
    }

    @Test public void envelopeNumbersAndBooleansAreNeverCoerced() throws Exception {
        for (String key : new String[] {"revision", "observationSequence", "sourceGeneration",
                "eventSequence", "eventObservedAtElapsedMs", "rawMask"}) {
            for (Object invalid : new Object[] {"1", true, JSONObject.NULL, 1.0, 1.5}) {
                JSONObject sample = json(1, 1, 8, 100).put(key, invalid);
                rejected(() -> KeyFobEventEvaluator.Observation.parse(sample));
            }
        }
        for (String key : new String[] {"active", "registered", "tableRegistered"}) {
            JSONObject sample = json(1, 1, 8, 100);
            sample.getJSONObject("runtime").put(key, "true");
            rejected(() -> KeyFobEventEvaluator.Observation.parse(sample));
        }
    }

    @Test public void noNativeEventHasNoPresenceAgeOrArrival() throws Exception {
        KeyFobEventEvaluator e = evaluator("near");
        KeyFobEventEvaluator.Snapshot s = read(e, 1, 0, -1, 100);
        assertTrue(s.active);
        assertFalse(s.arrival);
        assertFalse(s.fresh);
        assertTrue(s.toJson(100, "near").isNull("lastEventAgeMs"));
        assertEquals("UNKNOWN", s.state);
    }

    @Test public void reactivatedScopeCannotTurnCachedDetectionIntoAnArrival() throws Exception {
        KeyFobEventEvaluator e = evaluator("near");
        JSONObject outsideScope = json(1, 1, 8, 100);
        outsideScope.getJSONObject("scope").put("driverLock", 1);
        assertFalse(e.observe(KeyFobEventEvaluator.Observation.parse(outsideScope), 100, true).arrival);
        JSONObject recovered = json(2, 1, 8, 100);
        recovered.getJSONObject("scope").put("observedAtElapsedMs", 101);
        KeyFobEventEvaluator.Snapshot heartbeat = e.observe(KeyFobEventEvaluator.Observation.parse(recovered), 101, true);
        assertTrue(heartbeat.active);
        assertFalse(heartbeat.arrival);
        assertFalse(heartbeat.fresh);
        assertTrue(read(e, 3, 2, 8, 102).arrival);
    }

    @Test public void malformedScopeAndEventIdentityAreRejectedWithoutCoercion() throws Exception {
        for (String key : new String[] {"power", "driverLock", "observedAtElapsedMs"}) {
            JSONObject sample = json(1, 1, 8, 100);
            sample.getJSONObject("scope").put(key, "0");
            rejected(() -> KeyFobEventEvaluator.Observation.parse(sample));
        }
        JSONObject absentTime = json(1, 1, 8, 100).put("eventObservedAtElapsedMs", -1L);
        rejected(() -> KeyFobEventEvaluator.Observation.parse(absentTime));
        JSONObject inventedValue = json(1, 0, -1, 100).put("rawMask", 8);
        rejected(() -> KeyFobEventEvaluator.Observation.parse(inventedValue));
        JSONObject futureScope = json(1, 1, 8, 101);
        KeyFobEventEvaluator e = evaluator("near");
        KeyFobEventEvaluator.Observation future = KeyFobEventEvaluator.Observation.parse(futureScope);
        rejected(() -> e.observe(future, 100, true));
    }

    private KeyFobEventEvaluator lockEvaluator(String mode) {
        return new KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger.fromSetting(mode), 7, true);
    }

    private KeyFobEventEvaluator.Observation unlocked(long serial, long event, int mask, long elapsed)
            throws Exception {
        JSONObject sample = json(serial, event, mask, elapsed);
        sample.getJSONObject("scope").put("driverLock", 1);
        return KeyFobEventEvaluator.Observation.parse(sample);
    }

    @Test public void verifiedNearSurvivesUnlockAndFreshAwayProducesOneDeparture() throws Exception {
        KeyFobEventEvaluator e = lockEvaluator("away_to_near");
        read(e, 1, 1, 4, 100);
        assertTrue(read(e, 2, 2, 8, 200).arrival);
        JSONObject unlockedHeartbeat = json(3, 2, 8, 200);
        unlockedHeartbeat.getJSONObject("scope").put("driverLock", 1).put("observedAtElapsedMs", 300);
        KeyFobEventEvaluator.Snapshot listening = e.observe(
                KeyFobEventEvaluator.Observation.parse(unlockedHeartbeat), 300, true);
        assertTrue(listening.active);
        assertTrue(listening.lockArmed);
        assertFalse(listening.departure);
        KeyFobEventEvaluator.Observation away = unlocked(4, 3, 4, 400);
        KeyFobEventEvaluator.Snapshot departure = e.observe(away, 400, true);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, departure.event);
        assertTrue(departure.departure);
        assertFalse(departure.arrival);
        assertTrue(departure.departureKnown);
        assertTrue(e.decisionStillValid(away, 401, ProximityEvaluator.Event.DEPARTURE));
        assertFalse(e.decisionStillValid(away, 401));
        assertFalse(e.observe(unlocked(5, 4, 0, 410), 410, true).departure);
        assertTrue(e.decisionStillValid(away, 411, ProximityEvaluator.Event.DEPARTURE));
        assertFalse(e.observe(unlocked(6, 5, 4, 420), 420, true).departure);
        assertFalse(e.decisionStillValid(away, 421, ProximityEvaluator.Event.DEPARTURE));
    }

    @Test public void startupAwayNeverLocksAndUnlockedNearNeverUnlocks() throws Exception {
        for (String mode : new String[] {"near", "away_to_near"}) {
            KeyFobEventEvaluator e = lockEvaluator(mode);
            assertFalse(e.observe(unlocked(1, 1, 4, 100), 100, true).departure);
            KeyFobEventEvaluator.Snapshot near = e.observe(unlocked(2, 2, 8, 101), 101, true);
            assertFalse(near.arrival);
            assertTrue(near.lockArmed);
            assertFalse(e.allowsLock(101));
            assertTrue(e.observe(unlocked(3, 3, 4, 102), 102, true).departure);
        }
    }

    @Test public void defaultReceiverDoesNotContinueAfterUnlock() throws Exception {
        KeyFobEventEvaluator e = evaluator("near");
        assertTrue(read(e, 1, 1, 8, 100).arrival);
        KeyFobEventEvaluator.Snapshot absent = e.observe(unlocked(2, 2, 4, 101), 101, true);
        assertFalse(absent.active);
        assertFalse(absent.departure);
        assertFalse(absent.lockEnabled);
        assertFalse(e.allowsLock(101));
        assertTrue(absent.toJson(101, "near").getBoolean("unlockOnly"));
    }

    @Test public void lockedAwayDoesNotIssueRedundantLock() throws Exception {
        KeyFobEventEvaluator e = lockEvaluator("near");
        read(e, 1, 1, 8, 100);
        KeyFobEventEvaluator.Snapshot absent = read(e, 2, 2, 4, 101);
        assertEquals(ProximityEvaluator.Event.NONE, absent.event);
        assertTrue(absent.departureKnown);
        assertFalse(absent.lockArmed);
    }

    @Test public void knownAwayPersistsOnlyWithHealthyHeartbeatsAndCannotReplayDeparture() throws Exception {
        KeyFobEventEvaluator e = lockEvaluator("near");
        e.observe(unlocked(1, 1, 8, 100), 100, true);
        KeyFobEventEvaluator.Observation away = unlocked(2, 2, 4, 200);
        assertTrue(e.observe(away, 200, true).departure);
        long serial = 2;
        for (long now = 2200; now <= 20200; now += 2000) {
            JSONObject sample = json(++serial, 2, 4, 200);
            sample.getJSONObject("scope").put("driverLock", 1).put("observedAtElapsedMs", now);
            KeyFobEventEvaluator.Snapshot heartbeat = e.observe(
                    KeyFobEventEvaluator.Observation.parse(sample), now, true);
            assertTrue(heartbeat.departureKnown);
            assertTrue(e.allowsLock(now));
            assertFalse(heartbeat.departure);
            if (now > 5200) {
                assertFalse(heartbeat.fresh);
                assertEquals("UNKNOWN", heartbeat.state);
                assertFalse(e.decisionStillValid(away, now, ProximityEvaluator.Event.DEPARTURE));
            }
        }
        assertFalse(e.allowsLock(25201));
        assertFalse(e.current(25201).departureKnown);
    }

    @Test public void healthyQuietNearMemoryPermitsFreshDepartureWithoutPretendingCurrentPresence()
            throws Exception {
        KeyFobEventEvaluator e = lockEvaluator("near");
        e.observe(unlocked(1, 1, 8, 100), 100, true);
        long serial = 1;
        for (long now = 2100; now <= 12100; now += 2000) {
            JSONObject sample = json(++serial, 1, 8, 100);
            sample.getJSONObject("scope").put("driverLock", 1).put("observedAtElapsedMs", now);
            KeyFobEventEvaluator.Snapshot heartbeat = e.observe(
                    KeyFobEventEvaluator.Observation.parse(sample), now, true);
            assertTrue(heartbeat.lockArmed);
            assertFalse(heartbeat.departureKnown);
            assertFalse(heartbeat.departure);
        }
        assertEquals("UNKNOWN", e.current(12100).state);
        assertTrue(e.observe(unlocked(++serial, 2, 4, 12200), 12200, true).departure);
    }

    @Test public void sourceLossUnknownScopeRevisionAndGenerationClearNearVisit() throws Exception {
        for (String revoke : new String[] {"unknown", "power", "lockUnknown", "lockInvalid", "active",
                "registered", "tableRegistered", "generation", "revision", "disabled", "instance"}) {
            KeyFobEventEvaluator e = lockEvaluator("near");
            e.observe(unlocked(1, 1, 8, 100), 100, true);
            JSONObject next = json(2, 2, "unknown".equals(revoke) ? 12 : 0, 101);
            next.getJSONObject("scope").put("driverLock", 1);
            if ("power".equals(revoke)) next.getJSONObject("scope").put("power", 1);
            if ("lockUnknown".equals(revoke)) next.getJSONObject("scope").put("driverLock", -1);
            if ("lockInvalid".equals(revoke)) next.getJSONObject("scope").put("driverLock", 0);
            if ("active".equals(revoke) || "registered".equals(revoke) || "tableRegistered".equals(revoke))
                next.getJSONObject("runtime").put(revoke, false);
            if ("revision".equals(revoke)) next.put("revision", 8L);
            if ("generation".equals(revoke)) next.put("sourceGeneration", 2L);
            if ("instance".equals(revoke)) next.put("instanceId", "source-b");
            e.observe(KeyFobEventEvaluator.Observation.parse(next), 101, !"disabled".equals(revoke));
            JSONObject restored = json(3, 3, 4, 102);
            restored.getJSONObject("scope").put("driverLock", 1);
            if ("generation".equals(revoke)) restored.put("sourceGeneration", 2L);
            if ("instance".equals(revoke)) restored.put("instanceId", "source-b");
            assertFalse(revoke, e.observe(KeyFobEventEvaluator.Observation.parse(restored), 102, true).departure);
        }
    }

    @Test public void oldAwayAndHeartbeatGapCannotManufactureDeparture() throws Exception {
        for (String invalid : new String[] {"oldEvent", "futureEvent", "heartbeatGap", "scopeGap"}) {
            KeyFobEventEvaluator e = lockEvaluator("near");
            e.observe(unlocked(1, 1, 8, 100), 100, true);
            long now = "heartbeatGap".equals(invalid) ? 5200 : 200;
            JSONObject next = json(2, 2, 4,
                    "oldEvent".equals(invalid) ? 0 : "futureEvent".equals(invalid) ? now + 1 : now);
            next.getJSONObject("scope").put("driverLock", 1).put("observedAtElapsedMs",
                    "scopeGap".equals(invalid) ? 0 : now);
            if ("oldEvent".equals(invalid) || "scopeGap".equals(invalid)) {
                now = 5201;
                next.getJSONObject("scope").put("observedAtElapsedMs",
                        "scopeGap".equals(invalid) ? 0 : now);
            }
            assertFalse(invalid, e.observe(KeyFobEventEvaluator.Observation.parse(next), now, true).departure);
        }
    }

    @Test public void departureTokenIsRevokedByNewNearOrLockedScope() throws Exception {
        for (boolean newNear : new boolean[] {false, true}) {
            KeyFobEventEvaluator e = lockEvaluator("near");
            e.observe(unlocked(1, 1, 8, 100), 100, true);
            KeyFobEventEvaluator.Observation away = unlocked(2, 2, 4, 101);
            assertTrue(e.observe(away, 101, true).departure);
            if (newNear) e.observe(unlocked(3, 3, 8, 102), 102, true);
            else read(e, 3, 3, 0, 102);
            assertFalse(e.decisionStillValid(away, 103, ProximityEvaluator.Event.DEPARTURE));
            assertFalse(e.decisionStillValid(away, 103, ProximityEvaluator.Event.NONE));
        }
    }

    @Test public void receiverScopeRequiresOffAndKnownLockWithExplicitUnlockedOptIn() {
        for (int power : new int[] {-1, 0, 1, 2, 3})
            for (int lock : new int[] {-1, 0, 1, 2})
                for (boolean allowLock : new boolean[] {false, true})
                    assertEquals(power == 0 && (lock == 2 || (allowLock && lock == 1)),
                            KeyFobEventEvaluator.eligibleScope(power, lock, allowLock));
    }

    @Test public void relockWithoutNewAbsenceKeepsVisitAndDoesNotRepeatUnlock() throws Exception {
        KeyFobEventEvaluator e = lockEvaluator("near");
        assertTrue(read(e, 1, 1, 8, 100).arrival);
        assertFalse(e.observe(unlocked(2, 2, 8, 200), 200, true).arrival);
        KeyFobEventEvaluator.Snapshot relocked = read(e, 3, 3, 8, 300);
        assertFalse(relocked.arrival);
        assertTrue(relocked.lockArmed);
        assertFalse(relocked.departureKnown);
        assertFalse(e.decisionStillValid(observation(3, 3, 8, 300), 301));
        assertFalse(read(e, 4, 4, 0, 400).arrival);
        assertFalse(read(e, 5, 5, 8, 500).arrival);
        read(e, 6, 6, 4, 600);
        assertTrue(read(e, 7, 7, 8, 700).arrival);
    }

    /** A heartbeat repeating the latest native event (or none) with a fresh scope read at {@code now}. */
    private KeyFobEventEvaluator.Snapshot heartbeat(KeyFobEventEvaluator e, long serial, long event, int mask,
                                                  long eventElapsed, long now, int driverLock) throws Exception {
        JSONObject sample = json(serial, event, mask, eventElapsed);
        sample.getJSONObject("scope").put("driverLock", driverLock).put("observedAtElapsedMs", now);
        return e.observe(KeyFobEventEvaluator.Observation.parse(sample), now, true);
    }

    @Test public void seedAbsenceArmsOnlyAHealthyReceiver() throws Exception {
        KeyFobEventEvaluator e = lockEvaluator("away_to_near");
        // Nothing observed yet: the receiver is not healthy, so the seed is refused.
        assertFalse(e.seedAbsence(100));
        assertFalse(e.current(100).armed);
        e.observe(unlocked(1, 0, -1, 100), 100, true);
        assertTrue(e.seedAbsence(1_000));
        KeyFobEventEvaluator.Snapshot locked = heartbeat(e, 2, 0, -1, -1, 2_000, 2);
        assertTrue(locked.armed);
        assertEquals("AWAY", locked.lastKnownState);
        assertEquals("AWAY", locked.state);
        assertTrue(locked.fresh);
        assertFalse(locked.arrival);
        // Without the locking opt-in an unlocked scope is ineligible, so the receiver is unhealthy.
        KeyFobEventEvaluator unlockOnly = evaluator("away_to_near");
        unlockOnly.observe(unlocked(1, 0, -1, 100), 100, true);
        assertFalse(unlockOnly.seedAbsence(1_000));
        assertFalse(unlockOnly.current(1_000).armed);
        // A heartbeat gap longer than FRESH_MS makes the receiver unhealthy again.
        KeyFobEventEvaluator stale = lockEvaluator("away_to_near");
        stale.observe(unlocked(1, 0, -1, 100), 100, true);
        assertFalse(stale.seedAbsence(5_101));
        assertFalse(stale.current(5_101).armed);
        assertFalse(stale.seedAbsence(-1));
    }

    /** B2 (19 Oct): an ignored detection of a locked car in away_to_near mode names the missing arming. */
    @Test public void unarmedDetectionInAwayToNearModeIsNamedAndStillDoesNotUnlock() throws Exception {
        KeyFobEventEvaluator e = evaluator("away_to_near");
        heartbeat(e, 1, 0, -1, -1, 100, 2);
        KeyFobEventEvaluator.Snapshot ignored = read(e, 2, 1, 8, 200);
        assertEquals(ProximityEvaluator.Event.NONE, ignored.event);
        assertEquals("unarmed_detection", ignored.reason);
        assertTrue(ignored.active);
        assertFalse(ignored.armed);
        assertEquals("NEAR", ignored.lastKnownState);
        // Diagnostic only: the next heartbeat reports the plain detected state, nothing was armed.
        KeyFobEventEvaluator.Snapshot next = heartbeat(e, 3, 1, 8, 200, 2_000, 2);
        assertEquals("fob_detected", next.reason);
        assertFalse(next.armed);
        // A second detection without an absence frame in between is not "unarmed": the key stayed near.
        assertEquals("fob_detected", read(e, 4, 2, 8, 2_100).reason);
        // The car's absence frame arms; the following detection is the arrival exactly as before.
        assertTrue(read(e, 5, 3, 4, 2_200).armed);
        KeyFobEventEvaluator.Snapshot arrival = read(e, 6, 4, 8, 2_300);
        assertTrue(arrival.arrival);
        assertEquals("fob_detected", arrival.reason);
        // The quiet period keeps its own reason.
        KeyFobEventEvaluator lockRail = lockEvaluator("away_to_near");
        lockRail.observe(unlocked(1, 0, -1, 100), 100, true);
        assertTrue(lockRail.seedAbsence(1_000));
        assertEquals("post_lock_quiet", read(lockRail, 2, 1, 8, 5_000).reason);
        // In near mode the same first frame is the arrival, never "unarmed".
        KeyFobEventEvaluator near = evaluator("near");
        heartbeat(near, 1, 0, -1, -1, 100, 2);
        KeyFobEventEvaluator.Snapshot first = read(near, 2, 1, 8, 200);
        assertTrue(first.arrival);
        assertEquals("fob_detected", first.reason);
    }

    /**
     * 19 Oct field replay (cam_daemon.log, build 55.9): the daemon started 20:50:40 with the car already
     * locked and off; no native frame arrived for 28 minutes, then the owner's key was detected at
     * 21:18:38.633 (rawMask=8, armed=false). Under the new default fob trigger (near) that first fresh
     * detection is the arrival; under away_to_near it stays the ignored, now-named, unarmed detection.
     */
    @Test public void coldStartedLockedCarUnlocksOnTheFirstDetectionUnderTheDefaultFobTrigger() throws Exception {
        String defaultTrigger = RemoteAccessSettings.effective(RemoteAccessSettings.defaults())
                .getString("keyFobUnlockTrigger");
        assertEquals("near", defaultTrigger);
        KeyFobEventEvaluator rail = evaluator(defaultTrigger);
        KeyFobEventEvaluator contrast = evaluator("away_to_near");
        long detection = 1_678_633L; // 21:18:38.633 - 20:50:40.000
        long serial = 0;
        for (long now = 3_000; now < detection; now += 2_000) {
            KeyFobEventEvaluator.Snapshot idle = heartbeat(rail, ++serial, 0, -1, -1, now, 2);
            assertTrue(idle.active);
            assertFalse(idle.armed);
            assertEquals("UNKNOWN", idle.state);
            heartbeat(contrast, serial, 0, -1, -1, now, 2);
        }
        KeyFobEventEvaluator.Observation frame = observation(++serial, 1, 8, detection);
        KeyFobEventEvaluator.Snapshot arrival = rail.observe(frame, detection, true);
        assertEquals(ProximityEvaluator.Event.ARRIVAL, arrival.event);
        assertTrue(arrival.fresh);
        assertEquals("NEAR", arrival.state);
        assertTrue(rail.decisionStillValid(frame, detection + 1_200));
        KeyFobEventEvaluator.Snapshot ignored = contrast.observe(frame, detection, true);
        assertEquals(ProximityEvaluator.Event.NONE, ignored.event);
        assertEquals("unarmed_detection", ignored.reason);
        assertFalse(contrast.decisionStillValid(frame, detection + 1_200));
    }

    /** Near mode keeps its two brakes: no second arrival until an absence frame, and the post-lock quiet period. */
    @Test public void nearModeStillNeedsAnAbsenceBetweenArrivalsAndHonoursThePostLockQuietPeriod() throws Exception {
        KeyFobEventEvaluator e = evaluator("near");
        heartbeat(e, 1, 0, -1, -1, 100, 2);
        assertTrue(read(e, 2, 1, 8, 200).arrival);
        long serial = 2, event = 1;
        // The owner stays by the car: heartbeats and repeated detection frames never unlock again.
        for (long now = 2_000; now <= 6_000; now += 2_000)
            assertFalse(heartbeat(e, ++serial, 1, 8, 200, now, 2).arrival);
        for (long now = 6_100; now <= 6_300; now += 100)
            assertFalse(read(e, ++serial, ++event, 8, now).arrival);
        // The car reports the key absent; the next detection is a new arrival.
        assertTrue(read(e, ++serial, ++event, 4, 7_000).armed);
        assertTrue(read(e, ++serial, ++event, 8, 7_100).arrival);
        // After OverDrive's own confirmed lock the seeded quiet period blocks a lone detection in
        // near mode too; after it has passed the detection is an arrival.
        for (long quietDetectionAt : new long[] {5_000L, 10_999L, 11_000L, 20_000L}) {
            KeyFobEventEvaluator seeded = evaluator("near");
            heartbeat(seeded, 1, 0, -1, -1, 100, 2);
            assertTrue(seeded.seedAbsence(1_000, 10_000));
            long s = 1;
            for (long now = 2_000; now < quietDetectionAt; now += 2_000)
                assertTrue(heartbeat(seeded, ++s, 0, -1, -1, now, 2).armed);
            KeyFobEventEvaluator.Snapshot detection = read(seeded, ++s, 1, 8, quietDetectionAt);
            if (quietDetectionAt < 11_000L) {
                assertEquals("" + quietDetectionAt, ProximityEvaluator.Event.NONE, detection.event);
                assertEquals("" + quietDetectionAt, "post_lock_quiet", detection.reason);
            } else {
                assertEquals("" + quietDetectionAt, ProximityEvaluator.Event.ARRIVAL, detection.event);
            }
        }
    }

    @Test public void loneDetectionInsideThePostLockQuietPeriodIsNotAnArrival() throws Exception {
        KeyFobEventEvaluator e = lockEvaluator("away_to_near");
        e.observe(unlocked(1, 0, -1, 100), 100, true);
        assertTrue(e.seedAbsence(1_000));
        long serial = 1;
        for (long now = 2_000; now <= 8_000; now += 2_000)
            assertTrue(heartbeat(e, ++serial, 0, -1, -1, now, 2).armed);
        KeyFobEventEvaluator.Snapshot quiet = read(e, ++serial, 1, 8, 10_000);
        assertEquals(ProximityEvaluator.Event.NONE, quiet.event);
        assertEquals("post_lock_quiet", quiet.reason);
        assertFalse(quiet.armed);
        assertEquals("NEAR", quiet.lastKnownState);
        assertTrue(quiet.active);
        for (long now = 12_000; now <= 18_000; now += 2_000)
            assertFalse(heartbeat(e, ++serial, 1, 8, 10_000, now, 2).arrival);
        // The car's own absence report re-arms; the next detection is an arrival as today.
        assertTrue(read(e, ++serial, 2, 4, 20_000).armed);
        assertTrue(read(e, ++serial, 3, 8, 20_100).arrival);
    }

    @Test public void loneDetectionAfterThePostLockQuietPeriodIsAnArrival() throws Exception {
        KeyFobEventEvaluator e = lockEvaluator("away_to_near");
        e.observe(unlocked(1, 0, -1, 100), 100, true);
        assertTrue(e.seedAbsence(1_000));
        long serial = 1;
        for (long now = 2_000; now <= 14_000; now += 2_000)
            assertTrue(heartbeat(e, ++serial, 0, -1, -1, now, 2).armed);
        KeyFobEventEvaluator.Observation arrival = observation(++serial, 1, 8, 16_000);
        KeyFobEventEvaluator.Snapshot s = e.observe(arrival, 16_000, true);
        assertTrue(s.arrival);
        assertEquals("fob_detected", s.reason);
        assertTrue(e.decisionStillValid(arrival, 16_001));
    }

    @Test public void nativeAbsenceAfterASeedLiftsTheQuietPeriod() throws Exception {
        KeyFobEventEvaluator e = lockEvaluator("away_to_near");
        e.observe(unlocked(1, 0, -1, 100), 100, true);
        assertTrue(e.seedAbsence(1_000));
        heartbeat(e, 2, 0, -1, -1, 2_000, 2);
        heartbeat(e, 3, 0, -1, -1, 4_000, 2);
        assertTrue(read(e, 4, 1, 4, 5_000).armed);
        assertTrue(read(e, 5, 2, 8, 6_000).arrival);
    }

    @Test public void nearModeRespectsThePostLockQuietPeriod() throws Exception {
        KeyFobEventEvaluator e = lockEvaluator("near");
        e.observe(unlocked(1, 0, -1, 100), 100, true);
        assertTrue(e.seedAbsence(1_000));
        heartbeat(e, 2, 0, -1, -1, 2_000, 2);
        heartbeat(e, 3, 0, -1, -1, 4_000, 2);
        KeyFobEventEvaluator.Snapshot quiet = read(e, 4, 1, 8, 5_000);
        assertEquals(ProximityEvaluator.Event.NONE, quiet.event);
        assertEquals("post_lock_quiet", quiet.reason);
        assertTrue(read(e, 5, 2, 4, 6_000).armed);
        assertTrue(read(e, 6, 3, 8, 7_000).arrival);
    }

    @Test public void seedIsRevokedLikeAnyOtherAwayEdge() throws Exception {
        for (String revoke : new String[] {"generation", "unknown", "heartbeatGap"}) {
            KeyFobEventEvaluator e = lockEvaluator("away_to_near");
            e.observe(unlocked(1, 0, -1, 100), 100, true);
            assertTrue(e.seedAbsence(1_000));
            assertTrue(heartbeat(e, 2, 0, -1, -1, 2_000, 2).armed);
            KeyFobEventEvaluator.Snapshot revoked;
            JSONObject near;
            if ("generation".equals(revoke)) {
                JSONObject restart = json(3, 0, -1, 3_000).put("sourceGeneration", 2L);
                revoked = e.observe(KeyFobEventEvaluator.Observation.parse(restart), 3_000, true);
                near = json(4, 1, 8, 3_100).put("sourceGeneration", 2L);
            } else if ("unknown".equals(revoke)) {
                revoked = read(e, 3, 1, 12, 3_000);
                near = json(4, 2, 8, 3_100);
            } else {
                revoked = heartbeat(e, 3, 0, -1, -1, 7_100, 2);
                near = json(4, 1, 8, 7_200);
            }
            assertFalse(revoke, revoked.armed);
            long at = near.getJSONObject("scope").getLong("observedAtElapsedMs");
            assertFalse(revoke, e.observe(KeyFobEventEvaluator.Observation.parse(near), at, true).arrival);
        }
    }

    @Test public void adoptedRevisionKeepsArmingAndOldRevisionIsRevoked() throws Exception {
        KeyFobEventEvaluator e = evaluator("away_to_near");
        assertTrue(read(e, 1, 1, 4, 100).armed);
        e.adoptRevision(8);
        JSONObject heartbeat = json(2, 1, 4, 100).put("revision", 8L);
        heartbeat.getJSONObject("scope").put("observedAtElapsedMs", 2_000);
        KeyFobEventEvaluator.Snapshot kept = e.observe(KeyFobEventEvaluator.Observation.parse(heartbeat), 2_000, true);
        assertTrue(kept.active);
        assertTrue(kept.armed);
        assertEquals("AWAY", kept.lastKnownState);
        JSONObject near = json(3, 2, 8, 3_000).put("revision", 8L);
        assertTrue(e.observe(KeyFobEventEvaluator.Observation.parse(near), 3_000, true).arrival);
        // The previous revision is the stale one now.
        JSONObject stale = json(4, 2, 8, 3_000).put("revision", 7L);
        stale.getJSONObject("scope").put("observedAtElapsedMs", 3_500);
        KeyFobEventEvaluator.Snapshot revoked = e.observe(KeyFobEventEvaluator.Observation.parse(stale), 3_500, true);
        assertFalse(revoked.active);
        assertEquals("revoked", revoked.reason);
        // A negative revision is ignored.
        KeyFobEventEvaluator other = evaluator("away_to_near");
        other.adoptRevision(-1);
        assertTrue(read(other, 1, 1, 4, 100).active);
    }

    @Test public void cycleTwoReplaySeededLockThenNativePairStillFiresAndALoneEightAfterQuietWouldToo()
            throws Exception {
        // t=0 is the confirmed proximity lock; in cycle 2 the car's own 4 came at +33.3 s and the
        // 8 at +33.4 s. With the seed, a lone 8 after the 15 s quiet period would have unlocked too.
        for (String variant : new String[] {"nativePair", "loneAfterQuiet", "loneInsideQuiet"}) {
            KeyFobEventEvaluator e = lockEvaluator("away_to_near");
            e.observe(unlocked(1, 0, -1, 0), 0, true);
            assertTrue(variant, e.seedAbsence(0));
            long serial = 1, event = 0;
            long detection = "nativePair".equals(variant) ? 33_415
                    : "loneAfterQuiet".equals(variant) ? 20_000 : 10_000;
            for (long now = 2_000; now < detection; now += 2_000)
                assertTrue(variant, heartbeat(e, ++serial, 0, -1, -1, now, 2).armed);
            if ("nativePair".equals(variant)) assertTrue(read(e, ++serial, ++event, 4, 33_339).armed);
            KeyFobEventEvaluator.Snapshot s = read(e, ++serial, ++event, 8, detection);
            assertEquals(variant, !"loneInsideQuiet".equals(variant), s.arrival);
            if ("loneInsideQuiet".equals(variant)) assertEquals("post_lock_quiet", s.reason);
        }
    }

    // ── Cloud fallback (17 Oct): the revocation-only variant used by the cloud leg ──────────────────

    /** An 8 s-old arrival token with heartbeats and no newer event: current, though no longer valid. */
    @Test public void eightSecondOldArrivalWithNoNewerEventIsStillCurrent() throws Exception {
        KeyFobEventEvaluator e = evaluator("away_to_near");
        assertTrue(read(e, 1, 1, 4, 100).armed);
        KeyFobEventEvaluator.Observation arrival = observation(2, 2, 8, 1_000);
        assertTrue(e.observe(arrival, 1_000, true).arrival);
        assertTrue(e.decisionStillValid(arrival, 1_100));
        assertTrue(e.decisionStillCurrent(arrival, 1_100, ProximityEvaluator.Event.ARRIVAL));
        // Heartbeats every 2 s carry the same event; the car stays locked (the local unlock had no effect).
        long serial = 2;
        for (long now = 3_000; now <= 9_000; now += 2_000) {
            assertFalse(heartbeat(e, ++serial, 2, 8, 1_000, now, 2).arrival);
        }
        assertFalse(e.decisionStillValid(arrival, 9_000));                                // 8 s old: lease spent
        assertTrue(e.decisionStillCurrent(arrival, 9_000, ProximityEvaluator.Event.ARRIVAL)); // nothing revoked it
        // A repeated detection (mask 8) is not a revocation: the token stays current.
        assertFalse(read(e, ++serial, 3, 8, 9_500).arrival);
        assertTrue(e.decisionStillCurrent(arrival, 9_600, ProximityEvaluator.Event.ARRIVAL));
        // Wrong event kind, and never without a healthy heartbeat (the receiver itself went silent).
        assertFalse(e.decisionStillCurrent(arrival, 9_600, ProximityEvaluator.Event.DEPARTURE));
        assertFalse(e.decisionStillCurrent(arrival, 9_600, ProximityEvaluator.Event.NONE));
        assertFalse(e.decisionStillCurrent(arrival, 15_000, ProximityEvaluator.Event.ARRIVAL));
    }

    /** An Away edge after the arrival, or the car reporting itself unlocked, revokes the current token. */
    @Test public void newerAwayEdgeOrUnlockedCarRevokesTheCurrentArrival() throws Exception {
        KeyFobEventEvaluator e = evaluator("away_to_near");
        read(e, 1, 1, 4, 100);
        KeyFobEventEvaluator.Observation arrival = observation(2, 2, 8, 1_000);
        assertTrue(e.observe(arrival, 1_000, true).arrival);
        heartbeat(e, 3, 2, 8, 1_000, 3_000, 2);
        heartbeat(e, 4, 2, 8, 1_000, 5_000, 2);
        heartbeat(e, 5, 2, 8, 1_000, 7_000, 2);
        assertTrue(e.decisionStillCurrent(arrival, 7_100, ProximityEvaluator.Event.ARRIVAL));
        // Arrival then departure (mask 4): the arrival token is revoked.
        assertFalse(read(e, 6, 3, 4, 7_200).arrival);
        assertFalse(e.decisionStillCurrent(arrival, 7_300, ProximityEvaluator.Event.ARRIVAL));
        assertFalse(e.decisionStillValid(arrival, 7_300));
        // Separately: the car itself reports unlocked (driverLock 1) → the unlock is no longer actionable.
        KeyFobEventEvaluator unlockedCar = evaluator("away_to_near");
        read(unlockedCar, 1, 1, 4, 100);
        KeyFobEventEvaluator.Observation token = observation(2, 2, 8, 1_000);
        assertTrue(unlockedCar.observe(token, 1_000, true).arrival);
        heartbeat(unlockedCar, 3, 2, 8, 1_000, 3_000, 2);
        assertTrue(unlockedCar.decisionStillCurrent(token, 3_100, ProximityEvaluator.Event.ARRIVAL));
        heartbeat(unlockedCar, 4, 2, 8, 1_000, 5_000, 1);
        assertFalse(unlockedCar.decisionStillCurrent(token, 5_100, ProximityEvaluator.Event.ARRIVAL));
    }

    /** The departure token: current after 8 s of heartbeats; a detection or a locked car revokes it. */
    @Test public void eightSecondOldDepartureIsCurrentUntilADetectionOrTheLockLands() throws Exception {
        KeyFobEventEvaluator e = lockEvaluator("away_to_near");
        assertTrue(read(e, 1, 1, 4, 100).armed);
        assertTrue(read(e, 2, 2, 8, 200).arrival);
        KeyFobEventEvaluator.Observation away = unlocked(3, 3, 4, 1_000);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, e.observe(away, 1_000, true).event);
        assertTrue(e.decisionStillValid(away, 1_100, ProximityEvaluator.Event.DEPARTURE));
        assertTrue(e.decisionStillCurrent(away, 1_100, ProximityEvaluator.Event.DEPARTURE));
        long serial = 3;
        for (long now = 3_000; now <= 9_000; now += 2_000)
            assertFalse(heartbeat(e, ++serial, 3, 4, 1_000, now, 1).departure);
        assertFalse(e.decisionStillValid(away, 9_000, ProximityEvaluator.Event.DEPARTURE));
        assertTrue(e.decisionStillCurrent(away, 9_000, ProximityEvaluator.Event.DEPARTURE));
        assertFalse(e.decisionStillCurrent(away, 9_000, ProximityEvaluator.Event.ARRIVAL));
        // The car reports locked (the local lock landed late): no second lock is actionable.
        heartbeat(e, ++serial, 3, 4, 1_000, 9_500, 2);
        assertFalse(e.decisionStillCurrent(away, 9_600, ProximityEvaluator.Event.DEPARTURE));
        // Departure then detection on another evaluator: revoked.
        KeyFobEventEvaluator back = lockEvaluator("away_to_near");
        read(back, 1, 1, 4, 100);
        read(back, 2, 2, 8, 200);
        KeyFobEventEvaluator.Observation token = unlocked(3, 3, 4, 1_000);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, back.observe(token, 1_000, true).event);
        heartbeat(back, 4, 3, 4, 1_000, 3_000, 1);
        assertTrue(back.decisionStillCurrent(token, 3_100, ProximityEvaluator.Event.DEPARTURE));
        back.observe(unlocked(5, 4, 8, 3_200), 3_200, true);
        assertFalse(back.decisionStillCurrent(token, 3_300, ProximityEvaluator.Event.DEPARTURE));
    }

    /** Whenever the event is still inside its 5 s lease, the two predicates agree exactly. */
    @Test public void currentAgreesWithValidWhileTheEventLeaseHolds() throws Exception {
        KeyFobEventEvaluator e = evaluator("away_to_near");
        read(e, 1, 1, 4, 100);
        KeyFobEventEvaluator.Observation arrival = observation(2, 2, 8, 1_000);
        e.observe(arrival, 1_000, true);
        long serial = 2;
        for (long now = 1_000; now <= 6_000; now += 500) {
            if (now % 2_000 == 0) heartbeat(e, ++serial, 2, 8, 1_000, now, 2);
            for (ProximityEvaluator.Event event : ProximityEvaluator.Event.values())
                assertEquals(event + "@" + now, e.decisionStillValid(arrival, now, event),
                        e.decisionStillCurrent(arrival, now, event));
        }
    }
}
