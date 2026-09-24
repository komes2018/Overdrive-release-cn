package com.overdrive.app.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.monitor.GearMonitor;

import org.json.JSONObject;
import org.junit.Test;

public class MqttLightValidityTest {

    @Test
    public void diLink5UnknownLightsAndWiperPublishRetainedTombstones()
            throws Exception {
        BydVehicleData data = new BydVehicleData.Builder()
                .leftTurnState(1)
                .rightTurnState(1)
                .lowBeam(true)
                .highBeam(true)
                .frontFog(true)
                .rearFog(true)
                .hazard(true)
                .dayTimeLight(true)
                .wiperState(BydVehicleData.UNAVAILABLE)
                .lightKnownMask(BydVehicleData.LIGHT_KNOWN_NONE)
                .build();
        JSONObject payload = new JSONObject();

        MqttConnectionManager.putLightTelemetry(payload, data, true);
        MqttConnectionManager.putWiperTelemetry(payload, data, true);

        String[] keys = {
                "light_left_turn", "light_right_turn",
                "light_low_beam", "light_high_beam",
                "light_front_fog", "light_rear_fog",
                "light_hazard", "light_drl", "wiper_state"
        };
        for (String key : keys) {
            assertTrue(key, payload.has(key));
            assertTrue(key, payload.isNull(key));
        }
    }

    @Test
    public void diLink5KnownFalseRemainsFalse() throws Exception {
        int known = BydVehicleData.LIGHT_KNOWN_LOW_BEAM
                | BydVehicleData.LIGHT_KNOWN_TURN_HAZARD
                | BydVehicleData.LIGHT_KNOWN_DRL;
        BydVehicleData data = new BydVehicleData.Builder()
                .leftTurnState(0)
                .rightTurnState(1)
                .lowBeam(false)
                .highBeam(true)
                .hazard(false)
                .dayTimeLight(true)
                .wiperState(0)
                .lightKnownMask(known)
                .build();
        JSONObject payload = new JSONObject();

        MqttConnectionManager.putLightTelemetry(payload, data, true);
        MqttConnectionManager.putWiperTelemetry(payload, data, true);

        assertEquals(0, payload.getInt("light_left_turn"));
        assertEquals(1, payload.getInt("light_right_turn"));
        assertEquals(0, payload.getInt("light_low_beam"));
        assertEquals(0, payload.getInt("light_hazard"));
        assertEquals(1, payload.getInt("light_drl"));
        assertTrue(payload.isNull("light_high_beam"));
        assertEquals(0, payload.getInt("wiper_state"));
    }

    @Test
    public void legacyTelemetryIgnoresTheMaskAndKeepsPriorFieldBehavior()
            throws Exception {
        BydVehicleData data = new BydVehicleData.Builder()
                .rightTurnState(1)
                .lowBeam(false)
                .highBeam(true)
                .frontFog(false)
                .rearFog(true)
                .hazard(false)
                .dayTimeLight(true)
                .wiperState(BydVehicleData.UNAVAILABLE)
                .lightKnownMask(BydVehicleData.LIGHT_KNOWN_NONE)
                .build();
        JSONObject payload = new JSONObject();

        MqttConnectionManager.putLightTelemetry(payload, data, false);
        MqttConnectionManager.putWiperTelemetry(payload, data, false);

        assertFalse(payload.has("light_left_turn"));
        assertEquals(1, payload.getInt("light_right_turn"));
        assertEquals(0, payload.getInt("light_low_beam"));
        assertEquals(1, payload.getInt("light_high_beam"));
        assertEquals(0, payload.getInt("light_front_fog"));
        assertEquals(1, payload.getInt("light_rear_fog"));
        assertEquals(0, payload.getInt("light_hazard"));
        assertEquals(1, payload.getInt("light_drl"));
        assertFalse(payload.has("wiper_state"));
    }

    @Test
    public void diLink5ClearsUnknownChargingTypeAndV2l() throws Exception {
        JSONObject unknown = new JSONObject();
        MqttConnectionManager.putChargingTypeTelemetry(
                unknown,
                new BydVehicleData.Builder()
                        .chargingType(BydVehicleData.UNAVAILABLE)
                        .chargingGunState(2)
                        .build(),
                true);
        assertTrue(unknown.isNull("charging_type"));
        assertTrue(unknown.isNull("charging_v2l"));

        JSONObject known = new JSONObject();
        MqttConnectionManager.putChargingTypeTelemetry(
                known,
                new BydVehicleData.Builder()
                        .chargingType(0)
                        .chargingGunState(2)
                        .build(),
                true);
        assertEquals(0, known.getInt("charging_type"));
        assertEquals(0, known.getInt("charging_v2l"));
    }

