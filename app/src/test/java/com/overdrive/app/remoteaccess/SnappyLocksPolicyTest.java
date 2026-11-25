package com.overdrive.app.remoteaccess;

import static com.overdrive.app.remoteaccess.ProximityEvaluator.Event.ARRIVAL;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.Event.DEPARTURE;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.Event.NONE;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.AWAY;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.NEAR;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.UNKNOWN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 20 Oct snappy locks, evaluator and policy half. C1: the first stable Near episode after a connection
 * is an ARRIVAL under the settings-derived default. C2: native disconnection confirms on two
 * not-connected reads at least 2 s apart. C3: a phone departure waits for confirmed key absence only
 * with {@code lockWaitsForKeyAbsence}; a fresh key Present result vetoes either way. C4: the 15 s
 * absent-phone grace boundary.
 */
public class SnappyLocksPolicyTest {
    private static final String PHONE = "AA:BB:CC:DD:EE:01";
    private static final long STALE = 5_000;

    // ── C1 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    public void firstStableNearEpisodeAfterAConnectionIsAnArrivalUnderTheDefault() throws Exception {
        ProximityEvaluator evaluator = evaluator(RemoteAccessSettings.effective(trustedPhone()));
        // The phone connects already beside the car (normal-mode thresholds -12 / -24).
        ProximityEvaluator.Snapshot first = read(evaluator, 0, -8, true);
        assertEquals(NONE, first.event);
        assertEquals("stabilizing_near", first.reason);
        ProximityEvaluator.Snapshot arrival = read(evaluator, 1_000, -8, true);
        assertEquals(ARRIVAL, arrival.event);
        assertEquals(NEAR, arrival.state);
        assertTrue(arrival.arrivalCandidate);
        assertFalse(arrival.firstConnectionNoPriorAway);
        // One arrival per episode.
        assertEquals(NONE, read(evaluator, 2_000, -8, true).event);
        // Contrast: the Advanced Away → Near rule keeps suppressing the same first episode.
        JSONObject advanced = RemoteAccessSettings.effective(RemoteAccessSettings.merge(
                trustedPhone().put("advancedMode", true), new JSONObject().put("unlockTrigger", "away_to_near")));
        ProximityEvaluator conservative = evaluator(advanced);
        read(conservative, 0, -8, true);
        ProximityEvaluator.Snapshot suppressed = read(conservative, 1_000, -8, true);
        assertEquals(NONE, suppressed.event);
        assertTrue(suppressed.firstConnectionNoPriorAway);
    }

    // ── C2 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    public void twoNotConnectedReadsTwoSecondsApartConfirmDisconnection() throws Exception {
        assertEquals(2, ProximityEvaluator.DISCONNECTION_CONFIRM_READS);
        assertEquals(2_000L, ProximityEvaluator.DISCONNECTION_CONFIRM_SPAN_MS);
        ProximityEvaluator evaluator = evaluator(RemoteAccessSettings.effective(trustedPhone()));
        read(evaluator, 0, -30, true);
        ProximityEvaluator.Snapshot one = read(evaluator, 1_000, null, false);
        assertEquals("confirming_disconnection", one.reason);
        assertEquals(NONE, one.event);
        assertEquals(UNKNOWN, one.state);
        assertFalse(one.lockCandidate);
        assertEquals(1, one.disconnectionSampleCount);
        ProximityEvaluator.Snapshot confirmed = read(evaluator, 3_000, null, false);
        assertEquals("confirmed_disconnection", confirmed.reason);
        assertEquals("confirmed_disconnection", confirmed.evidenceKind);
        assertEquals(AWAY, confirmed.state);
        assertEquals(DEPARTURE, confirmed.event);
        assertTrue(confirmed.lockCandidate);
        assertTrue(confirmed.departureEligible);
        assertEquals(2, confirmed.disconnectionSampleCount);
        assertEquals(2_000, confirmed.disconnectedForMs);
    }

    @Test
    public void oneReadOrTwoReadsUnderTwoSecondsDoNotConfirm() throws Exception {
        ProximityEvaluator evaluator = evaluator(RemoteAccessSettings.effective(trustedPhone()));
        read(evaluator, 0, -30, true);
        assertEquals("confirming_disconnection", read(evaluator, 1_000, null, false).reason);
        ProximityEvaluator.Snapshot tooSoon = read(evaluator, 2_999, null, false);
        assertEquals("confirming_disconnection", tooSoon.reason);
        assertEquals(NONE, tooSoon.event);
        assertEquals(UNKNOWN, tooSoon.state);
        assertFalse(tooSoon.lockCandidate);
        // A third read at 3 s spans the 2 s and confirms.
        assertEquals(DEPARTURE, read(evaluator, 3_000, null, false).event);
    }

    @Test
    public void aConnectedReadInBetweenRestartsTheDisconnectionCount() throws Exception {
        ProximityEvaluator evaluator = evaluator(RemoteAccessSettings.effective(trustedPhone()));
        read(evaluator, 0, -30, true);
        read(evaluator, 1_000, null, false);
        ProximityEvaluator.Snapshot back = read(evaluator, 2_000, -30, true);
        assertEquals(0, back.disconnectionSampleCount);
        assertFalse(back.lockCandidate);
        // 3 s is 2 s after the first not-connected read, but only the first read of a new run.
        ProximityEvaluator.Snapshot restarted = read(evaluator, 3_000, null, false);
        assertEquals("confirming_disconnection", restarted.reason);
        assertEquals(1, restarted.disconnectionSampleCount);
        assertEquals(NONE, restarted.event);
        assertEquals(NONE, read(evaluator, 4_000, null, false).event);
        ProximityEvaluator.Snapshot confirmed = read(evaluator, 5_000, null, false);
        assertEquals(DEPARTURE, confirmed.event);
        assertEquals(2_000, confirmed.disconnectedForMs);
    }

    // ── C3 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    public void phoneDepartureLocksWithoutWaitingForTheKeySearchUnlessTheSwitchIsOn() throws Exception {
        long now = 100_000;
        List<MultiPhoneProximityPolicy.Observation> phones = Arrays.asList(phone(AWAY, now));
        // Key fob locking configured; the key search has not confirmed absence and reports nothing fresh.
        KeyFobPresenceEvaluator search = new KeyFobPresenceEvaluator(7, 5_000, 1);
        assertFalse(search.allowsLock(now));
        boolean freshPresent = freshKeyPresent(search, now);
        assertFalse(freshPresent);
        assertTrue(consensus(freshPresent, true, true, false, search.allowsLock(now), phones, now));
        assertFalse(consensus(freshPresent, true, true, true, search.allowsLock(now), phones, now));
        // The default effective settings run without the wait, in both modes unless Advanced turns it on.
        assertFalse(RemoteAccessSettings.lockWaitsForKeyAbsence(RemoteAccessSettings.effective(trustedPhone())));
        // Confirmed key absence satisfies the wait; without fob locking configured the switch changes nothing.
        assertTrue(consensus(false, true, true, true, true, phones, now));
        assertTrue(consensus(false, true, false, true, false, phones, now));
        // Fob-triggered departures still need confirmed absence whatever the switch says.
        for (boolean waits : new boolean[] {false, true}) {
            assertFalse(consensus(false, false, true, waits, false, phones, now));
            assertTrue(consensus(false, false, true, waits, true, phones, now));
        }
        // The phones' own consensus still applies: a Near phone blocks the phone lock.
        assertFalse(consensus(false, true, true, false, false,
                Arrays.asList(phone(AWAY, now), phone(NEAR, now)), now));
    }

    @Test
    public void aFreshKeyPresentResultVetoesThePhoneLockEitherWay() throws Exception {
        PresenceFixture fob = new PresenceFixture();
        KeyFobPresenceEvaluator.Snapshot present = fob.read("present", 1_000);
        assertEquals("PRESENT", present.state);
        long now = 2_000;
        assertTrue(freshKeyPresent(fob.policy, now));
        List<MultiPhoneProximityPolicy.Observation> phones = Arrays.asList(phone(AWAY, now));
        for (boolean waits : new boolean[] {false, true})
            assertFalse(consensus(freshKeyPresent(fob.policy, now), true, true, waits,
                    fob.policy.allowsLock(now), phones, now));
        // Once the Present result has expired it no longer vetoes the default (no-wait) phone lock.
        long later = 1_000 + KeyFobPresenceEvaluator.FAST_RESULT_FRESH_MS + 1;
        fob.aliveUntil(later);
        assertFalse(freshKeyPresent(fob.policy, later));
        assertTrue(consensus(false, true, true, false, fob.policy.allowsLock(later),
                Arrays.asList(phone(AWAY, later)), later));
    }

    // ── C4 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    public void absentPhoneGraceIsFifteenSeconds() {
        assertEquals(15_000L, MultiPhoneProximityPolicy.ABSENT_PHONE_GRACE_MS);
        long now = 1_000_000;
        MultiPhoneProximityPolicy.Observation departing = phone(AWAY, now);
        assertFalse(MultiPhoneProximityPolicy.allAway(Arrays.asList(departing,
                neverConnected(now - 14_999, now)), now, STALE));
        assertTrue(MultiPhoneProximityPolicy.allAway(Arrays.asList(departing,
                neverConnected(now - 15_000, now)), now, STALE));
        assertTrue(MultiPhoneProximityPolicy.canLock(true, false, false, Arrays.asList(departing,
                neverConnected(now - 15_000, now)), now, STALE));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /**
     * lockConsensus as the handler computes it: the fresh key approach/presence veto first (the first
     * argument already includes "fob detection on"), then canLock with
     * requiresFobAway = !phoneDeparture || (fobConfigured && lockWaitsForKeyAbsence). The handler wiring
     * is pinned by source in RemoteAccessSnappyLocksAssetTest.
     */
    private static boolean consensus(boolean freshKeyNearOrPresent, boolean phoneDeparture, boolean fobConfigured,
            boolean lockWaitsForKeyAbsence, boolean fobAbsenceConfirmed,
            List<MultiPhoneProximityPolicy.Observation> phones, long now) {
        if (freshKeyNearOrPresent) return false;
        return MultiPhoneProximityPolicy.canLock(phoneDeparture,
                !phoneDeparture || (fobConfigured && lockWaitsForKeyAbsence),
                fobConfigured && fobAbsenceConfirmed, phones, now, STALE);
    }

    /** The handler's hasFreshKeyFobPresence. */
    private static boolean freshKeyPresent(KeyFobPresenceEvaluator presence, long now) {
        KeyFobPresenceEvaluator.Snapshot snapshot = presence.current(now);
        return snapshot.fresh && "PRESENT".equals(snapshot.state);
    }

    private static MultiPhoneProximityPolicy.Observation phone(ProximityEvaluator.State state, long at) {
        return new MultiPhoneProximityPolicy.Observation(true, true, true, state, at);
    }

    private static MultiPhoneProximityPolicy.Observation neverConnected(long monitorStarted, long nativeObserved) {
        return new MultiPhoneProximityPolicy.Observation(true, false, false, UNKNOWN, -1, false,
                false, true, true, monitorStarted, nativeObserved, -1);
    }

    /** The evaluator the handler builds for the phone (PhoneState: evaluatorConfig(settings, profile)). */
    private static ProximityEvaluator evaluator(JSONObject effective) {
        return new ProximityEvaluator(RemoteAccessSettings.evaluatorConfig(effective,
                RemoteAccessSettings.phone(effective, PHONE)));
    }

    private static ProximityEvaluator.Snapshot read(ProximityEvaluator evaluator, long at, Integer raw,
            boolean connected) throws Exception {
        return evaluator.observe(PHONE, raw, at, connected, true, BluetoothConnectionEvidence.parse(
                BluetoothConnectionEvidenceTest.packet(connected ? "connected" : "disconnected", at)));
    }

    private static JSONObject trustedPhone() throws Exception {
        return RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("enabled", true).put("deviceAddress", PHONE).put("phones", new JSONArray()
                        .put(new JSONObject().put("address", PHONE).put("name", "Owner").put("enabled", true)
                                .put("nearThreshold", -8).put("awayThreshold", -35))));
    }

    /** Minimal signed-relay presence feed (off, unlocked, healthy), as in KeyFobPresenceEvaluatorTest. */
    static final class PresenceFixture {
        final KeyFobPresenceEvaluator policy;
        long serial, id, cycle, now = -1;
        JSONObject last;

        PresenceFixture() { this(5_000, 1); }

        PresenceFixture(long window, int samples) { policy = new KeyFobPresenceEvaluator(7, window, samples); }

        JSONObject envelope(long at) throws Exception {
            return new JSONObject().put("revision", 7L).put("instanceId", "worker-a")
                    .put("sourceGeneration", 1L).put("observationSequence", ++serial)
                    .put("eventSequence", 0L).put("rawMask", -1).put("eventObservedAtElapsedMs", -1L)
                    .put("runtime", new JSONObject().put("active", false).put("registered", false)
                            .put("tableRegistered", false).put("reason", "passive_can_unavailable"))
                    .put("scope", new JSONObject().put("power", 0).put("driverLock", 1)
                            .put("observedAtElapsedMs", at));
        }

        KeyFobPresenceEvaluator.Snapshot deliver(JSONObject packet, long at) throws Exception {
            now = at;
            return policy.observe(KeyFobPresenceEvaluator.Observation.parse(packet,
                    KeyFobEventEvaluator.Observation.parse(packet)), at, true);
        }

        void aliveUntil(long at) throws Exception {
            while (now >= 0 && now + 2_000 < at) {
                JSONObject packet = envelope(now + 2_000);
                if (last != null) {
                    java.util.Iterator<String> keys = last.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        if (key.startsWith("presence")) packet.put(key, last.get(key));
                    }
                }
                deliver(packet, now + 2_000);
            }
        }

        KeyFobPresenceEvaluator.Snapshot read(String state, long at) throws Exception {
            return read(state, at, 200);
        }

        KeyFobPresenceEvaluator.Snapshot read(String state, long at, long queryMs) throws Exception {
            aliveUntil(at);
            JSONObject packet = envelope(at)
                    .put("presenceObservationState", state).put("presenceSourceActive", true)
                    .put("presenceSourceGeneration", 1L).put("presenceObservationId", ++id)
                    .put("presenceSearchCycleId", ++cycle)
                    .put("presenceObservationElapsedRealtimeMs", at)
                    .put("presenceQueryStartedElapsedRealtimeMs", Math.max(0, at - queryMs))
                    .put("presenceSearchStatus", "idle");
            last = new JSONObject(packet.toString());
            return deliver(packet, at);
        }
    }
}
