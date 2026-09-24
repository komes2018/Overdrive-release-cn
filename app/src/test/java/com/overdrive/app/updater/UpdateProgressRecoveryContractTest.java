package com.overdrive.app.updater;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Source-level contract for the app-side daemon-owned update progress UI. */
public class UpdateProgressRecoveryContractTest {

    @Test
    public void progressDialogCannotBeDismissedByOutsideTouch() throws IOException {
        String dialog = read(
                "app/src/main/java/com/overdrive/app/updater/UpdateDialog.java");

        assertTrue(dialog.contains(".setCancelable(false)"));
        assertTrue(dialog.contains("dialog.setCanceledOnTouchOutside(false);"));
    }

    @Test
    public void manualCheckReopensDaemonOwnedProgressBeforeCheckingAgain()
            throws IOException {
        String main = read(
                "app/src/main/java/com/overdrive/app/ui/MainActivity.kt");

        int manual = main.indexOf("fun checkForAppUpdateManual()");
        int fresh = main.indexOf("private fun checkForAppUpdateManualFresh(", manual);
        assertTrue(manual >= 0 && fresh > manual);
        String entry = main.substring(manual, fresh);
        assertTrue(entry.contains("reopenActiveUpdateProgressOr"));

        int reopen = main.indexOf("private fun reopenActiveUpdateProgressOr");
        int freshBody = main.indexOf("private fun checkForAppUpdateManualFresh(", reopen);
        assertTrue(reopen >= 0 && freshBody > reopen);
        String recovery = main.substring(reopen, freshBody);
        assertTrue(recovery.contains("\"GET_UPDATE_PROGRESS\""));
        assertTrue(recovery.contains("phase in activeUpdatePhases"));
        assertTrue(recovery.contains("showUpdateProgressDialog()"));
        assertTrue(recovery.contains("startUpdateProgressPolling(progress, phase)"));
    }

    @Test
    public void installGateRaceAttachesToExistingUpdate() throws IOException {
        String main = read(
                "app/src/main/java/com/overdrive/app/ui/MainActivity.kt");

        int install = main.indexOf("private fun performAppUpdate(");
        int pollHandle = main.indexOf(
                "/** Handle for the active update progress poll loop", install);
        assertTrue(install >= 0 && pollHandle > install);
        String body = main.substring(install, pollHandle);
        assertTrue(body.contains(
                "err.contains(\"already in progress\", ignoreCase = true)"));
        int race = body.indexOf(
                "if (err.contains(\"already in progress\", ignoreCase = true))");
        int genericError = body.indexOf(
                "progress.showError(getString(R.string.update_error_start_failed", race);
        assertTrue(race >= 0 && genericError > race);
        String raceBranch = body.substring(race, genericError);
        assertTrue(raceBranch.contains("startUpdateProgressPolling(progress)"));
        assertTrue(raceBranch.contains("return@runOnUiThread"));
    }

    @Test
    public void stalePollReplyCannotTakeOverAReopenedDialog() throws IOException {
        String main = read(
                "app/src/main/java/com/overdrive/app/ui/MainActivity.kt");

        int polling = main.indexOf("private fun startUpdateProgressPolling(");
        int next = main.indexOf(
                "/**\n     * SOTA: Setup storage directories", polling);
        assertTrue(polling >= 0 && next > polling);
        String body = main.substring(polling, next);

        assertTrue(body.contains("if (updatePollRunnable !== this)"));
        assertTrue(body.indexOf("if (updatePollRunnable !== this)")
                < body.indexOf("if (resp == null"));
    }

    @Test
    public void hiddenHandoffReplyCannotReplaceAReopenedDialog()
            throws IOException {
        String main = read(
                "app/src/main/java/com/overdrive/app/ui/MainActivity.kt");

        int install = main.indexOf("private fun performAppUpdate(");
        int polling = main.indexOf(
                "/** Handle for the active update progress poll loop", install);
        assertTrue(install >= 0 && polling > install);
        String body = main.substring(install, polling);

        int handleGate = body.indexOf(
                "if (updateProgressHandle !== progress)");
        int responseHandling = body.indexOf("if (resp == null)");
        int startPolling = body.indexOf(
                "startUpdateProgressPolling(progress)");
        assertTrue(handleGate >= 0);
        assertTrue(handleGate < responseHandling);
        assertTrue(handleGate < startPolling);
    }

    @Test
    public void rapidManualChecksIgnoreOutOfOrderAsyncReplies()
            throws IOException {
        String main = read(
                "app/src/main/java/com/overdrive/app/ui/MainActivity.kt");

        int manual = main.indexOf("fun checkForAppUpdateManual()");
        int reopen = main.indexOf(
                "private fun reopenActiveUpdateProgressOr", manual);
        assertTrue(manual >= 0 && reopen > manual);
        String entry = main.substring(manual, reopen);
        assertTrue(entry.contains(
                "manualUpdateCheckGeneration.incrementAndGet()"));
        assertTrue(entry.contains(
                "reopenActiveUpdateProgressOr(checkGeneration)"));

        int fresh = main.indexOf(
                "private fun checkForAppUpdateManualFresh(", reopen);
        assertTrue(fresh > reopen);
        String recovery = main.substring(reopen, fresh);
        int staleGate = recovery.indexOf(
                "manualUpdateCheckGeneration.get() != checkGeneration");
        int phaseRead = recovery.indexOf("val phase =");
        assertTrue(staleGate >= 0 && phaseRead > staleGate);

        int alpha = main.indexOf("private fun checkAlphaVersions(", fresh);
        assertTrue(alpha > fresh);
        String lookup = main.substring(fresh, alpha);
        assertTrue(lookup.contains(
                "isCurrentManualUpdateCheck(checkGeneration)"));
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
