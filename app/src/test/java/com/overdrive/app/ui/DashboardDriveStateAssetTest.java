package com.overdrive.app.ui;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Pins the web dashboard's authoritative power, gear and motion-state contract. */
public class DashboardDriveStateAssetTest {

    @Test
    public void heroExposesStablePowerGearAndSpeedReadouts() throws IOException {
        String page = readRepositoryFile("app/src/main/assets/web/local/index.html");

        assertTrue(page.contains("id=\"dashDriveState\""));
        assertTrue(page.contains("id=\"dashDrivePower\""));
        assertTrue(page.contains("id=\"dashDriveMode\""));
        assertTrue(page.contains("id=\"dashDriveGear\""));
        assertTrue(page.contains("id=\"dashDriveSpeed\""));
        assertTrue(page.contains("grid-template-columns: 54px minmax(0, 1fr) auto"));
        assertTrue(page.contains("grid-template-columns: 50px minmax(0, 1fr)"));
        assertTrue(page.contains("margin-left: 12px"));
        assertTrue(page.contains("grid-template-columns: repeat(2, minmax(0, 1fr))"));
    }

    @Test
    public void resolverUsesDaemonPowerGearChargingAndFreshGpsContracts()
            throws IOException {
        String page = readRepositoryFile("app/src/main/assets/web/local/index.html");
        String resolver = between(
                page,
                "resolveDriveState: function(status) {",
                "updateDriveState: function(status) {");

        assertTrue(resolver.contains("status.recordingStatus || {}"));
        assertTrue(resolver.contains("typeof status.acc === 'boolean'"));
        assertTrue(resolver.contains("recording.gear"));
        assertTrue(resolver.contains("gps.speed * 3.6"));
        assertTrue(resolver.contains("gps.isStale !== true"));
        assertTrue(resolver.contains("gps.isCached !== true"));
        assertTrue(resolver.contains("if (!powerOn && gear !== 'P') gear = '--';"));
        assertTrue(resolver.contains(
                "Number(charging.powerKw || charging.chargingPowerKW || 0) > 0"));
        assertTrue(resolver.contains("charging.gunConnected === true"));
        assertTrue(resolver.contains("charging.plugged === true"));

        String handler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/ChargingApiHandler.java");
        assertTrue(handler.contains(
                "boolean hasLiveDetectorSample = after.observedAtMs > 0;"));

        int fault = resolver.indexOf("charging.fault === true");
        int charging = resolver.indexOf("charging.charging === true");
        int reverse = resolver.indexOf("powerOn && gear === 'R'");
        int drive = resolver.indexOf(
                "powerOn && (gear === 'D' || gear === 'M' || gear === 'S')");
        int neutral = resolver.indexOf("powerOn && gear === 'N'");
        int park = resolver.indexOf("powerOn && gear === 'P'");
        int plugged = resolver.indexOf("charging.plugged === true", park);
        assertTrue(fault >= 0 && charging > fault);
        assertTrue(reverse > charging && drive > reverse);
        assertTrue(neutral > drive && park > neutral);
        assertTrue(plugged > park);
    }

    @Test
    public void statusRefreshPromotesDriveStateIntoHeroAndMainInfo()
            throws IOException {
        String page = readRepositoryFile("app/src/main/assets/web/local/index.html");
        String refresh = between(
                page,
                "updateFromStatus: function(status) {",
                "openDirections: function() {");

        assertTrue(refresh.contains("var driveState = this.updateDriveState(status);"));
        assertTrue(refresh.contains("setText('dashAcc', tOnOff(driveState.powerOn));"));
        assertTrue(page.contains("glance.classList.add('vehicle-' + state.mode);"));
        assertTrue(page.contains("data-i18n=\"dashboard.metric_power\""));
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue("missing start marker: " + startMarker, start >= 0);
        assertTrue("missing end marker: " + endMarker, end > start);
        return source.substring(start, end);
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
