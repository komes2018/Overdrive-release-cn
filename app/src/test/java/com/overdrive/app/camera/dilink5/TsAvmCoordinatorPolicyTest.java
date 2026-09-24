package com.overdrive.app.camera.dilink5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class TsAvmCoordinatorPolicyTest {

    @Test
    public void connectionEpochRejectsStaleAndInvalidatedCompletions() {
        assertTrue(TsAvmCoordinator.acceptsCompletion(4L, 4L, false));
        assertFalse(TsAvmCoordinator.acceptsCompletion(3L, 4L, false));
        assertFalse(TsAvmCoordinator.acceptsCompletion(4L, 4L, true));
    }

    @Test
    public void cacheCannotSettleAcrossAnUnresolvedOppositeCall() {
        assertTrue(TsAvmCoordinator.canSettleAppliedState(
                Boolean.TRUE, true, false));
        assertFalse(TsAvmCoordinator.canSettleAppliedState(
                Boolean.TRUE, true, true));
        assertFalse(TsAvmCoordinator.canSettleAppliedState(
                Boolean.FALSE, true, false));
        assertFalse(TsAvmCoordinator.canSettleAppliedState(
                null, true, false));
        assertTrue(TsAvmCoordinator.blocksAppliedState(true, false));
        assertTrue(TsAvmCoordinator.blocksAppliedState(false, true));
        assertFalse(TsAvmCoordinator.blocksAppliedState(true, true));
    }

    @Test
    public void onlyTheLatestExpiredDirectionIsInternallyReconciled() {
        assertTrue(TsAvmCoordinator.shouldReconcileExpiredRequest(
                false, 7L, false, 7L, false));
        assertTrue(TsAvmCoordinator.shouldReconcileExpiredRequest(
                true, 8L, true, 8L, false));
        assertFalse(TsAvmCoordinator.shouldReconcileExpiredRequest(
                false, 7L, false, 8L, false));
        assertFalse(TsAvmCoordinator.shouldReconcileExpiredRequest(
                false, 7L, true, 7L, false));
        assertFalse(TsAvmCoordinator.shouldReconcileExpiredRequest(
                false, 7L, false, 7L, true));
    }

    @Test
    public void onlyRestoredUnconfirmedStartingSuppressesInternalRetry() {
        assertTrue(TsAvmCoordinator.shouldSuppressInternalReconcile(
                false,
                TsAvmCoordinator.AVM_OWNERSHIP_STARTING,
                false,
                false));
        assertFalse(TsAvmCoordinator.shouldSuppressInternalReconcile(
                false,
                TsAvmCoordinator.AVM_OWNERSHIP_STARTING,
                false,
                true));
        assertFalse(TsAvmCoordinator.shouldSuppressInternalReconcile(
                false,
                TsAvmCoordinator.AVM_OWNERSHIP_OWNED,
                false,
                false));
        assertFalse(TsAvmCoordinator.shouldSuppressInternalReconcile(
                true,
                TsAvmCoordinator.AVM_OWNERSHIP_STARTING,
                false,
                false));
    }

    @Test
    public void recoveryLaneIsStopOnlyAndExclusive() {
        assertEquals(
                TsAvmCoordinator.BINDER_LANE_PRIMARY,
                TsAvmCoordinator.selectBinderLane(true, false, false, false));
        assertEquals(
                TsAvmCoordinator.BINDER_LANE_PRIMARY,
                TsAvmCoordinator.selectBinderLane(false, false, false, false));
        assertEquals(
                TsAvmCoordinator.BINDER_LANE_RECOVERY,
                TsAvmCoordinator.selectBinderLane(false, true, true, false));
        assertEquals(
                TsAvmCoordinator.BINDER_LANE_NONE,
                TsAvmCoordinator.selectBinderLane(true, true, true, false));
        assertEquals(
                TsAvmCoordinator.BINDER_LANE_NONE,
                TsAvmCoordinator.selectBinderLane(true, false, false, true));
        assertEquals(
                TsAvmCoordinator.BINDER_LANE_NONE,
                TsAvmCoordinator.selectBinderLane(false, false, false, true));
    }

    @Test
    public void statusSemanticsAreDirectionSpecific() {
        for (int status : new int[] {-1, 0, 1, 3, 4, 5}) {
            assertFalse(TsAvmCoordinator.isDesiredAvmStatus(true, status));
        }
        assertTrue(TsAvmCoordinator.isDesiredAvmStatus(true, 2));

        for (int status : new int[] {-1, 0, 3, 4}) {
            assertTrue(TsAvmCoordinator.isDesiredAvmStatus(false, status));
        }
        for (int status : new int[] {1, 2, 5}) {
            assertFalse(TsAvmCoordinator.isDesiredAvmStatus(false, status));
        }

        for (int status : new int[] {-1, 0, 1, 2, 3, 4}) {
            assertFalse(TsAvmCoordinator.isFailureAvmStatus(status));
        }
        assertTrue(TsAvmCoordinator.isFailureAvmStatus(5));

        assertTrue(TsAvmCoordinator.isStartCommandStatus(3));
        for (int status : new int[] {-1, 0, 1, 2, 4, 5}) {
            assertFalse(TsAvmCoordinator.isStartCommandStatus(status));
        }
    }

    @Test
    public void physicalStopRetainsObservedAppStartAuthorityAcrossRetries() {
        assertFalse(TsAvmCoordinator.shouldIssuePhysicalStop(
                false, false, false));
        assertTrue(TsAvmCoordinator.shouldIssuePhysicalStop(
                true, false, false));
        assertTrue(TsAvmCoordinator.shouldIssuePhysicalStop(
                false, true, false));
        assertTrue(TsAvmCoordinator.shouldIssuePhysicalStop(
                false, false, true));
    }

    @Test
    public void issuedStartRemainsUnresolvedUntilMainThreadRetiresIt() {
        assertTrue(TsAvmCoordinator.isUnresolvedStartState(
                false, false, true, true));
        assertTrue(TsAvmCoordinator.isUnresolvedStartState(
                false, false, false, false));
        assertFalse(TsAvmCoordinator.isUnresolvedStartState(
                true, false, true, true));
        assertFalse(TsAvmCoordinator.isUnresolvedStartState(
                false, true, true, true));
    }

    @Test
    public void restoredOwnershipRequiresAFullQuietWindow() {
        assertTrue(TsAvmCoordinator.canConfirmRecoveredStop(
                false, 0L, 0L));
        assertFalse(TsAvmCoordinator.canConfirmRecoveredStop(
                true, 4_999L, 5_000L));
        assertTrue(TsAvmCoordinator.canConfirmRecoveredStop(
                true, 5_000L, 5_000L));
        assertFalse(TsAvmCoordinator.canConfirmRecoveredStop(
                true, 10_000L, 0L));
    }

    @Test
    public void failedPersistenceCannotAdvanceOwnership() {
        assertEquals(
                TsAvmCoordinator.AVM_OWNERSHIP_STARTING,
                TsAvmCoordinator.ownershipStateAfterCommit(
                        TsAvmCoordinator.AVM_OWNERSHIP_STARTING,
                        TsAvmCoordinator.AVM_OWNERSHIP_OWNED,
                        false));
        assertEquals(
                TsAvmCoordinator.AVM_OWNERSHIP_OWNED,
                TsAvmCoordinator.ownershipStateAfterCommit(
                        TsAvmCoordinator.AVM_OWNERSHIP_STARTING,
                        TsAvmCoordinator.AVM_OWNERSHIP_OWNED,
                        true));
    }

    @Test
    public void dispatchReservesRecoveryTimeAndEachAttemptGetsFreshTime() {
        assertEquals(
                11_000L,
                TsAvmCoordinator.deadlineAfterDispatch(
                        5_000L, true, 1_000L));
        assertEquals(
                5_000L,
                TsAvmCoordinator.deadlineAfterDispatch(
                        5_000L, false, 1_000L));
        long first = TsAvmCoordinator.appRequestDeadline(100L);
        long second = TsAvmCoordinator.appRequestDeadline(600L);
        assertEquals(500L, second - first);
        assertEquals(
                TsAvmCoordinator.AVM_APP_REQUEST_TIMEOUT_MS,
                first - 100L);
    }

    @Test
    public void preflightAcceptsOnlyResponsiveNonErrorAvmStatuses() {
        for (int status : new int[] {-1, 0, 1, 2, 3, 4}) {
            assertTrue(TsAvmCoordinator.isResponsiveAvmStatus(status));
        }
        assertFalse(TsAvmCoordinator.isResponsiveAvmStatus(5));
        assertFalse(TsAvmCoordinator.isResponsiveAvmStatus(-2));
        assertFalse(TsAvmCoordinator.isResponsiveAvmStatus(6));
        assertTrue(
                TsAvmCoordinator.AVM_PREFLIGHT_APP_REQUEST_TIMEOUT_MS
                        < TsAvmCoordinator.AVM_APP_REQUEST_TIMEOUT_MS);
        assertTrue(
                TsAvmCoordinator.AVM_SERVICE_PROBE_TIMEOUT_MS
                        < TsAvmCoordinator
                                .AVM_PREFLIGHT_APP_REQUEST_TIMEOUT_MS);
    }

    @Test
    public void preflightRetriesTimeoutsAndExplicitFailuresWithinItsBound() {
        assertTrue(TsAvmCoordinator.shouldRetryPreflightProbe(null, 1));
        assertTrue(TsAvmCoordinator.shouldRetryPreflightProbe(
                Boolean.FALSE, 2));
        assertFalse(TsAvmCoordinator.shouldRetryPreflightProbe(
                Boolean.TRUE, 1));
        assertFalse(TsAvmCoordinator.shouldRetryPreflightProbe(
                Boolean.FALSE,
                TsAvmCoordinator.AVM_PREFLIGHT_DISPATCH_ATTEMPTS));
    }

    @Test
    public void preflightApiPreservesTransportTimeoutVersusRejection()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/TsAvmCoordinator.java");
        String probe = source.substring(
                source.indexOf(
                        "public static Boolean requestProbeResultInAppProcess()"),
                source.indexOf(
                        "static boolean shouldRetryPreflightProbe("));
        assertTrue(probe.contains("boolean explicitlyRejected = false;"));
        assertTrue(probe.contains(
                "if (Boolean.FALSE.equals(result))"));
        assertTrue(probe.contains(
                "return explicitlyRejected ? Boolean.FALSE : null;"));
    }

    @Test
    public void ownedStopIsIssuedBeforeStatusPolling() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/TsAvmCoordinator.java");
        assertOrdered(
                source,
                "if (!request.start",
                "shouldIssuePhysicalStop(",
                "service.stopAvm();",
                "while (SystemClock.elapsedRealtime()",
                "service.getAvmStatus();");
        assertTrue(source.contains("physicalCalls.add(call);"));
        assertOrdered(
                source,
                "avmForegroundConfirmed = true;",
                "unresolvedCall.startObserved = true;");
        assertTrue(source.contains("acceptsCompletion("));
        assertTrue(source.contains("hasUnresolvedOppositeCall("));
        assertTrue(source.contains("queueDesiredState();"));
    }

    private static String readRepositoryFile(String relativePath)
            throws IOException {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("Repository file not found: " + relativePath);
    }

    private static void assertOrdered(String text, String... values) {
        int position = -1;
        for (String value : values) {
            int next = text.indexOf(value, position + 1);
            assertTrue("Missing or out-of-order: " + value, next > position);
            position = next;
        }
    }
}
