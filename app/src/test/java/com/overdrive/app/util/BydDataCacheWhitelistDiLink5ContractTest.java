package com.overdrive.app.util;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class BydDataCacheWhitelistDiLink5ContractTest {

    @Test
    public void backgroundAccessWaitsForHealthRetriesAndRetainsFailure()
            throws Exception {
        String whitelist = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/util/"
                        + "BydDataCacheWhitelist.kt");
        String activity = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/MainActivity.kt");
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");

        assertTrue(whitelist.contains(
                "fun applyAll(context: Context): Boolean"));
        assertTrue(whitelist.contains(
                "return applyViaDaemonWhenReady()"));
        assertTrue(whitelist.contains(
                "if (isDaemonReady() && applyViaDaemon()) return true"));
        assertTrue(whitelist.contains(
                "DaemonHttpClient.open(\"/status\", \"GET\""));
        assertTrue(whitelist.contains(
                "@Volatile private var lastApplyFailure: String?"));
        assertTrue(whitelist.contains(
                "fun getLastApplyFailure(): String?"));
        assertTrue(daemon.contains(
                "appContext = CameraDaemon.getAppContext();"));
        assertTrue(daemon.contains(
                "result.put(\"error\", \"Daemon context is not ready\")"));
        assertTrue(daemon.contains(
                "result.put(\"startupProvider\", startupProvider)"));
        assertTrue(daemon.contains("cmd appops get "));
        assertTrue(daemon.contains("|| exit 1"));

        assertTrue(activity.contains(
                "val applied = BydDataCacheWhitelist.applyAll(this)"));
        assertTrue(activity.contains("if (!applied) {"));
        assertTrue(activity.contains(
                "BydDataCacheWhitelist.getLastApplyFailure()"));
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
}
