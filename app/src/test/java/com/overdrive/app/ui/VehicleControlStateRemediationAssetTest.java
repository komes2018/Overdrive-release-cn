package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards asynchronous state reconciliation in the Vehicle Control web asset. */
public class VehicleControlStateRemediationAssetTest {

    @Test
    public void windowTargetsOnlyRenderAfterTheirCurrentCommandSucceeds() throws IOException {
        String script = readVehicleControlScript();

        assertTrue(script.contains("_windowCommandRevisions"));
        assertTrue(script.contains("if (self._windowCommandRevisions[area] !== revision) return;"));
        int accepted = script.indexOf("if (result && result.success) {");
        int markPreset = script.indexOf("self.markWindowPreset(area, target);", accepted);
        assertTrue("preset selection must follow a successful response", markPreset > accepted);
        assertTrue(script.contains("self.updateWindowBars();\n                                    self.fetchState();"));
        assertTrue(script.contains("var open = data.windowOpen || {};"));
        assertTrue(script.contains("this.translatedText('vehicle.open', 'Open')"));
    }

    @Test
    public void climateAndSeatFailuresRestoreOptimisticStateAndRespectRemoteClimate()
            throws IOException {
        String script = readVehicleControlScript();

        assertTrue(script.contains("function submitClimateValue(field, next, request)"));
        assertTrue(script.contains("self.vehicleState[property] = previous;"));
        assertTrue(script.contains("self.finishClimateMutation(field, revision)"));
        assertTrue(script.contains("data.climate.remoteClimateActive === true"));
        assertTrue(script.contains(
                "&& climatePowerRevision === self._climatePowerRevision\n"
                        + "                        && !self._climatePending.power)"));
        assertTrue(script.contains("var seatCommandRevision = this._seatCommandRevision;"));
        assertTrue(script.contains("function submitSeatLevel(pos, kind, level, previous)"));
        assertTrue(script.contains("self.vehicleState.seatHeat = previous.heat;"));
        assertTrue(script.contains("self.vehicleState.seatCool = previous.cool;"));
        assertTrue(script.contains("self._seatPending = 0;"));
    }

    @Test
    public void successfulSeatCommandShowsRoutedToastBeforeOptionalVfx() throws IOException {
        String script = readVehicleControlScript();
        int submit = script.indexOf("function submitSeatLevel(pos, kind, level, previous)");
        int success = script.indexOf("if (result && result.success) {", submit);
        int toast = script.indexOf("self.toastFromResult({", success);
        int vfx = script.indexOf("showSeatLevelVfx(pos, kind, level);", toast);

        assertTrue("seat success branch must use the routed response", toast > success);
        assertTrue("toast must be visible even when optional VFX fails", vfx > toast);
        assertTrue(script.contains("message: feedbackMessage || result.message"));
        assertTrue(script.contains("path: result.path"));
        assertTrue(readRepositoryFile("app/src/main/assets/web/shared/core.js")
                .contains("if (text == null || text === '')"));
    }

    @Test
    public void chargingReadsCannotOverwriteNewerEditsOrCommands() throws IOException {
        String script = readVehicleControlScript();

        assertTrue(script.contains("_chargeCapFetchRevision"));
        assertTrue(script.contains("_chargeCapPendingRevision"));
        assertTrue(script.contains("_chargingScheduleFetchRevision"));
        assertTrue(script.contains("stateRevision !== self._chargeCapRevision"));
        assertTrue(script.contains("|| self._chargeCapPendingRevision) return;"));
        assertTrue(script.contains("stateRevision !== self._chargingScheduleRevision"));
        assertTrue(script.contains("|| self._scheduleDirty\n                    || self._smartChargePending) return;"));
        assertTrue(script.contains("|| self._smartChargePending) return;"));
        assertTrue(script.contains("if (!self.beginSmartChargeRequest('toggle', 'btnSmartChargeToggle')) return;"));
        assertTrue(script.contains("if (!self.beginSmartChargeRequest('start', 'btnStartCharging')) return;"));
        assertTrue(script.contains("if (!self.beginSmartChargeRequest('save', 'btnChargeScheduleSave')) return;"));
        assertTrue(script.contains("var pending = !!this._smartChargePending;"));
        assertTrue(script.contains("btn.disabled = unsupported || pending;"));
        assertTrue(script.contains("saveBtn.disabled = unsupported || pending;"));
    }

    @Test
    public void failedChargeCapToggleResponseRestoresAndRefreshesCurrentState()
            throws IOException {
        String script = readVehicleControlScript();
        int toggleStart = script.indexOf("this.bindBtn('btnChargeCapToggle'");
        int sliderStart = script.indexOf("var capSlider =", toggleStart);
        String toggle = script.substring(toggleStart, sliderStart);

        int failure = toggle.indexOf("} else {");
        int restore = toggle.indexOf("self.updateChargeCapUI();", failure);
        int refresh = toggle.indexOf("self.fetchChargeCap();", restore);
        assertTrue("toggle failure branch must restore the rendered state", restore > failure);
        assertTrue("toggle failure branch must fetch fresh verified state", refresh > restore);
    }

    @Test
    public void userScheduleEditsMarkTheFetchGenerationDirty() throws IOException {
        String script = readVehicleControlScript();

        assertTrue(script.contains("markScheduleDirty: function()"));
        assertTrue(script.contains("this._scheduleDirty = true;"));
        assertTrue(script.contains("self.markScheduleDirty();"));
        assertFalse(script.contains("self.apiPost('/api/vehicle/climate', { action: 'set_temp', zone: 1, temp: t });"));
        assertFalse(script.contains("self.apiPost('/api/vehicle/seat', {\n                        action: 'heating'"));
    }

    private static String readVehicleControlScript() throws IOException {
        return readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");
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
