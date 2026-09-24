package com.overdrive.app.byd;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.Test;

public class BydVehicleDataDiLink5SnapshotTest {
    @Test
    public void bridgeJsonKeepsTheFieldsConsumedAcrossProcesses() {
        BydVehicleData source = new BydVehicleData.Builder()
                .socPercent(87.0)
                .voltage12v(13.4, 1234L)
                .speedKmh(42.5)
                .accelPercent(31)
                .brakePercent(7)
                .steeringAngleDegrees(-125.5)
                .gearMode(4)
                .operationMode(3)
                .elecRangeKm(420)
                .totalMileageKm(12345)
                .hvPackVoltage(612.5)
                .hvPackCurrentAmps(18.25)
                .hvBatteryPowerKw(11.18)
                .insideTempC(21.75, 4567L)
                .enginePowerKw(-8.5)
                .enginePowerAtMs(5678L)
                .chargingState(2)
                .chargingStateAtMs(6789L)
                .chargingPowerKw(7.25)
                .chargingPowerAtMs(7001L)
                .chargingPowerChangedAtMs(7002L)
                .chargingPowerLastObservedKw(7.2)
                .externalChargingPowerKw(7.1)
                .externalChargingPowerAtMs(7101L)
                .externalChargingPowerChangedAtMs(7102L)
                .externalChargingPowerLastObservedKw(7.0)
                .chargePowerKw(6.9)
                .chargePowerAtMs(7201L)
                .chargePowerChangedAtMs(7202L)
                .chargePowerLastObservedKw(6.8)
                .clusterChargePowerKw(6.7)
                .clusterChargePowerAtMs(7301L)
                .clusterChargePowerChangedAtMs(7302L)
                .clusterChargePowerLastObservedKw(6.6)
                .tyrePressure(new int[]{270, 275, 280, 285})
                .tyreTemperature(new int[]{35, 36, 37, 38})
                .doorLockStatus(new int[]{2, 2, 2, 2})
                .leftTurnState(1)
                .rightTurnState(0)
                .lowBeam(true)
                .dayTimeLight(true)
                .lightAutoStatus(1)
                .seatbeltStatus(new int[]{1, 0})
                .seatHeat(new int[]{2, 0})
                .seatCool(new int[]{0, 1})
                .seatVentilationSupport(new int[]{1, 0})
                .acStartState(1)
                .tempUnit(1)
                .acSetpointDriver(22)
                .acSetpointPassenger(23)
                .wiperState(8)
                .autoWiperState(1)
                .passengerDetection(new int[]{1})
                .availableDevices(new String[]{"Speed", "Light", "AC"})
                .unavailableDevices(new String[]{"Radar"})
                .build();

        BydVehicleData parsed = BydVehicleData.fromJson(source.toBridgeJson());

        assertEquals(source.timestamp, parsed.timestamp);
        assertEquals(87.0, parsed.socPercent, 0.0);
        assertEquals(13.4, parsed.voltage12v, 0.0);
        assertEquals(1234L, parsed.voltage12vAtMs);
        assertEquals(42.5, parsed.speedKmh, 0.0);
        assertEquals(31, parsed.accelPercent);
        assertEquals(7, parsed.brakePercent);
        assertEquals(4, parsed.gearMode);
        assertEquals(3, parsed.operationMode);
        assertEquals(420, parsed.elecRangeKm);
        assertEquals(12345, parsed.totalMileageKm);
        assertEquals(612.5, parsed.hvPackVoltage, 0.0);
        assertEquals(18.25, parsed.hvPackCurrentAmps, 0.0);
        assertEquals(11.18, parsed.hvBatteryPowerKw, 0.0);
        assertEquals(21.75, parsed.insideTempC, 0.0);
        assertEquals(4567L, parsed.insideTempReadAt);
        assertEquals(-8.5, parsed.enginePowerKw, 0.0);
        assertEquals(5678L, parsed.enginePowerAtMs);
        assertEquals(2, parsed.chargingState);
        assertEquals(6789L, parsed.chargingStateAtMs);
        assertEquals(7.25, parsed.chargingPowerKw, 0.0);
        assertEquals(7001L, parsed.chargingPowerAtMs);
        assertEquals(7002L, parsed.chargingPowerChangedAtMs);
        assertEquals(7.2, parsed.chargingPowerLastObservedKw, 0.0);
        assertEquals(7.1, parsed.externalChargingPowerKw, 0.0);
        assertEquals(7101L, parsed.externalChargingPowerAtMs);
        assertEquals(7102L, parsed.externalChargingPowerChangedAtMs);
        assertEquals(7.0, parsed.externalChargingPowerLastObservedKw, 0.0);
        assertEquals(6.9, parsed.chargePowerKw, 0.0);
        assertEquals(7201L, parsed.chargePowerAtMs);
        assertEquals(7202L, parsed.chargePowerChangedAtMs);
        assertEquals(6.8, parsed.chargePowerLastObservedKw, 0.0);
        assertEquals(6.7, parsed.clusterChargePowerKw, 0.0);
        assertEquals(7301L, parsed.clusterChargePowerAtMs);
        assertEquals(7302L, parsed.clusterChargePowerChangedAtMs);
        assertEquals(6.6, parsed.clusterChargePowerLastObservedKw, 0.0);
        assertArrayEquals(new int[]{270, 275, 280, 285}, parsed.tyrePressure);
        assertArrayEquals(new int[]{35, 36, 37, 38}, parsed.tyreTemperature);
        assertArrayEquals(new int[]{2, 2, 2, 2}, parsed.doorLockStatus);
        assertEquals(1, parsed.leftTurnState);
        assertEquals(0, parsed.rightTurnState);
        assertTrue(parsed.lowBeam);
        assertTrue(parsed.dayTimeLight);
        assertTrue(parsed.isLightKnown(BydVehicleData.LIGHT_KNOWN_ALL));
        assertEquals(1, parsed.lightAutoStatus);
        assertArrayEquals(new int[]{1, 0}, parsed.seatbeltStatus);
        assertArrayEquals(new int[]{2, 0}, parsed.seatHeat);
        assertArrayEquals(new int[]{0, 1}, parsed.seatCool);
        assertArrayEquals(new int[]{1, 0}, parsed.seatVentilationSupport);
        assertEquals(1, parsed.acStartState);
        assertEquals(1, parsed.tempUnit);
        assertEquals(22, parsed.acSetpointDriver);
        assertEquals(23, parsed.acSetpointPassenger);
        assertEquals(-125.5, parsed.steeringAngleDegrees, 0.0);
        assertEquals(8, parsed.wiperState);
        assertEquals(1, parsed.autoWiperState);
        assertArrayEquals(new int[]{1}, parsed.passengerDetection);
        assertArrayEquals(new String[]{"Speed", "Light", "AC"}, parsed.availableDevices);
        assertArrayEquals(new String[]{"Radar"}, parsed.unavailableDevices);
    }

