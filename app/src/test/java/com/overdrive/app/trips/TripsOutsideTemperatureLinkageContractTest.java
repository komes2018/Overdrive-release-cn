package com.overdrive.app.trips;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * R25: outside-temperature reads must never link the optional SDK instrument
 * class at compile time.
 *
 * A direct reference (import or FQN member access) compiles against the
 * stubs module but throws {@link NoClassDefFoundError} — an Error, which
 * sails through {@code catch (Exception)} — on firmware that ships without
 * the class. Every consumer must read the normalized collector snapshot
 * (BydVehicleData.outsideTempC) first and may keep the legacy device read
 * only behind reflection (Class.forName) with a Throwable catch. Defaults
 * are retained: TripDetector keeps 0, TripApiHandler keeps 20 (mild).
 */
public class TripsOutsideTemperatureLinkageContractTest {

    private static final String[] CONSUMERS = {
            "app/src/main/java/com/overdrive/app/trips/TripDetector.java",
            "app/src/main/java/com/overdrive/app/trips/TripApiHandler.java",
            "app/src/main/java/com/overdrive/app/server/LauncherApiHandler.java",
    };

    @Test
    public void noDirectInstrumentClassLinkageAndSnapshotFirst()
            throws Exception {
        for (String relativePath : CONSUMERS) {
            String source = readRepositoryFile(relativePath);

            // No compile-time linkage: the class name may appear ONLY inside
            // a Class.forName reflection string.
            assertFalse(relativePath + " must not import the instrument class",
                    source.contains("import android.hardware.bydauto.instrument"));
            int index = 0;
            while (true) {
                index = source.indexOf(
                        "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
                        index);
                if (index < 0) break;
                int lineStart = source.lastIndexOf('\n', index);
                String prefix = source.substring(lineStart + 1, index);
                assertTrue(relativePath + " references the instrument class "
                                + "outside Class.forName at offset " + index,
                        prefix.contains("Class.forName(\"")
                                || prefix.trim().startsWith("//")
                                || prefix.trim().startsWith("*"));
                index += 1;
            }

            // Normalized snapshot is consulted before the legacy fallback.
            int snapshot = source.indexOf("BydDataCollector.getInstance()");
            int outsideField = source.indexOf("outsideTempC", snapshot);
            int legacy = source.indexOf(
                    "Class.forName(\"android.hardware.bydauto.instrument.BYDAutoInstrumentDevice\")");
            assertTrue(relativePath + " must read the collector snapshot",
                    snapshot >= 0 && outsideField > snapshot);
            assertTrue(relativePath + " must keep the reflection fallback "
                            + "AFTER the snapshot read",
                    legacy > snapshot);
        }

        // Defaults retained.
        String detector = readRepositoryFile(CONSUMERS[0]);
        assertTrue(detector.contains("activeTrip.extTempC = 0;"));
        String api = readRepositoryFile(CONSUMERS[1]);
        assertTrue(api.contains("int extTemp = 20; // Default mild temperature"));
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
