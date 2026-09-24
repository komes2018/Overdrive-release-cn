package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.hardware.bydauto.safetybelt.AbsBYDAutoSafetyBeltListener;

import org.junit.Test;

/** Regression coverage for passenger-occupancy sources that do not rely on a belt buckle. */
public class PassengerOccupancyTest {

    @Test
    public void normalizesOnlyDocumentedPassengerStates() {
        assertEquals(0, BydDataCollector.normalizePassengerStatus(0));
        assertEquals(1, BydDataCollector.normalizePassengerStatus(1));
        assertEquals(BydVehicleData.UNAVAILABLE, BydDataCollector.normalizePassengerStatus(2));
        assertEquals(BydVehicleData.UNAVAILABLE, BydDataCollector.normalizePassengerStatus(-1));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizePassengerStatus(Integer.MIN_VALUE));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizePassengerStatus(65535));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizePassengerStatus(0.5d));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizePassengerStatus(1.5d));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizePassengerStatus(Double.NaN));
        assertEquals(BydVehicleData.UNAVAILABLE, BydDataCollector.normalizePassengerStatus(null));
        assertEquals(BydVehicleData.UNAVAILABLE, BydDataCollector.normalizePassengerStatus("1"));
    }

    @Test
    public void normalizesOnlyDocumentedFrontPassengerBeltCallbacks() {
        assertEquals(0, BydDataCollector.normalizePassengerSeatbeltCallback(2, 0));
        assertEquals(1, BydDataCollector.normalizePassengerSeatbeltCallback(2, 1));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizePassengerSeatbeltCallback(1, 1));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizePassengerSeatbeltCallback(2, 2));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizePassengerSeatbeltCallback(null, 1));
    }

    @Test
    public void readsDedicatedSeatbeltStateWithoutTreatingInvalidAsUnbuckled() {
        FakeDedicatedSeatbeltDevice device = new FakeDedicatedSeatbeltDevice();

        device.state = 1;
        assertEquals(1, BydDataCollector.readDedicatedSeatbeltState(device, 2));
        device.state = 0;
        assertEquals(0, BydDataCollector.readDedicatedSeatbeltState(device, 2));
        device.state = 2;
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.readDedicatedSeatbeltState(device, 2));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.readDedicatedSeatbeltState(new Object(), 2));
    }

    @Test
    public void preservesDirectPassengerSensorBeforeApplyingEstimate() {
        assertEquals(0, BydDataCollector.resolvePassengerOccupancy(0, true, 1, true));
        assertEquals(1, BydDataCollector.resolvePassengerOccupancy(1, false, 0, false));
    }

    @Test
    public void estimatesPassengerPresentFromReminderOrEstablishedBuckledBelt() {
        assertEquals(1, BydDataCollector.resolvePassengerOccupancy(
                BydVehicleData.UNAVAILABLE, true, 0, false));
        assertEquals(1, BydDataCollector.resolvePassengerOccupancy(
                BydVehicleData.UNAVAILABLE, false, 1, true));
    }

    @Test
    public void estimatesPassengerEmptyFromValidUnbuckledBeltOnly() {
        assertEquals(0, BydDataCollector.resolvePassengerOccupancy(
                BydVehicleData.UNAVAILABLE, false, 0, false));
        assertEquals(BydVehicleData.UNAVAILABLE, BydDataCollector.resolvePassengerOccupancy(
                BydVehicleData.UNAVAILABLE, false, BydVehicleData.UNAVAILABLE, false));
    }

    @Test
    public void ignoresBootTimeBuckledPassengerBeltUntilItIsEstablished() {
        assertEquals(BydVehicleData.UNAVAILABLE, BydDataCollector.resolvePassengerOccupancy(
                BydVehicleData.UNAVAILABLE, false, 1, false));
    }

    @Test
    public void typedSafetyBeltListenerForwardsPassengerCallback() {
        FakeSafetyBeltDevice device = new FakeSafetyBeltDevice();
        final int[] received = new int[2];

        assertTrue(BydDeviceHelper.registerSafetyBeltListener(device, (method, args) -> {
            assertEquals("onPassengerStatusChanged", method);
            received[0] = ((Number) args[0]).intValue();
            received[1] = ((Number) args[1]).intValue();
        }));

        device.listener.onPassengerStatusChanged(1, 1);
        assertEquals(1, received[0]);
        assertEquals(1, received[1]);
        assertTrue(BydDeviceHelper.unregisterSafetyBeltListener(device));
        assertTrue(device.unregistered);
    }

    @Test
    public void typedSafetyBeltListenerForwardsPassengerBeltCallback() {
        FakeSafetyBeltDevice device = new FakeSafetyBeltDevice();
        final String[] receivedMethod = new String[1];
        final int[] received = new int[2];

        assertTrue(BydDeviceHelper.registerSafetyBeltListener(device, (method, args) -> {
            receivedMethod[0] = method;
            received[0] = ((Number) args[0]).intValue();
            received[1] = ((Number) args[1]).intValue();
        }));

        device.listener.onSafetyBeltStatusChanged(2, 0);
        assertEquals("onSafetyBeltStatusChanged", receivedMethod[0]);
        assertEquals(2, received[0]);
        assertEquals(0, received[1]);
        assertTrue(BydDeviceHelper.unregisterSafetyBeltListener(device));
    }

    @Test
    public void typedSafetyBeltListenerIsRetainedWithoutDuplicateRegistration() {
        FakeSafetyBeltDevice device = new FakeSafetyBeltDevice();

        assertTrue(BydDeviceHelper.registerSafetyBeltListener(device, (method, args) -> {}));
        AbsBYDAutoSafetyBeltListener first = device.listener;
        assertTrue(BydDeviceHelper.registerSafetyBeltListener(device, (method, args) -> {}));

        assertSame(first, device.listener);
        assertTrue(BydDeviceHelper.unregisterSafetyBeltListener(device));
        assertTrue(device.unregistered);
    }

    public static final class FakeSafetyBeltDevice {
        AbsBYDAutoSafetyBeltListener listener;
        boolean unregistered;

        public void registerListener(AbsBYDAutoSafetyBeltListener listener) {
            this.listener = listener;
        }

        public void unregisterListener(AbsBYDAutoSafetyBeltListener listener) {
            unregistered = this.listener == listener;
        }
    }

    public static final class FakeDedicatedSeatbeltDevice {
        int state;

        public int getSafetyBeltStatus(int area) {
            return state;
        }
    }
}