    @Test
    public void unavailableSeatClimateValuesRoundTripAsJsonNull() throws Exception {
        BydVehicleData source = new BydVehicleData.Builder()
                .seatHeat(new int[]{BydVehicleData.UNAVAILABLE, 2})
                .seatCool(new int[]{1, BydVehicleData.UNAVAILABLE})
                .seatVentilationSupport(
                        new int[]{1, BydVehicleData.UNAVAILABLE})
                .build();

        org.json.JSONObject json = source.toBridgeJson();
        assertTrue(json.getJSONArray("seatHeat").isNull(0));
        assertEquals(2, json.getJSONArray("seatHeat").getInt(1));
        assertEquals(1, json.getJSONArray("seatCool").getInt(0));
        assertTrue(json.getJSONArray("seatCool").isNull(1));
        assertEquals(1, json.getJSONArray("seatVentilationSupport").getInt(0));
        assertTrue(json.getJSONArray("seatVentilationSupport").isNull(1));

        BydVehicleData parsed = BydVehicleData.fromJson(json);
        assertArrayEquals(source.seatHeat, parsed.seatHeat);
        assertArrayEquals(source.seatCool, parsed.seatCool);
        assertArrayEquals(
                source.seatVentilationSupport,
                parsed.seatVentilationSupport);
    }

    @Test
    public void unavailableTyreValuesRoundTripAsJsonNull() throws Exception {
        int unavailable = BydVehicleData.UNAVAILABLE;
        BydVehicleData source = new BydVehicleData.Builder()
                .tyrePressure(new int[]{unavailable, 270, unavailable, 280})
                .tyrePressureState(new int[]{unavailable, 0, 1, unavailable})
                .tyreAirLeakState(new int[]{0, unavailable, 1, unavailable})
                .tyreSignalState(new int[]{unavailable, 0, unavailable, 1})
                .tyreTemperature(new int[]{unavailable, 30, 31, unavailable})
                .build();

        org.json.JSONObject json = source.toBridgeJson();
        assertTrue(json.getJSONArray("tyrePressure").isNull(0));
        assertTrue(json.getJSONArray("tyrePressureState").isNull(0));
        assertTrue(json.getJSONArray("tyreAirLeakState").isNull(1));
        assertTrue(json.getJSONArray("tyreSignalState").isNull(0));
        assertTrue(json.getJSONArray("tyreTemperature").isNull(0));

        BydVehicleData parsed = BydVehicleData.fromJson(json);
        assertArrayEquals(source.tyrePressure, parsed.tyrePressure);
        assertArrayEquals(source.tyrePressureState, parsed.tyrePressureState);
        assertArrayEquals(source.tyreAirLeakState, parsed.tyreAirLeakState);
        assertArrayEquals(source.tyreSignalState, parsed.tyreSignalState);
        assertArrayEquals(source.tyreTemperature, parsed.tyreTemperature);
    }

