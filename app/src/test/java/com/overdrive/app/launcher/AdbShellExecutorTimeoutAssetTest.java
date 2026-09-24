package com.overdrive.app.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Prevents loopback ADB from regressing to Dadb's infinite socket defaults. */
public class AdbShellExecutorTimeoutAssetTest {

    @Test
    public void dadbConnectionsUseFiniteConnectAndSocketTimeouts() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/launcher/AdbShellExecutor.kt")
                .replace("\r\n", "\n");

        assertTrue(source.contains("private const val CONNECT_TIMEOUT_MS = 2_000"));
        assertTrue(source.contains("private const val SOCKET_TIMEOUT_MS = 60_000"));

        // The invariant, independent of constant names: no Dadb.create may use the
        // 3-arg overload, which leaves connect and SO_TIMEOUT at Dadb's infinite
        // defaults. Every call site must pass both bounds explicitly.
        assertFalse(source.contains(
                "Dadb.create(\"127.0.0.1\", ADB_PORT, keyPair)"));
        for (String call : source.split("Dadb\\.create\\(\"127\\.0\\.0\\.1\"")) {
            if (!call.startsWith(", ADB_PORT, keyPair,")) continue;
            assertTrue("every Dadb.create must pass CONNECT_TIMEOUT_MS",
                    call.startsWith(", ADB_PORT, keyPair, CONNECT_TIMEOUT_MS,"));
        }
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }

            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
