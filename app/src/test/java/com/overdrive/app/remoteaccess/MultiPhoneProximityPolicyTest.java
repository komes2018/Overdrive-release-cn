package com.overdrive.app.remoteaccess;

import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.AWAY;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.NEAR;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.TRANSITION;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.UNKNOWN;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** Verifies physical-key priority and conservative departure from phone fallback. */
public class MultiPhoneProximityPolicyTest {
    private static final long NOW = 30_000;
    private static final long STALE = 5_000;

    private static MultiPhoneProximityPolicy.Observation away(long observed) {
        return new MultiPhoneProximityPolicy.Observation(true, true, true, AWAY, observed);
    }

    private static boolean all(MultiPhoneProximityPolicy.Observation... observations) {
        return MultiPhoneProximityPolicy.allAway(Arrays.asList(observations), NOW, STALE);
    }

    @Test
    public void oneOrMultipleFreshConnectedAwayPhonesPermitDeparture() {
        assertTrue(all(away(NOW)));
        assertTrue(all(away(NOW), away(NOW - 2_000), away(NOW - 4_000)));
    }

    @Test
    public void oneNearPhoneBlocksLockingRegardlessOfOtherAwayPhones() {
        MultiPhoneProximityPolicy.Observation near =
                new MultiPhoneProximityPolicy.Observation(true, true, true, NEAR, NOW);
        assertFalse(all(away(NOW), near));
        assertFalse(all(near, away(NOW)));
    }

    @Test
    public void unconfirmedTransitionUnknownAndNullStatesBlockLocking() {
        for (ProximityEvaluator.State state : new ProximityEvaluator.State[] {TRANSITION, UNKNOWN, null}) {
            assertFalse(all(away(NOW),
                    new MultiPhoneProximityPolicy.Observation(true, true, true, state, NOW)));
        }
    }

    @Test
    public void aDisconnectedAwayPhoneStillBlocksLocking() {
        assertFalse(all(away(NOW),
                new MultiPhoneProximityPolicy.Observation(true, false, true, AWAY, NOW)));
    }

    @Test public void verifiedDisconnectedAwayCanJoinFreshConnectedAwayConsensus() {
        MultiPhoneProximityPolicy.Observation absent =
                new MultiPhoneProximityPolicy.Observation(true, false, false, AWAY, NOW, true);
        assertTrue(all(absent));
        assertTrue(all(absent, away(NOW - 1_000)));
        assertFalse(all(absent, new MultiPhoneProximityPolicy.Observation(
                true, true, true, NEAR, NOW)));
        assertFalse(all(absent, new MultiPhoneProximityPolicy.Observation(
                true, false, false, UNKNOWN, NOW)));
    }

    @Test public void staleOrContradictoryDisconnectionProofCannotJoinConsensus() {
        for (long observed : new long[]{-1, NOW + 1, NOW - STALE - 1})
            assertFalse(all(new MultiPhoneProximityPolicy.Observation(
                    true, false, false, AWAY, observed, true)));
        assertFalse(all(new MultiPhoneProximityPolicy.Observation(
                true, true, false, AWAY, NOW, true)));
        assertFalse(all(new MultiPhoneProximityPolicy.Observation(
                true, false, true, AWAY, NOW, true)));
        assertFalse(all(new MultiPhoneProximityPolicy.Observation(
                true, false, false, TRANSITION, NOW, true)));
    }

    @Test
    public void missingRawDataBlocksEvenWhenCachedStateSaysAway() {
        assertFalse(all(away(NOW),
                new MultiPhoneProximityPolicy.Observation(true, true, false, AWAY, NOW)));
    }

    @Test
    public void staleAndFutureReadingsBlockLocking() {
        assertFalse(all(away(NOW), away(NOW - STALE - 1)));
        assertFalse(all(away(NOW), away(NOW + 1)));
        assertFalse(all(away(NOW), away(-1)));
    }

    @Test
    public void expiryBoundaryIsInclusiveAndOneMillisecondBeyondIsRejected() {
        assertTrue(all(away(NOW - STALE)));
        assertFalse(all(away(NOW - STALE - 1)));
    }

    @Test
    public void disabledPhonesAreIgnoredEvenIfNearOrMissing() {
        MultiPhoneProximityPolicy.Observation disabledNear =
                new MultiPhoneProximityPolicy.Observation(false, true, true, NEAR, NOW);
        MultiPhoneProximityPolicy.Observation disabledMissing =
                new MultiPhoneProximityPolicy.Observation(false, false, false, null, -1);
        assertTrue(all(away(NOW), disabledNear, disabledMissing));
    }

