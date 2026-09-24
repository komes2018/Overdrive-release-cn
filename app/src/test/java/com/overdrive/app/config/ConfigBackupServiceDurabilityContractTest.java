package com.overdrive.app.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Source-level contracts for cross-file backup publication and restore ordering. */
public class ConfigBackupServiceDurabilityContractTest {

    @Test
    public void exportSnapshotsStrictConfigAndDidUnderOneLock()
            throws IOException {
        String build = between(
                source(),
                "fun buildBundle(",
                "// ==================== IMPORT");

        assertOrdered(
                build,
                "UnifiedConfigManager.runUnderConfigLock {",
                "UnifiedConfigManager.readDurableConfigStrict()",
                "stripEphemeral(unified)",
                "readDidSnapshotStrict()");
        assertFalse(build.contains("forceReload()"));
    }

    @Test
    public void restoreUsesJournalAndCredentialFreeBridgeBeforeDid()
            throws IOException {
        String source = source();
        String apply = between(
                source,
                "fun applyBundle(",
                "// ==================== helpers");
        String transition = between(
                source,
                "private fun beginBundledDidTransition(",
                "private fun credentialFreeBridge(");

        assertOrdered(
                apply,
                "UnifiedConfigManager.runUnderConfigLock {",
                "recoverInterruptedRestoreUnderConfigLock()",
                "readDidSnapshotStrict()",
                "UnifiedConfigManager.readDurableConfigForRestore()",
                "beginBundledDidTransition(",
                "UnifiedConfigManager.saveConfig(",
                "clearRestoreJournal()");
        assertOrdered(
                transition,
                "writeRestoreJournal(priorDid, priorConfig)",
                "credentialFreeBridge(priorConfig)",
                "writeDidBytesAtomic(bytes)");
        assertFalse(transition.contains("writeDidBytesAtomic(bytes)\n"
                + "            writeRestoreJournal"));
    }

    @Test
    public void journalDidPublicationAndAbsentRollbackAreDurable()
            throws IOException {
        String source = source();
        String write = between(
                source,
                "private fun writeAtomicFile(",
                "private fun syncDidDirectory(");
        String rollback = between(
                source,
                "private fun restoreDidSnapshot(",
                "private fun writeDidBytesAtomic(");

        assertOrdered(
                write,
                "FileOutputStream(tmp).use",
                "output.write(bytes)",
                "output.flush()",
                "output.fd.sync()",
                "tmp.renameTo(file)",
                "syncDidDirectory(parent)");
        assertOrdered(
                rollback,
                "if (snapshot.existed)",
                "writeDidBytesAtomic(snapshot.bytes)",
                "file.delete()",
                "syncDidDirectory(parent)");
        assertFalse(write.contains("file.writeText("));
        assertTrue(source.contains(
                "journal.toString().toByteArray(StandardCharsets.UTF_8)"));
        assertTrue(source.contains(
                "clearRestoreJournal()"));
    }

    @Test
    public void startupRollbackAlsoUsesCredentialFreeBridge()
            throws IOException {
        String source = source();
        String recovery = between(
                source,
                "fun recoverInterruptedRestoreUnderConfigLock()",
                "private fun beginBundledDidTransition(");
        String manager = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/config/"
                        + "UnifiedConfigManager.kt");
        String init = between(manager, "fun init()", "/** Create the lock file");

