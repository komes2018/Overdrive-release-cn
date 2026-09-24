package com.overdrive.app.mqtt;

import com.overdrive.app.byd.BydVehicleData;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.routing.VehicleCommandRouter;

import org.junit.Test;

/** Ensures all ingress surfaces reject malformed catalog payloads before actuation. */
public class VehicleControlCatalogSafetyTest {

    @Test
    public void lightTogglesIgnoreUnknownStaleReadback() {
        BydVehicleData unknown = new BydVehicleData.Builder()
                .dayTimeLight(true)
                .hazard(true)
                .lightKnownMask(BydVehicleData.LIGHT_KNOWN_NONE)
                .build();

        assertNull(VehicleControlCatalog
                .get("drl").toAction(null, "toggle", unknown));
        assertNull(VehicleControlCatalog
                .get("hazard").toAction(null, "toggle", unknown));
        assertNull(VehicleControlCatalog
                .get("drl").toAction(null, "toggle", null));
        assertNull(VehicleControlCatalog
                .get("hazard").toAction(null, "toggle", null));

        BydVehicleData knownOn = unknown.toBuilder()
                .lightKnownMask(BydVehicleData.LIGHT_KNOWN_DRL
                        | BydVehicleData.LIGHT_KNOWN_TURN_HAZARD)
                .build();
        VehicleCommandRouter.LightsCommand drlKnown =
                (VehicleCommandRouter.LightsCommand) VehicleControlCatalog
                        .get("drl").toAction(null, "toggle", knownOn).command;
        VehicleCommandRouter.HazardCommand hazardKnown =
                (VehicleCommandRouter.HazardCommand) VehicleControlCatalog
                        .get("hazard").toAction(null, "toggle", knownOn).command;
        assertFalse(drlKnown.drlOn);
        assertFalse(hazardKnown.on);
    }

    @Test
    public void rejectsMalformedSwitchSelectCoverAndNumberPayloads() {
        assertNull(VehicleControlCatalog.get("drl").toAction(null, "enabled", null));
        assertNull(VehicleControlCatalog.get("adas_aeb").toAction(null, "off", null));
        assertNull(VehicleControlCatalog.get("adas_aeb").toAction(null, "toggle", null));
        assertNull(VehicleControlCatalog.get("seat_heat_driver").toAction(null, "turbo", null));
        assertNull(VehicleControlCatalog.get("windows_all").toAction(null, "RAISE", null));
        assertNull(VehicleControlCatalog.get("ambient_colour").toAction(null, "32", null));
        assertNull(VehicleControlCatalog.get("ambient_colour").toAction(null, "one", null));
        assertNull(VehicleControlCatalog.get("powertrain_mode").toAction(null, "", null));
        assertNull(VehicleControlCatalog.get("powertrain_mode").toAction(null, "hybrid", null));
        assertNull(VehicleControlCatalog.powertrainModeValue(null));
        assertNull(VehicleControlCatalog.powertrainModeValue("not-a-mode"));
        assertNull(VehicleControlCatalog.powertrainModeValue("force_ev"));
        assertNull(VehicleControlCatalog.powertrainModeValue("fuel"));
        assertNull(VehicleControlCatalog.powertrainModeValue("keep"));
        assertEquals(Integer.valueOf(1), VehicleControlCatalog.powertrainModeValue("ev"));
        assertEquals(Integer.valueOf(3), VehicleControlCatalog.powertrainModeValue("hev"));
    }

    @Test
    public void everyCatalogPlatformRejectsInvalidPayloadAndUnexpectedSubtopic() {
        for (VehicleControlCatalog.ControlEntity entity : VehicleControlCatalog.all()) {
            assertNull(entity.key + " must reject arbitrary payloads",
                    entity.toAction(null, "__invalid__", null));

            if (!"climate".equals(entity.platform)) {
                assertNull(entity.key + " must reject unexpected subtopics",
                        entity.toAction("position", validPayload(entity), null));
            }
        }
    }