    @Test
    public void noEnabledPhonesNeverPermitAutomaticLocking() {
        assertFalse(MultiPhoneProximityPolicy.allAway(Collections.emptyList(), NOW, STALE));
        assertFalse(all(new MultiPhoneProximityPolicy.Observation(false, true, true, AWAY, NOW)));
    }

    @Test
    public void missingRosterOrEntryFailsClosed() {
        assertFalse(MultiPhoneProximityPolicy.allAway(null, NOW, STALE));
        assertFalse(all(away(NOW), null));
    }

    @Test
    public void invalidClockOrFreshnessIntervalFailsClosed() {
        List<MultiPhoneProximityPolicy.Observation> roster = Collections.singletonList(away(NOW));
        assertFalse(MultiPhoneProximityPolicy.allAway(roster, -1, STALE));
        assertFalse(MultiPhoneProximityPolicy.allAway(roster, NOW, 0));
        assertFalse(MultiPhoneProximityPolicy.allAway(roster, NOW, -1));
    }

    @Test
    public void monotonicTimeZeroAndLargeValuesDoNotOverflow() {
        assertTrue(MultiPhoneProximityPolicy.allAway(Collections.singletonList(away(0)), 0, STALE));
        assertTrue(MultiPhoneProximityPolicy.allAway(
                Collections.singletonList(away(Long.MAX_VALUE - 1)), Long.MAX_VALUE, STALE));
        assertFalse(MultiPhoneProximityPolicy.allAway(
                Collections.singletonList(away(0)), Long.MAX_VALUE, STALE));
    }

    @Test
    public void firstReturningRawNearBlocksLockingWhileOldDepartureMedianStillSaysAway() {
        ProximityEvaluator returning = fastPhone();
        for (long time = 0; time <= 15_000; time += 1_000)
            returning.observe("AA:BB:CC:DD:EE:01", -30, time, true, true);
        ProximityEvaluator.Snapshot first =
                returning.observe("AA:BB:CC:DD:EE:01", -8, 16_000, true, true);
        assertTrue(first.departureMedianRssi <= -24);
        assertTrue(first.nearCandidate);
        assertFalse(first.departureEligible);
        assertFalse(MultiPhoneProximityPolicy.allAway(
                Arrays.asList(away(16_000), forDeparture(first)), 16_000, STALE));
    }

    @Test
    public void deadbandPhoneCannotCountAsAwayEvenWithHistoricalAwayStateAndMedian() {
        ProximityEvaluator returning = fastPhone();
        for (long time = 0; time <= 15_000; time += 1_000)
            returning.observe("AA:BB:CC:DD:EE:01", -30, time, true, true);
        ProximityEvaluator.Snapshot middle =
                returning.observe("AA:BB:CC:DD:EE:01", -18, 16_000, true, true);
        assertTrue(middle.stableState == AWAY);
        assertTrue(middle.departureMedianRssi <= -24);
        assertFalse(middle.departureEligible);
        assertFalse(MultiPhoneProximityPolicy.allAway(
                Arrays.asList(away(16_000), forDeparture(middle)), 16_000, STALE));
    }

    @Test
    public void fobOnlyDepartureMayLockWithoutInventingAnEnabledPhone() {
        assertTrue(consensus(false, true, true));
        assertTrue(consensus(false, true, true,
                new MultiPhoneProximityPolicy.Observation(false, true, true, NEAR, NOW)));
        assertFalse(consensus(true, true, true));
        assertFalse(consensus(true, false, false));
        assertFalse(consensus(false, false, true));
    }

    @Test
    public void verifiedFobDepartureTakesPriorityOverEveryPhoneState() {
        for (ProximityEvaluator.State state :
                new ProximityEvaluator.State[] {AWAY, NEAR, UNKNOWN, TRANSITION, null})
            assertTrue(consensus(false, true, true, away(NOW),
                    new MultiPhoneProximityPolicy.Observation(true, true, true, state, NOW)));
    }

    @Test
    public void phoneDepartureStillRequiresAllPhonesAwayAndConfiguredFobAbsent() {
        assertTrue(consensus(true, true, true, away(NOW), away(NOW - 1000)));
        assertFalse(consensus(true, true, false, away(NOW), away(NOW - 1000)));
        for (ProximityEvaluator.State blocked :
                new ProximityEvaluator.State[] {NEAR, UNKNOWN, TRANSITION, null})
            assertFalse(consensus(true, true, true, away(NOW),
                    new MultiPhoneProximityPolicy.Observation(true, true, true, blocked, NOW)));
    }

    @Test
    public void disabledFobSourceDoesNotBlockFreshPhoneDeparture() {
        assertTrue(consensus(true, false, false, away(NOW)));
        assertFalse(consensus(true, true, false, away(NOW)));
    }

