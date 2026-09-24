package com.overdrive.app.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

/** Regression coverage for process-safe camera and ACC watchdog ownership. */
public class DaemonWatchdogOwnershipContractTest {

    @Test
    public void cameraWatchdogAcquiresAtomicallyBeforeEnteringLoop()
            throws Exception {
        String script = join(DaemonLauncher.Companion
                .buildCamDaemonWatchdogScript(
                        "/data/app/base.apk",
                        "/data/app/lib",
                        "/sdcard/Android/data/com.overdrive.app/files",
                        ""));

        assertOwnershipProtocol(script, "/data/local/tmp/start_cam_daemon.sh",
                "/data/local/tmp/cam_watchdog.pid",
                "/data/local/tmp/cam_watchdog.lock");
        assertTrue(script.indexOf("if ! acquire_watchdog_lock; then")
                < script.indexOf("while true; do"));
        assertShellSyntax(script);
    }

    @Test
    public void concurrentLaunchCannotStealLockBeforeOwnerPublishesPid()
            throws Exception {
        Path shell = Paths.get("/bin/sh");
        Assume.assumeTrue(Files.isExecutable(shell));

        Path root = Files.createTempDirectory("overdrive-watchdog-race-");
        try {
            Path scriptPath = root.resolve("start_cam_daemon.sh");
            Path pidFile = root.resolve("cam_watchdog.pid");
            Path lockDir = root.resolve("cam_watchdog.lock");
            Path owners = root.resolve("owners.txt");

            String generated = join(DaemonLauncher.Companion
                    .buildCamDaemonWatchdogScript(
                            "/data/app/base.apk",
                            "/data/app/lib",
                            "/sdcard/Android/data/com.overdrive.app/files",
                            ""));
            String acquisitionEnd = "if ! acquire_watchdog_lock; then\n"
                    + "  exit 0\n"
                    + "fi";
            int end = generated.indexOf(acquisitionEnd);
            assertTrue(end >= 0);
            String protocol = generated.substring(
                    0, end + acquisitionEnd.length())
                    .replace("/data/local/tmp/start_cam_daemon.sh",
                            scriptPath.toString())
                    .replace("/data/local/tmp/cam_watchdog.pid",
                            pidFile.toString())
                    .replace("/data/local/tmp/cam_watchdog.lock",
                            lockDir.toString())
                    // Make both contenders reach the atomic directory
                    // acquisition together.
                    .replace(
                            "if mkdir \"$WATCHDOG_LOCK_DIR\" 2>/dev/null; then",
                            "sleep 1\n"
                                    + "  if mkdir \"$WATCHDOG_LOCK_DIR\" "
                                    + "2>/dev/null; then")
                    + "\necho $$ >> \"" + owners + "\"\n"
                    + "sleep 1\n";
            Files.write(scriptPath,
                    protocol.getBytes(StandardCharsets.UTF_8));

            Process first = new ProcessBuilder(
                    shell.toString(), scriptPath.toString())
                    .redirectErrorStream(true)
                    .start();
            Process second = new ProcessBuilder(
                    shell.toString(), scriptPath.toString())
                    .redirectErrorStream(true)
                    .start();
            byte[] firstOutput = first.getInputStream().readAllBytes();
            byte[] secondOutput = second.getInputStream().readAllBytes();
            assertEquals(new String(firstOutput, StandardCharsets.UTF_8),
                    0, first.waitFor());
            assertEquals(new String(secondOutput, StandardCharsets.UTF_8),
                    0, second.waitFor());

            assertEquals(1, Files.readAllLines(owners).size());
            assertFalse(Files.exists(pidFile));
            assertFalse(Files.exists(lockDir));
        } finally {
            if (Files.exists(root)) {
                try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                    paths.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (Exception ignored) {
                                    // Best-effort test cleanup.
                                }
                            });
                }
            }
        }
    }

    @Test
    public void uiDeploymentPreservesWatchdogScriptsByteForByte()
            throws Exception {
        Path shell = Paths.get("/bin/sh");
        Assume.assumeTrue(Files.isExecutable(shell));

        Path root = Files.createTempDirectory("overdrive-watchdog-write-");
        try {
            List<String> camera = DaemonLauncher.Companion
                    .buildCamDaemonWatchdogScript(
                            "/data/app/base.apk",
                            "/data/app/lib",
                            "/sdcard/Android/data/com.overdrive.app/files",
                            "");
            List<String> acc = DaemonLauncher.Companion
                    .buildAccSentryWatchdogScript(
                            "/data/app/base.apk", "");

            assertWriteRoundTrip(
                    shell, root.resolve("start_cam_daemon.sh"), camera);
            assertWriteRoundTrip(
                    shell, root.resolve("start_acc_sentry.sh"), acc);
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void deploymentPathsClearStaleOwnershipAfterStoppingOldWrapper()
            throws Exception {
        String launcher = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/launcher/DaemonLauncher.kt");
        String cameraLaunch = section(
                launcher,
                "private fun launchCameraDaemonInternal(",
                "private fun writeCamDaemonScript(");
        assertOrdered(cameraLaunch,
                "psAwkKillLine(\"cam_daemon\")",
                "sleep 1",
                "$CAMERA_WATCHDOG_PID_FILE",
                "rm -rf $CAMERA_WATCHDOG_LOCK_PATH");

        String accLaunch = section(
                launcher,
                "private fun launchAccSentryDaemonInternal(",
                "private fun writeWatchdogScript(");
        assertOrdered(accLaunch,
                "psAwkKillLine(\"acc_sentry\")",
                "sleep 1",
                "$ACC_SENTRY_WATCHDOG_PID_FILE ");
        assertTrue(accLaunch.contains(
                "rm -rf $ACC_SENTRY_WATCHDOG_LOCK_PATH"));

        String telegram = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/telegram/"
                        + "DaemonCommandHandler.java");
        String cameraTelegram = section(
                telegram,
                "private boolean startCameraDaemonWithWatchdog(",
                "private boolean startAccSentryDaemonWithWatchdog(");
        assertOrdered(cameraTelegram,
                "grep -F 'cam_daemon'",
                "sleep 1",
                "/data/local/tmp/cam_watchdog.lock");
        assertTrue(cameraTelegram.contains(
                "rm -rf /data/local/tmp/cam_watchdog.lock"));

        String accTelegram = section(
                telegram,
                "private boolean startAccSentryDaemonWithWatchdog(",
                "private String getReservedZrokUrl(");
        assertOrdered(accTelegram,
                "grep -F 'acc_sentry'",
                "sleep 1",
                "/data/local/tmp/acc_sentry_watchdog.lock");
        assertTrue(accTelegram.contains(
                "rm -rf /data/local/tmp/acc_sentry_watchdog.lock"));

        String cameraController = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/daemon/"
                        + "CameraDaemonController.kt");
        assertTrue(cameraController.contains(
                "rm -rf /data/local/tmp/cam_watchdog.lock"));

        String accController = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/daemon/"
                        + "AccSentryDaemonController.kt");
        assertTrue(accController.contains(
                "rm -rf /data/local/tmp/acc_sentry_watchdog.lock"));
    }

    @Test
    public void accWatchdogOwnsLockBeforeMutatingLogOrDeviceConfig()
            throws Exception {
        String script = join(DaemonLauncher.Companion
                .buildAccSentryWatchdogScript(
                        "/data/app/base.apk", ""));

        assertOwnershipProtocol(script, "/data/local/tmp/start_acc_sentry.sh",
                "/data/local/tmp/acc_sentry_watchdog.pid",
                "/data/local/tmp/acc_sentry_watchdog.lock");
        int acquired = script.indexOf("if ! acquire_watchdog_lock; then");
        assertTrue(acquired < script.indexOf("/system/bin/device_config put"));
        assertTrue(acquired < script.indexOf("=== WATCHDOG STARTED ==="));
        assertShellSyntax(script);
    }

    private static void assertOwnershipProtocol(
            String script, String scriptPath, String pidFile, String lockFile) {
        assertTrue(script.contains("WATCHDOG_SCRIPT=\"" + scriptPath + "\""));
        assertTrue(script.contains("WATCHDOG_PID_FILE=\"" + pidFile + "\""));
        assertTrue(script.contains("WATCHDOG_LOCK_DIR=\"" + lockFile + "\""));
        assertTrue(script.contains(
                "WATCHDOG_LOCK_OWNER=\"$WATCHDOG_LOCK_DIR/pid\""));

        int atomicMkdir = script.indexOf(
                "if mkdir \"$WATCHDOG_LOCK_DIR\" 2>/dev/null; then");
        int ownerPidWrite =
                script.indexOf("echo $$ > \"$WATCHDOG_LOCK_OWNER\"");
        int publicPidWrite =
                script.indexOf("echo $$ > \"$WATCHDOG_PID_FILE\"");
        assertTrue(atomicMkdir >= 0);
        assertTrue(ownerPidWrite > atomicMkdir);
        assertTrue(publicPidWrite > ownerPidWrite);
        assertFalse(script.contains("if ln "));

        assertTrue(script.contains(
                "[ -r \"/proc/$CANDIDATE_PID/cmdline\" ] || return 1"));
        assertTrue(script.contains(
                "grep -F \"$WATCHDOG_SCRIPT\" >/dev/null 2>&1"));
        int identityCheck =
                script.indexOf("if watchdog_pid_matches \"$OLD_WPID\"; then");
        assertTrue(identityCheck >= 0);
        String acquisition = script.substring(
                script.indexOf("acquire_watchdog_lock() {"),
                script.indexOf("cleanup_watchdog_lock() {"));
        assertFalse(acquisition.contains(
                "rm -rf \"$WATCHDOG_LOCK_DIR\""));
        assertTrue(script.contains("trap 'cleanup_watchdog_lock' EXIT"));
        assertTrue(script.contains(
                "if [ \"$CURRENT_WPID\" = \"$$\" ]; then"));
        assertTrue(script.contains(
                "if [ \"$LEGACY_WPID\" = \"$$\" ]; then"));
        assertTrue(script.contains(
                "rmdir \"$WATCHDOG_LOCK_DIR\" 2>/dev/null"));

        // Ownership must come exclusively from atomic mkdir, not a
        // check-then-write PID file or Android-blocked hard link.
        assertFalse(script.contains(
                "if [ -f \"$WATCHDOG_PID_FILE\" ]; then"));
    }

    private static void assertWriteRoundTrip(
            Path shell, Path scriptPath, List<String> lines) throws Exception {
        String expected = String.join("\n", lines) + "\n";
        assertTrue(expected.contains("\\000"));

        String writeCommand = DaemonLauncher.Companion
                .buildWatchdogScriptWriteCommand(
                        scriptPath.toString(), lines);
        Process writer = new ProcessBuilder(
                shell.toString(), "-c", writeCommand)
                .redirectErrorStream(true)
                .start();
        byte[] writerOutput = writer.getInputStream().readAllBytes();
        assertEquals(
                new String(writerOutput, StandardCharsets.UTF_8),
                0,
                writer.waitFor());

        byte[] actualBytes = Files.readAllBytes(scriptPath);
        for (byte value : actualBytes) {
            assertTrue("deployed watchdog contains a binary NUL", value != 0);
        }
        assertEquals(expected,
                new String(actualBytes, StandardCharsets.UTF_8));

        Process syntax = new ProcessBuilder(
                shell.toString(), "-n", scriptPath.toString())
                .redirectErrorStream(true)
                .start();
        byte[] syntaxOutput = syntax.getInputStream().readAllBytes();
        assertEquals(
                new String(syntaxOutput, StandardCharsets.UTF_8),
                0,
                syntax.waitFor());
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // Best-effort test cleanup.
                        }
                    });
        }
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    private static String section(
            String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue("missing section start: " + startMarker, start >= 0);
        assertTrue("missing section end: " + endMarker, end > start);
        return source.substring(start, end);
    }

    private static void assertOrdered(String source, String... fragments) {
        int previous = -1;
        for (String fragment : fragments) {
            int current = source.indexOf(fragment, previous + 1);
            assertTrue("missing/out-of-order fragment: " + fragment,
                    current > previous);
            previous = current;
        }
    }

    private static String readRepositoryFile(String relativePath)
            throws Exception {
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
        throw new AssertionError(
                "Could not locate repository file: " + relativePath);
    }

    private static void assertShellSyntax(String script) throws Exception {
        Path shell = Paths.get("/bin/sh");
        Assume.assumeTrue(Files.isExecutable(shell));
        Path file = Files.createTempFile("overdrive-watchdog-", ".sh");
        try {
            Files.write(file, script.getBytes(StandardCharsets.UTF_8));
            Process process = new ProcessBuilder(
                    shell.toString(), "-n", file.toString())
                    .redirectErrorStream(true)
                    .start();
            byte[] output = process.getInputStream().readAllBytes();
            int exit = process.waitFor();
            assertTrue("shell syntax error: "
                            + new String(output, StandardCharsets.UTF_8),
                    exit == 0);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