    @Test
    public void eachCatalogPlatformRetainsItsAdvertisedValidPayloads() {
        for (VehicleControlCatalog.ControlEntity entity : VehicleControlCatalog.all()) {
            if ("climate".equals(entity.platform)) continue;
            assertNotNull(entity.key + " must accept a valid " + entity.platform + " payload",
                    entity.toAction(null, validPayload(entity), null));
        }

        VehicleControlCatalog.ControlEntity climate = VehicleControlCatalog.get("climate");
        assertNotNull(climate.toAction("mode", "auto", null));
        assertNotNull(climate.toAction("temperature", "22", null));
        assertNotNull(climate.toAction("fan_mode", "1", null));
    }

    @Test
    public void numberControlsEnforceTheirPublishedStep() {
        VehicleControlCatalog.ControlEntity chargeCap =
                VehicleControlCatalog.get("charge_cap_percent");

        assertNotNull(chargeCap.toAction(null, "50", null));
        assertNotNull(chargeCap.toAction(null, "55", null));
        assertNull(chargeCap.toAction(null, "51", null));

        for (VehicleControlCatalog.ControlEntity entity : VehicleControlCatalog.all()) {
            if (!"number".equals(entity.platform) || entity.step <= 1d
                    || entity.min + 1d > entity.max) {
                continue;
            }
            assertNull(entity.key + " must reject values outside its declared step",
                    entity.toAction(null, String.valueOf((int) entity.min + 1), null));
        }
    }

    @Test
    public void climateRejectsInvalidModesTemperaturesAndFanLevels() {
        VehicleControlCatalog.ControlEntity climate = VehicleControlCatalog.get("climate");

        assertNull(climate.toAction(null, "auto", null));
        assertNull(climate.toAction("mode", "heat", null));
        assertNull(climate.toAction("temperature", "NaN", null));
        assertNull(climate.toAction("temperature", "34", null));
        assertNull(climate.toAction("temperature", "22.5", null));
        assertNull(climate.toAction("fan_mode", "0", null));
        assertNull(climate.toAction("fan_mode", "fast", null));
    }

    @Test
    public void tailgateAndWindowsUseSafeFallbackAwareCommands() {
        VehicleControlCatalog.ControlAction tailgate =
                VehicleControlCatalog.get("tailgate").toAction(null, "OPEN", null);
        VehicleControlCatalog.ControlAction windows =
                VehicleControlCatalog.get("windows_all").toAction(null, "OPEN", null);
        VehicleControlCatalog.ControlAction vent =
                VehicleControlCatalog.get("windows_vent").toAction(null, "PRESS", null);

        assertTrue(tailgate.command instanceof VehicleCommandRouter.TrunkOpenCommand);
        assertTrue(windows.command instanceof VehicleCommandRouter.OpenAllWindowsCommand);
        assertTrue(vent.command instanceof VehicleCommandRouter.VentAllWindowsCommand);
        assertTrue(tailgate.command.hasCloudPath());
        assertTrue(tailgate.command.hasSdkPath());
        assertTrue(windows.command.hasSdkPath());
        assertFalse(windows.command.hasCloudPath());
        assertTrue(vent.command.hasCloudPath());
        assertTrue(vent.command.hasSdkPath());
        assertTrue(tailgate.command.allowCloudFallbackFromMqtt());
        assertFalse(windows.command.allowCloudFallbackFromMqtt());
        assertTrue(vent.command.allowCloudFallbackFromMqtt());
        assertTrue(new VehicleCommandRouter.ClimateOnCommand(22).allowCloudFallbackFromMqtt());
        assertTrue(new VehicleCommandRouter.SmartChargingToggleCommand(true)
                .allowCloudFallbackFromMqtt());
        assertTrue(new VehicleCommandRouter.ChargeScheduleCommand("22:00", "06:00", "e", true)
                .allowCloudFallbackFromMqtt());
        assertTrue(new VehicleCommandRouter.StartChargingNowCommand()
                .allowCloudFallbackFromMqtt());
        assertFalse(new VehicleCommandRouter.MirrorFoldCommand(true).allowCloudFallbackFromMqtt());
    }