    @Test
    public void diLink5UnknownDetailGroupsPublishRetainedTombstones()
            throws Exception {
        JSONObject payload = new JSONObject();
        MqttConnectionManager.putDiLink5UnknownStateTelemetry(payload);

        String[] keys = {
                "charging_state", "charging_gun", "charging_mode",
                "charging_pct", "tyre_p_fl", "tyre_p_state_fr",
                "tyre_leak_rl", "tyre_signal_rr", "tyre_t_fl",
                "tyre_system_state", "ambient_enabled", "ac_on",
                "ac_fan", "temp_unit", "climate_setpoint",
                "climate_setpoint_passenger", "drift_mode",
                "speed_limit_warning"
        };
        for (String key : keys) {
            assertTrue(key, payload.has(key));
            assertTrue(key, payload.isNull(key));
        }
    }

    @Test
    public void tyreStateSentinelsPreserveLegacyWireBehaviorOnly()
            throws Exception {
        String[] keys = {
                "tyre_p_state_fl", "tyre_p_state_fr",
                "tyre_p_state_rl", "tyre_p_state_rr"
        };
        int[] values = {BydVehicleData.UNAVAILABLE, 0, 1, 0};

        JSONObject legacy = new JSONObject();
        MqttConnectionManager.putTyreStateTelemetry(
                legacy, keys, values, false);
        assertEquals(
                BydVehicleData.UNAVAILABLE,
                legacy.getInt("tyre_p_state_fl"));

        JSONObject diLink5 = new JSONObject();
        MqttConnectionManager.putDiLink5UnknownStateTelemetry(diLink5);
        MqttConnectionManager.putTyreStateTelemetry(
                diLink5, keys, values, true);
        assertTrue(diLink5.isNull("tyre_p_state_fl"));
        assertEquals(0, diLink5.getInt("tyre_p_state_fr"));
        assertEquals(1, diLink5.getInt("tyre_p_state_rl"));
    }

    @Test
    public void diLink5PrimitiveDefaultsStayUnknownButKnownFalsePublishes()
            throws Exception {
        JSONObject unknown = new JSONObject();
        MqttConnectionManager.putPrimitiveValidityTelemetry(
                unknown, new BydVehicleData.Builder().build(), true);
        assertTrue(unknown.isNull("ambient_colour"));
        assertTrue(unknown.isNull("drift_mode"));
        assertTrue(unknown.isNull("speed_limit_warning"));

        JSONObject knownFalse = new JSONObject();
        MqttConnectionManager.putPrimitiveValidityTelemetry(
                knownFalse,
                new BydVehicleData.Builder()
                        .ambientColour(1)
                        .driftModeEnabled(false)
                        .speedLimitWarning(false)
                        .build(),
                true);
        assertEquals(1, knownFalse.getInt("ambient_colour"));
        assertEquals(0, knownFalse.getInt("drift_mode"));
        assertEquals(0, knownFalse.getInt("speed_limit_warning"));

        JSONObject legacy = new JSONObject();
        MqttConnectionManager.putPrimitiveValidityTelemetry(
                legacy, new BydVehicleData.Builder().build(), false);
        assertEquals(1, legacy.getInt("ambient_colour"));
        assertEquals(0, legacy.getInt("drift_mode"));
        assertEquals(0, legacy.getInt("speed_limit_warning"));
    }

    @Test
    public void diLink5MotionWinsUntilAccOffIsFreshAndAuthoritative() {
        assertEquals(Boolean.FALSE, MqttConnectionManager.resolveDiLink5Parked(
                false, false, false, 0.0, GearMonitor.GEAR_D));
        assertEquals(Boolean.FALSE, MqttConnectionManager.resolveDiLink5Parked(
                false, true, true, 1.0, BydVehicleData.UNAVAILABLE));
        assertEquals(Boolean.TRUE, MqttConnectionManager.resolveDiLink5Parked(
                false, true, true, Double.NaN, BydVehicleData.UNAVAILABLE));
        assertEquals(null, MqttConnectionManager.resolveDiLink5Parked(
                false, false, false, Double.NaN, BydVehicleData.UNAVAILABLE));
    }
}
