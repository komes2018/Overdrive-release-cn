package com.overdrive.app.remoteaccess;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.Event.*;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.*;

/** Shared policy uses explicit peer reads independently of the Android SDK transport. */
public class ProximityConnectionEvidenceTest {
    private static final String PHONE = "AA:BB:CC:DD:EE:01";
    private static final String MODE = "00000000-0000-0000-0000-000000000001";
    private static final String NEXT_MODE = "00000000-0000-0000-0000-000000000002";

    private static ProximityEvaluator evaluator() {
        return new ProximityEvaluator(new ProximityEvaluator.Config(-12, -24,
                15_000, 4_000, 5_000, 4_000, 2_000,
                ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR));
    }

    private static BluetoothConnectionEvidence proof(boolean connected, long at) throws Exception {
        return BluetoothConnectionEvidence.parse(BluetoothConnectionEvidenceTest.packet(
                connected ? "connected" : "disconnected", at));
    }

    private static ProximityEvaluator.Snapshot read(ProximityEvaluator evaluator, long at,
            Integer raw, boolean connected) throws Exception {
        return evaluator.observe(PHONE, raw, at, connected, true, proof(connected, at));
    }

    private static ProximityEvaluator.Snapshot away(ProximityEvaluator evaluator) throws Exception {
        read(evaluator, 0, -8, true);
        read(evaluator, 1_000, -8, true);
        assertFalse(read(evaluator, 2_000, -8, true).arrivalCandidate);
        assertEquals(UNKNOWN, read(evaluator, 3_000, null, false).state);
        assertEquals(UNKNOWN, read(evaluator, 6_000, null, false).state);
        return read(evaluator, 9_000, null, false);
    }

    @Test public void threeNativeDisconnectedReadsConfirmAwayWithoutFabricatedRssi() throws Exception {
        ProximityEvaluator evaluator = evaluator();
        ProximityEvaluator.Snapshot confirmed = away(evaluator);
        assertEquals(AWAY, confirmed.state);
        assertEquals(DEPARTURE, confirmed.event);
        assertEquals("confirmed_disconnection", confirmed.evidenceKind);
        assertTrue(confirmed.connectionKnown);
        assertTrue(confirmed.radioOn);
        assertTrue(confirmed.departureEligible);
        assertTrue(confirmed.lockCandidate);
        assertTrue(confirmed.armed);
        assertEquals(3, confirmed.disconnectionSampleCount);
        assertEquals(6_000, confirmed.disconnectedForMs);
        assertNull(confirmed.rawRssi);
        assertNull(confirmed.medianRssi);
        assertNull(confirmed.departureMedianRssi);
    }

    @Test public void initiallyUnconnectedPhoneNeverInventsDeparture() throws Exception {
        ProximityEvaluator evaluator = evaluator();
        for (long at = 0; at <= 30_000; at += 3_000) {
            ProximityEvaluator.Snapshot unknown = read(evaluator, at, null, false);
            assertEquals(UNKNOWN, unknown.state);
            assertFalse(unknown.armed);
            assertFalse(unknown.lockCandidate);
            assertFalse(unknown.departureEligible);
        }
    }

    @Test public void nativeConnectedProofCanEstablishHistoryWithoutIssuingAction() throws Exception {
        ProximityEvaluator evaluator = evaluator();
        ProximityEvaluator.Snapshot connected = read(evaluator, 0, null, true);
        assertEquals(UNKNOWN, connected.state);
        assertFalse(connected.arrivalCandidate);
        assertFalse(connected.lockCandidate);
        read(evaluator, 1_000, null, false);
        read(evaluator, 4_000, null, false);
        assertTrue(read(evaluator, 7_000, null, false).lockCandidate);
    }

    @Test public void statusAndDuplicateProofCannotRenewActionOrAdvanceAbsence() throws Exception {
        ProximityEvaluator evaluator = evaluator();
        ProximityEvaluator.Snapshot confirmed = away(evaluator);
        long episode = confirmed.episodeId;
        ProximityEvaluator.Snapshot status = evaluator.snapshot(9_100);
        assertFalse(status.lockCandidate);
        assertFalse(status.arrivalCandidate);
        assertEquals(NONE, status.event);
        ProximityEvaluator.Snapshot duplicate = evaluator.observe(PHONE, null, 9_200,
                false, true, proof(false, 9_000));
        assertEquals(3, duplicate.disconnectionSampleCount);
        assertEquals(6_000, duplicate.disconnectedForMs);
        assertFalse(duplicate.lockCandidate);
        assertEquals(200, duplicate.freshnessMs);
        ProximityEvaluator.Snapshot renewal = read(evaluator, 12_000, null, false);
        assertEquals(NONE, renewal.event);
        assertTrue(renewal.lockCandidate);
        assertEquals(episode, renewal.episodeId);
    }

