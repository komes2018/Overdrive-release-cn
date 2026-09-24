package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Regression contracts for DI3 projection transport and recovery ordering. */
public class Di3ProjectionTransportContractTest {

    @Test
    public void legacyProjectionUsesBinderFirstAndLetsLateCallsResolve()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "ClusterProjectionController.java");
        String dispatch = section(
                source,
                "private static LegacyCommandResult sendInfoMarkedResult(",
                "public enum DiLink5CommandResult");

        assertOrdered(
                dispatch,
                "markLegacyCommandInFlight()",
                "runLegacyBinderAttempt(",
                "attempt.completion.await(",
                "watchLateLegacyBinderAttempt(attempt, op)",
                "if (attempt.result == LegacyTransportResult.UNAVAILABLE)",
                "sendInfoShellMarkedResult(service, op)");
        assertTrue(dispatch.contains(
                "sendInfoBinderTransportResult(String service, int op)"));
        assertTrue(dispatch.contains(
                "scheduleRecoveryAfterLateLegacyCommand()"));
        assertTrue(dispatch.contains(
                "retryRecoveryAfterLegacyCommandResolution()"));
        assertFalse(dispatch.contains("destroyForcibly"));

        String shellFallback = section(
                source,
                "private static LegacyCommandResult sendInfoShellMarkedResult(",
                "static boolean isAcceptedLegacyServiceCallResult(");
        assertOrdered(
                shellFallback,
                "process.waitFor(",
                "watchLateLegacyShellCommand(process, service, op)",
                "LegacyCommandResult.INDETERMINATE");
        assertFalse(shellFallback.contains("destroyForcibly"));
    }

    @Test
    public void unknownLegacyCommandStopsRecoveryRetriesAndDuplicateDispatch()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "ClusterProjectionController.java");
        String bootRecovery = section(
                source,
                "private static void runBootGaugeRestore(",
                "private static boolean sleepForLegacyRestore(");

        assertOrdered(
                bootRecovery,
                "LegacyCommandResult closeResult =",
                "closeResult == LegacyCommandResult.INDETERMINATE",
                "LegacyBootRecoveryResult\n"
                        + "                                    "
                        + ".SAME_BOOT_INDETERMINATE",
                "break;");
        assertTrue(bootRecovery.contains(
                "refreshResult\n"
                        + "                            "
                        + "== LegacyCommandResult.INDETERMINATE"));
        assertTrue(bootRecovery.contains(
                "profileResult\n"
                        + "                                "
                        + "== LegacyCommandResult.INDETERMINATE"));

        String bootAdmission = section(
                source,
                "private static LegacyBootRecoveryResult\n"
                        + "            clearStaleGateAtBootResultSynchronously(",
                "private static void runBootGaugeRestore(");
        assertOrdered(
                bootAdmission,
                "legacyIndeterminateCommandMayStillComplete(",
                "isLegacyProjectionBlockedByDiLink5Recovery())",
                "ensureLegacyBootRecoveryOwnership(marker)",
                "new BootRestoreTicket()");

        String promotion = section(
                source,
                "private static boolean ensureLegacyBootRecoveryOwnership(",
                "private boolean writeGateFlags(");
        assertOrdered(
                promotion,
                "ownership.put(GATE_FORCE_STOP, true)",
                "ownership.put(GATE_ACTIVE_UNTIL, 0L)",
                "ownership.put(GATE_COMMAND_INDETERMINATE, false)",
                "ownership.put(GATE_COMMAND_BOOT_ID, \"\")",
                "UnifiedConfigManager.updateValues(",
                "promoted.forceStop",
                "promoted.activeUntilMs == 0L",
                "!promoted.commandIndeterminate",
                "promoted.commandBootId.isEmpty()");

        String marker = section(
                source,
                "private static LegacyCommandMarkerResult\n"
                        + "            markLegacyCommandInFlight()",
                "private static boolean "
                        + "rollbackLegacyCommandMarkerBeforeDispatch()");
        assertOrdered(
                marker,
                "readDurableConfigStrict()",
                "legacyIndeterminateCommandMayStillComplete(",
                "refusing a second legacy projection command",
                "if (!existing.forceStop)",
                "UnifiedConfigManager.updateValues(");
        assertTrue(source.contains(
                "rollbackLegacyCommandMarkerBeforeDispatch()"));
        assertTrue(source.contains(
                "clearLegacyCommandInFlightAfterDispatch()"));

        String markerClear = section(
                source,
                "private static boolean "
                        + "rollbackLegacyCommandMarkerBeforeDispatch()",
                "static boolean isLegacyCommandMarkerClearVerified(");
        assertOrdered(
                markerClear,
                "clearLegacyCommandMarker(false)",
                "clearLegacyCommandMarker(true)",
                "if (retainRecoveryOwnership)",
                "markerValues.put(GATE_FORCE_STOP, true)",
                "markerValues.put(GATE_COMMAND_INDETERMINATE, false)",
                "markerValues.put(GATE_COMMAND_BOOT_ID, \"\")");

        String gateClear = section(
                source,
                "private static boolean clearGateFlagsStatic()",
                "private static LegacyCommandMarkerResult");
        assertOrdered(
                gateClear,
                "synchronized (LEGACY_COMMAND_DISPATCH_LOCK)",
                "clearGateFlagsUnderCommandLock()",
                "legacyIndeterminateCommandMayStillComplete(",
                "UnifiedConfigManager.updateValues(");
    }

    @Test
    public void crossModeRecoveryPreservesSameBootFailureCause()
            throws IOException {
        String cast = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/launcher/"
                        + "DiLink5ClusterCast.kt");
        assertTrue(cast.contains(
                "clearStaleGateAfterDiLink5RecoveryResultSynchronously()"));
        assertTrue(cast.contains(
                "fun retryRecoveryAfterLegacyCommandResolution()"));
        assertTrue(cast.contains(
                "scheduleLegacyRecoveryResumePoll()"));
        assertTrue(cast.contains(
                "sameBootIndeterminate =\n"
                        + "                            "
                        + "legacyRecovery.sameBootIndeterminate"));
        assertTrue(cast.contains(
                "retryBlockedBySameBootIndeterminate ||\n"
                        + "                                "
                        + "legacyRecovery.sameBootIndeterminate"));

        String legacyCast = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/launcher/"
                        + "ClusterCast.java");
        assertTrue(legacyCast.contains(
                "ClusterProjectionController\n"
                        + "                .isLegacyProjectionAdmissionBlocked()"));
        assertFalse(legacyCast.contains(
                "unresolved DI5 recovery ownership"));
    }

    private static String section(
            String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start);
        if (start < 0 || end <= start) {
            throw new AssertionError(
                    "Could not find source section: "
                            + startNeedle + " -> " + endNeedle);
        }
        return source.substring(start, end);
    }

    private static void assertOrdered(String source, String... needles) {
        int cursor = -1;
        for (String needle : needles) {
            int next = source.indexOf(needle, cursor + 1);
            assertTrue("Missing or out-of-order source fragment: " + needle,
                    next > cursor);
            cursor = next;
        }
    }

    private static String readRepositoryFile(String relativePath)
            throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate),
                        StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule),
                        StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError(
                "Could not locate repository file: " + relativePath);
    }
}
