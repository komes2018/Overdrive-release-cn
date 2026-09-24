package com.overdrive.app.automation.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards remote and hybrid cloud-capable controls from becoming API-only again. */
public class CloudAutomationControlCoverageTest {

    @Test
    public void cloudControlsAreSelectableAutomationActions() {
        Actions actions = new Actions();

        assertApiAction(actions, "windowsVent", 0);
        assertApiAction(actions, "remoteClimateSchedule", 3);
        assertApiAction(actions, "remoteClimateScheduleUpdate", 4);
        assertApiAction(actions, "remoteClimateScheduleDelete", 1);
        assertApiAction(actions, "smartCharging", 1);
        assertApiAction(actions, "chargingSchedule", 4);
        assertApiAction(actions, "startChargingNow", 0);
    }

    @Test
    public void cloudControlsUseTheirDedicatedVehicleEndpoints() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/automation/action/Actions.java");
        String normalized = source.replace("\\\"", "\"");

        assertTrue(source.contains("new Label(\"windowsVent\", \"automation.vent_windows\")"));
        assertTrue(source.contains("/api/vehicle/window"));
        assertTrue(normalized.contains("{\"action\":\"vent\"}"));
        assertTrue(source.contains("/api/vehicle/climate-schedule"));
        assertTrue(source.contains("/api/vehicle/charging-schedule"));
        assertTrue(source.contains("/api/vehicle/start-charging"));
        assertTrue(normalized.contains("\"bookingTime\":\"${bookingTime}\""));
        assertTrue(normalized.contains("\"durationMinutes\":${durationMinutes}"));
        assertTrue(normalized.contains("\"bookingId\":\"${bookingId}\""));
        assertTrue(normalized.contains("\"startChargeTime\":\"${startChargeTime}\""));
    }

    @Test
    public void englishLabelsExistForTheNewCloudActions() throws Exception {
        JSONObject english = new JSONObject(readRepositoryFile(
                "app/src/main/assets/server-i18n/en.json"));
        JSONObject automation = english.getJSONObject("automation");

        for (String key : new String[] {
                "vent_windows",
                "vent_windows_description",
                "remote_climate_schedule",
                "remote_climate_schedule_description",
                "remote_climate_schedule_update",
                "remote_climate_schedule_update_description",
                "remote_climate_schedule_delete",
                "remote_climate_schedule_delete_description",
                "smart_charging",
                "smart_charging_description",
                "charging_schedule",
                "charging_schedule_description",
                "start_charging",
                "start_charging_description"
        }) {
            assertTrue("Missing automation." + key, automation.has(key));
        }
    }

    private static void assertApiAction(Actions actions, String id, int variableCount) {
        Action action = actions.getAction(id);
        assertTrue(id + " must be an ApiAction", action instanceof ApiAction);
        assertEquals(variableCount, ((ApiAction) action).getVariables().size());
    }

    private static String readRepositoryFile(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 6 && current != null; depth++, current = current.getParent()) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