    @Test
    public void publicTyreJsonPreservesLegacySentinelsOnlyOutsideDiLink5()
            throws Exception {
        int unavailable = BydVehicleData.UNAVAILABLE;
        BydVehicleData source = new BydVehicleData.Builder()
                .tyrePressure(new int[]{unavailable, 270, 271, 272})
                .tyrePressureState(new int[]{unavailable, 0, 0, 0})
                .tyreAirLeakState(new int[]{unavailable, 0, 0, 0})
                .tyreSignalState(new int[]{unavailable, 0, 0, 0})
                .tyreTemperature(new int[]{unavailable, 30, 31, 32})
                .build();

        org.json.JSONObject legacy = source.toJsonForPlatform(false);
        assertEquals(unavailable, legacy.getJSONArray("tyrePressure").getInt(0));
        assertEquals(unavailable, legacy.getJSONArray("tyrePressureState").getInt(0));
        assertEquals(unavailable, legacy.getJSONArray("tyreAirLeakState").getInt(0));
        assertEquals(unavailable, legacy.getJSONArray("tyreSignalState").getInt(0));
        assertTrue(legacy.getJSONArray("tyreTemperature").isNull(0));

        org.json.JSONObject diLink5 = source.toJsonForPlatform(true);
        assertTrue(diLink5.getJSONArray("tyrePressure").isNull(0));
        assertTrue(diLink5.getJSONArray("tyrePressureState").isNull(0));
        assertTrue(diLink5.getJSONArray("tyreAirLeakState").isNull(0));
        assertTrue(diLink5.getJSONArray("tyreSignalState").isNull(0));
        assertTrue(diLink5.getJSONArray("tyreTemperature").isNull(0));
    }

    @Test
    public void bridgeJsonRoundTripsEverySnapshotField() throws Exception {
        BydVehicleData.Builder builder = new BydVehicleData.Builder();
        long now = System.currentTimeMillis();
        for (Field field : BydVehicleData.Builder.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Class<?> type = field.getType();
            if (type == double.class) {
                field.setDouble(builder, 12.345678);
            } else if (type == int.class) {
                field.setInt(builder, "lightKnownMask".equals(field.getName())
                        ? BydVehicleData.LIGHT_KNOWN_ALL : 2);
            } else if (type == long.class) {
                field.setLong(builder, now);
            } else if (type == boolean.class) {
                field.setBoolean(builder, true);
            } else if (type == String.class) {
                field.set(builder, "value-" + field.getName());
            } else if (type == int[].class) {
                field.set(builder, new int[]{1, 2, 3, 4});
            } else if (type == String[].class) {
                field.set(builder, new String[]{"one", "two"});
            } else {
                throw new AssertionError("Unhandled builder field: " + field.getName());
            }
        }

        BydVehicleData source = builder.build();
        BydVehicleData parsed = BydVehicleData.fromJson(source.toBridgeJson());
        assertNotNull(parsed);

        for (Field field : BydVehicleData.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object expected = field.get(source);
            Object actual = field.get(parsed);
            if (field.getType() == int[].class) {
                assertArrayEquals(field.getName(), (int[]) expected, (int[]) actual);
            } else if (field.getType() == String[].class) {
                assertArrayEquals(field.getName(), (String[]) expected, (String[]) actual);
            } else if (field.getType() == double.class) {
                assertEquals(field.getName(), (Double) expected, (Double) actual, 0.000001);
            } else {
                assertEquals(field.getName(), expected, actual);
            }
        }
    }

