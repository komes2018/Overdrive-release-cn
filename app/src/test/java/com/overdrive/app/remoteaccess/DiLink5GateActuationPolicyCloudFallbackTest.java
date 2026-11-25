package com.overdrive.app.remoteaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.DiLink5LockRail;

import org.junit.Test;

/**
 * 17 Oct cloud fallback actuator + closure naming: the pure helpers {@code RemoteAccessApiHandler}
 * drives on the daemon (which cannot run on the JVM). Style of {@code RemoteAccessApiHandlerGateDetailTest}.
 */
public class DiLink5GateActuationPolicyCloudFallbackTest {
    private static final long CLOUD_TIMEOUT_MS = 30_000L;
    private static final long DISPATCHED = 100_000L;

    private static BydDataCollector.DiLink5LockEvidence evidence(DiLink5LockRail rail, int state) {
        return new BydDataCollector.DiLink5LockEvidence(state, 1_000L, rail, state, 0L);
    }

    @Test
    public void di5ImmediateRuleFiresOnlyWhenTheTypedRailHoldsNoValidReading() {
        // typed=none: evidenceFor(TYPED_CABIN) returned null (disabled, INVALID or stale) → immediate.
        assertTrue(DiLink5GateActuationPolicy.cloudFallbackImmediate(true, null));
        // An unknown-state evidence object counts as none too.
        assertTrue(DiLink5GateActuationPolicy.cloudFallbackImmediate(true,
                BydDataCollector.DiLink5LockEvidence.unknown()));
        assertTrue(DiLink5GateActuationPolicy.cloudFallbackImmediate(true,
                evidence(DiLink5LockRail.TYPED_CABIN, BydDataCollector.DOOR_STATE_INVALID)));
        // A valid typed reading: the local writer can be confirmed by its own rail → wait for the window.
        assertFalse(DiLink5GateActuationPolicy.cloudFallbackImmediate(true,
                evidence(DiLink5LockRail.TYPED_CABIN, BydDataCollector.DOOR_STATE_LOCK)));
        assertFalse(DiLink5GateActuationPolicy.cloudFallbackImmediate(true,
                evidence(DiLink5LockRail.TYPED_CABIN, BydDataCollector.DOOR_STATE_UNLOCK)));
        // Never off Di5, whatever the evidence.
        assertFalse(DiLink5GateActuationPolicy.cloudFallbackImmediate(false, null));
        assertFalse(DiLink5GateActuationPolicy.cloudFallbackImmediate(false,
                BydDataCollector.DiLink5LockEvidence.unknown()));
    }

    @Test
    public void detailSuffixNamesPathAndRailWithoutNumbers() {
        assertEquals("SDK", DiLink5GateActuationPolicy.PATH_SDK);
        assertEquals("SDK_THEN_CLOUD", DiLink5GateActuationPolicy.PATH_SDK_THEN_CLOUD);
        assertEquals("CLOUD", DiLink5GateActuationPolicy.PATH_CLOUD);
        assertEquals(" (path=SDK)", DiLink5GateActuationPolicy.cloudFallbackDetail(
                DiLink5GateActuationPolicy.PATH_SDK, null));
        assertEquals(" (path=SDK_THEN_CLOUD rail=carsvc)", DiLink5GateActuationPolicy.cloudFallbackDetail(
                DiLink5GateActuationPolicy.PATH_SDK_THEN_CLOUD, DiLink5LockRail.CARSVC));
        assertEquals(" (path=CLOUD rail=typed-cabin)", DiLink5GateActuationPolicy.cloudFallbackDetail(
                DiLink5GateActuationPolicy.PATH_CLOUD, DiLink5LockRail.TYPED_CABIN));
        assertEquals(" (path=CLOUD rail=ota)", DiLink5GateActuationPolicy.cloudFallbackDetail(
                DiLink5GateActuationPolicy.PATH_CLOUD, DiLink5LockRail.OTA));
        for (String path : new String[] {DiLink5GateActuationPolicy.PATH_SDK,
                DiLink5GateActuationPolicy.PATH_SDK_THEN_CLOUD, DiLink5GateActuationPolicy.PATH_CLOUD}) {
            assertFalse(DiLink5GateActuationPolicy.cloudFallbackDetail(path, null).matches(".*\\d.*"));
            for (DiLink5LockRail rail : DiLink5LockRail.values())
                assertFalse(DiLink5GateActuationPolicy.cloudFallbackDetail(path, rail).matches(".*\\d.*"));
        }
    }

