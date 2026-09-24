package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.BydVehicleData;

import org.junit.Test;

public class GearMonitorFreshnessTest {

    @Test
    public void coldStoppedAndStaleGearAreNotObserved() {
        assertFalse(GearMonitor.isObservationFresh(false, 1_000L, 900L));
        assertFalse(GearMonitor.isObservationFresh(true, 1_000L, 0L));
        assertFalse(GearMonitor.isObservationFresh(true, 900L, 1_000L));
        assertTrue(GearMonitor.isObservationFresh(true, 1_000L, 1L));
        assertFalse(GearMonitor.isObservationFresh(true, 1_001L, 1L));
        assertTrue(GearMonitor.isObservationFresh(
                true, 5_000L, 1L, 5_000L));
        assertFalse(GearMonitor.isObservationFresh(
                true, 5_001L, 1L, 5_000L));
    }

    @Test
    public void shiftModeDecodeNeverManufacturesParkFromInvalidInput() {
        assertEquals(GearMonitor.GEAR_P, GearMonitor.decodeShiftMode(0));
        assertEquals(GearMonitor.GEAR_R, GearMonitor.decodeShiftMode(2));
        assertEquals(GearMonitor.GEAR_N, GearMonitor.decodeShiftMode(3));
        assertEquals(GearMonitor.GEAR_D, GearMonitor.decodeShiftMode(4));
        assertEquals(BydVehicleData.UNAVAILABLE,
                GearMonitor.decodeShiftMode(255));
        assertFalse(GearMonitor.isValidGearMode(BydVehicleData.UNAVAILABLE));
    }
}
