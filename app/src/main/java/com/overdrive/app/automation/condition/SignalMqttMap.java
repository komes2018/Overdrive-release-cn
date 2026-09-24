package com.overdrive.app.automation.condition;

import java.util.HashMap;
import java.util.Map;

/**
 * Automation signal id → the MQTT telemetry key publishing the SAME fact.
 *
 * <p>Purely a read-only cross-reference for the trigger/condition picker, for users who
 * drive one car from both automations and Home Assistant. The two surfaces name things
 * differently on purpose — signals are attribute-addressed camelCase in the condition
 * grammar ({@code speed:units=kmph}), MQTT keys are flat snake_case HA entities — and
 * renaming either side would break saved automations ({@code Automation.fromJson} drops a
 * rule whose condition id no longer resolves) or HA entity ids (derived from the field key
 * in {@code HomeAssistantDiscovery}). So the names stay put and this maps between them.
 *
 * <p><b>Cosmetic only — never stored, never resolved against.</b> Mirrors
 * {@link com.overdrive.app.automation.AutomationCategories}: an unmapped id yields null and
 * the picker simply shows no twin, so a newly-added signal can never break by being absent.
 *
 * <p>Every entry below was verified against the actual {@code payload.put(...)} call in
 * {@code MqttConnectionManager} — NOT inferred from name similarity, which is wrong in
 * several cases worth knowing about:
 * <ul>
 *   <li>{@code power} (signal) is the IGNITION level and maps to {@code power_level};
 *       the MQTT key {@code power} is drive-motor kW, an unrelated fact.</li>
 *   <li>{@code temperature} maps to {@code inside_temp} ({@code insideTempC}, the field the
 *       signal reads), not the similarly-named {@code cabin_temp} ({@code insideTempCelsius},
 *       a different reader).</li>
 *   <li>{@code batterySoh} maps to {@code soh_oem} ({@code sohPercent}, what the signal
 *       publishes); MQTT {@code soh} is the SohEstimator display value.</li>
 *   <li>{@code chargingState} maps to {@code is_charging} (the fused verdict both use); the
 *       near-identically-named MQTT {@code charging_state} is a raw BMS enum.</li>
 * </ul>
 *
 * <p>A signal with NO twin is deliberately absent rather than mapped to a near-miss: e.g.
 * {@code lock}, {@code energyRegen}, {@code autoWiper}, {@code autoLights}, and every
 * non-vehicle signal (time, wifi, bt, location, surveillance, variable, mqttTrigger) publish
 * nothing comparable. {@code doorState} is the sharpest near-miss and is documented inline
 * below: {@code door_lock} is lock state, not open/closed, and is all-UNAVAILABLE on this HAL.
 *
 * <p>An ATTRIBUTE-addressed signal maps to the key(s) covering the whole family, since one
 * signal id spans every area/seat/side: {@code seatbelt} and {@code window_open} are arrays
 * of slots, {@code pm25} has one key per area. Where the signal is a DERIVED reduction of
 * such a key (e.g. {@code radarNearest} is the closest of {@code radar_distances}; the
 * {@code occupant} driver seat is inferred and published to MQTT at all only for the
 * passenger), the twin is the underlying key — the same fact, not always the same number.
 */
public final class SignalMqttMap {

    private SignalMqttMap() {}

    private static final Map<String, String> MQTT = new HashMap<>();
    static {
        // ── Core driving / energy ──
        // NOTE the deliberate cross-over: signal `power` is ignition (off/acc/on).
        MQTT.put("power", "power_level");
        MQTT.put("batteryLevel", "soc");
        MQTT.put("targetSoc", "target_soc");
        MQTT.put("estimatedRange", "ev_range_km");
        MQTT.put("gear", "gear");
        MQTT.put("speed", "speed");
        MQTT.put("accelerator", "accel_pct");
        MQTT.put("brake", "brake_pct");
        MQTT.put("steeringAngle", "steering_deg");
        MQTT.put("slope", "slope_deg");
        MQTT.put("driveMode", "op_mode");
        MQTT.put("powertrainMode", "energy_mode");
        // ── Charging / batteries ──
        MQTT.put("chargingState", "is_charging");
        MQTT.put("chargeGun", "charging_gun");
        MQTT.put("batterySoh", "soh_oem");
        MQTT.put("keyBattery", "key_battery");
        MQTT.put("aux12vBattery", "batt_12v_level");
        // The SAME 12V rail as a number. The signal is DECIVOLTS (135 = 13.5V) so it can be
        // compared as an int; MQTT publishes real volts. Same fact, different scale.
        MQTT.put("aux12vVoltage", "volt_12v");
        MQTT.put("fuelLevel", "fuel_pct");
        // ── Climate / temperature ──
        MQTT.put("ac", "ac_on");
        MQTT.put("temperature", "inside_temp");
        MQTT.put("outsideTemp", "ext_temp");
        MQTT.put("acSetpoint", "climate_setpoint");
        MQTT.put("steeringHeat", "steering_wheel_heat");
        // ── Lighting ── (the signal's `area` attribute picks which light_* key)
        MQTT.put("lights", "light_low_beam / light_high_beam / light_hazard / light_drl");
        MQTT.put("ambient", "ambient_enabled");
        // turnSignal is deliberately NOT mapped: it reads the combined
        // getTurnLightFlashState (readTurnNow), whereas light_left_turn/light_right_turn
        // publish the per-side getTurnLightState that is dead on this firmware. Same
        // concept, different source — claiming a twin would point users at a stuck key.
        // ── Windows / body ──
        MQTT.put("windowOpenPercent", "window_open");
        MQTT.put("windowState", "window_open");
        MQTT.put("wiperActive", "wiper_state");
        // doorState is deliberately NOT mapped to door_lock: that key is LOCK state, a
        // different fact from open/closed — and on this HAL it is published all-UNAVAILABLE
        // (see BydDataCollector.collectDoorLocks; lock state comes from the cloud path). A
        // twin pointing at a permanently-null key would be worse than no twin.
        // Per-seat heat/cool level. The signal's `type` attribute picks which key.
        MQTT.put("seatClimate", "seat_heat / seat_cool");
        // ── ADAS / safety ──
        MQTT.put("slw", "speed_limit_warning");
        MQTT.put("cpd", "child_presence_detection");
        MQTT.put("seatbelt", "seatbelt");
        MQTT.put("occupant", "passenger_detection");
        MQTT.put("radarNearest", "radar_distances");
        // Worst-corner reductions of a per-corner MQTT key family (the signal publishes the
        // most urgent state across the four wheels; MQTT publishes each wheel separately).
        MQTT.put("tyrePressureWarn",
                "tyre_p_state_fl / tyre_p_state_fr / tyre_p_state_rl / tyre_p_state_rr");
        MQTT.put("tyreLeakWarn",
                "tyre_leak_fl / tyre_leak_fr / tyre_leak_rl / tyre_leak_rr");
        // ── Air quality ── (one key per area; the signal's `area` attribute picks which)
        MQTT.put("pm25", "pm25_inside / pm25_outside");
    }

    /**
     * The MQTT telemetry key twinning this signal id, or null when the signal has no
     * MQTT counterpart (the common case for time/network/surveillance signals).
     */
    public static String forId(String id) {
        return id == null ? null : MQTT.get(id);
    }
}