    @Test
    public void cloudLegGetsTheDecisionBudgetLeftMinusTheLocalWindowStillDue() {
        assertEquals(30_000L, DiLink5GateActuationPolicy.DECISION_BUDGET_MS);
        assertEquals(1_000L, DiLink5GateActuationPolicy.CLOUD_LEG_MIN_BUDGET_MS);
        // Di5 immediate leg 0.5 s after dispatch, reserving the pinned 12 s carsvc window: 17.5 s.
        assertEquals(17_500L, DiLink5GateActuationPolicy.cloudFallbackCloudTimeoutMs(
                DISPATCHED, DISPATCHED + 500L, 12_000L, CLOUD_TIMEOUT_MS));
        // Non-Di5 unlock after the ≈8.5 s legacy readback/re-send sequence, reserving the 4 s window: 17.5 s.
        assertEquals(17_500L, DiLink5GateActuationPolicy.cloudFallbackCloudTimeoutMs(
                DISPATCHED, DISPATCHED + 8_500L, 4_000L, CLOUD_TIMEOUT_MS));
        // Refused local leg 0.5 s after dispatch, non-Di5: 25.5 s (never more than the command's own 30 s).
        assertEquals(25_500L, DiLink5GateActuationPolicy.cloudFallbackCloudTimeoutMs(
                DISPATCHED, DISPATCHED + 500L, 4_000L, CLOUD_TIMEOUT_MS));
        assertEquals(10_000L, DiLink5GateActuationPolicy.cloudFallbackCloudTimeoutMs(
                DISPATCHED, DISPATCHED, 0L, 10_000L));
        // Clock regression counts as no time spent; a negative reserve counts as none.
        assertEquals(30_000L, DiLink5GateActuationPolicy.cloudFallbackCloudTimeoutMs(
                DISPATCHED, DISPATCHED - 5_000L, -1L, CLOUD_TIMEOUT_MS));
        // Budget gone: 0, never negative.
        assertEquals(0L, DiLink5GateActuationPolicy.cloudFallbackCloudTimeoutMs(
                DISPATCHED, DISPATCHED + 30_000L, 0L, CLOUD_TIMEOUT_MS));
        assertEquals(0L, DiLink5GateActuationPolicy.cloudFallbackCloudTimeoutMs(
                DISPATCHED, DISPATCHED + 20_000L, 12_000L, CLOUD_TIMEOUT_MS));
    }

    @Test
    public void secondWindowIsTheRailsWindowCutToTheThirtySecondDecisionBudget() {
        // Non-Di5 (no rail) 12 s after the SDK dispatch (8.5 s sequence + a 3.5 s cloud ack): the full 4 s.
        assertEquals(4_000L, DiLink5GateActuationPolicy.cloudFallbackSecondWindowMs(
                null, DISPATCHED, DISPATCHED + 12_000L));
        // carsvc 12 s after dispatch: the full 12 s window (18 s left).
        assertEquals(12_000L, DiLink5GateActuationPolicy.cloudFallbackSecondWindowMs(
                DiLink5LockRail.CARSVC, DISPATCHED, DISPATCHED + 12_000L));
        // carsvc 20 s after dispatch: only 10 s left of the 30 s budget.
        assertEquals(10_000L, DiLink5GateActuationPolicy.cloudFallbackSecondWindowMs(
                DiLink5LockRail.CARSVC, DISPATCHED, DISPATCHED + 20_000L));
        // Non-Di5 after 28 s: 2 s of the 4 s window.
        assertEquals(2_000L, DiLink5GateActuationPolicy.cloudFallbackSecondWindowMs(
                null, DISPATCHED, DISPATCHED + 28_000L));
        // Budget exhausted: no second window at all; never negative.
        assertEquals(0L, DiLink5GateActuationPolicy.cloudFallbackSecondWindowMs(
                DiLink5LockRail.CARSVC, DISPATCHED, DISPATCHED + 30_000L));
        assertEquals(0L, DiLink5GateActuationPolicy.cloudFallbackSecondWindowMs(
                DiLink5LockRail.CARSVC, DISPATCHED, DISPATCHED + 50_000L));
        // Clock regression counts as no time spent: the full rail window.
        assertEquals(8_000L, DiLink5GateActuationPolicy.cloudFallbackSecondWindowMs(
                DiLink5LockRail.OTA, DISPATCHED, DISPATCHED - 5_000L));
        // Whole-decision worst case: cloud bound + reserved window never exceed the budget.
        for (DiLink5LockRail rail : new DiLink5LockRail[] {null, DiLink5LockRail.TYPED_CABIN,
                DiLink5LockRail.OTA, DiLink5LockRail.CARSVC}) {
            for (long spent = 0L; spent <= 31_000L; spent += 500L) {
                long reserve = DiLink5GateActuationPolicy.confirmationWindowMs(rail);
                long cloud = DiLink5GateActuationPolicy.cloudFallbackCloudTimeoutMs(
                        DISPATCHED, DISPATCHED + spent, reserve, CLOUD_TIMEOUT_MS);
                long second = DiLink5GateActuationPolicy.cloudFallbackSecondWindowMs(
                        rail, DISPATCHED, DISPATCHED + spent + cloud);
                assertTrue(rail + "@" + spent, spent + cloud + second <= DiLink5GateActuationPolicy.DECISION_BUDGET_MS
                        || spent >= DiLink5GateActuationPolicy.DECISION_BUDGET_MS);
            }
        }
    }

