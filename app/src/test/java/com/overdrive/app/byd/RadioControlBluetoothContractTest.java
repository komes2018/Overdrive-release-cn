package com.overdrive.app.byd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class RadioControlBluetoothContractTest {

    @Test
    public void bluetoothUsesAndVerifiesTheReferenceAppPath() throws Exception {
        String radio = read("app/src/main/java/com/overdrive/app/byd/RadioControl.java");
        String actuator = read(
                "app/src/main/java/com/overdrive/app/services/VehicleActuatorService.java");

        assertTrue(radio.contains("--es action bluetooth --ez enabled"));
        assertFalse(radio.contains("\"svc bluetooth "));
        assertTrue(actuator.contains("BluetoothAdapter.getDefaultAdapter()"));
        assertTrue(actuator.contains("adapter.enable()"));
        assertTrue(actuator.contains("adapter.disable()"));
        assertTrue(actuator.contains("return adapter.isEnabled() == enabled;"));
        assertTrue(actuator.contains("BLUETOOTH_VERIFY_TIMEOUT_MS"));
    }

    private static String read(String relativePath) throws Exception {
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
        throw new AssertionError("Could not locate project file: " + relativePath);
    }
}
