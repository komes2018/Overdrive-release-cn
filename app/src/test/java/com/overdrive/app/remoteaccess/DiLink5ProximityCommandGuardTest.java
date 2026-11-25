package com.overdrive.app.remoteaccess;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiLink5ProximityCommandGuardTest {
    private DiLink5ProximityCommandGuard.Scope scope() {
        return new DiLink5ProximityCommandGuard.Scope(
                "00000000-0000-0000-0000-000000000001", "unlock", "di5-generation", 7L, 5000L);
    }
    private JSONObject settings() throws Exception {
        return new JSONObject().put("enabled", true).put("unlockEnabled", true)
                .put("lockEnabled", true).put("dryRun", false).put("revision", 7L);
    }
    private JSONObject reply() throws Exception {
        return scope().toJson().put("command", "remoteAccessActuationResponse")
                .put("allowed", true).put("requestId", "request-1").put("observedAtElapsedMs", 1100L);
    }
    @Test public void originalScopeRoundtripsWithoutExtendingItsDeadline() throws Exception {
        DiLink5ProximityCommandGuard.Scope copy =
                DiLink5ProximityCommandGuard.Scope.parse(scope().toJson().toString());
        assertEquals(5000L, copy.expiresAt);
        assertEquals(7L, copy.revision);
        assertTrue(DiLink5ProximityCommandGuard.locallyAllowed(copy, settings(), 1200L, true, true, false));
    }
    @Test public void masterOffAndChangedRevisionRevokeQueuedCommands() throws Exception {
        assertFalse(DiLink5ProximityCommandGuard.locallyAllowed(
                scope(), settings().put("enabled", false), 1200L, true, true, false));
        assertFalse(DiLink5ProximityCommandGuard.locallyAllowed(
                scope(), settings().put("revision", 8L), 1200L, true, true, false));
    }
    @Test public void sourceConsentModeAndDeadlineRemainRequired() throws Exception {
        assertFalse(DiLink5ProximityCommandGuard.locallyAllowed(scope(), settings(), 5000L, true, true, false));
        assertFalse(DiLink5ProximityCommandGuard.locallyAllowed(scope(), settings(), 1200L, false, true, false));
        assertFalse(DiLink5ProximityCommandGuard.locallyAllowed(scope(), settings(), 1200L, true, false, false));
        assertFalse(DiLink5ProximityCommandGuard.locallyAllowed(scope(), settings(), 1200L, true, true, true));
        assertFalse(DiLink5ProximityCommandGuard.locallyAllowed(
                scope(), settings().put("dryRun", true), 1200L, true, true, false));
        assertFalse(DiLink5ProximityCommandGuard.locallyAllowed(
                scope(), settings().put("unlockEnabled", false), 1200L, true, true, false));
    }
    @Test public void newerReadingsInTheSameEpisodeDoNotCancelAnArrival() {
        assertTrue(DiLink5ProximityCommandGuard.sameEpisode(10L, 12L, 10L));
        assertTrue(DiLink5ProximityCommandGuard.sameEpisode(10L, 10L, 10L));
        assertFalse(DiLink5ProximityCommandGuard.sameEpisode(10L, 9L, 9L));
        // Unknown/Away followed by a new Near episode cannot reuse the earlier arrival.
        assertFalse(DiLink5ProximityCommandGuard.sameEpisode(10L, 12L, 12L));
    }
    @Test public void strongLatestRawReadingSupportsTheOriginalNearArrival() {
        assertTrue(DiLink5ProximityCommandGuard.latestArrivalReadingAgrees(-8, -12, true));
        assertTrue(DiLink5ProximityCommandGuard.latestArrivalReadingAgrees(-12, -12, true));
        // Each enabled phone retains its effective configured/calibrated threshold.
        assertTrue(DiLink5ProximityCommandGuard.latestArrivalReadingAgrees(-18, -20, true));
        assertFalse(DiLink5ProximityCommandGuard.latestArrivalReadingAgrees(-18, -16, true));
    }
    @Test public void weakAndDeadbandLatestReadingsRevokeEvenAHeldNearState() throws Exception {
        JSONObject heldNear = new JSONObject().put("state", "NEAR").put("filteredRssi", -10)
                .put("connected", true);
        for (int latestRaw : new int[]{-13, -18, -24, -30}) {
            heldNear.put("rawRssi", latestRaw);
            assertEquals("NEAR", heldNear.getString("state"));
            assertFalse(DiLink5ProximityCommandGuard.latestArrivalReadingAgrees(
                    heldNear.opt("rawRssi"), -12, heldNear.optBoolean("connected")));
        }
    }
    @Test public void missingDisconnectedAndMalformedLatestSamplesRevokeAnArrival() {
        assertFalse(DiLink5ProximityCommandGuard.latestArrivalReadingAgrees(null, -12, true));
        assertFalse(DiLink5ProximityCommandGuard.latestArrivalReadingAgrees(JSONObject.NULL, -12, true));
        assertFalse(DiLink5ProximityCommandGuard.latestArrivalReadingAgrees(-8, -12, false));
        assertFalse(DiLink5ProximityCommandGuard.latestArrivalReadingAgrees("-8", -12, true));
        assertFalse(DiLink5ProximityCommandGuard.latestArrivalReadingAgrees(-8.5, -12, true));
        assertFalse(DiLink5ProximityCommandGuard.latestArrivalReadingAgrees(Double.NaN, -12, true));
        assertFalse(DiLink5ProximityCommandGuard.latestArrivalReadingAgrees(21, -12, true));
        assertFalse(DiLink5ProximityCommandGuard.latestArrivalReadingAgrees(-128, -127, true));
    }
    @Test public void authenticatedReplyMustMatchTheExactOutstandingScope() throws Exception {
        assertTrue(DiLink5ProximityCommandGuard.validReply(scope(), reply(), "request-1", 1000L, 1200L));
        for (String key : new String[]{"token", "action", "modeGeneration", "requestId", "command"}) {
            assertFalse(key, DiLink5ProximityCommandGuard.validReply(
                    scope(), reply().put(key, "wrong"), "request-1", 1000L, 1200L));
        }
        assertFalse(DiLink5ProximityCommandGuard.validReply(
                scope(), reply().put("revision", 8L), "request-1", 1000L, 1200L));
        assertFalse(DiLink5ProximityCommandGuard.validReply(
                scope(), reply().put("allowed", false), "request-1", 1000L, 1200L));
    }
    @Test public void delayedReplayedFutureAndExpiredRepliesCannotAuthorizeAWrite() throws Exception {
        assertFalse(DiLink5ProximityCommandGuard.validReply(scope(), reply(), "request-1", 1000L, 1400L));
        assertFalse(DiLink5ProximityCommandGuard.validReply(scope(), reply(), "request-1", 1150L, 1200L));
        assertFalse(DiLink5ProximityCommandGuard.validReply(scope(), reply(), "request-1", 1000L, 1050L));
        assertFalse(DiLink5ProximityCommandGuard.validReply(
                scope(), reply().put("observedAtElapsedMs", 4990L), "request-1", 4900L, 5000L));
    }
    @Test public void stringAndFractionalAuthorizationFieldsAreRejected() throws Exception {
        assertFalse(DiLink5ProximityCommandGuard.validReply(
                scope(), reply().put("observedAtElapsedMs", "1100"), "request-1", 1000L, 1200L));
        assertFalse(DiLink5ProximityCommandGuard.validReply(
                scope(), reply().put("revision", 7.5), "request-1", 1000L, 1200L));
        try {
            DiLink5ProximityCommandGuard.Scope.parse(scope().toJson().put("revision", "7").toString());
            fail("String revision accepted");
        } catch (IllegalArgumentException expected) {}
    }
}