    private static final int[] CLOSED = {0, 0, 0, 0, 0, 0, 0};

    private static String verdict(boolean settingsCurrent, boolean switchOn, boolean dryRun, boolean current,
                                  int power, boolean lock, int[] doors, int local, int target, long budget) {
        return DiLink5GateActuationPolicy.cloudLegSkipReason(settingsCurrent, switchOn, dryRun, current,
                "decision revoked", power, lock, doors, local, target, budget);
    }

    /** Product-owner tests: an 8 s-old, still-current decision fires; revocation, power or a door skips. */
    @Test
    public void cloudLegFiresForAStillCurrentDecisionWhateverItsAge() {
        // An 8 s-old UNLOCK decision with no newer signal (current=true): the cloud leg goes out. The
        // verdict has no observation-age input at all; only the budget left matters.
        long budgetAfterEightSeconds = DiLink5GateActuationPolicy.cloudFallbackCloudTimeoutMs(
                DISPATCHED, DISPATCHED + 8_000L, 4_000L, CLOUD_TIMEOUT_MS);
        assertEquals(18_000L, budgetAfterEightSeconds);
        assertNull(verdict(true, true, false, true, 0, false, null,
                BydDataCollector.DOOR_STATE_LOCK, BydDataCollector.DOOR_STATE_UNLOCK, budgetAfterEightSeconds));
        // The same for a LOCK with every closure closed.
        assertNull(verdict(true, true, false, true, 0, true, CLOSED,
                BydDataCollector.DOOR_STATE_UNLOCK, BydDataCollector.DOOR_STATE_LOCK, budgetAfterEightSeconds));
        // A decision revoked by a newer signal (arrival then departure, or vice versa): skipped, with the
        // source's own reason when it has one.
        assertEquals("decision revoked", verdict(true, true, false, false, 0, false, null,
                BydDataCollector.DOOR_STATE_LOCK, BydDataCollector.DOOR_STATE_UNLOCK, budgetAfterEightSeconds));
        assertEquals("Automatic action paused inside a selected safe zone",
                DiLink5GateActuationPolicy.cloudLegSkipReason(true, true, false, false,
                        "Automatic action paused inside a selected safe zone", 0, false, null,
                        BydDataCollector.DOOR_STATE_LOCK, BydDataCollector.DOOR_STATE_UNLOCK, budgetAfterEightSeconds));
        assertEquals("decision revoked", DiLink5GateActuationPolicy.cloudLegSkipReason(true, true, false, false,
                null, 0, false, null, BydDataCollector.DOOR_STATE_LOCK, BydDataCollector.DOOR_STATE_UNLOCK,
                budgetAfterEightSeconds));
        // Vehicle power came on between the legs: skipped.
        assertEquals("vehicle no longer off", verdict(true, true, false, true, 1, false, null,
                BydDataCollector.DOOR_STATE_LOCK, BydDataCollector.DOOR_STATE_UNLOCK, budgetAfterEightSeconds));
        assertEquals("vehicle no longer off", verdict(true, true, false, true, -1, true, CLOSED,
                BydDataCollector.DOOR_STATE_UNLOCK, BydDataCollector.DOOR_STATE_LOCK, budgetAfterEightSeconds));
        // A door opened between the legs (LOCK only): skipped, naming the closure; unknown closures too.
        assertEquals("Close all doors, hood and trunk: front-right door open", verdict(true, true, false, true,
                0, true, new int[] {0, 1, 0, 0, 0, 0, 0}, BydDataCollector.DOOR_STATE_UNLOCK,
                BydDataCollector.DOOR_STATE_LOCK, budgetAfterEightSeconds));
        assertEquals("Close all doors, hood and trunk: trunk unknown", verdict(true, true, false, true,
                0, true, new int[] {0, 0, 0, 0, 0, -1, 0}, BydDataCollector.DOOR_STATE_UNLOCK,
                BydDataCollector.DOOR_STATE_LOCK, budgetAfterEightSeconds));
        assertEquals("door and lid state unavailable", verdict(true, true, false, true, 0, true, null,
                BydDataCollector.DOOR_STATE_UNLOCK, BydDataCollector.DOOR_STATE_LOCK, budgetAfterEightSeconds));
        assertEquals("door and lid state unavailable", verdict(true, true, false, true, 0, true, new int[] {0, 0},
                BydDataCollector.DOOR_STATE_UNLOCK, BydDataCollector.DOOR_STATE_LOCK, budgetAfterEightSeconds));
        // An UNLOCK never looks at the closures (an open door is no reason to keep the car locked).
        assertNull(verdict(true, true, false, true, 0, false, new int[] {1, 1, 1, 1, 1, 1, 1},
                BydDataCollector.DOOR_STATE_LOCK, BydDataCollector.DOOR_STATE_UNLOCK, budgetAfterEightSeconds));
    }