        assertOrdered(
                recovery,
                "credentialFreeBridge(priorConfig)",
                "UnifiedConfigManager.saveConfig(bridge",
                "restoreDidSnapshot(priorDid)",
                "UnifiedConfigManager.saveConfig(",
                "clearRestoreJournal()");
        assertOrdered(
                init,
                "provisionLockFile()",
                "withConfigFileLock {",
                "ConfigBackupService.recoverInterruptedRestoreUnderConfigLock()");
    }

    @Test
    public void restorePreservesCurrentPendingRepriceObligations()
            throws IOException {
        String apply = between(
                source(),
                "fun applyBundle(",
                "// ==================== helpers");

        assertOrdered(
                apply,
                "val incoming = JSONObject(incomingRaw.toString())",
                "stripEphemeral(incoming)",
                "val toWrite = JSONObject(current.toString())");
        assertFalse(apply.contains("stripEphemeral(toWrite)"));
    }

    @Test
    public void changedDidReplacesCredentialsOutsideAdditiveMerge()
            throws IOException {
        String source = source();
        String apply = between(
                source,
                "fun applyBundle(",
                "// ==================== helpers");
        String replacement = between(
                source,
                "private fun replaceCredentialSectionsAfterDidChange(",
                "private fun safeFingerprint(");

        assertTrue(apply.contains(
                "didWrite.changed\n"
                        + "                                    && CREDENTIAL_SECTIONS.contains(k)"));
        assertOrdered(
                apply,
                "val toWrite = JSONObject(current.toString())",
                "deepMergeInto(baseVal, incomingVal, k)",
                "replaceCredentialSectionsAfterDidChange(",
                "UnifiedConfigManager.ensureDefaults(toWrite)");
        assertOrdered(
                replacement,
                "if (!didChanged) return",
                "for (section in CREDENTIAL_SECTIONS)",
                "target.remove(section)",
                "if (rejectedSections.contains(section)) continue",
                "verifiedIncoming.optJSONObject(section) ?: continue",
                "target.put(section, JSONObject(replacement.toString()))");
    }

    @Test
    public void changedDidClearsAbsentOrRejectedCredentialsWithoutWeakeningRollback()
            throws IOException {
        String source = source();
        String apply = between(
                source,
                "fun applyBundle(",
                "// ==================== helpers");
        String replacement = between(
                source,
                "private fun replaceCredentialSectionsAfterDidChange(",
                "private fun safeFingerprint(");

        assertOrdered(
                apply,
                "didWrite.usable && bundleSecretsDecrypt(incoming)",
                "if (didWrite.usable && secretsDecryptable) emptySet()",
                "replaceCredentialSectionsAfterDidChange(");
        assertFalse(replacement.contains("deepMergeInto("));
        assertOrdered(
                apply,
                "val saved = UnifiedConfigManager.saveConfig(",
                "if (didWrite.changed)",
                "if (saved)",
                "clearRestoreJournal()",
                "recoverInterruptedRestoreUnderConfigLock()");
    }

    @Test
    public void modelLineageResetPrecedesRestoredConfigCommit()
            throws IOException {
        String apply = between(
                source(),
                "fun applyBundle(",
                "// ==================== helpers");

        assertOrdered(
                apply,
                "resolvedVehicleModelId(current)",
                "resetSohForModelRestore()",
                "UnifiedConfigManager.saveConfig(");
        assertTrue(source().contains(
                "estimator.reset()"));
        assertTrue(source().contains(
                "SohLineageResetException"));
    }

    @Test
    public void restoreStagesTheFinalVehicleModeBeforeCommit()
            throws IOException {
        String source = source();
        String apply = between(
                source,
                "fun applyBundle(",
                "// ==================== helpers");

        assertOrdered(
                apply,
                "UnifiedConfigManager.ensureDefaults(toWrite)",
                "val currentVehicleMode =",
                "val restoredVehicleMode =",
                "DiLink5Platform.stageConfiguredMode(\n"
                        + "                            restoredVehicleMode, currentVehicleMode)",
                "!DiLink5Platform.isActiveMode(restoredVehicleMode)",
                "UnifiedConfigManager.saveConfig(");
        assertTrue(source.contains("val restartRequired: Boolean = false"));
        assertTrue(apply.contains(
                "restartRequired = transaction.vehicleModeChanged"));

        String ipc = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java");
        String web = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/ConfigBackupApiHandler.java");
        assertTrue(ipc.contains(
                "response.put(\"restartRequired\", res.getRestartRequired())"));
        assertTrue(web.contains(
                "r.put(\"restartRequired\", result.getRestartRequired())"));
        assertFalse(ipc.contains(
                "incoming.optString(\"cameraMode\", \"default\")"));
        String platform = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/"
                        + "DiLink5Platform.java");
        assertTrue(platform.contains(
                "active, pending, configured, requested"));
        assertTrue(platform.contains(
                "String active = effectiveMode(readActiveMode(), pending, configured);"));
        assertTrue(platform.contains(
                "requested.equals(pending) && requested.equals(configured)"));
        assertTrue(apply.contains(
                "DiLink5Platform.currentActiveMode()"));
        assertTrue(apply.contains(
                "if (!restoredCamera.has(\"cameraMode\"))"));
        assertTrue(apply.contains(
                "restoredCamera.put(\"cameraMode\", currentVehicleMode)"));
        assertTrue(apply.contains(
                "DiLink5Platform.normalizeConfiguredMode("));
        assertFalse(apply.contains(
                "optString(\"cameraMode\", \"default\")"));
    }

    @Test
    public void backupStripsInternalClocksAndPendingReprices()
            throws IOException {
        String source = source();
        assertTrue(source.contains(
                "\"__overdriveMutationClocks\""));
        assertTrue(source.contains(
                "\"chargingAnalytics.pendingTariffReprices\""));
        assertTrue(source.contains(
                "\"chargingAnalytics.pendingTariffRepriceTokens\""));
        assertOrdered(
                between(source, "fun buildBundle(", "// ==================== IMPORT"),
                "for (sec in EXCLUDED_SECTIONS)",
                "stripEphemeral(unified)");
    }

    @Test
    public void backupCarriesEncryptedGenAiCredentialWithDeviceKey()
            throws IOException {
        String source = source();
        assertTrue(source.contains(
                "listOf(\"bydCloud\", \"navMap\", \"telegram\", \"genAi\")"));
        assertTrue(source.contains(
                "\"genAi\"    -> \"GenAI provider API key\""));
    }

    private static void assertOrdered(String source, String... needles) {
        int previous = -1;
        for (String needle : needles) {
            int position = source.indexOf(needle, previous + 1);
            assertTrue("Missing or out of order: " + needle, position > previous);
            previous = position;
        }
    }

    private static String between(
            String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start);
        assertTrue("Missing start: " + startNeedle, start >= 0);
        assertTrue("Missing end: " + endNeedle, end > start);
        return source.substring(start, end);
    }

    private static String source() throws IOException {
        return readRepositoryFile(
                "app/src/main/java/com/overdrive/app/config/"
                        + "ConfigBackupService.kt");
    }

    private static String readRepositoryFile(String relativePath)
            throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
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
        throw new AssertionError("Could not locate " + relativePath);
    }
}
