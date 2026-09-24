package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class AcControlModeCompatibilityTest {

    @Test
    public void diLinkThreeControlModePairUsesMeasuredIds() {
        assertEquals(0x1DE00018, BydFeatureIds.AC_CTRL_MODE_SET);
        assertEquals(0x1DE00015, BydFeatureIds.AC_CTRL_SOURCE_SET);
    }

    @Test
    public void autoModeNeverSendsAnUnresolvedLegacyId() throws Exception {
        String source = read(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");

        int powerStart = source.indexOf("public boolean setAcPower(boolean on)");
        int powerEnd = source.indexOf("public boolean setAcTemperature(", powerStart);
        String power = source.substring(powerStart, powerEnd);
        assertTrue(power.contains(
                "if (!BydFeatureIds.isResolved(BydFeatureIds.AC_AUTO_MODE_SET))"));

        int autoStart = source.indexOf("public boolean setAcAutoMode(boolean on)");
        int autoEnd = source.indexOf("public boolean setAcTemperatureSync", autoStart);
        String auto = source.substring(autoStart, autoEnd);
        assertTrue(auto.contains(
                "if (BydFeatureIds.isResolved(BydFeatureIds.AC_AUTO_MODE_SET))"));
        assertTrue(auto.contains("BydDeviceHelper.callSetBatch("));
        assertTrue(auto.contains("BydFeatureIds.AC_CTRL_MODE_SET"));
        assertTrue(auto.contains("BydFeatureIds.AC_CTRL_SOURCE_SET"));
    }

    private static String read(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
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