    @Test
    public void cloudLegSkipReasonsCoverSettingsSwitchLearningDuplicateAndBudgetInOrder() {
        int lockState = BydDataCollector.DOOR_STATE_LOCK, unlockState = BydDataCollector.DOOR_STATE_UNLOCK;
        assertEquals("settings changed", verdict(false, true, false, true, 0, false, null, lockState, unlockState, 20_000L));
        assertEquals("cloud fallback switched off", verdict(true, false, false, true, 0, false, null, lockState, unlockState, 20_000L));
        assertEquals("Learning mode", verdict(true, true, true, true, 0, false, null, lockState, unlockState, 20_000L));
        // Duplicate suppression: the LOCAL before-state already shows the target → no cloud command.
        assertEquals(DiLink5GateActuationPolicy.CLOUD_LEG_SKIP_LOCAL_AT_TARGET,
                verdict(true, true, false, true, 0, false, null, unlockState, unlockState, 20_000L));
        assertEquals("local state already at target",
                verdict(true, true, false, true, 0, true, CLOSED, lockState, lockState, 20_000L));
        // An unknown local state is NOT a skip reason (product decision: dropped); the cloud leg goes out.
        assertNull(verdict(true, true, false, true, 0, false, null, BydDataCollector.DOOR_STATE_INVALID, unlockState, 20_000L));
        // Budget: under the 1 s floor → skipped; at the floor → sent.
        assertEquals("decision budget exhausted", verdict(true, true, false, true, 0, false, null, lockState, unlockState, 999L));
        assertEquals("decision budget exhausted", verdict(true, true, false, true, 0, false, null, lockState, unlockState, 0L));
        assertNull(verdict(true, true, false, true, 0, false, null, lockState, unlockState, 1_000L));
        // Precedence: settings first, then the switch, Learning mode, revocation, power, closures, duplicate, budget.
        assertEquals("settings changed", verdict(false, false, true, false, 1, true, null, lockState, lockState, 0L));
        assertEquals("cloud fallback switched off", verdict(true, false, true, false, 1, true, null, lockState, lockState, 0L));
        assertEquals("Learning mode", verdict(true, true, true, false, 1, true, null, lockState, lockState, 0L));
        assertEquals("decision revoked", verdict(true, true, false, false, 1, true, null, lockState, lockState, 0L));
        assertEquals("vehicle no longer off", verdict(true, true, false, true, 1, true, null, lockState, lockState, 0L));
        assertEquals("door and lid state unavailable", verdict(true, true, false, true, 0, true, null, lockState, lockState, 0L));
        assertEquals("local state already at target", verdict(true, true, false, true, 0, true, CLOSED, lockState, lockState, 0L));
        assertEquals("decision budget exhausted", verdict(true, true, false, true, 0, true, CLOSED, unlockState, lockState, 0L));
        // No reason ever carries a digit (recordAction dedup).
        for (String reason : new String[] {"settings changed", "cloud fallback switched off", "Learning mode",
                "vehicle no longer off", "door and lid state unavailable", "decision budget exhausted",
                DiLink5GateActuationPolicy.CLOUD_LEG_SKIP_LOCAL_AT_TARGET})
            assertFalse(reason, reason.matches(".*\\d.*"));
    }