    @Test
    public void unavailablePhoneEvidenceCannotVetoVerifiedFobDeparture() {
        assertTrue(consensus(false, true, true,
                new MultiPhoneProximityPolicy.Observation(true, false, true, AWAY, NOW)));
        assertTrue(consensus(false, true, true,
                new MultiPhoneProximityPolicy.Observation(true, true, false, AWAY, NOW)));
        assertTrue(consensus(false, true, true,
                new MultiPhoneProximityPolicy.Observation(true, false, false, UNKNOWN, -1)));
        assertTrue(consensus(false, true, true, away(NOW - STALE - 1)));
        assertTrue(consensus(false, true, true, away(NOW + 1)));
        assertTrue(consensus(false, true, true, away(-1)));
        assertTrue(MultiPhoneProximityPolicy.canLock(false, true, true, null, NOW, STALE));
        assertTrue(consensus(false, true, true, (MultiPhoneProximityPolicy.Observation) null));
        assertTrue(consensus(false, true, true, null, null));
    }

    @Test
    public void mixedPhoneRosterCannotVetoVerifiedFobDeparture() {
        assertTrue(consensus(false, true, true, away(NOW),
                new MultiPhoneProximityPolicy.Observation(true, true, true, NEAR, NOW),
                new MultiPhoneProximityPolicy.Observation(true, false, false, UNKNOWN, -1),
                away(NOW - STALE - 1), null));
    }

    @Test
    public void phoneFallbackDoesNotInheritFobPriorityOverUnavailableEvidence() {
        MultiPhoneProximityPolicy.Observation[] unavailable = {
                new MultiPhoneProximityPolicy.Observation(true, false, true, AWAY, NOW),
                new MultiPhoneProximityPolicy.Observation(true, true, false, AWAY, NOW),
                new MultiPhoneProximityPolicy.Observation(true, false, false, UNKNOWN, -1),
                away(NOW - STALE - 1), away(NOW + 1), away(-1), null
        };
        for (boolean requiresFob : new boolean[] {false, true}) {
            for (MultiPhoneProximityPolicy.Observation observation : unavailable)
                assertFalse(consensus(true, requiresFob, true, away(NOW), observation));
            assertFalse(consensus(true, requiresFob, true));
            assertFalse(MultiPhoneProximityPolicy.canLock(true, requiresFob, true, null, NOW, STALE));
        }
    }

    @Test
    public void unconfirmedFobDepartureNeverLocksEvenWhenEveryPhoneIsAway() {
        for (boolean requiresFob : new boolean[] {false, true}) {
            assertFalse(consensus(false, requiresFob, false, away(NOW), away(NOW - 1000)));
            assertFalse(consensus(false, requiresFob, false));
            assertFalse(MultiPhoneProximityPolicy.canLock(false, requiresFob, false, null, NOW, STALE));
        }
        assertFalse(consensus(false, false, true, away(NOW)));
        assertFalse(MultiPhoneProximityPolicy.canLock(false, false, true, null, NOW, STALE));
    }

    @Test
    public void fobPriorityStillRejectsInvalidClockAndFreshnessParameters() {
        assertFalse(MultiPhoneProximityPolicy.canLock(false, true, true, null, -1, STALE));
        assertFalse(MultiPhoneProximityPolicy.canLock(false, true, true, null, NOW, 0));
        assertFalse(MultiPhoneProximityPolicy.canLock(false, true, true, null, NOW, -1));
        assertTrue(MultiPhoneProximityPolicy.canLock(false, true, true, null, 0, STALE));
        assertTrue(MultiPhoneProximityPolicy.canLock(false, true, true, null, Long.MAX_VALUE, STALE));
    }

    private static boolean consensus(boolean phoneDeparture, boolean requiresFob, boolean fobAway,
            MultiPhoneProximityPolicy.Observation... phones) {
        return MultiPhoneProximityPolicy.canLock(phoneDeparture, requiresFob, fobAway,
                Arrays.asList(phones), NOW, STALE);
    }

    private static ProximityEvaluator fastPhone() {
        return new ProximityEvaluator(new ProximityEvaluator.Config(-12, -24,
                15_000, 4_000, STALE, 4_000, 2_000,
                ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR));
    }

    /** Matches the API's conservative conversion of current departure evidence. */
    private static MultiPhoneProximityPolicy.Observation forDeparture(ProximityEvaluator.Snapshot signal) {
        return new MultiPhoneProximityPolicy.Observation(true, true, signal.rawRssi != null,
                signal.departureEligible ? signal.state : TRANSITION, 16_000);
    }
}
