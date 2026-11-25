package com.overdrive.app.remoteaccess;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual signed-relay field policy without Android services or vehicle commands. */
public class KeyFobPresenceEvaluatorTest {
    private static void copyPresence(JSONObject source, JSONObject target) throws Exception {
        java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("presence")) target.put(key, source.get(key));
        }
    }

    private static final class Fixture {
        final KeyFobPresenceEvaluator policy;
        long serial, id, cycle, generation = 1, now = -1, revision = 7;
        String instance = "worker-a";
        int power, driverLock = 1;
        JSONObject last;

        Fixture() { this(10_000); }
        Fixture(long window) { policy = new KeyFobPresenceEvaluator(7, window); }

        JSONObject envelope(long at) throws Exception {
            return new JSONObject().put("revision", revision).put("instanceId", instance)
                    .put("sourceGeneration", 1L).put("observationSequence", ++serial)
                    .put("eventSequence", 0L).put("rawMask", -1)
                    .put("eventObservedAtElapsedMs", -1L)
                    .put("runtime", new JSONObject().put("active", false).put("registered", false)
                            .put("tableRegistered", false).put("reason", "passive_can_unavailable"))
                    .put("scope", new JSONObject().put("power", power).put("driverLock", driverLock)
                            .put("observedAtElapsedMs", at));
        }

        KeyFobPresenceEvaluator.Snapshot deliver(JSONObject packet, long at, boolean enabled) throws Exception {
            now = at;
            return policy.observe(KeyFobPresenceEvaluator.Observation.parse(packet,
                    KeyFobEventEvaluator.Observation.parse(packet)), at, enabled);
        }

        KeyFobPresenceEvaluator.Snapshot heartbeat(long at) throws Exception {
            JSONObject packet = envelope(at);
            if (last != null) copyPresence(last, packet);
            return deliver(packet, at, true);
        }

        void aliveUntil(long at) throws Exception {
            while (now >= 0 && now + 2_000 < at) heartbeat(now + 2_000);
        }

        KeyFobPresenceEvaluator.Observation result(String state, long at) throws Exception {
            aliveUntil(at);
            JSONObject packet = envelope(at)
                    .put("presenceObservationState", state).put("presenceSourceActive", true)
                    .put("presenceSourceGeneration", generation).put("presenceObservationId", ++id)
                    .put("presenceSearchCycleId", ++cycle)
                    .put("presenceObservationElapsedRealtimeMs", at)
                    .put("presenceQueryStartedElapsedRealtimeMs", Math.max(0, at - 200))
                    .put("presenceSearchStatus", "idle");
            last = new JSONObject(packet.toString());
            now = at;
            return KeyFobPresenceEvaluator.Observation.parse(packet, KeyFobEventEvaluator.Observation.parse(packet));
        }

        KeyFobPresenceEvaluator.Snapshot read(String state, long at) throws Exception {
            return policy.observe(result(state, at), at, true);
        }

        KeyFobPresenceEvaluator.Snapshot timeout(long at) throws Exception {
            result("unknown", at);
            last.put("presenceQueryStartedElapsedRealtimeMs", at - KeyFobPresenceEvaluator.MAX_QUERY_MS)
                    .put("presenceSearchStatus", "unavailable");
            return deliver(last, at, true);
        }

        KeyFobPresenceEvaluator.Observation confirmedDeparture() throws Exception {
            read("present", 100);
            read("absent", 10_100);
            KeyFobPresenceEvaluator.Observation value = result("absent", 20_100);
            KeyFobPresenceEvaluator.Snapshot completed = policy.observe(value, 20_100, true);
            assertEquals(ProximityEvaluator.Event.DEPARTURE, completed.event);
            assertTrue(completed.lockCandidate);
            return value;
        }
    }

    @Test public void initialTwoFreshAbsencesConfirmLockWithoutPriorPresent() throws Exception {
        Fixture f = new Fixture();
        KeyFobPresenceEvaluator.Snapshot first = f.read("absent", 100);
        assertEquals("confirming_absence", first.reason);
        assertEquals(ProximityEvaluator.Event.NONE, first.event);
        assertTrue(first.armed);
        assertEquals(1, first.sampleCount);
        assertFalse(first.departureKnown);
        assertFalse(first.lockCandidate);
        KeyFobPresenceEvaluator.Snapshot confirmed = f.read("absent", 10_100);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, confirmed.event);
        assertEquals("physical_key_absence_confirmed", confirmed.reason);
        assertTrue(confirmed.departureKnown);
        assertTrue(confirmed.lockCandidate);
        assertFalse(confirmed.armed);
        assertEquals(10_000L, confirmed.absentForMs);
    }

    @Test public void presentThenTwoFreshAbsencesSpanWindowAndEmitOnlyOneDeparture() throws Exception {
        Fixture f = new Fixture();
        assertTrue(f.read("present", 100).armed);
        KeyFobPresenceEvaluator.Snapshot first = f.read("absent", 10_100);
        assertEquals(1, first.sampleCount);
        assertEquals(0L, first.absentForMs);
        assertFalse(first.departureKnown);
        assertFalse(first.lockCandidate);
        assertEquals(ProximityEvaluator.Event.NONE, first.event);
        KeyFobPresenceEvaluator.Snapshot confirmed = f.read("absent", 20_100);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, confirmed.event);
        assertTrue(confirmed.departureKnown);
        assertTrue(confirmed.lockCandidate);
        assertEquals(10_000L, confirmed.absentForMs);
        assertEquals(ProximityEvaluator.Event.NONE, f.read("absent", 30_100).event);
    }

    @Test public void configuredLongWindowRequiresActualLaterResults() throws Exception {
        Fixture f = new Fixture(30_000);
        f.read("present", 100);
        for (long at : new long[] {10_100, 20_100, 30_100})
            assertEquals(ProximityEvaluator.Event.NONE, f.read("absent", at).event);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, f.read("absent", 40_100).event);
    }

    @Test public void heartbeatsAndPendingQueryCannotAdvanceAbsenceCountdownOrEmitDeparture() throws Exception {
        Fixture f = new Fixture();
        f.read("absent", 10_100);
        f.last.put("presenceSearchStatus", "pending");
        KeyFobPresenceEvaluator.Snapshot latest = null;
        for (long at = 12_100; at <= 24_100; at += 2_000) {
            latest = f.heartbeat(at);
            assertEquals(ProximityEvaluator.Event.NONE, latest.event);
            assertFalse(latest.departureKnown);
            assertFalse(latest.lockCandidate);
            assertEquals(1, latest.sampleCount);
            assertEquals(0L, latest.absentForMs);
        }
        assertNotNull(latest);
        assertEquals(14_000L, latest.lastObservationAgeMs);
    }

    @Test public void secondAbsentResultConfirmsEvenWhenCallbacksSpanJustUnderTheWindow() throws Exception {
        // B3: the window is counted in consecutive completed Absent queries at the search cadence,
        // so two results whose callbacks landed 9.99 s apart confirm on the second result.
        Fixture f = new Fixture();
        f.read("present", 100);
        assertEquals(1, f.read("absent", 10_100).sampleCount);
        KeyFobPresenceEvaluator.Snapshot confirmed = f.read("absent", 19_999);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, confirmed.event);
        assertEquals(2, confirmed.sampleCount);
        assertEquals(2, confirmed.requiredSamples);
        assertEquals(9_899L, confirmed.absentForMs);
        assertTrue(confirmed.lockCandidate);
        assertTrue(confirmed.departureKnown);
    }

    @Test public void cycleZeroReplayNoLongerNeedsAThirdSample() throws Exception {
        // Logged cycle 0: Absent callbacks at +1.847 s and +11.846 s (9.999 s apart) needed a third.
        Fixture f = new Fixture();
        assertEquals(1, f.read("absent", 1_847).sampleCount);
        KeyFobPresenceEvaluator.Snapshot second = f.read("absent", 11_846);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, second.event);
        assertTrue(second.lockCandidate);
        assertTrue(f.policy.allowsLock(11_846));
        KeyFobPresenceEvaluator.Snapshot third = f.read("absent", 22_000);
        assertEquals(ProximityEvaluator.Event.NONE, third.event);
        assertTrue(third.lockCandidate);
        assertEquals("physical_key_absence_confirmed", third.reason);
    }

    @Test public void aSingleAbsentResultNeverConfirms() throws Exception {
        Fixture f = new Fixture();
        assertEquals(1, f.read("absent", 100).sampleCount);
        for (long at = 2_100; at <= 18_100; at += 2_000) {
            KeyFobPresenceEvaluator.Snapshot heartbeat = f.heartbeat(at);
            assertEquals(1, heartbeat.sampleCount);
            assertEquals(ProximityEvaluator.Event.NONE, heartbeat.event);
            assertFalse(heartbeat.lockCandidate);
            assertFalse(heartbeat.departureKnown);
        }
        KeyFobPresenceEvaluator.Snapshot lastFresh = f.heartbeat(19_900);
        assertTrue(lastFresh.fresh);
        assertEquals(1, lastFresh.sampleCount);
        assertFalse(f.policy.allowsLock(19_900));
        KeyFobPresenceEvaluator.Snapshot stale = f.heartbeat(20_200);
        assertEquals("presence_result_stale", stale.reason);
        assertEquals(0, stale.sampleCount);
        assertEquals("UNKNOWN", stale.state);
    }

    @Test public void cadenceAndFreshnessDeriveFromTheWindow() {
        long[][] table = {
                {5_000, 5_000, 12_000, 2}, {10_000, 10_000, 20_000, 2}, {15_000, 5_000, 12_000, 4},
                {20_000, 10_000, 20_000, 3}, {30_000, 10_000, 20_000, 4}, {300_000, 10_000, 20_000, 31}};
        for (long[] row : table) {
            long interval = KeyFobPresenceEvaluator.pollIntervalMs(row[0]);
            assertEquals("window " + row[0], row[1], interval);
            assertEquals("window " + row[0], row[2], KeyFobPresenceEvaluator.resultFreshMs(interval));
            assertEquals("window " + row[0], (int) row[3],
                    KeyFobPresenceEvaluator.minimumAbsentSamples(row[0], interval));
        }
        // The default constants are untouched: the 10 s window is default-neutral.
        assertEquals(10_000L, KeyFobPresenceEvaluator.POLL_INTERVAL_MS);
        assertEquals(20_000L, KeyFobPresenceEvaluator.RESULT_FRESH_MS);
        assertEquals(6_000L, KeyFobPresenceEvaluator.MAX_QUERY_MS);
        assertEquals(5_000L, KeyFobPresenceEvaluator.SOURCE_FRESH_MS);
    }

    @Test public void constructorAcceptsFiveSecondStepsAndRejectsNonMultiples() {
        for (long window : new long[] {5_000, 15_000, 25_000, 300_000})
            assertNotNull(new KeyFobPresenceEvaluator(7, window));
        for (long window : new long[] {0, 4_999, 7_500, 12_000, 305_000, -5_000}) {
            try { new KeyFobPresenceEvaluator(7, window); fail("Accepted window " + window); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void fiveSecondWindowConfirmsOnTheSecondResultAndExpiresAfterTwelveSeconds() throws Exception {
        Fixture f = new Fixture(5_000);
        KeyFobPresenceEvaluator.Snapshot first = f.read("absent", 100);
        assertEquals(1, first.sampleCount);
        assertEquals(ProximityEvaluator.Event.NONE, first.event);
        KeyFobPresenceEvaluator.Snapshot confirmed = f.read("absent", 5_100);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, confirmed.event);
        assertTrue(confirmed.lockCandidate);
        assertEquals(5_000L, confirmed.absentForMs);
        JSONObject status = confirmed.toJson();
        assertEquals(5_000L, status.getLong("pollIntervalMs"));
        assertEquals(2, status.getInt("requiredSamples"));
        assertEquals(5_000L, status.getLong("windowMs"));
        // Results expire after twelve seconds at the five-second cadence.
        Fixture g = new Fixture(5_000);
        g.read("absent", 100);
        for (long at = 2_100; at <= 10_100; at += 2_000) assertEquals(1, g.heartbeat(at).sampleCount);
        KeyFobPresenceEvaluator.Snapshot stillFresh = g.heartbeat(11_900);
        assertTrue(stillFresh.fresh);
        assertEquals(1, stillFresh.sampleCount);
        KeyFobPresenceEvaluator.Snapshot stale = g.heartbeat(12_200);
        assertFalse(stale.fresh);
        assertEquals("presence_result_stale", stale.reason);
        assertEquals(0, stale.sampleCount);
    }

    @Test public void fifteenSecondWindowNeedsFourResultsAtFiveSecondCadence() throws Exception {
        Fixture f = new Fixture(15_000);
        for (long at : new long[] {100, 5_100, 10_100}) {
            KeyFobPresenceEvaluator.Snapshot sample = f.read("absent", at);
            assertEquals(ProximityEvaluator.Event.NONE, sample.event);
            assertEquals(4, sample.requiredSamples);
            assertFalse(sample.lockCandidate);
        }
        KeyFobPresenceEvaluator.Snapshot confirmed = f.read("absent", 15_100);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, confirmed.event);
        assertEquals(4, confirmed.sampleCount);
        assertEquals(15_000L, confirmed.absentForMs);
        assertTrue(confirmed.lockCandidate);
    }

    @Test public void presentUnknownAndStaleStillResetSamplesAtAShortWindow() throws Exception {
        Fixture present = new Fixture(5_000);
        assertEquals(1, present.read("absent", 100).sampleCount);
        assertEquals(0, present.read("present", 5_100).sampleCount);
        Fixture unknown = new Fixture(5_000);
        assertEquals(1, unknown.read("absent", 100).sampleCount);
        KeyFobPresenceEvaluator.Snapshot unavailable = unknown.timeout(7_100);
        assertEquals(0, unavailable.sampleCount);
        assertEquals("physical_key_search_unavailable", unavailable.reason);
        Fixture gap = new Fixture(5_000);
        assertEquals(1, gap.read("absent", 100).sampleCount);
        for (long at = 2_100; at <= 12_100; at += 2_000) gap.heartbeat(at);
        assertEquals(0, gap.heartbeat(13_100).sampleCount);
        KeyFobPresenceEvaluator.Snapshot restarted = gap.read("absent", 13_200);
        assertEquals(1, restarted.sampleCount);
        assertEquals(ProximityEvaluator.Event.NONE, restarted.event);
    }

    @Test public void relockGraceHoldsALockForFifteenSecondsAfterAProximityUnlockUnlessADoorOpened() {
        assertEquals(15_000L, KeyFobPresenceEvaluator.RELOCK_GRACE_MS);
        // Within 15 s of a proximity unlock and no door opened since: hold.
        assertTrue(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", 1_000, -1, 1_000));
        assertTrue(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", 1_000, -1, 15_999));
        assertFalse(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", 1_000, -1, 16_000));
        // A door that opened after the unlock releases the hold at once.
        assertFalse(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", 1_000, 5_000, 10_000));
        assertFalse(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", 1_000, 1_000, 10_000));
        // A door edge from before the unlock, or one stamped in the future, does not.
        assertTrue(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", 1_000, 500, 10_000));
        assertTrue(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", 1_000, 11_000, 10_000));
        // Only a dispatched proximity unlock arms the hold; a lock or no action never does.
        assertFalse(KeyFobPresenceEvaluator.relockGraceBlocks("lock", 1_000, -1, 5_000));
        assertFalse(KeyFobPresenceEvaluator.relockGraceBlocks(null, -1, -1, 5_000));
        assertFalse(KeyFobPresenceEvaluator.relockGraceBlocks("", -1, -1, 5_000));
        assertFalse(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", -1, -1, 5_000));
        // A clock regression blocks, like the cooldown policy.
        assertTrue(KeyFobPresenceEvaluator.relockGraceBlocks("unlock", 1_000, -1, 900));
    }

    @Test public void presentWhileOnDoesNotArmButFreshOffAbsenceStartsConfirmation() throws Exception {
        Fixture f = new Fixture();
        f.power = 2;
        assertFalse(f.read("present", 100).armed);
        f.power = 0;
        assertEquals(ProximityEvaluator.Event.NONE, f.read("absent", 10_100).event);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, f.read("absent", 20_100).event);
    }

    @Test public void absenceOnDoesNotActuateAndOffNeedsNewConfirmation() throws Exception {
        Fixture f = new Fixture();
        f.power = 2;
        f.read("present", 100);
        f.read("absent", 10_100);
        assertEquals(ProximityEvaluator.Event.NONE, f.read("absent", 20_100).event);
        assertFalse(f.policy.allowsLock(20_100));
        f.power = 0;
        assertEquals(ProximityEvaluator.Event.NONE, f.read("absent", 30_100).event);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, f.read("absent", 40_100).event);
    }

    @Test public void everyPoweredOnScopeRequiresNewOffOnlyAbsenceWindow() throws Exception {
        for (int power : new int[]{1, 2, 3}) {
            Fixture f = new Fixture();
            f.read("absent", 100);
            f.power = power;
            KeyFobPresenceEvaluator.Snapshot on = f.heartbeat(200);
            assertFalse(on.armed);
            assertFalse(on.lockCandidate);
            assertEquals(0, on.sampleCount);
            assertEquals("presence_vehicle_on", on.reason);
            f.power = 0;
            assertEquals(0, f.heartbeat(300).sampleCount);
            KeyFobPresenceEvaluator.Snapshot first = f.read("absent", 10_100);
            assertEquals(1, first.sampleCount);
            assertFalse(first.lockCandidate);
            assertTrue(f.read("absent", 20_100).lockCandidate);
        }
    }

    @Test public void alreadyLockedScopeCannotAccumulateAndUnlockNeedsNewWindow() throws Exception {
        Fixture f = new Fixture();
        f.driverLock = 2;
        KeyFobPresenceEvaluator.Snapshot locked = f.read("absent", 100);
        assertEquals("presence_vehicle_locked", locked.reason);
        assertFalse(locked.armed);
        assertEquals(0, locked.sampleCount);
        assertFalse(f.read("absent", 10_100).lockCandidate);
        assertFalse(f.policy.allowsLock(10_100));
        f.driverLock = 1;
        KeyFobPresenceEvaluator.Snapshot first = f.read("absent", 20_100);
        assertEquals(1, first.sampleCount);
        assertFalse(first.lockCandidate);
        assertTrue(f.read("absent", 30_100).lockCandidate);
    }

    @Test public void presentCancelsInitialAbsenceAndFollowingAbsencesStartWholeNewWindow()
            throws Exception {
        Fixture f = new Fixture();
        f.read("absent", 100);
        KeyFobPresenceEvaluator.Snapshot present = f.read("present", 5_100);
        assertEquals("physical_key_present", present.reason);
        assertEquals(0, present.sampleCount);
        assertFalse(present.departureKnown);
        KeyFobPresenceEvaluator.Snapshot first = f.read("absent", 10_100);
        assertEquals(1, first.sampleCount);
        assertEquals(10_100L, first.absenceSinceElapsedMs);
        assertFalse(first.lockCandidate);
        assertTrue(f.read("absent", 20_100).lockCandidate);
    }

    @Test public void sourceErrorOrRevokedRevisionCannotJoinEarlierAbsenceWindow()
            throws Exception {
        for (boolean revokedRevision : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            f.read("absent", 100);
            if (revokedRevision) {
                f.revision = 8;
                assertFalse(f.heartbeat(200).active);
                f.revision = 7;
            } else {
                f.policy.invalidate("native_search_error");
                assertFalse(f.policy.current(200).lockCandidate);
            }
            KeyFobPresenceEvaluator.Snapshot first = f.read("absent", 10_100);
            assertEquals(1, first.sampleCount);
            assertFalse(first.lockCandidate);
            assertTrue(f.read("absent", 20_100).lockCandidate);
        }
    }

    @Test public void newPresentImmediatelyRevokesQueuedDepartureAndConsensus() throws Exception {
        Fixture f = new Fixture();
        KeyFobPresenceEvaluator.Observation departure = f.confirmedDeparture();
        assertTrue(f.policy.decisionStillValid(departure, 20_101));
        f.read("present", 20_200);
        assertFalse(f.policy.decisionStillValid(departure, 20_201));
        assertFalse(f.policy.allowsLock(20_201));
    }

    @Test public void completedTimeoutRevokesTokenAndRequiresNewAbsenceWindow() throws Exception {
        Fixture f = new Fixture();
        KeyFobPresenceEvaluator.Observation departure = f.confirmedDeparture();
        KeyFobPresenceEvaluator.Snapshot unavailable = f.timeout(20_200);
        assertEquals("UNKNOWN", unavailable.state);
        assertFalse(unavailable.fresh);
        assertFalse(unavailable.departureKnown);
        assertFalse(unavailable.lockCandidate);
        assertEquals(0, unavailable.sampleCount);
        assertEquals(0L, unavailable.absentForMs);
        assertEquals(-1L, unavailable.absenceSinceElapsedMs);
        assertFalse(f.policy.decisionStillValid(departure, 20_201));
        assertFalse(f.policy.allowsLock(20_201));
        KeyFobPresenceEvaluator.Snapshot first = f.read("absent", 30_200);
        assertEquals(ProximityEvaluator.Event.NONE, first.event);
        assertEquals(1, first.sampleCount);
        assertFalse(first.lockCandidate);
        assertFalse(f.policy.allowsLock(30_200));
        KeyFobPresenceEvaluator.Snapshot renewed = f.read("absent", 40_200);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, renewed.event);
        assertTrue(renewed.lockCandidate);
        assertTrue(f.policy.allowsLock(40_200));
    }

    @Test public void healthyRepeatedTimeoutsNeverCountAsAbsenceAndRequireWholeNewWindow() throws Exception {
        Fixture f = new Fixture();
        f.read("present", 100);
        f.read("absent", 10_100);
        for (long at = 20_100; at <= 100_100; at += 10_000) {
            KeyFobPresenceEvaluator.Snapshot unavailable = f.timeout(at);
            assertEquals("UNKNOWN", unavailable.state);
            assertFalse(unavailable.fresh);
            assertFalse(unavailable.departureKnown);
            assertFalse(unavailable.lockCandidate);
            assertFalse(unavailable.armed);
            assertEquals(ProximityEvaluator.Event.NONE, unavailable.event);
            assertEquals(0, unavailable.sampleCount);
            assertEquals(0L, unavailable.absentForMs);
            assertFalse(f.policy.allowsLock(at));
            assertFalse(f.heartbeat(at + 100).lockCandidate);
        }
        KeyFobPresenceEvaluator.Snapshot first = f.read("absent", 110_100);
        assertEquals(1, first.sampleCount);
        assertFalse(first.departureKnown);
        assertFalse(first.lockCandidate);
        KeyFobPresenceEvaluator.Snapshot second = f.read("absent", 120_100);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, second.event);
        assertTrue(second.lockCandidate);
        assertEquals(10_000L, second.absentForMs);
    }

    @Test public void loggedTimedOutSearchSequenceRecoversAtFirstLaterConfirmedWindow() throws Exception {
        Fixture f = new Fixture();
        // Milliseconds after 19:37:00, replayed from the sanitized failed attempt.
        // The first two consecutive later Absent results are 19:38:40.497 and
        // 19:38:50.732; earlier results are separated by unavailable queries.
        long[] times = {26_792, 41_040, 53_267, 63_464, 71_646, 84_058, 94_311, 100_497, 110_732};
        String[] states = {"present", "absent", "unknown", "unknown", "absent",
                "unknown", "unknown", "absent", "absent"};
        for (int i = 0; i < times.length; i++) {
            KeyFobPresenceEvaluator.Snapshot sample = "unknown".equals(states[i])
                    ? f.timeout(times[i]) : f.read(states[i], times[i]);
            if (i == times.length - 1) {
                assertEquals(ProximityEvaluator.Event.DEPARTURE, sample.event);
                assertTrue(sample.lockCandidate);
                assertEquals(2, sample.sampleCount);
                assertEquals(10_235L, sample.absentForMs);
                assertEquals(110_732L, f.now);
            } else {
                assertEquals(ProximityEvaluator.Event.NONE, sample.event);
                assertFalse(sample.lockCandidate);
            }
        }
    }

    @Test public void initialTimeoutsCannotCountButLaterTwoFreshAbsencesCanConfirm() throws Exception {
        Fixture f = new Fixture();
        for (long at = 10_100; at <= 30_100; at += 10_000) f.timeout(at);
        assertFalse(f.read("absent", 40_100).lockCandidate);
        KeyFobPresenceEvaluator.Snapshot second = f.read("absent", 50_100);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, second.event);
        assertEquals("physical_key_absence_confirmed", second.reason);
        assertTrue(second.departureKnown);
        assertTrue(second.lockCandidate);
    }

    @Test public void aFreshAbsentQueryRenewsBlockedCandidateWithoutRepeatingDepartureEvent() throws Exception {
        Fixture f = new Fixture();
        KeyFobPresenceEvaluator.Observation first = f.confirmedDeparture();
        assertTrue(f.policy.decisionStillValid(first, 20_101));
        f.last.put("presenceSearchStatus", "pending");
        for (long at = 22_100; at <= 28_100; at += 2_000) {
            KeyFobPresenceEvaluator.Snapshot heartbeat = f.heartbeat(at);
            assertEquals(ProximityEvaluator.Event.NONE, heartbeat.event);
            assertFalse(heartbeat.lockCandidate);
            assertFalse(f.policy.current(at).lockCandidate);
        }
        assertFalse(f.policy.decisionStillValid(first, 28_100));
        KeyFobPresenceEvaluator.Observation next = f.result("absent", 30_100);
        KeyFobPresenceEvaluator.Snapshot retry = f.policy.observe(next, 30_100, true);
        assertEquals(ProximityEvaluator.Event.NONE, retry.event);
        assertTrue(retry.lockCandidate);
        assertTrue(retry.toJson().getBoolean("lockCandidate"));
        assertTrue(f.policy.decisionStillValid(next, 30_101));
        assertFalse(f.policy.decisionStillValid(first, 30_101));
        assertFalse(f.heartbeat(30_200).lockCandidate);
        assertFalse(f.policy.current(30_200).lockCandidate);
        f.read("present", 30_300);
        assertFalse(f.policy.decisionStillValid(next, 30_301));
        assertFalse(f.policy.allowsLock(30_301));
    }

    @Test public void candidateKeepsOriginalCallbackTimeAndFiveSecondBoundary() throws Exception {
        Fixture f = new Fixture();
        KeyFobPresenceEvaluator.Observation first = f.confirmedDeparture();
        assertFalse(f.heartbeat(25_100).lockCandidate);
        assertTrue(f.policy.decisionStillValid(first, 25_100));
        assertFalse(f.heartbeat(25_101).lockCandidate);
        assertFalse(f.policy.decisionStillValid(first, 25_101));
        assertEquals(5_001L, f.policy.current(25_101).lastObservationAgeMs);
    }

    @Test public void lateFreshAbsenceCanConfirmStateButCannotSupplyExpiredLockCandidate() throws Exception {
        Fixture f = new Fixture();
        f.read("present", 100);
        f.read("absent", 10_100);
        JSONObject previous = new JSONObject(f.last.toString());
        KeyFobPresenceEvaluator.Observation next = f.result("absent", 20_100);
        JSONObject completedQuery = new JSONObject(f.last.toString());
        // Scope/collector are current, but this callback was delivered after its
        // actuation lease. A later genuine query is required to attempt locking.
        f.last = previous;
        f.aliveUntil(25_101);
        JSONObject delayed = f.envelope(25_101);
        copyPresence(completedQuery, delayed);
        KeyFobPresenceEvaluator.Snapshot completed = f.deliver(delayed, 25_101, true);
        assertTrue(completed.departureKnown);
        assertFalse(completed.lockCandidate);
        assertFalse(f.policy.decisionStillValid(next, 25_101));
    }

    @Test public void sourceGapAfterTimeoutRequiresWholeNewAbsenceWindow() throws Exception {
        Fixture f = new Fixture();
        f.read("present", 100);
        f.timeout(10_100);
        // Suppress fixture heartbeats to reproduce a lost collector.
        f.now = -1;
        KeyFobPresenceEvaluator.Snapshot first = f.read("absent", 16_101);
        assertTrue(first.armed);
        assertEquals(1, first.sampleCount);
        assertFalse(first.lockCandidate);
        KeyFobPresenceEvaluator.Snapshot second = f.read("absent", 26_101);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, second.event);
        assertTrue(second.lockCandidate);
    }

    @Test public void invalidVehicleScopeAfterTimeoutRequiresWholeNewAbsenceWindow() throws Exception {
        for (String invalid : new String[] {"power", "lock", "scope"}) {
            Fixture f = new Fixture();
            f.read("present", 100);
            f.timeout(10_100);
            JSONObject unavailable = f.envelope(10_200);
            copyPresence(f.last, unavailable);
            if ("power".equals(invalid)) unavailable.getJSONObject("scope").put("power", -1);
            else if ("lock".equals(invalid)) unavailable.getJSONObject("scope").put("driverLock", -1);
            else unavailable.getJSONObject("scope").put("observedAtElapsedMs", 5_199L);
            KeyFobPresenceEvaluator.Snapshot retired = f.deliver(unavailable, 10_200, true);
            assertEquals("UNKNOWN", retired.state);
            assertFalse(retired.active);
            assertFalse(retired.lockCandidate);
            assertFalse(f.read("absent", 20_100).lockCandidate);
            KeyFobPresenceEvaluator.Snapshot second = f.read("absent", 30_100);
            assertEquals(ProximityEvaluator.Event.DEPARTURE, second.event);
            assertTrue(second.lockCandidate);
        }
    }

    @Test public void overlongUnavailableQueryRequiresWholeNewAbsenceWindow() throws Exception {
        Fixture f = new Fixture();
        f.read("present", 100);
        f.result("unknown", 10_100);
        f.last.put("presenceQueryStartedElapsedRealtimeMs", 4_099L);
        KeyFobPresenceEvaluator.Snapshot invalid = f.deliver(f.last, 10_100, true);
        assertEquals("presence_result_stale", invalid.reason);
        assertFalse(invalid.lockCandidate);
        assertFalse(f.read("absent", 20_100).lockCandidate);
        assertTrue(f.read("absent", 30_100).lockCandidate);
    }

    @Test public void sourceGapClearsWindowEvenWhenOldResultIsStillFresh() throws Exception {
        Fixture f = new Fixture();
        f.read("present", 100);
        KeyFobPresenceEvaluator.Observation value = f.result("absent", 5_200);
        // Deliver the result to a separate policy that saw no intervening heartbeats.
        KeyFobPresenceEvaluator policy = new KeyFobPresenceEvaluator(7, 10_000);
        Fixture seed = new Fixture();
        policy.observe(seed.result("present", 100), 100, true);
        KeyFobPresenceEvaluator.Snapshot freshWindow = policy.observe(value, 5_200, true);
        assertTrue(freshWindow.armed);
        assertEquals(1, freshWindow.sampleCount);
        assertFalse(freshWindow.lockCandidate);
        assertFalse(policy.allowsLock(5_200));
    }

    @Test public void staleResultsExpireDespiteFreshHeartbeats() throws Exception {
        Fixture f = new Fixture();
        f.confirmedDeparture();
        for (long at = 22_100; at <= 42_100; at += 2_000) f.heartbeat(at);
        assertFalse(f.policy.allowsLock(42_100));
        assertFalse(f.policy.current(42_100).armed);
        assertEquals("UNKNOWN", f.policy.current(42_100).state);
        assertEquals(ProximityEvaluator.Event.NONE, f.read("absent", 42_200).event);
    }

    @Test public void heartbeatCannotRefreshOriginalCallbackTimeAndTokenExpiresAtFiveSeconds() throws Exception {
        Fixture f = new Fixture();
        KeyFobPresenceEvaluator.Observation departure = f.confirmedDeparture();
        f.heartbeat(24_100);
        assertTrue(f.policy.decisionStillValid(departure, 24_100));
        f.heartbeat(26_100);
        assertFalse(f.policy.decisionStillValid(departure, 26_100));
        assertTrue(f.policy.allowsLock(26_100));
        assertEquals(6_000L, f.policy.current(26_100).lastObservationAgeMs);
    }

    @Test public void generationAndInstanceChangesRequireWholeNewAbsenceWindow() throws Exception {
        for (boolean newInstance : new boolean[] {false, true}) {
            Fixture f = new Fixture();
            KeyFobPresenceEvaluator.Observation departure = f.confirmedDeparture();
            if (newInstance) {
                f.instance = "worker-b";
                f.serial = 0;
            } else f.generation++;
            f.id = f.cycle = 0;
            assertEquals(ProximityEvaluator.Event.NONE, f.read("absent", 20_200).event);
            assertFalse(f.policy.allowsLock(20_200));
            assertFalse(f.policy.decisionStillValid(departure, 20_200));
            KeyFobPresenceEvaluator.Snapshot confirmed = f.read("absent", 30_200);
            assertEquals(ProximityEvaluator.Event.DEPARTURE, confirmed.event);
            assertTrue(confirmed.lockCandidate);
        }
    }

    @Test public void revisionMismatchDisabledAndUnavailableSourcesFailClosed() throws Exception {
        for (String invalid : new String[] {"revision", "disabled", "unavailable"}) {
            Fixture f = new Fixture();
            KeyFobPresenceEvaluator.Observation departure = f.confirmedDeparture();
            JSONObject packet = f.envelope(20_200);
            copyPresence(f.last, packet);
            if ("revision".equals(invalid)) packet.put("revision", 8L);
            if ("unavailable".equals(invalid)) packet.put("presenceSourceActive", false);
            f.deliver(packet, 20_200, !"disabled".equals(invalid));
            assertFalse(f.policy.allowsLock(20_200));
            assertFalse(f.policy.decisionStillValid(departure, 20_200));
        }
    }

    @Test public void missingPresenceFieldsAreUnavailableAndRetirePriorEvidence() throws Exception {
        Fixture f = new Fixture();
        KeyFobPresenceEvaluator.Observation departure = f.confirmedDeparture();
        assertEquals("UNKNOWN", f.deliver(f.envelope(20_200), 20_200, true).state);
        assertFalse(f.policy.allowsLock(20_200));
        assertFalse(f.policy.decisionStillValid(departure, 20_200));
    }

    @Test public void changingExistingResultOrReusingQueryCycleIsRejectedAndRevokesEvidence() throws Exception {
        for (boolean reuseCycle : new boolean[] {false, true}) {
            Fixture f = new Fixture();
            f.confirmedDeparture();
            JSONObject packet = f.envelope(20_200);
            copyPresence(f.last, packet);
            if (reuseCycle) packet.put("presenceObservationId", f.id + 1)
                    .put("presenceObservationElapsedRealtimeMs", 20_200L)
                    .put("presenceQueryStartedElapsedRealtimeMs", 20_100L);
            else packet.put("presenceObservationState", "present");
            try { f.deliver(packet, 20_200, true); fail("Replayed query accepted"); }
            catch (IllegalArgumentException expected) { }
            assertFalse(f.policy.allowsLock(20_200));
        }
    }

    @Test public void futureCallbackAndTooLongKnownQueryCannotAuthorizeAbsence() throws Exception {
        Fixture f = new Fixture();
        f.read("present", 100);
        f.read("absent", 10_100);
        f.result("absent", 20_100);
        JSONObject packet = new JSONObject(f.last.toString()).put("presenceQueryStartedElapsedRealtimeMs", 14_000L);
        assertEquals(ProximityEvaluator.Event.NONE, f.deliver(packet, 20_100, true).event);
        assertFalse(f.policy.allowsLock(20_100));
        packet.put("observationSequence", ++f.serial).put("presenceObservationId", ++f.id)
                .put("presenceSearchCycleId", ++f.cycle)
                .put("presenceObservationElapsedRealtimeMs", 20_300L);
        try { f.deliver(packet, 20_200, true); fail("Future callback accepted"); }
        catch (IllegalArgumentException expected) { }
        assertFalse(f.policy.allowsLock(20_200));
    }

    @Test public void alreadyLockedConfirmationDoesNotEmitLockAndUnknownLockNeverPermitsAction() throws Exception {
        for (int lock : new int[] {2, -1}) {
            Fixture f = new Fixture();
            f.driverLock = lock;
            f.read("present", 100);
            f.read("absent", 10_100);
            assertEquals(ProximityEvaluator.Event.NONE, f.read("absent", 20_100).event);
            if (lock == -1) assertFalse(f.policy.allowsLock(20_100));
        }
    }

    @Test public void freshPresenceOverridesPassiveAwayInSharedPhoneLockConsensus() throws Exception {
        Fixture f = new Fixture();
        f.read("present", 100);
        java.util.List<MultiPhoneProximityPolicy.Observation> phones = java.util.Collections.singletonList(
                new MultiPhoneProximityPolicy.Observation(true, true, true, ProximityEvaluator.State.AWAY, 100));
        assertFalse(MultiPhoneProximityPolicy.canLock(true, true, f.policy.allowsLock(100), phones, 100, 5_000));
        f.read("absent", 10_100);
        f.read("absent", 20_100);
        phones = java.util.Collections.singletonList(
                new MultiPhoneProximityPolicy.Observation(true, true, true, ProximityEvaluator.State.AWAY, 20_100));
        assertTrue(MultiPhoneProximityPolicy.canLock(true, true, f.policy.allowsLock(20_100), phones, 20_100, 5_000));
    }

    @Test public void statusReportsOriginalResultAgeAndActualSourceAges() throws Exception {
        Fixture f = new Fixture();
        f.read("present", 100);
        f.heartbeat(2_100);
        JSONObject status = f.policy.current(2_300).toJson();
        assertEquals(2_200L, status.getLong("lastObservationAgeMs"));
        assertEquals(200L, status.getLong("heartbeatAgeMs"));
        assertEquals(200L, status.getLong("scopeAgeMs"));
        assertEquals(10_000L, status.getLong("windowMs"));
        assertEquals(10_000L, status.getLong("pollIntervalMs"));
        assertEquals(2, status.getInt("requiredSamples"));
    }

    @Test public void onlySharedRadioChangesCanRetainPhysicalPresenceSession() throws Exception {
        JSONObject saved = new JSONObject().put("revision", 7L).put("enabled", true)
                .put("keyFobEnabled", true).put("keyFobLockEnabled", true).put("advancedMode", true)
                .put("keyFobLockWindowMs", 30_000L).put("keepBluetoothOn", true)
                .put("phones", new org.json.JSONArray());
        JSONObject effective = new JSONObject(saved.toString());
        JSONObject radioOff = new JSONObject(saved.toString()).put("keepBluetoothOn", false);
        JSONObject effectiveOff = new JSONObject(effective.toString()).put("keepBluetoothOn", false);
        assertTrue(KeyFobPresenceEvaluator.canRetainAcrossRadioChange(saved, effective, radioOff, effectiveOff));
        for (String key : new String[] {"revision", "enabled", "keyFobEnabled", "keyFobLockEnabled",
                "advancedMode", "keyFobLockWindowMs", "phones"}) {
            JSONObject changed = new JSONObject(radioOff.toString());
            if ("revision".equals(key)) changed.put(key, 8L);
            else if ("keyFobLockWindowMs".equals(key)) changed.put(key, 40_000L);
            else if ("phones".equals(key))
                changed.put(key, new org.json.JSONArray().put(new JSONObject().put("enabled", true)));
            else changed.put(key, false);
            assertFalse("Retained session after " + key,
                    KeyFobPresenceEvaluator.canRetainAcrossRadioChange(saved, effective, changed, effectiveOff));
        }
        assertFalse(KeyFobPresenceEvaluator.canRetainAcrossRadioChange(saved, effective, radioOff,
                new JSONObject(effectiveOff.toString()).put("keyFobLockWindowMs", 10_000L)));
    }

    @Test public void freshPassiveNearVetoRevokesQueuedLockAndRequiresNewAbsenceWindow() throws Exception {
        Fixture f = new Fixture();
        KeyFobPresenceEvaluator.Observation departure = f.confirmedDeparture();
        JSONObject near = f.envelope(20_200).put("eventSequence", 1L).put("rawMask", 8)
                .put("eventObservedAtElapsedMs", 20_200L)
                .put("runtime", new JSONObject().put("active", true).put("registered", true)
                        .put("tableRegistered", true).put("reason", "listening"));
        KeyFobEventEvaluator passive = new KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger.NEAR, 7, true);
        KeyFobEventEvaluator.Snapshot approach = passive.observe(
                KeyFobEventEvaluator.Observation.parse(near), 20_200, true);
        assertTrue(approach.fresh);
        assertEquals("NEAR", approach.state);
        f.policy.vetoFreshApproach();
        assertFalse(f.policy.decisionStillValid(departure, 20_200));
        assertFalse(f.policy.allowsLock(20_200));
        assertEquals(0, f.policy.current(20_200).sampleCount);
        assertEquals("fresh_key_approach", f.policy.current(20_200).reason);
        // Quiet, healthy CAN history expires; it cannot veto every future exit.
        for (long at : new long[] {22_200, 24_200, 26_200}) {
            near.put("observationSequence", near.getLong("observationSequence") + 1);
            near.getJSONObject("scope").put("observedAtElapsedMs", at);
            passive.observe(KeyFobEventEvaluator.Observation.parse(near), at, true);
        }
        assertFalse(passive.current(26_200).fresh);
        assertEquals(ProximityEvaluator.Event.NONE, f.read("absent", 30_100).event);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, f.read("absent", 40_100).event);
    }

    @Test public void healthyHeartbeatAfterSourceLossCannotRestoreAnOldKnownResult() throws Exception {
        Fixture f = new Fixture();
        f.confirmedDeparture();
        assertFalse(f.policy.current(26_101).departureKnown);
        KeyFobPresenceEvaluator.Snapshot restoredHeartbeat = f.heartbeat(26_102);
        assertEquals("UNKNOWN", restoredHeartbeat.state);
        assertFalse(restoredHeartbeat.fresh);
        assertFalse(restoredHeartbeat.departureKnown);
        assertFalse(restoredHeartbeat.armed);
    }

    private static JSONObject pauseLocked(Fixture f, long at) throws Exception {
        f.driverLock = 2;
        f.generation++;
        f.id = f.cycle = 0;
        JSONObject packet = f.envelope(at)
                .put("presenceObservationState", "unknown").put("presenceSourceActive", false)
                .put("presenceSourceGeneration", f.generation).put("presenceObservationId", 0L)
                .put("presenceSearchCycleId", 0L).put("presenceObservationElapsedRealtimeMs", -1L)
                .put("presenceQueryStartedElapsedRealtimeMs", -1L)
                .put("presenceSearchStatus", "paused_locked");
        f.last = new JSONObject(packet.toString());
        return packet;
    }

    @Test public void lockedPauseRevokesDepartureAndStaysVisibleWithFreshScope() throws Exception {
        Fixture f = new Fixture();
        KeyFobPresenceEvaluator.Observation departure = f.confirmedDeparture();
        KeyFobPresenceEvaluator.Snapshot paused = f.deliver(pauseLocked(f, 20_200), 20_200, true);
        assertEquals("presence_search_paused_locked", paused.reason);
        assertEquals("UNKNOWN", paused.state);
        assertFalse(paused.active);
        assertFalse(paused.fresh);
        assertFalse(paused.armed);
        assertFalse(paused.departureKnown);
        assertFalse(f.policy.decisionStillValid(departure, 20_200));
        f.heartbeat(22_200);
        f.policy.vetoFreshApproach();
        JSONObject status = f.policy.current(22_300).toJson();
        assertEquals("presence_search_paused_locked", status.getString("reason"));
        assertEquals(100L, status.getLong("heartbeatAgeMs"));
        assertEquals(100L, status.getLong("scopeAgeMs"));
        assertFalse(status.getBoolean("departureKnown"));
    }

    @Test public void expiredOrRevokedLockedPauseCannotReportFreshLockScope() throws Exception {
        Fixture f = new Fixture();
        f.deliver(pauseLocked(f, 100), 100, true);
        assertEquals("presence_collector_stale", f.policy.current(5_101).reason);
        assertFalse(f.policy.allowsLock(5_101));
        f = new Fixture();
        assertEquals("presence_disabled", f.deliver(pauseLocked(f, 100), 100, false).reason);
        assertFalse(f.policy.current(100).armed);
    }

    @Test public void resumeAfterLockedPauseRequiresWholeNewAbsenceWindow() throws Exception {
        Fixture f = new Fixture();
        f.confirmedDeparture();
        f.deliver(pauseLocked(f, 20_200), 20_200, true);
        f.driverLock = 1;
        f.last.put("presenceSearchStatus", "idle").put("presenceSourceActive", true);
        assertEquals(ProximityEvaluator.Event.NONE, f.read("absent", 30_200).event);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, f.read("absent", 40_200).event);
        assertTrue(f.policy.allowsLock(40_200));
        assertTrue(f.read("present", 50_200).armed);
        assertEquals(ProximityEvaluator.Event.NONE, f.read("absent", 60_200).event);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, f.read("absent", 70_200).event);
    }

    @Test public void pausedSearchCannotClaimActiveOnUnlockedOrUnknownScope() throws Exception {
        for (String invalid : new String[] {"active", "on", "unlocked", "unknown"}) {
            Fixture f = new Fixture();
            JSONObject packet = pauseLocked(f, 100);
            if ("active".equals(invalid)) packet.put("presenceSourceActive", true);
            else if ("on".equals(invalid)) packet.getJSONObject("scope").put("power", 2);
            else if ("unlocked".equals(invalid)) packet.getJSONObject("scope").put("driverLock", 1);
            else packet.getJSONObject("scope").put("driverLock", -1);
            try {
                KeyFobPresenceEvaluator.Observation.parse(packet,
                        KeyFobEventEvaluator.Observation.parse(packet));
                fail("Invalid locked-pause scope accepted: " + invalid);
            } catch (IllegalArgumentException expected) { }
        }
    }

    // ── Cloud fallback (17 Oct): the revocation-only variant used by the cloud leg ──────────────────

    /** An 8 s-old confirmed departure with heartbeats and no newer result: current, though no longer valid. */
    @Test public void eightSecondOldDepartureWithNoNewerResultIsStillCurrent() throws Exception {
        Fixture f = new Fixture();
        KeyFobPresenceEvaluator.Observation departure = f.confirmedDeparture();
        assertTrue(f.policy.decisionStillValid(departure, 20_101));
        assertTrue(f.policy.decisionStillCurrent(departure, 20_101));
        f.aliveUntil(28_100);
        assertFalse(f.policy.decisionStillValid(departure, 28_100));   // 8 s old: the 5 s lease is spent …
        assertTrue(f.policy.decisionStillCurrent(departure, 28_100)); // … but nothing revoked the absence.
        // The absence result itself ages out of its own freshness (20 s): no longer current either.
        f.aliveUntil(40_200);
        assertFalse(f.policy.decisionStillCurrent(departure, 40_200));
        assertFalse(f.policy.allowsLock(40_200));
    }

    /** A new "present" result, a timed-out search, a locked or powered car revoke the current token. */
    @Test public void newerPresenceTimeoutOrVehicleStateRevokesTheCurrentDeparture() throws Exception {
        Fixture f = new Fixture();
        KeyFobPresenceEvaluator.Observation departure = f.confirmedDeparture();
        f.aliveUntil(28_100);
        assertTrue(f.policy.decisionStillCurrent(departure, 28_100));
        f.read("present", 28_200);
        assertFalse(f.policy.decisionStillCurrent(departure, 28_201));
        assertFalse(f.policy.decisionStillValid(departure, 28_201));

        Fixture timedOut = new Fixture();
        KeyFobPresenceEvaluator.Observation token = timedOut.confirmedDeparture();
        timedOut.aliveUntil(26_100);
        assertTrue(timedOut.policy.decisionStillCurrent(token, 26_100));
        timedOut.timeout(26_200);
        assertFalse(timedOut.policy.decisionStillCurrent(token, 26_201));

        Fixture locked = new Fixture();
        KeyFobPresenceEvaluator.Observation lockedToken = locked.confirmedDeparture();
        locked.aliveUntil(26_100);
        assertTrue(locked.policy.decisionStillCurrent(lockedToken, 26_100));
        locked.driverLock = 2; // the local lock landed late: no second lock is actionable
        locked.heartbeat(26_200);
        assertFalse(locked.policy.decisionStillCurrent(lockedToken, 26_201));

        Fixture powered = new Fixture();
        KeyFobPresenceEvaluator.Observation poweredToken = powered.confirmedDeparture();
        powered.aliveUntil(26_100);
        powered.power = 1;
        powered.heartbeat(26_200);
        assertFalse(powered.policy.decisionStillCurrent(poweredToken, 26_201));

        // A silent source (no heartbeat for more than SOURCE_FRESH_MS) is not current either.
        Fixture silent = new Fixture();
        KeyFobPresenceEvaluator.Observation silentToken = silent.confirmedDeparture();
        assertFalse(silent.policy.decisionStillCurrent(silentToken, 20_100 + KeyFobPresenceEvaluator.SOURCE_FRESH_MS + 1));
        // A revoked revision is not current.
        Fixture revoked = new Fixture();
        KeyFobPresenceEvaluator.Observation revokedToken = revoked.confirmedDeparture();
        revoked.revision = 8;
        KeyFobPresenceEvaluator.Observation other = revoked.result("absent", 22_100);
        assertFalse(revoked.policy.decisionStillCurrent(other, 22_101));
    }

    /** Whenever the result is still inside its 5 s lease, the two predicates agree exactly. */
    @Test public void currentAgreesWithValidWhileTheResultLeaseHolds() throws Exception {
        Fixture f = new Fixture();
        KeyFobPresenceEvaluator.Observation departure = f.confirmedDeparture();
        for (long now = 20_100; now <= 25_100; now += 250) {
            if ((now - 20_100) % 2_000 == 0 && now > 20_100) f.heartbeat(now);
            assertEquals("@" + now, f.policy.decisionStillValid(departure, now),
                    f.policy.decisionStillCurrent(departure, now));
        }
        assertTrue(f.policy.decisionStillCurrent(departure, 25_100));
        assertTrue(f.policy.decisionStillValid(departure, 25_100));
        assertFalse(f.policy.decisionStillValid(departure, 25_101));
        assertTrue(f.policy.decisionStillCurrent(departure, 25_101));
    }
}