    @Test
    public void cloudLegSummaryCoversEveryRouterOutcome() {
        assertEquals("cloud not configured",
                DiLink5GateActuationPolicy.cloudLegSummary("AUTH_REQUIRED", "x", false, "online"));
        assertEquals("cloud leg not available for this command",
                DiLink5GateActuationPolicy.cloudLegSummary("NOT_SUPPORTED", "x", false, "online"));
        assertEquals("blocked: vehicle not confirmed parked",
                DiLink5GateActuationPolicy.cloudLegSummary("BLOCKED_DRIVING", "x", false, "online"));
        assertEquals("rate limited: Too many requests",
                DiLink5GateActuationPolicy.cloudLegSummary("RATE_LIMITED", "Too many requests", false, "online"));
        assertEquals("acknowledged",
                DiLink5GateActuationPolicy.cloudLegSummary("SUCCESS", "Cloud sent", false, "online"));
        // FAILED / VEHICLE_UNREACHABLE: timeout wins, then the cloud's offline label, else the message.
        assertEquals("timeout",
                DiLink5GateActuationPolicy.cloudLegSummary("FAILED", "Cloud failed", true, "offline"));
        assertEquals("car offline in BYD cloud",
                DiLink5GateActuationPolicy.cloudLegSummary("FAILED", "Cloud failed", false, "offline"));
        assertEquals("car offline in BYD cloud",
                DiLink5GateActuationPolicy.cloudLegSummary("VEHICLE_UNREACHABLE", "x", false, "offline"));
        assertEquals("unreachable: Cloud failed",
                DiLink5GateActuationPolicy.cloudLegSummary("FAILED", "Cloud failed", false, "online"));
        assertEquals("unreachable: Cloud failed",
                DiLink5GateActuationPolicy.cloudLegSummary("VEHICLE_UNREACHABLE", "Cloud failed", false, "unknown"));
        // Null message and unknown outcome names never throw.
        assertEquals("unreachable: ",
                DiLink5GateActuationPolicy.cloudLegSummary("FAILED", null, false, null));
        assertEquals("rate limited: ",
                DiLink5GateActuationPolicy.cloudLegSummary("RATE_LIMITED", null, false, null));
        assertEquals("unreachable: odd",
                DiLink5GateActuationPolicy.cloudLegSummary("SOMETHING_ELSE", "odd", false, "online"));
    }

    @Test
    public void doorBlockReasonNamesEachClosureOpenOrUnknownInIndexOrder() {
        String[] names = {"front-left door", "front-right door", "rear-left door", "rear-right door", "hood", "trunk"};
        for (int i = 0; i < 6; i++) {
            int[] open = new int[7];
            open[i] = 1;
            assertEquals("Close all doors, hood and trunk: " + names[i] + " open",
                    DiLink5GateActuationPolicy.doorBlockReason(open));
            int[] unknown = new int[7];
            unknown[i] = -1;
            assertEquals("Close all doors, hood and trunk: " + names[i] + " unknown",
                    DiLink5GateActuationPolicy.doorBlockReason(unknown));
            // Any other non-zero value is "unknown" too (the old loop blocked on != 0).
            int[] odd = new int[7];
            odd[i] = 7;
            assertEquals("Close all doors, hood and trunk: " + names[i] + " unknown",
                    DiLink5GateActuationPolicy.doorBlockReason(odd));
        }
        // Several at once, joined in index order.
        assertEquals("Close all doors, hood and trunk: front-right door open, hood unknown, trunk open",
                DiLink5GateActuationPolicy.doorBlockReason(new int[] {0, 1, 0, 0, -1, 1, 0}));
        // All six closed → no block, whatever the fuel cap (index 6) says — exactly as the old loop.
        assertNull(DiLink5GateActuationPolicy.doorBlockReason(new int[] {0, 0, 0, 0, 0, 0, 0}));
        assertNull(DiLink5GateActuationPolicy.doorBlockReason(new int[] {0, 0, 0, 0, 0, 0, 1}));
        assertNull(DiLink5GateActuationPolicy.doorBlockReason(new int[] {0, 0, 0, 0, 0, 0, -1}));
        assertNull(DiLink5GateActuationPolicy.doorBlockReason(new int[] {0, 0, 0, 0, 0, 0}));
        // Same predicate as the old loop for every pattern: blocks iff any of the first six is non-zero.
        for (int mask = 0; mask < 64; mask++) {
            int[] doors = new int[7];
            for (int i = 0; i < 6; i++) doors[i] = (mask >> i) & 1;
            assertEquals(mask != 0, DiLink5GateActuationPolicy.doorBlockReason(doors) != null);
        }
        // The message never carries a digit (recordAction dedup; D1 rule).
        assertFalse(DiLink5GateActuationPolicy.doorBlockReason(new int[] {1, -1, 1, -1, 1, -1, 1}).matches(".*\\d.*"));
    }