    @Test public void firstReconnectRevokesLockButFreshNearCanReturnAndRetry() throws Exception {
        ProximityEvaluator evaluator = evaluator();
        ProximityEvaluator.Snapshot departed = away(evaluator);
        ProximityEvaluator.Snapshot reconnect = read(evaluator, 10_000, null, true);
        assertEquals(UNKNOWN, reconnect.state);
        assertTrue(reconnect.armed);
        assertFalse(reconnect.departureEligible);
        assertFalse(reconnect.lockCandidate);
        assertFalse(reconnect.arrivalCandidate);
        assertFalse(read(evaluator, 11_000, -8, true).arrivalCandidate);
        assertFalse(read(evaluator, 12_000, -8, true).arrivalCandidate);
        ProximityEvaluator.Snapshot arrival = read(evaluator, 13_000, -8, true);
        assertEquals(NEAR, arrival.state);
        assertEquals(ARRIVAL, arrival.event);
        assertTrue(arrival.arrivalCandidate);
        assertFalse(arrival.lockCandidate);
        assertTrue(arrival.episodeId > departed.episodeId);
        ProximityEvaluator.Snapshot retry = read(evaluator, 14_000, -8, true);
        assertEquals(NONE, retry.event);
        assertTrue(retry.arrivalCandidate);
        assertEquals(arrival.episodeId, retry.episodeId);
        assertFalse(evaluator.snapshot(14_100).arrivalCandidate);
    }

    @Test public void radioOrNativeUnknownRevokesReturnArmAndAllDepartureEvidence() throws Exception {
        for (boolean radioKnown : new boolean[]{false, true}) {
            ProximityEvaluator evaluator = evaluator();
            away(evaluator);
            JSONObject unknown = BluetoothConnectionEvidenceTest.packet("unknown", 10_000)
                    .put("radioReadKnown", radioKnown).put("radioOn", false);
            ProximityEvaluator.Snapshot revoked = evaluator.observe(PHONE, null, 10_000,
                    false, true, BluetoothConnectionEvidence.parse(unknown));
            assertEquals(UNKNOWN, revoked.state);
            assertFalse(revoked.armed);
            assertFalse(revoked.departureEligible);
            assertFalse(revoked.lockCandidate);
            for (long at : new long[]{11_000, 12_000, 13_000})
                assertFalse(read(evaluator, at, -8, true).arrivalCandidate);
        }
    }

    @Test public void staleOrModeChangedNativeProofCannotReusePriorConnection() throws Exception {
        for (boolean modeChanged : new boolean[]{false, true}) {
            ProximityEvaluator evaluator = evaluator();
            away(evaluator);
            JSONObject packet = BluetoothConnectionEvidenceTest.packet("disconnected",
                    modeChanged ? 10_000 : 3_999);
            if (modeChanged) packet.put("connectionModeGeneration", NEXT_MODE);
            ProximityEvaluator.Snapshot unknown = evaluator.observe(PHONE, null, 10_000,
                    false, true, BluetoothConnectionEvidence.parse(packet));
            assertEquals(UNKNOWN, unknown.state);
            assertFalse(unknown.armed);
            assertFalse(unknown.lockCandidate);
        }
    }

    @Test public void collectorGapPowerAndPeerChangeRevokeHistoricalAway() throws Exception {
        for (String cause : new String[]{"gap", "power", "peer"}) {
            ProximityEvaluator evaluator = evaluator();
            away(evaluator);
            long at = "gap".equals(cause) ? 14_001 : 10_000;
            ProximityEvaluator.Snapshot revoked = evaluator.observe(
                    "peer".equals(cause) ? "AA:BB:CC:DD:EE:02" : PHONE,
                    null, at, false, !"power".equals(cause), proof(false, at));
            assertEquals(UNKNOWN, revoked.state);
            assertFalse(revoked.armed);
            assertFalse(revoked.lockCandidate);
        }
    }

    @Test public void actionCandidateEpisodeIsMonotonicAcrossResetAndLegacyStartupNearStaysQuiet()
            throws Exception {
        ProximityEvaluator evaluator = evaluator();
        long episode = away(evaluator).episodeId;
        evaluator.reset();
        long second = away(evaluator).episodeId;
        assertTrue(second > episode);
        ProximityEvaluator legacy = evaluator();
        legacy.observe(PHONE, -8, 0, true, true);
        legacy.observe(PHONE, -8, 1_000, true, true);
        ProximityEvaluator.Snapshot near = legacy.observe(PHONE, -8, 2_000, true, true);
        assertEquals(NEAR, near.state);
        assertFalse(near.arrivalCandidate);
        assertFalse(near.lockCandidate);
    }

    @Test public void stricterConfiguredDepartureDwellStillAppliesToNativeAbsence() throws Exception {
        ProximityEvaluator evaluator = new ProximityEvaluator(new ProximityEvaluator.Config(
                -12, -24, 1, 12_000, 5_000, 3, ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR));
        read(evaluator, 0, -8, true);
        for (long at : new long[]{1_000, 4_000, 7_000, 10_000})
            assertFalse(read(evaluator, at, null, false).lockCandidate);
        ProximityEvaluator.Snapshot confirmed = read(evaluator, 13_000, null, false);
        assertTrue(confirmed.lockCandidate);
        assertEquals(12_000, confirmed.disconnectedForMs);
    }
}
