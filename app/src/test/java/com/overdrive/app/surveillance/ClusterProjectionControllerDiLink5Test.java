package com.overdrive.app.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClusterProjectionControllerDiLink5Test {

    @Test
    public void diLink5OpcodeGateIncludesLifecycleOnly() {
        for (int op : new int[] {0, 16, 18}) {
            assertTrue(
                    ClusterProjectionController
                            .isAllowedDiLink5ContainerOpcode(op));
        }
        for (int op : new int[] {-1, 1, 17, 29, 30, 31, 35}) {
            assertFalse(
                    ClusterProjectionController
                            .isAllowedDiLink5ContainerOpcode(op));
        }
    }

    @Test
    public void inactiveModeCleanupAuthorityCannotEnableProjection() {
        assertTrue(ClusterProjectionController
                .isAllowedDiLink5CleanupOpcode(0));
        assertTrue(ClusterProjectionController
                .isAllowedDiLink5CleanupOpcode(18));
        assertFalse(ClusterProjectionController
                .isAllowedDiLink5CleanupOpcode(16));
    }

    @Test
    public void nativeDiLink5ResultRejectsOnlyNegativeValues() {
        assertTrue(
                ClusterProjectionController
                        .isAcceptedDiLink5ContainerResult(0));
        assertTrue(
                ClusterProjectionController
                        .isAcceptedDiLink5ContainerResult(1));
        assertFalse(
                ClusterProjectionController
                        .isAcceptedDiLink5ContainerResult(-1));
    }

    @Test
    public void serviceCallParserReadsTheReturnedParcelWord() {
        assertEquals(
                Integer.valueOf(1),
                ClusterProjectionController.parseDiLink5ServiceCallResult(
                        "Result: Parcel(00000000 00000001 '........')"));
        assertEquals(
                Integer.valueOf(-1),
                ClusterProjectionController.parseDiLink5ServiceCallResult(
                        "Result: Parcel(00000000 ffffffff '........')"));
        assertNull(
                ClusterProjectionController.parseDiLink5ServiceCallResult(
                        "Result: Parcel(00000000 '....')"));
        assertNull(
                ClusterProjectionController.parseDiLink5ServiceCallResult(
                        "Result: Parcel(fffffffe 00000001 '........')"));
        assertEquals(
                Integer.valueOf(0),
                ClusterProjectionController.parseDiLink5ServiceCallStatus(
                        "Result: Parcel(00000000 '....')"));
        assertEquals(
                Integer.valueOf(-2),
                ClusterProjectionController.parseDiLink5ServiceCallStatus(
                        "Result: Parcel(fffffffe 00000001 '........')"));
        assertNull(
                ClusterProjectionController.parseDiLink5ServiceCallResult(
                        "Service AutoContainer does not exist"));
    }

    @Test
    public void legacyServiceCallRequiresAConfirmedBinderSuccess() {
        assertTrue(
                ClusterProjectionController
                        .isAcceptedLegacyServiceCallResult(
                                0,
                                false,
                                "Result: Parcel(00000000 '....')"));
        assertTrue(
                ClusterProjectionController
                        .isAcceptedLegacyServiceCallResult(
                                0,
                                false,
                                "Result: Parcel(00000000 00000001 '........')"));
        assertFalse(
                ClusterProjectionController
                        .isAcceptedLegacyServiceCallResult(
                                0,
                                false,
                                "Result: Parcel(00000000 ffffffff '........')"));
        assertFalse(
                ClusterProjectionController
                        .isAcceptedLegacyServiceCallResult(
                                0,
                                false,
                                "Result: Parcel(fffffffe 00000001 '........')"));
        assertFalse(
                ClusterProjectionController
                        .isAcceptedLegacyServiceCallResult(
                                1,
                                false,
                                "Result: Parcel(00000000 '....')"));
        assertFalse(
                ClusterProjectionController
                        .isAcceptedLegacyServiceCallResult(
                                0,
                                true,
                                "Result: Parcel(00000000 '....')"));
        assertFalse(
                ClusterProjectionController
                        .isAcceptedLegacyServiceCallResult(
                                0,
                                false,
                                "service call returned no parcel"));
    }

    @Test
    public void onlyExplicitMissingServiceOutputAllowsAliasFallback() {
        assertTrue(
                ClusterProjectionController
                        .isDiLink5ServiceUnavailableOutput(
                                "Service AutoContainer does not exist",
                                "AutoContainer"));
        assertTrue(
                ClusterProjectionController
                        .isDiLink5ServiceUnavailableOutput(
                                "Service auto_container: not found",
                                "auto_container"));
        assertFalse(
                ClusterProjectionController
                        .isDiLink5ServiceUnavailableOutput(
                                "binder transaction failed with exit 1",
                                "AutoContainer"));
        assertFalse(
                ClusterProjectionController
                        .isDiLink5ServiceUnavailableOutput(
                                "Result: Parcel()",
                                "AutoContainer"));
    }

    @Test
    public void onlyProjectionEnableMayBootstrapTheContainerService() {
        assertTrue(ClusterProjectionController
                .shouldBootstrapDiLink5ContainerService(
                        16,
                        ClusterProjectionController.DiLink5CommandResult
                                .UNAVAILABLE));
        assertFalse(ClusterProjectionController
                .shouldBootstrapDiLink5ContainerService(
                        18,
                        ClusterProjectionController.DiLink5CommandResult
                                .UNAVAILABLE));
        assertFalse(ClusterProjectionController
                .shouldBootstrapDiLink5ContainerService(
                        0,
                        ClusterProjectionController.DiLink5CommandResult
                                .UNAVAILABLE));
        assertFalse(ClusterProjectionController
                .shouldBootstrapDiLink5ContainerService(
                        16,
                        ClusterProjectionController.DiLink5CommandResult
                                .REJECTED));
    }

    @Test
    public void xdjaBootstrapRequiresAnExactInstalledPackageResult() {
        assertTrue(ClusterProjectionController.isPackagePathResult(
                0, "package:/system/priv-app/XdjaContainerService/base.apk\n"));
        assertFalse(ClusterProjectionController.isPackagePathResult(
                1, "package:/system/priv-app/XdjaContainerService/base.apk\n"));
        assertFalse(ClusterProjectionController.isPackagePathResult(
                0, "Unknown package: com.xdja.containerservice"));
    }

    @Test
    public void serviceBootstrapRejectsShellLevelFailures() {
        assertTrue(ClusterProjectionController.isSuccessfulServiceStartResult(
                0,
                false,
                "Starting service: Intent { cmp=com.xdja.containerservice/"
                        + ".AutoDisplayService }"));
        assertFalse(ClusterProjectionController.isSuccessfulServiceStartResult(
                0, true, ""));
        assertFalse(ClusterProjectionController.isSuccessfulServiceStartResult(
                0, false, "Error: Not allowed to start service"));
        assertFalse(ClusterProjectionController.isSuccessfulServiceStartResult(
                1, false, "Starting service"));
    }

    @Test
    public void legacyCommandOwnershipExpiresOnlyAcrossAConfirmedReboot() {
        String bootA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        String bootB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

        assertTrue(
                ClusterProjectionController
                        .legacyIndeterminateCommandMayStillComplete(
                                true, bootA, bootA));
        assertTrue(
                ClusterProjectionController
                        .legacyIndeterminateCommandMayStillComplete(
                                true, "", bootA));
        assertFalse(
                ClusterProjectionController
                        .legacyIndeterminateCommandMayStillComplete(
                                true, bootA, bootB));
        assertFalse(
                ClusterProjectionController
                        .legacyIndeterminateCommandMayStillComplete(
                                false, bootA, bootA));
    }

    @Test
    public void expiredCommandOnlyOwnershipRequiresFullGatePromotion() {
        String oldBoot = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        String currentBoot = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        ClusterProjectionController.LegacyGateSnapshot commandOnly =
                new ClusterProjectionController.LegacyGateSnapshot(
                        true, false, 0L, true, oldBoot);
        ClusterProjectionController.LegacyGateSnapshot fullGate =
                new ClusterProjectionController.LegacyGateSnapshot(
                        true, true, 0L, false, "");
        ClusterProjectionController.LegacyGateSnapshot activeUntilOnly =
                new ClusterProjectionController.LegacyGateSnapshot(
                        true, false, 123L, false, "");
        ClusterProjectionController.LegacyGateSnapshot fullGateWithOldCommand =
                new ClusterProjectionController.LegacyGateSnapshot(
                        true, true, 0L, true, oldBoot);
        ClusterProjectionController.LegacyGateSnapshot clean =
                new ClusterProjectionController.LegacyGateSnapshot(
                        true, false, 0L, false, "");

        assertFalse(ClusterProjectionController
                .legacyIndeterminateCommandMayStillComplete(
                        commandOnly.commandIndeterminate,
                        commandOnly.commandBootId,
                        currentBoot));
        assertTrue(ClusterProjectionController
                .legacyBootRecoveryOwnershipNeedsPromotion(commandOnly));
        assertTrue(ClusterProjectionController
                .legacyBootRecoveryOwnershipNeedsPromotion(activeUntilOnly));
        assertTrue(ClusterProjectionController
                .legacyBootRecoveryOwnershipNeedsPromotion(
                        fullGateWithOldCommand));
        assertFalse(ClusterProjectionController
                .legacyBootRecoveryOwnershipNeedsPromotion(fullGate));
        assertFalse(ClusterProjectionController
                .legacyBootRecoveryOwnershipNeedsPromotion(clean));
    }

    @Test
    public void commandMarkerClearVerificationSeparatesRollbackFromDispatch() {
        ClusterProjectionController.LegacyGateSnapshot clearedCommandOnly =
                new ClusterProjectionController.LegacyGateSnapshot(
                        true, false, 0L, false, "");
        ClusterProjectionController.LegacyGateSnapshot clearedWithFullGate =
                new ClusterProjectionController.LegacyGateSnapshot(
                        true, true, 0L, false, "");

        assertTrue(ClusterProjectionController
                .isLegacyCommandMarkerClearVerified(
                        clearedCommandOnly, false));
        assertFalse(ClusterProjectionController
                .isLegacyCommandMarkerClearVerified(
                        clearedCommandOnly, true));
        assertTrue(ClusterProjectionController
                .isLegacyCommandMarkerClearVerified(
                        clearedWithFullGate, true));
    }

    @Test
    public void legacyRecoveryResultSeparatesRetryableAndSameBootFailures() {
        assertTrue(
                ClusterProjectionController.LegacyBootRecoveryResult
                        .RECOVERED.isRecovered());
        assertFalse(
                ClusterProjectionController.LegacyBootRecoveryResult
                        .RETRYABLE_FAILURE.isRecovered());
        assertFalse(
                ClusterProjectionController.LegacyBootRecoveryResult
                        .SAME_BOOT_INDETERMINATE.isRecovered());
        assertTrue(
                ClusterProjectionController.LegacyBootRecoveryResult
                        .SAME_BOOT_INDETERMINATE
                        .isSameBootIndeterminate());
        assertFalse(
                ClusterProjectionController.LegacyBootRecoveryResult
                        .RETRYABLE_FAILURE
                        .isSameBootIndeterminate());
    }
}