    // ── P5: dashboard Lock/Unlock verdict table (plan §2.8) ──────────────────────────────────────

    private static final DiLink5GateActuationPolicy.Confirmation CONFIRMED =
            DiLink5GateActuationPolicy.Confirmation.CONFIRMED;
    private static final DiLink5GateActuationPolicy.Confirmation UNCONFIRMED =
            DiLink5GateActuationPolicy.Confirmation.UNCONFIRMED;

    private static DiLink5GateActuationPolicy.DoorLockVerdict verdict(String sdk,
            DiLink5GateActuationPolicy.Confirmation first, boolean on, String cloud,
            DiLink5GateActuationPolicy.Confirmation second, boolean timedOut, String online) {
        return DiLink5GateActuationPolicy.manualDoorLockVerdict(sdk, first, on, cloud, second, timedOut, online);
    }

    private static void assertRow(DiLink5GateActuationPolicy.DoorLockVerdict v, String outcome, String path,
                                  String baseKey, String suffix, boolean success) {
        assertEquals(outcome, v.outcome);
        assertEquals(path, v.path);
        assertEquals(baseKey, v.baseKey);
        assertEquals(suffix, v.suffix);
        assertEquals(success, v.success);
        assertFalse(v.passThrough());
        assertEquals(success ? "local" : "none", v.confirmation());
    }

