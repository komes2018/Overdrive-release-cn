package com.overdrive.app.byd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

public class BydDeviceHelperIntInvocationTest {
    @Test
    public void fallsBackOnlyWhenThePreferredMethodIsAbsent() {
        LegacyDevice legacy = new LegacyDevice();
        assertTrue(BydDeviceHelper.invokeFirstAvailableIntMethod(
                legacy, 42, "setHudBrightness", "setHUDBrightness"));
        assertTrue(legacy.called);

        ThrowingDevice throwing = new ThrowingDevice();
        assertFalse(BydDeviceHelper.invokeFirstAvailableIntMethod(
                throwing, 42, "setHudBrightness", "setHUDBrightness"));
        assertFalse(throwing.fallbackCalled);

        RejectingDevice rejecting = new RejectingDevice();
        assertFalse(BydDeviceHelper.invokeFirstAvailableIntMethod(
                rejecting, 42, "setHudBrightness", "setHUDBrightness"));
        assertFalse(rejecting.fallbackCalled);

        BooleanRejectingDevice booleanRejecting = new BooleanRejectingDevice();
        assertFalse(BydDeviceHelper.invokeFirstAvailableIntMethod(
                booleanRejecting, 42, "setHudBrightness", "setHUDBrightness"));
        assertFalse(booleanRejecting.fallbackCalled);
    }

    @Test
    public void oneArgumentCallsHonorTheDiLink5Deadline() throws Exception {
        String helper = new String(
                Files.readAllBytes(Paths.get(
                        "src/main/java/com/overdrive/app/byd/BydDeviceHelper.java")),
                StandardCharsets.UTF_8);
        int method = helper.indexOf(
                "public static Object callGetter(Object device, String methodName, int param)");
        int nextMethod = helper.indexOf(
                "public static Object callMethod(", method);
        assertTrue(method >= 0);
        assertTrue(nextMethod > method);
        assertTrue(helper.substring(method, nextMethod).contains(
                "VehicleActuatorBridge.isDiLink5RequestExpired()"));
    }

    @Test
    public void adapterReadinessRequiresAConnectedServiceAndLiveAdapter() {
        assertTrue(BydDeviceHelper.adapterManagerReady(
                new ConnectedAdapterManager(), "getAdapter"));
        assertFalse(BydDeviceHelper.adapterManagerReady(
                new DisconnectedAdapterManager(), "getAdapter"));
        assertFalse(BydDeviceHelper.adapterManagerReady(
                new MissingAdapterManager(), "getAdapter"));
        assertFalse(BydDeviceHelper.adapterManagerReady(
                new UnknownConnectionManager(), "getAdapter"));
    }

    public static final class LegacyDevice {
        boolean called;

        public void setHUDBrightness(int value) {
            called = value == 42;
        }
    }

    public static final class ThrowingDevice {
        boolean fallbackCalled;

        public void setHudBrightness(int value) {
            throw new IllegalStateException("rejected");
        }

        public void setHUDBrightness(int value) {
            fallbackCalled = true;
        }
    }

    public static final class RejectingDevice {
        boolean fallbackCalled;

        public int setHudBrightness(int value) {
            return -2147482648;
        }

        public void setHUDBrightness(int value) {
            fallbackCalled = true;
        }
    }

    public static final class BooleanRejectingDevice {
        boolean fallbackCalled;

        public boolean setHudBrightness(int value) {
            return false;
        }

        public void setHUDBrightness(int value) {
            fallbackCalled = true;
        }
    }

    public static final class ConnectedAdapterManager {
        public boolean isCarServiceConnect() {
            return true;
        }

        public Object getAdapter() {
            return this;
        }
    }

    public static final class DisconnectedAdapterManager {
        public boolean isCarServiceConnect() {
            return false;
        }

        public Object getAdapter() {
            return this;
        }
    }

    public static final class MissingAdapterManager {
        public boolean isCarServiceConnect() {
            return true;
        }

        public Object getAdapter() {
            return null;
        }
    }

    public static final class UnknownConnectionManager {
        public Object getAdapter() {
            return this;
        }
    }
}
