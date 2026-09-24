package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.hardware.bydauto.collectdata.AbsBYDAutoCollectDataListener;
import android.hardware.bydauto.energy.AbsBYDAutoEnergyListener;
import android.hardware.bydauto.setting.AbsBYDAutoSettingListener;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class BydDeviceHelperListenerRecoveryTest {

    @Test
    public void refreshUsesTheSameListenerAndCapitalizedCollectDataUnregister() {
        FakeCollectDataDevice device = new FakeCollectDataDevice();
        AtomicInteger callbacks = new AtomicInteger();

        assertTrue(BydDeviceHelper.registerCollectDataListener(
                device,
                (method, args) -> callbacks.incrementAndGet()));
        assertEquals(1, device.registerCount);

        assertEquals(1, BydDeviceHelper.refreshRetainedListeners(device));
        assertEquals(1, device.unregisterCount);
        assertEquals(2, device.registerCount);
        assertSame(device.firstListener, device.listener);

        device.listener.onDriverMotorSpeed(10, 20);
        assertEquals(1, callbacks.get());

        assertEquals(1, BydDeviceHelper.unregisterRetainedListeners(device));
        assertEquals(2, device.unregisterCount);
    }

    @Test
    public void failedReregisterRemainsEligibleForRetry() {
        FakeCollectDataDevice device = new FakeCollectDataDevice();
        assertTrue(BydDeviceHelper.registerCollectDataListener(
                device, (method, args) -> {}));

        device.failNextRegister = true;
        assertEquals(0, BydDeviceHelper.refreshRetainedListeners(device));
        assertTrue(BydDeviceHelper.hasDetachedRetainedListeners(device));

        assertEquals(1, BydDeviceHelper.refreshRetainedListeners(device));
        assertEquals(1, BydDeviceHelper.unregisterRetainedListeners(device));
    }

    @Test
    public void energyRegistrationUsesTheConcreteSdkSignatureAndRecovers() {
        FakeEnergyDevice device = new FakeEnergyDevice();
        AtomicInteger callbacks = new AtomicInteger();

        assertTrue(BydDeviceHelper.registerEnergyListener(
                device,
                (method, args) -> callbacks.incrementAndGet()));
        assertEquals(1, device.registerCount);

        device.listener.onEnergyModeChanged(3);
        assertEquals(1, callbacks.get());

        assertEquals(1, BydDeviceHelper.refreshRetainedListeners(device));
        assertEquals(1, device.unregisterCount);
        assertEquals(2, device.registerCount);
        assertSame(device.firstListener, device.listener);
        assertEquals(1, BydDeviceHelper.unregisterRetainedListeners(device));
    }

    @Test
    public void settingRegistrationForwardsTheExactCpdCallback() {
        FakeSettingDevice device = new FakeSettingDevice();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicInteger value = new AtomicInteger();

        assertTrue(BydDeviceHelper.registerSettingListener(
                device,
                (name, args) -> {
                    method.set(name);
                    value.set(((Number) args[0]).intValue());
                }));

        device.listener.onCpdImsSwitchStateChanged(3);

        assertEquals("onCpdImsSwitchStateChanged", method.get());
        assertEquals(3, value.get());
        assertEquals(1, BydDeviceHelper.unregisterRetainedListeners(device));
    }

    public static final class FakeCollectDataDevice {
        AbsBYDAutoCollectDataListener listener;
        AbsBYDAutoCollectDataListener firstListener;
        int registerCount;
        int unregisterCount;
        boolean failNextRegister;

        public void registerListener(AbsBYDAutoCollectDataListener value) {
            if (failNextRegister) {
                failNextRegister = false;
                throw new IllegalStateException("transient");
            }
            listener = value;
            if (firstListener == null) firstListener = value;
            registerCount++;
        }

        public void unRegisterListener(AbsBYDAutoCollectDataListener value) {
            assertSame(listener, value);
            unregisterCount++;
        }
    }

    public static final class FakeEnergyDevice {
        AbsBYDAutoEnergyListener listener;
        AbsBYDAutoEnergyListener firstListener;
        int registerCount;
        int unregisterCount;

        public void registerListener(AbsBYDAutoEnergyListener value) {
            listener = value;
            if (firstListener == null) firstListener = value;
            registerCount++;
        }

        public void unregisterListener(AbsBYDAutoEnergyListener value) {
            assertSame(listener, value);
            unregisterCount++;
        }
    }

    public static final class FakeSettingDevice {
        AbsBYDAutoSettingListener listener;

        public void registerListener(AbsBYDAutoSettingListener value) {
            listener = value;
        }

        public void unregisterListener(AbsBYDAutoSettingListener value) {
            assertSame(listener, value);
        }
    }
}