    @Test
    public void bridgeKeepsPrecisionWithoutChangingPublicJsonFormatting()
            throws Exception {
        BydVehicleData source = new BydVehicleData.Builder()
                .highCellVoltage(3.456789)
                .build();

        assertEquals(
                3.46,
                source.toJson().getJSONObject("cellVoltage").getDouble("highV"),
                0.0);
        assertEquals(
                3.456789,
                source.toBridgeJson().getJSONObject("cellVoltage").getDouble("highV"),
                0.0);
    }

    @Test
    public void diLink5OnlyFieldsDoNotChangeLegacyPublicJson() throws Exception {
        BydVehicleData source = new BydVehicleData.Builder()
                .hvPackVoltage(612.5)
                .hvPackCurrentAmps(18.25)
                .hvBatteryPowerKw(11.18)
                .dayTimeLight(true)
                .steeringWheelHeat(2)
                .seatVentilationSupport(new int[]{1, 0})
                .ambientColour(8)
                .ambientEnabled(1)
                .speedLimitWarning(true)
                .childPresenceDetection(1)
                .build();

        org.json.JSONObject legacy = source.toJsonForPlatform(false);
        assertFalse(legacy.getJSONObject("battery").has("hvPackVoltage"));
        assertFalse(legacy.getJSONObject("lights").has("dayTimeLight"));
        assertFalse(legacy.has("steeringWheelHeat"));
        assertFalse(legacy.has("seatVentilationSupport"));
        assertFalse(legacy.has("ambientColour"));
        assertFalse(legacy.has("speedLimitWarning"));
        assertFalse(legacy.has("childPresenceDetection"));

        org.json.JSONObject diLink5 = source.toJsonForPlatform(true);
        assertEquals(612.5,
                diLink5.getJSONObject("battery").getDouble("hvPackVoltage"), 0.0);
        assertTrue(diLink5.getJSONObject("lights").getBoolean("dayTimeLight"));
        assertEquals(2, diLink5.getInt("steeringWheelHeat"));
        assertArrayEquals(
                new int[]{1, 0},
                new int[]{
                        diLink5.getJSONArray("seatVentilationSupport").getInt(0),
                        diLink5.getJSONArray("seatVentilationSupport").getInt(1)
                });
        assertEquals(8, diLink5.getInt("ambientColour"));
        assertTrue(diLink5.getBoolean("speedLimitWarning"));
        assertEquals(1, diLink5.getInt("childPresenceDetection"));
    }

    @Test
    public void diLink5PrimitiveValiditySurvivesTheBridge() throws Exception {
        BydVehicleData unknown = new BydVehicleData.Builder().build();
        org.json.JSONObject unknownBridge = unknown.toBridgeJson();
        assertFalse(unknownBridge.has("ambientColour"));
        assertFalse(unknownBridge.has("speedLimitWarning"));
        assertFalse(unknownBridge.optJSONObject("extendedBodywork")
                != null && unknownBridge.getJSONObject("extendedBodywork")
                .has("driftModeEnabled"));

        BydVehicleData knownFalse = new BydVehicleData.Builder()
                .ambientColour(1)
                .speedLimitWarning(false)
                .driftModeEnabled(false)
                .build();
        BydVehicleData parsed =
                BydVehicleData.fromJson(knownFalse.toBridgeJson());
        assertTrue(parsed.ambientColourKnown);
        assertEquals(1, parsed.ambientColour);
        assertTrue(parsed.speedLimitWarningKnown);
        assertFalse(parsed.speedLimitWarning);
        assertTrue(parsed.driftModeKnown);
        assertFalse(parsed.driftModeEnabled);
    }