    @Test
    public void manualDoorLockVerdictSucceedsOnlyWhenALocalReadbackConfirmed() {
        // Local leg accepted and the car read back the target: success on the local path.
        assertRow(verdict("SUCCESS", CONFIRMED, true, null, null, false, "online"),
                "success", "local", "local_sent", " · confirmed by the car", true);
        assertRow(verdict("SUCCESS", CONFIRMED, false, null, null, false, "unknown"),
                "success", "local", "local_sent", " · confirmed by the car", true);
        // Accepted, not confirmed, switch off → unconfirmed on the local path (never SUCCESS on rc=0).
        assertRow(verdict("SUCCESS", UNCONFIRMED, false, null, null, false, "online"),
                "unconfirmed", "local", "local_sent", " · not confirmed by the car", false);
        // Switch on but the leg did not run (defensive): say so.
        assertRow(verdict("SUCCESS", UNCONFIRMED, true, null, null, false, "online"),
                "unconfirmed", "local", "local_sent", " · not confirmed by the car; cloud command not sent", false);
        // Cloud acknowledged and the second LOCAL window confirmed: success, local-then-cloud.
        assertRow(verdict("SUCCESS", UNCONFIRMED, true, "SUCCESS", CONFIRMED, false, "online"),
                "success", "local-then-cloud", "local_unavailable_used_cloud", " · confirmed by the car", true);
        // Cloud acknowledged, no local readback: cloud_acknowledged, NEVER success.
        assertRow(verdict("SUCCESS", UNCONFIRMED, true, "SUCCESS", UNCONFIRMED, false, "online"),
                "cloud_acknowledged", "local-then-cloud", "cloud_sent",
                " · cloud acknowledged; not confirmed by the car", false);
        assertRow(verdict("SUCCESS", UNCONFIRMED, true, "SUCCESS", null, false, "online"),
                "cloud_acknowledged", "local-then-cloud", "cloud_sent",
                " · cloud acknowledged; not confirmed by the car", false);
        // Nothing was sent by the cloud leg (not configured / not available): unconfirmed, says why.
        assertRow(verdict("SUCCESS", UNCONFIRMED, true, "AUTH_REQUIRED", null, false, "online"),
                "unconfirmed", "local", "local_sent", " · not confirmed by the car; cloud not configured", false);
        assertRow(verdict("SUCCESS", UNCONFIRMED, true, "NOT_SUPPORTED", null, false, "online"),
                "unconfirmed", "local", "local_sent",
                " · not confirmed by the car; cloud leg not available for this command", false);
        // Cloud command sent and failed: failed on local-then-cloud with the cloud_failed base text;
        // timeout and the cloud's own offline label add information, a generic failure adds none.
        assertRow(verdict("SUCCESS", UNCONFIRMED, true, "FAILED", null, true, "offline"),
                "failed", "local-then-cloud", "cloud_failed", " · not confirmed by the car; timeout", false);
        assertRow(verdict("SUCCESS", UNCONFIRMED, true, "FAILED", null, false, "offline"),
                "failed", "local-then-cloud", "cloud_failed",
                " · not confirmed by the car; car offline in BYD cloud", false);
        assertRow(verdict("SUCCESS", UNCONFIRMED, true, "VEHICLE_UNREACHABLE", null, false, "offline"),
                "failed", "local-then-cloud", "cloud_failed",
                " · not confirmed by the car; car offline in BYD cloud", false);
        assertRow(verdict("SUCCESS", UNCONFIRMED, true, "FAILED", null, false, "online"),
                "failed", "local-then-cloud", "cloud_failed", " · not confirmed by the car", false);
        assertRow(verdict("SUCCESS", UNCONFIRMED, true, "FAILED", null, false, null),
                "failed", "local-then-cloud", "cloud_failed", " · not confirmed by the car", false);
    }

    @Test
    public void manualDoorLockVerdictAfterARefusedLocalLegKeepsTodaysTextsUnlessTheCarConfirms() {
        // Refused local leg, cloud not configured / not available / not run: today's local_failed.
        assertRow(verdict("FAILED", null, true, "AUTH_REQUIRED", null, false, "online"),
                "failed", "local", "local_failed", "", false);
        assertRow(verdict("FAILED", null, false, "AUTH_REQUIRED", null, false, "online"),
                "failed", "local", "local_failed", "", false);
        assertRow(verdict("FAILED", null, true, "NOT_SUPPORTED", null, false, "online"),
                "failed", "local", "local_failed", "", false);
        assertRow(verdict("FAILED", null, true, null, null, false, "online"),
                "failed", "local", "local_failed", "", false);
        assertRow(verdict("NOT_SUPPORTED", null, true, null, null, false, "online"),
                "failed", "local", "local_failed", "", false);
        // Cloud acknowledged after a refused local leg: only a LOCAL readback makes it a success.
        assertRow(verdict("FAILED", null, true, "SUCCESS", CONFIRMED, false, "online"),
                "success", "local-then-cloud", "local_unavailable_used_cloud", " · confirmed by the car", true);
        assertRow(verdict("FAILED", null, false, "SUCCESS", CONFIRMED, false, "online"),
                "success", "local-then-cloud", "local_unavailable_used_cloud", " · confirmed by the car", true);
        assertRow(verdict("FAILED", null, true, "SUCCESS", UNCONFIRMED, false, "online"),
                "cloud_acknowledged", "local-then-cloud", "cloud_sent",
                " · cloud acknowledged; not confirmed by the car", false);
        // Both legs failed: cloud_failed, labelled with timeout / offline when known.
        assertRow(verdict("FAILED", null, true, "FAILED", null, true, "online"),
                "failed", "local-then-cloud", "cloud_failed", " · timeout", false);
        assertRow(verdict("FAILED", null, true, "FAILED", null, false, "offline"),
                "failed", "local-then-cloud", "cloud_failed", " · car offline in BYD cloud", false);
        assertRow(verdict("FAILED", null, true, "FAILED", null, false, "online"),
                "failed", "local-then-cloud", "cloud_failed", "", false);
        assertRow(verdict("FAILED", null, true, "VEHICLE_UNREACHABLE", null, false, "unknown"),
                "failed", "local-then-cloud", "cloud_failed", "", false);
    }

