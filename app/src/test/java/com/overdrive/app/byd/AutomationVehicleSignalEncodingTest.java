package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Locks automation-facing BYD enum mappings to the connected-unit SDK contracts. */
public class AutomationVehicleSignalEncodingTest {

    @Test
    public void tyrePressureStatesUseConnectedSdkEncoding() {
        assertEquals(0, BydVehicleData.TYRE_PRESSURE_STATE_NORMAL);
        assertEquals(1, BydVehicleData.TYRE_PRESSURE_STATE_OVERPRESSURE);
        assertEquals(2, BydVehicleData.TYRE_PRESSURE_STATE_UNDERPRESSURE);
        assertTrue(BydVehicleData.isTyreOverpressureState(1));
        assertFalse(BydVehicleData.isTyreOverpressureState(2));
        assertTrue(BydVehicleData.isTyreUnderpressureState(2));
        assertFalse(BydVehicleData.isTyreUnderpressureState(1));
    }

    @Test
    public void drlAndAutoHeadlightRejectUnavailableValues() {
        assertEquals(1, BydDataCollector.normalizeDrlState(1));
        assertEquals(0, BydDataCollector.normalizeDrlState(2));
        assertEquals(BydVehicleData.UNAVAILABLE, BydDataCollector.normalizeDrlState(0));
        assertEquals(BydVehicleData.UNAVAILABLE, BydDataCollector.normalizeDrlState(-10011));

        assertEquals(0, BydDataCollector.normalizeAutoHeadlightState(0));
        assertEquals(1, BydDataCollector.normalizeAutoHeadlightState(1));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeAutoHeadlightState(2));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeAutoHeadlightState(-10011));
    }

    @Test
    public void autoWiperSupportsCurrentAndLegacyDomainsWithoutGuessing() {
        assertEquals(1, BydDataCollector.normalizeAutoWiperSettingState(1));
        assertEquals(0, BydDataCollector.normalizeAutoWiperSettingState(2));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeAutoWiperSettingState(0));

        assertEquals(0, BydDataCollector.normalizeAutoWiperBodyworkState(0));
        assertEquals(1, BydDataCollector.normalizeAutoWiperBodyworkState(1));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeAutoWiperBodyworkState(2));
    }

    @Test
    public void wiperActivityCombinesDedicatedAndSettingRails() {
        assertEquals(1, BydDataCollector.normalizeWiperActivity(8, 0));
        assertEquals(1, BydDataCollector.normalizeWiperActivity(9, 1));
        assertEquals(1, BydDataCollector.normalizeWiperActivity(0, 2));
        assertEquals(0, BydDataCollector.normalizeWiperActivity(0, 1));
        assertEquals(BydVehicleData.UNAVAILABLE, BydDataCollector.normalizeWiperActivity(
                BydVehicleData.UNAVAILABLE, 0));
        assertEquals(BydVehicleData.UNAVAILABLE, BydDataCollector.normalizeWiperActivity(
                BydVehicleData.UNAVAILABLE, 4));
        assertEquals(BydVehicleData.UNAVAILABLE, BydDataCollector.normalizeWiperActivity(
                BydVehicleData.UNAVAILABLE, 255));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeWiperActivity(-10011, -10011));
    }

    @Test
    public void sunroofAndSunshadeCommandsUseSdkMotionEncoding() {
        assertEquals(1, BydDataCollector.sunWindowVoiceCommand(1));
        assertEquals(2, BydDataCollector.sunWindowVoiceCommand(2));
        assertEquals(4, BydDataCollector.sunWindowVoiceCommand(3));
        assertEquals(3, BydDataCollector.sunWindowVoiceCommand(4));
        assertEquals(5, BydDataCollector.sunWindowVoiceCommand(5));
        assertEquals(-1, BydDataCollector.sunWindowVoiceCommand(0));
        assertEquals(-1, BydDataCollector.sunWindowVoiceCommand(6));
        assertEquals(1, BydDataCollector.diLink5HazardCommand(true));
        assertEquals(2, BydDataCollector.diLink5HazardCommand(false));
    }

    @Test
    public void headlightSelectorUsesTheSdkDomain() {
        assertEquals(1, BydDataCollector.HEADLIGHT_MODE_OFF);
        assertEquals(2, BydDataCollector.HEADLIGHT_MODE_AUTO);
        assertEquals(3, BydDataCollector.HEADLIGHT_MODE_PARKING);
        assertEquals(4, BydDataCollector.HEADLIGHT_MODE_LOW_BEAM);
    }

    @Test
    public void speedFactorNeverUsesDistanceDisplayPreference() throws Exception {
        BydDataCollector collector = BydDataCollector.getInstance();
        java.lang.reflect.Field detected =
                BydDataCollector.class.getDeclaredField("hwUnitDetected");
        java.lang.reflect.Field hardware =
                BydDataCollector.class.getDeclaredField("speedHwFactor");
        java.lang.reflect.Field display =
                BydDataCollector.class.getDeclaredField("distanceToKmFactor");
        detected.setAccessible(true);
        hardware.setAccessible(true);
        display.setAccessible(true);

        boolean previousDetected = detected.getBoolean(collector);
        double previousHardware = hardware.getDouble(collector);
        double previousDisplay = display.getDouble(collector);
        try {
            detected.setBoolean(collector, true);
            hardware.setDouble(collector, 1.0);
            display.setDouble(collector, 1.60934);
            assertEquals(1.0, collector.getSpeedToKmhFactor(), 0.000001);

            detected.setBoolean(collector, false);
            assertEquals(1.0, collector.getSpeedToKmhFactor(), 0.000001);
        } finally {
            detected.setBoolean(collector, previousDetected);
            hardware.setDouble(collector, previousHardware);
            display.setDouble(collector, previousDisplay);
        }
    }

    @Test
    public void mileageUnitMappingsPreserveLegacyAndSeparateUnitTwoSpeed() {
        assertEquals(1.60934,
                BydDataCollector.distanceToKmFactorForMileageUnit(0), 0.000001);
        assertEquals(1.60934,
                BydDataCollector.speedToKmhFactorForMileageUnit(0), 0.000001);
        assertEquals(1.0,
                BydDataCollector.distanceToKmFactorForMileageUnit(1), 0.000001);
        assertEquals(1.0,
                BydDataCollector.speedToKmhFactorForMileageUnit(1), 0.000001);
        assertEquals(1.60934,
                BydDataCollector.distanceToKmFactorForMileageUnit(2), 0.000001);
        assertEquals(1.0,
                BydDataCollector.speedToKmhFactorForMileageUnit(2), 0.000001);
        org.junit.Assert.assertTrue(Double.isNaN(
                BydDataCollector.distanceToKmFactorForMileageUnit(99)));
        org.junit.Assert.assertTrue(Double.isNaN(
                BydDataCollector.speedToKmhFactorForMileageUnit(99)));
    }

    @Test
    public void distanceDisplayIsIndependentWithHardwareAndFallbackWithoutIt()
            throws Exception {
        BydDataCollector collector = BydDataCollector.getInstance();
        java.lang.reflect.Field detected =
                BydDataCollector.class.getDeclaredField("distanceHwUnitDetected");
        java.lang.reflect.Field hardware =
                BydDataCollector.class.getDeclaredField("distanceHwFactor");
        java.lang.reflect.Field effective =
                BydDataCollector.class.getDeclaredField("distanceToKmFactor");
        java.lang.reflect.Field display =
                BydDataCollector.class.getDeclaredField("milesDisplayMode");
        java.lang.reflect.Field unitDetected =
                BydDataCollector.class.getDeclaredField("unitDetected");
        detected.setAccessible(true);
        hardware.setAccessible(true);
        effective.setAccessible(true);
        display.setAccessible(true);
        unitDetected.setAccessible(true);

        boolean previousDetected = detected.getBoolean(collector);
        double previousHardware = hardware.getDouble(collector);
        double previousEffective = effective.getDouble(collector);
        boolean previousDisplay = display.getBoolean(collector);
        boolean previousUnitDetected = unitDetected.getBoolean(collector);
        try {
            double speedFactor = collector.getSpeedToKmhFactor();
            detected.setBoolean(collector, true);
            hardware.setDouble(collector, 1.60934);
            effective.setDouble(collector, 1.60934);
            collector.setDistanceUnitOverride("km");
            assertFalse(collector.isMilesMode());
            assertEquals(1.60934, collector.getDistanceToKmFactor(), 0.000001);
            assertEquals(1.60934, collector.getRawDistanceToKmFactor(), 0.000001);
            assertEquals(speedFactor, collector.getSpeedToKmhFactor(), 0.000001);

            collector.setDistanceUnitOverride("mi");
            assertTrue(collector.isMilesMode());
            assertEquals(1.60934, collector.getDistanceToKmFactor(), 0.000001);
            assertEquals(speedFactor, collector.getSpeedToKmhFactor(), 0.000001);

            detected.setBoolean(collector, false);
            collector.setDistanceUnitOverride("km");
            assertFalse(collector.isMilesMode());
            assertEquals(1.0, collector.getRawDistanceToKmFactor(), 0.000001);
            assertEquals(speedFactor, collector.getSpeedToKmhFactor(), 0.000001);

            collector.setDistanceUnitOverride("mi");
            assertTrue(collector.isMilesMode());
            assertEquals(1.60934, collector.getRawDistanceToKmFactor(), 0.000001);
            assertEquals(speedFactor, collector.getSpeedToKmhFactor(), 0.000001);
        } finally {
            detected.setBoolean(collector, previousDetected);
            hardware.setDouble(collector, previousHardware);
            effective.setDouble(collector, previousEffective);
            display.setBoolean(collector, previousDisplay);
            unitDetected.setBoolean(collector, previousUnitDetected);
        }
    }

    @Test
    public void rawSpeedConversionRejectsNonReadings() {
        assertEquals(80.467,
                BydDataCollector.convertRawSpeedToKmh(50.0, 1.60934), 0.000001);
        org.junit.Assert.assertTrue(Double.isNaN(
                BydDataCollector.convertRawSpeedToKmh(
                        BydFeatureIds.SDK_NOT_AVAILABLE, 1.0)));
        org.junit.Assert.assertTrue(Double.isNaN(
                BydDataCollector.convertRawSpeedToKmh(-1.0, 1.0)));
        org.junit.Assert.assertTrue(Double.isNaN(
                BydDataCollector.convertRawSpeedToKmh(Double.NaN, 1.0)));
        org.junit.Assert.assertTrue(Double.isNaN(
                BydDataCollector.convertRawSpeedToKmh(65535.0, 1.0)));
    }

    @Test
    public void pedalNormalizationRejectsSdkRails() {
        assertEquals(0, BydDataCollector.normalizePedalPercent(0));
        assertEquals(100, BydDataCollector.normalizePedalPercent(100));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizePedalPercent(101));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizePedalPercent(65535));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizePedalPercent(-10011));
    }
}
