package com.overdrive.app.camera;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class BlindSpotRearOnlyMirrorContractTest {

    @Test
    public void rearOnlyBlindSpotViewMirrorsBothCameraLayouts() throws IOException {
        String scaler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/streaming/GpuStreamScaler.java");
        String rearBranch = "\"            } else if (uBsMergeMode == 2) {\\n\" +";

        int dilink4Start = scaler.indexOf(rearBranch);
        int dilink4End = scaler.indexOf("\"            } else {\\n\" +", dilink4Start);
        assertTrue(dilink4Start >= 0 && dilink4End > dilink4Start);
        assertTrue(scaler.substring(dilink4Start, dilink4End)
                .contains("\"                sl.x = 0.5 - sl.x;\\n\" +"));

        int legacyStart = scaler.indexOf(rearBranch, dilink4End);
        int legacyEnd = scaler.indexOf("\"            } else {\\n\" +", legacyStart);
        assertTrue(legacyStart >= 0 && legacyEnd > legacyStart);
        assertTrue(scaler.substring(legacyStart, legacyEnd)
                .contains("\"                rc.x = 1.0 - rc.x;\\n\" +"));
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
        throw new AssertionError("Could not locate " + relativePath);
    }
}