    @Test
    public void manualDoorLockVerdictPassesRateLimitedAndBlockedDrivingThrough() {
        for (String name : new String[] {"RATE_LIMITED", "BLOCKED_DRIVING"}) {
            for (String sdk : new String[] {"SUCCESS", "FAILED"}) {
                DiLink5GateActuationPolicy.DoorLockVerdict v = verdict(sdk,
                        "SUCCESS".equals(sdk) ? UNCONFIRMED : null, true, name, null, false, "online");
                assertTrue(name, v.passThrough());
                assertEquals(name.toLowerCase(java.util.Locale.ROOT), v.outcome);
                assertNull(v.path);
                assertNull(v.baseKey);
                assertEquals("", v.suffix);
                assertFalse(v.success);
                assertEquals("none", v.confirmation());
            }
            // From the local leg itself (the handler returns today's response before any readback).
            DiLink5GateActuationPolicy.DoorLockVerdict local = verdict(name, null, true, null, null, false, "online");
            assertTrue(local.passThrough());
            assertEquals(name.toLowerCase(java.util.Locale.ROOT), local.outcome);
            assertFalse(local.success);
        }
        // A pass-through row is the ONLY row without a base key; every other row names its base text.
        assertFalse(verdict("SUCCESS", CONFIRMED, true, null, null, false, "online").passThrough());
        assertFalse(verdict("FAILED", null, true, "FAILED", null, false, "online").passThrough());
    }

    @Test
    public void manualDoorLockVerdictSuccessIsExactlyALocalConfirmationAcrossTheWholeTable() {
        String[] sdkOutcomes = {"SUCCESS", "FAILED", "NOT_SUPPORTED", "RATE_LIMITED", "BLOCKED_DRIVING"};
        String[] cloudOutcomes = {null, "SUCCESS", "FAILED", "VEHICLE_UNREACHABLE", "AUTH_REQUIRED",
                "NOT_SUPPORTED", "RATE_LIMITED", "BLOCKED_DRIVING"};
        DiLink5GateActuationPolicy.Confirmation[] windows = {null, CONFIRMED, UNCONFIRMED};
        String[] online = {null, "online", "offline", "unknown"};
        for (String sdk : sdkOutcomes) for (String cloud : cloudOutcomes)
            for (DiLink5GateActuationPolicy.Confirmation first : windows)
                for (DiLink5GateActuationPolicy.Confirmation second : windows)
                    for (boolean on : new boolean[] {true, false}) for (boolean timedOut : new boolean[] {true, false})
                        for (String state : online) {
                            DiLink5GateActuationPolicy.DoorLockVerdict v =
                                    verdict(sdk, first, on, cloud, second, timedOut, state);
                            String key = sdk + "/" + first + "/" + on + "/" + cloud + "/" + second + "/" + timedOut + "/" + state;
                            // success ⇔ a LOCAL window confirmed (first after the local leg, or second
                            // after an acknowledged cloud leg); never from any router outcome alone.
                            boolean localConfirmed = ("SUCCESS".equals(sdk) && first == CONFIRMED)
                                    || ("SUCCESS".equals(cloud) && second == CONFIRMED);
                            assertEquals(key, localConfirmed, v.success);
                            assertEquals(key, v.success, "success".equals(v.outcome));
                            assertEquals(key, v.success ? "local" : "none", v.confirmation());
                            if ("cloud_acknowledged".equals(v.outcome)) assertFalse(key, v.success);
                            if (v.passThrough()) {
                                assertNull(key, v.path);
                                assertFalse(key, v.success);
                            } else {
                                assertTrue(key, "local".equals(v.path) || "local-then-cloud".equals(v.path));
                                assertTrue(key, v.baseKey.matches("local_sent|local_failed|cloud_failed|local_unavailable_used_cloud|cloud_sent"));
                            }
                            assertTrue(key, v.outcome.matches("success|unconfirmed|cloud_acknowledged|failed|rate_limited|blocked_driving"));
                            assertFalse(key, v.suffix.matches(".*\\d.*"));
                            assertTrue(key, v.suffix.isEmpty() || v.suffix.startsWith(" · "));
                        }
    }
}