    @Test
    public void lightValidityStaysInternalAndOmitsOnlyUnknownDiLink5Values()
            throws Exception {
        int known = BydVehicleData.LIGHT_KNOWN_LOW_BEAM
                | BydVehicleData.LIGHT_KNOWN_TURN_HAZARD;
        BydVehicleData source = new BydVehicleData.Builder()
                .leftTurnState(0)
                .rightTurnState(0)
                .lowBeam(false)
                .highBeam(true)
                .frontFog(true)
                .rearFog(true)
                .hazard(false)
                .dayTimeLight(true)
                .lightKnownMask(known)
                .build();

        org.json.JSONObject publicJson = source.toJsonForPlatform(true);
        org.json.JSONObject lights = publicJson.getJSONObject("lights");
        assertTrue(lights.has("lowBeam"));
        assertFalse(lights.getBoolean("lowBeam"));
        assertTrue(lights.has("hazard"));
        assertFalse(lights.getBoolean("hazard"));
        assertEquals(0, lights.getInt("leftTurn"));
        assertEquals(0, lights.getInt("rightTurn"));
        assertFalse(lights.has("highBeam"));
        assertFalse(lights.has("frontFog"));
        assertFalse(lights.has("rearFog"));
        assertFalse(lights.has("dayTimeLight"));
        assertFalse(publicJson.has("_snapshotMeta"));

        org.json.JSONObject bridge = source.toBridgeJson();
        assertEquals(known, bridge.getJSONObject("_snapshotMeta")
                .getInt("lightKnownMask"));
        BydVehicleData parsed = BydVehicleData.fromJson(bridge);
        assertTrue(parsed.isLightKnown(
                BydVehicleData.LIGHT_KNOWN_LOW_BEAM));
        assertTrue(parsed.isLightKnown(
                BydVehicleData.LIGHT_KNOWN_TURN_HAZARD));
        assertFalse(parsed.isLightKnown(
                BydVehicleData.LIGHT_KNOWN_HIGH_BEAM));
        assertFalse(parsed.isLightKnown(BydVehicleData.LIGHT_KNOWN_DRL));

        org.json.JSONObject legacy = source.toJsonForPlatform(false)
                .getJSONObject("lights");
        assertTrue(legacy.has("lowBeam"));
        assertTrue(legacy.has("highBeam"));
        assertTrue(legacy.has("frontFog"));
        assertTrue(legacy.has("rearFog"));
        assertTrue(legacy.has("hazard"));
        assertFalse(legacy.has("dayTimeLight"));
    }

    @Test
    public void unknownChildPresenceDetectionIsOmittedAndStaysUnavailable() {
        BydVehicleData source = new BydVehicleData.Builder().build();

        assertFalse(source.toJsonForPlatform(true)
                .has("childPresenceDetection"));

        BydVehicleData parsed =
                BydVehicleData.fromJson(source.toBridgeJson());
        assertNotNull(parsed);
        assertEquals(
                BydVehicleData.UNAVAILABLE,
                parsed.childPresenceDetection);
    }

    @Test
    public void staleBridgeCannotKeepDrivingTelemetryActive() {
        BydVehicleData source = new BydVehicleData.Builder()
                .socPercent(87.0)
                .speedKmh(42.5)
                .accelPercent(31)
                .brakePercent(7)
                .steeringAngleDegrees(-125.5)
                .gearMode(4)
                .build();

        BydVehicleData cleared =
                BydDataCollector.clearStaleDiLink5DrivingFields(source);

        assertNotSame(source, cleared);
        assertTrue(Double.isNaN(cleared.speedKmh));
        assertEquals(BydVehicleData.UNAVAILABLE, cleared.gearMode);
        assertEquals(BydVehicleData.UNAVAILABLE, cleared.accelPercent);
        assertEquals(BydVehicleData.UNAVAILABLE, cleared.brakePercent);
        assertTrue(Double.isNaN(cleared.steeringAngleDegrees));
        assertEquals(source.timestamp, cleared.timestamp);
        assertEquals(87.0, cleared.socPercent, 0.0);
        assertSame(cleared,
                BydDataCollector.clearStaleDiLink5DrivingFields(cleared));
    }

    @Test
    public void staleWholeBridgeClearsLightsAndWipers() {
        BydVehicleData source = new BydVehicleData.Builder()
                .lowBeam(true)
                .hazard(true)
                .dayTimeLight(true)
                .wiperState(1)
                .autoWiperState(1)
                .build();

        BydVehicleData cleared =
                BydDataCollector.clearStaleDiLink5VehicleFields(source);

        assertFalse(cleared.isLightKnown(
                BydVehicleData.LIGHT_KNOWN_LOW_BEAM));
        assertFalse(cleared.isLightKnown(
                BydVehicleData.LIGHT_KNOWN_TURN_HAZARD));
        assertFalse(cleared.isLightKnown(BydVehicleData.LIGHT_KNOWN_DRL));
        assertEquals(BydVehicleData.UNAVAILABLE, cleared.wiperState);
        assertEquals(BydVehicleData.UNAVAILABLE, cleared.autoWiperState);
    }

    @Test
    public void staleBridgeCannotDriveAutomations() {
        long now = 100_000L;
        assertTrue(BydDataCollector.isDiLink5AutomationSnapshotFresh(
                now, now - 15_000L));
        assertFalse(BydDataCollector.isDiLink5AutomationSnapshotFresh(
                now, now - 15_001L));
        assertFalse(BydDataCollector.isDiLink5AutomationSnapshotFresh(now, 0L));
        assertFalse(BydDataCollector.isDiLink5AutomationSnapshotFresh(
                now, now + 1L));
    }
}