    @Test
    public void cloudOnlyClimateAndSmartChargeControlsHaveStrictMqttPayloads() throws Exception {
        VehicleControlCatalog.ControlAction climate = VehicleControlCatalog
                .get("remote_climate_start")
                .toAction(null, "{\"temp\":15,\"durationMinutes\":25}", null);
        assertNotNull(climate);
        VehicleCommandRouter.ClimateOnCommand climateCommand =
                (VehicleCommandRouter.ClimateOnCommand) climate.command;
        assertEquals(15.0, climateCommand.tempCelsius, 0.0);
        assertEquals(25, climateCommand.remoteDurationMinutes);
        assertNull(VehicleControlCatalog.get("remote_climate_start")
                .toAction(null, "{\"temp\":15,\"durationMinutes\":12}", null));
        long future = (System.currentTimeMillis() / 1000L) + 120L;
        VehicleControlCatalog.ControlAction create = VehicleControlCatalog
                .get("remote_climate_schedule")
                .toAction(null, "{\"action\":\"create\",\"bookingTime\":\"" + future
                        + "\",\"temp\":15,\"durationMinutes\":25}", null);
        assertNotNull(create);
        assertTrue(create.command instanceof VehicleCommandRouter.ClimateScheduleCommand);
        VehicleCommandRouter.ClimateScheduleCommand createCommand =
                (VehicleCommandRouter.ClimateScheduleCommand) create.command;
        assertEquals(VehicleCommandRouter.ClimateScheduleCommand.CREATE, createCommand.mode);
        assertEquals(Long.valueOf(future), createCommand.bookingTimeSeconds);
        assertTrue(VehicleControlCatalog.get("remote_climate_schedule").toAction(null,
                "{\"action\":\"delete\",\"bookingId\":\"1216038691305533440\"}", null).command
                instanceof VehicleCommandRouter.ClimateScheduleCommand);
        assertNull(VehicleControlCatalog.get("remote_climate_schedule").toAction(null,
                "{\"action\":\"update\",\"bookingId\":\"1216038691305533440\","
                        + "\"bookingTime\":\"1\",\"temp\":22,\"durationMinutes\":20}", null));
        assertNull(VehicleControlCatalog.get("remote_climate_schedule").toAction(null,
                "{\"action\":\"delete\",\"bookingId\":\"1216038691305533440.0\"}", null));

        VehicleControlCatalog.ControlAction toggle = VehicleControlCatalog.get("smart_charging")
                .toAction(null, "on", null);
        assertTrue(toggle.command instanceof VehicleCommandRouter.SmartChargingToggleCommand);
        VehicleControlCatalog.ControlEntity smartCharging =
                VehicleControlCatalog.get("smart_charging");
        assertNull(smartCharging.stateKey);
        org.json.JSONObject smartChargingComponent =
                smartCharging.component("overdrive/test/control", "test-device");
        assertFalse(smartChargingComponent.has("state_topic"));
        assertTrue(smartChargingComponent.getBoolean("optimistic"));
        assertTrue(VehicleControlCatalog.get("start_charging_now")
                .toAction(null, "PRESS", null).command
                instanceof VehicleCommandRouter.StartChargingNowCommand);
        assertTrue(VehicleControlCatalog.get("smart_charge_schedule").toAction(null,
                "{\"startChargeTime\":\"22:00\",\"endChargeTime\":\"06:00\","
                        + "\"chargeWay\":\"e\",\"enabled\":true}", null).command
                instanceof VehicleCommandRouter.ChargeScheduleCommand);
        assertNull(VehicleControlCatalog.get("smart_charge_schedule").toAction(null,
                "{\"startChargeTime\":\"24:00\",\"endChargeTime\":\"06:00\","
                        + "\"chargeWay\":\"e\",\"enabled\":true}", null));
    }

