package com.overdrive.app.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class Di5ParkedKeepAliveSettingsTest {

    @Test
    public void defaultsAreInertAndMatchSeededConfig() {
        Di5ParkedKeepAliveSettings s =
                Di5ParkedKeepAliveSettings.fromSurveillance(new JSONObject(), false);
        assertFalse(s.isEnabled());
        assertFalse(s.hasAnyEffectiveLever());
        assertFalse(s.isMcuHoldEffective());
        assertFalse(s.isMcuPowerHoldEffective());
        assertFalse(s.isCameraHeartbeatEffective());
        assertFalse(s.isApHoldEffective());
        // Lever defaults exist but are only effective under the master switch.
        assertTrue(s.mcuHoldInConfig);
        assertTrue(s.mcuPowerHoldInConfig);
        assertFalse(s.cameraHeartbeatInConfig);
        assertFalse(s.apHoldInConfig);
        assertEquals(30, s.reassertSeconds);
        assertEquals(11.8, s.cutoffVoltage, 1e-9);
        assertEquals(3, s.cutoffSamples);
        assertEquals(120, s.voltageMaxAgeSeconds);
    }

    @Test
    public void nullSectionIsTreatedAsDefaults() {
        Di5ParkedKeepAliveSettings s =
                Di5ParkedKeepAliveSettings.fromSurveillance(null, false);
        assertFalse(s.isEnabled());
        assertEquals(Di5ParkedKeepAliveSettings.disabled().toString(), s.toString());
    }

    @Test
    public void masterSwitchEnablesOnlyMcuHoldByDefault() throws Exception {
        JSONObject surv = new JSONObject().put(Di5ParkedKeepAliveSettings.KEY_ENABLED, true);
        Di5ParkedKeepAliveSettings s = Di5ParkedKeepAliveSettings.fromSurveillance(surv, false);
        assertTrue(s.isEnabled());
        assertTrue(s.isMcuHoldEffective());
        // OEM-parity power hold rides the MCU hold by default.
        assertTrue(s.isMcuPowerHoldEffective());
        assertFalse(s.isCameraHeartbeatEffective());
        assertFalse(s.isApHoldEffective());
        assertTrue(s.hasAnyEffectiveLever());
    }

    @Test
    public void mcuPowerHoldRidesTheMcuHoldLever() throws Exception {
        // Escape hatch: master ON, power hold explicitly OFF.
        JSONObject hatchOff = new JSONObject()
                .put(Di5ParkedKeepAliveSettings.KEY_ENABLED, true)
                .put(Di5ParkedKeepAliveSettings.KEY_MCU_POWER_HOLD, false);
        Di5ParkedKeepAliveSettings s1 =
                Di5ParkedKeepAliveSettings.fromSurveillance(hatchOff, false);
        assertTrue(s1.isMcuHoldEffective());
        assertFalse(s1.isMcuPowerHoldEffective());
        // MCU hold lever off pulls the power hold with it, even when its own
        // key is true — the power hold is part of the MCU hold, not a fourth lever.
        JSONObject mcuOff = new JSONObject()
                .put(Di5ParkedKeepAliveSettings.KEY_ENABLED, true)
                .put(Di5ParkedKeepAliveSettings.KEY_MCU_HOLD, false)
                .put(Di5ParkedKeepAliveSettings.KEY_MCU_POWER_HOLD, true);
        Di5ParkedKeepAliveSettings s2 =
                Di5ParkedKeepAliveSettings.fromSurveillance(mcuOff, false);
        assertFalse(s2.isMcuHoldEffective());
        assertFalse(s2.isMcuPowerHoldEffective());
        // Kill switch beats everything.
        Di5ParkedKeepAliveSettings s3 = Di5ParkedKeepAliveSettings.fromSurveillance(
                new JSONObject().put(Di5ParkedKeepAliveSettings.KEY_ENABLED, true), true);
        assertFalse(s3.isMcuPowerHoldEffective());
    }

    @Test
    public void killSwitchForcesEverythingOff() throws Exception {
        JSONObject surv = new JSONObject()
                .put(Di5ParkedKeepAliveSettings.KEY_ENABLED, true)
                .put(Di5ParkedKeepAliveSettings.KEY_CAMERA_HEARTBEAT, true)
                .put(Di5ParkedKeepAliveSettings.KEY_AP_HOLD, true)
                .put(Di5ParkedKeepAliveSettings.KEY_AP_HOLD_PREFLIGHT_PASSED, true);
        Di5ParkedKeepAliveSettings s = Di5ParkedKeepAliveSettings.fromSurveillance(surv, true);
        assertTrue(s.enabledInConfig);
        assertFalse(s.isEnabled());
        assertFalse(s.hasAnyEffectiveLever());
        assertTrue(s.killSwitchActive);
    }

    @Test
    public void killSwitchValueParsing() {
        assertTrue(Di5ParkedKeepAliveSettings.isKillSwitchValue("0"));
        assertTrue(Di5ParkedKeepAliveSettings.isKillSwitchValue(" false "));
        assertTrue(Di5ParkedKeepAliveSettings.isKillSwitchValue("OFF"));
        assertFalse(Di5ParkedKeepAliveSettings.isKillSwitchValue(""));
        assertFalse(Di5ParkedKeepAliveSettings.isKillSwitchValue(null));
        assertFalse(Di5ParkedKeepAliveSettings.isKillSwitchValue("1"));
    }

    @Test
    public void apHoldRequiresRecordedPreflight() throws Exception {
        JSONObject surv = new JSONObject()
                .put(Di5ParkedKeepAliveSettings.KEY_ENABLED, true)
                .put(Di5ParkedKeepAliveSettings.KEY_AP_HOLD, true);
        Di5ParkedKeepAliveSettings noPreflight =
                Di5ParkedKeepAliveSettings.fromSurveillance(surv, false);
        assertFalse(noPreflight.isApHoldEffective());

        surv.put(Di5ParkedKeepAliveSettings.KEY_AP_HOLD_PREFLIGHT_PASSED, true);
        Di5ParkedKeepAliveSettings withPreflight =
                Di5ParkedKeepAliveSettings.fromSurveillance(surv, false);
        assertTrue(withPreflight.isApHoldEffective());
    }

    @Test
    public void numericKeysAreClampedAndMalformedValuesFallBack() throws Exception {
        JSONObject surv = new JSONObject()
                .put(Di5ParkedKeepAliveSettings.KEY_REASSERT_SECONDS, 1)
                .put(Di5ParkedKeepAliveSettings.KEY_CUTOFF_VOLTAGE, 99.0)
                .put(Di5ParkedKeepAliveSettings.KEY_CUTOFF_SAMPLES, 0)
                .put(Di5ParkedKeepAliveSettings.KEY_VOLTAGE_MAX_AGE_SECONDS, "not-a-number");
        Di5ParkedKeepAliveSettings s = Di5ParkedKeepAliveSettings.fromSurveillance(surv, false);
        assertEquals(Di5ParkedKeepAliveSettings.MIN_REASSERT_SECONDS, s.reassertSeconds);
        assertEquals(Di5ParkedKeepAliveSettings.MAX_CUTOFF_VOLTAGE, s.cutoffVoltage, 1e-9);
        assertEquals(Di5ParkedKeepAliveSettings.MIN_CUTOFF_SAMPLES, s.cutoffSamples);
        assertEquals(Di5ParkedKeepAliveSettings.DEFAULT_VOLTAGE_MAX_AGE_SECONDS,
                s.voltageMaxAgeSeconds);
    }

    @Test
    public void toJsonReportsEffectiveValues() throws Exception {
        JSONObject surv = new JSONObject()
                .put(Di5ParkedKeepAliveSettings.KEY_ENABLED, true)
                .put(Di5ParkedKeepAliveSettings.KEY_MCU_HOLD, false);
        JSONObject j = Di5ParkedKeepAliveSettings.fromSurveillance(surv, false).toJson();
        assertTrue(j.getBoolean("enabled"));
        assertFalse(j.getBoolean("mcuHold"));
        assertFalse(j.getBoolean("cameraHeartbeat"));
        assertFalse(j.getBoolean("apHold"));
        assertEquals(30, j.getInt("reassertSeconds"));
    }
}