    @Test
    public void tailgateStopUsesTheUrgentMqttPathWithoutReorderingANewerOpen() throws Exception {
        VehicleControlCatalog.ControlEntity tailgate = VehicleControlCatalog.get("tailgate");

        assertTrue(MqttCommandRouter.isTailgateOpen(tailgate, null, " OPEN "));
        assertTrue(MqttCommandRouter.isTailgateStop(tailgate, null, "stop"));
        assertFalse(MqttCommandRouter.isTailgateOpen(tailgate, "position", "OPEN"));
        assertFalse(MqttCommandRouter.isTailgateStop(tailgate, null, "CLOSE"));

        MqttCommandRouter router = new MqttCommandRouter("test", (key, value) -> {});
        try {
            java.lang.reflect.Field normal = MqttCommandRouter.class.getDeclaredField("exec");
            java.lang.reflect.Field urgent = MqttCommandRouter.class.getDeclaredField("urgentExec");
            normal.setAccessible(true);
            urgent.setAccessible(true);
            assertFalse(normal.get(router) == urgent.get(router));
        } finally {
            router.shutdown();
        }

        String source = readMqttRouterSource();
        assertTrue(source.contains("router.abortPendingTailgateOpen();"));
        assertTrue(source.contains("urgentExec.submit("));
        assertTrue(source.contains("router.bindTailgateOpenStopGeneration("));
        assertTrue(source.contains("router.bindTailgateStopCancellation(command)"));
        assertTrue(source.contains("awaitTailgateStop(precedingTailgateStop)"));
        assertTrue(source.contains("tailgateStopBarrier.set(stop)"));
        assertTrue(source.contains("private final Object tailgateIngressLock"));
        assertTrue(source.contains("synchronized (tailgateIngressLock)"));
        assertTrue(source.contains(
                "long tailgateOpenGeneration = router.captureTailgateOpenStopGeneration();"));
    }

    @Test
    public void seatCatalogUsesThreeLevelsAndNormalizesLegacyMediumToHigh() {
        VehicleControlCatalog.ControlEntity driverHeat =
                VehicleControlCatalog.get("seat_heat_driver");

        assertEquals(java.util.Arrays.asList("off", "low", "high"), driverHeat.options);
        assertNull(driverHeat.toAction(null, "3", null));

        VehicleControlCatalog.ControlAction legacy = driverHeat.toAction(null, "medium", null);
        assertNotNull(legacy);
        VehicleCommandRouter.SeatHeatCommand command =
                (VehicleCommandRouter.SeatHeatCommand) legacy.command;
        assertEquals(2, command.level);
        assertEquals(2, command.driverHeat);
        assertEquals("high", legacy.echoValue);

        VehicleControlCatalog.ControlAction high = driverHeat.toAction(null, "high", null);
        assertNotNull(high);
        assertEquals(2, ((VehicleCommandRouter.SeatHeatCommand) high.command).level);
        assertFalse(((VehicleCommandRouter.SeatHeatCommand) high.command).level == 3);
    }

    @Test
    public void seatCatalogKeepsCloudFallbackIntentWhenLocalStateIsUnavailable() {
        VehicleControlCatalog.ControlAction action = VehicleControlCatalog.get("seat_heat_driver")
                .toAction(null, "low", null);

        assertNotNull(action);
        assertTrue(action.command.hasCloudPath());
        assertTrue(action.command.allowCloudFallbackFromMqtt());
    }

    private static String validPayload(VehicleControlCatalog.ControlEntity entity) {
        switch (entity.platform) {
            case "switch":
                return "on";
            case "cover":
                return "OPEN";
            case "number":
                return String.valueOf((int) entity.min);
            case "select":
                return entity.options.get(0);
            case "button":
                return "PRESS";
            case "text":
                if ("remote_climate_start".equals(entity.key)) {
                    return "{\"temp\":22,\"durationMinutes\":20}";
                }
                if ("remote_climate_schedule".equals(entity.key)) {
                    return "{\"action\":\"delete\",\"bookingId\":\"1216038691305533440\"}";
                }
                if ("smart_charge_schedule".equals(entity.key)) {
                    return "{\"startChargeTime\":\"22:00\",\"endChargeTime\":\"06:00\","
                            + "\"chargeWay\":\"e\",\"enabled\":true}";
                }
                throw new AssertionError("Unexpected text catalog entity: " + entity.key);
            case "lock":
                return "LOCK";
            default:
                throw new AssertionError("Unexpected catalog platform: " + entity.platform);
        }
    }

    private static String readMqttRouterSource() throws Exception {
        java.nio.file.Path fromModule = java.nio.file.Paths.get(
                "src/main/java/com/overdrive/app/mqtt/MqttCommandRouter.java");
        if (java.nio.file.Files.exists(fromModule)) {
            return new String(java.nio.file.Files.readAllBytes(fromModule),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "app/src/main/java/com/overdrive/app/mqtt/MqttCommandRouter.java")),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
