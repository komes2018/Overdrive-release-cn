package com.overdrive.app.remoteaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

/** Reflection fixtures reject incompatible native layouts without accessing Android services. */
public class BluetoothPlatformSupportTest {
    public static final class Uuid {}
    public interface Callback {
        void onClientRegistered(int status, int client);
        void onReadRemoteRssi(String address, int rssi, int status);
    }
    public abstract static class CallbackStub implements Callback {
        private static final int TRANSACTION_onClientRegistered = 1;
        private static final int TRANSACTION_onReadRemoteRssi = 12;
    }
    public abstract static class ChangedCallbackStub implements Callback {
        private static final int TRANSACTION_onClientRegistered = 1;
        private static final int TRANSACTION_onReadRemoteRssi = 13;
    }
    public abstract static class MissingCallbackStub implements Callback {
        private static final int TRANSACTION_onClientRegistered = 1;
    }
    public abstract static class ChangedRegistrationStub implements Callback {
        private static final int TRANSACTION_onClientRegistered = 2;
        private static final int TRANSACTION_onReadRemoteRssi = 12;
    }
    public abstract static class NonStaticCallbackStub implements Callback {
        private static final int TRANSACTION_onClientRegistered = 1;
        private final int TRANSACTION_onReadRemoteRssi = 12;
    }
    public abstract static class ShiftedCallbackStub implements Callback {
        private static final int TRANSACTION_onClientRegistered = 31;
        private static final int TRANSACTION_onReadRemoteRssi = 47;
    }
    public abstract static class LastUserTransactionStub implements Callback {
        private static final int TRANSACTION_onClientRegistered = 1;
        private static final int TRANSACTION_onReadRemoteRssi = 0x00ffffff;
    }
    public abstract static class DuplicateCallbackStub implements Callback {
        private static final int TRANSACTION_onClientRegistered = 1;
        private static final int TRANSACTION_onReadRemoteRssi = 1;
    }
    public abstract static class ZeroCallbackStub implements Callback {
        private static final int TRANSACTION_onClientRegistered = 0;
        private static final int TRANSACTION_onReadRemoteRssi = 12;
    }
    public abstract static class NegativeCallbackStub implements Callback {
        private static final int TRANSACTION_onClientRegistered = 1;
        private static final int TRANSACTION_onReadRemoteRssi = -1;
    }
    public abstract static class ReservedCallbackStub implements Callback {
        private static final int TRANSACTION_onClientRegistered = 1;
        private static final int TRANSACTION_onReadRemoteRssi = 0x5f4e5446;
    }
    public abstract static class OverflowCallbackStub implements Callback {
        private static final int TRANSACTION_onClientRegistered = 1;
        private static final int TRANSACTION_onReadRemoteRssi = 0x01000000;
    }
    public abstract static class BoxedCallbackStub implements Callback {
        private static final int TRANSACTION_onClientRegistered = 1;
        private static final Integer TRANSACTION_onReadRemoteRssi = 12;
    }
    public static final class MetadataReads {
        static int count;
    }
    public abstract static class InitializingCallbackStub implements Callback {
        private static final int TRANSACTION_onClientRegistered = 1;
        private static final int TRANSACTION_onReadRemoteRssi = 12;
        static { MetadataReads.count++; }
    }
    public interface Gatt {
        void registerClient(Uuid uuid, Callback callback);
        void readRemoteRssi(int client, String address);
        void unregisterClient(int client);
    }
    public interface Manager { Gatt getBluetoothGatt(); }
    public interface ChangedGatt {
        void registerClient(Uuid uuid, Callback callback, boolean enhanced);
        void readRemoteRssi(int client, String address);
        void unregisterClient(int client);
    }
    public interface ChangedManager { ChangedGatt getBluetoothGatt(); }
    public interface MissingReadGatt {
        void registerClient(Uuid uuid, Callback callback);
        void unregisterClient(int client);
    }
    public interface MissingReadManager { MissingReadGatt getBluetoothGatt(); }
    public interface WrongReadGatt {
        void registerClient(Uuid uuid, Callback callback);
        int readRemoteRssi(int client, String address);
        void unregisterClient(int client);
    }
    public interface WrongReadManager { WrongReadGatt getBluetoothGatt(); }
    public interface MissingUnregisterGatt {
        void registerClient(Uuid uuid, Callback callback);
        void readRemoteRssi(int client, String address);
    }
    public interface MissingUnregisterManager { MissingUnregisterGatt getBluetoothGatt(); }
    public interface MissingRegistrationCallback {
        void onClientRegistered(int status, long client);
        void onReadRemoteRssi(String address, int rssi, int status);
    }
    public abstract static class MissingRegistrationCallbackStub
            implements MissingRegistrationCallback {
        private static final int TRANSACTION_onClientRegistered = 1;
        private static final int TRANSACTION_onReadRemoteRssi = 12;
    }
    public interface ChangedCallbackGatt {
        void registerClient(Uuid uuid, MissingRegistrationCallback callback);
        void readRemoteRssi(int client, String address);
        void unregisterClient(int client);
    }
    public interface ChangedCallbackManager { ChangedCallbackGatt getBluetoothGatt(); }

    public static final class Device {}
    public static final class Context {}
    public interface Listener {}
    public interface Profile {}
    public interface AdapterService {
        Device[] getBondedDevices();
        boolean isDiscovering();
        int getAdapterConnectionState();
        int getConnectionState(Device device);
    }
    public interface LegacyHeadset {
        int getPriority(Device device);
        int getConnectionState(Device device);
        boolean connect(Device device);
        List<Device> getDevicesMatchingConnectionStates(int[] states);
    }
    public interface Di5Headset {
        int getConnectionPolicy(Device device);
        int getConnectionState(Device device);
        boolean connect(Device device);
        List<Device> getDevicesMatchingConnectionStates(int[] states);
    }
    public static final class LegacyProxy implements Profile {
        LegacyProxy(Context context, Listener listener) {}
        private LegacyHeadset getService() { return null; }
    }
    public static final class Di5Proxy implements Profile {
        Di5Proxy(Context context, Listener listener) {}
        private Di5Headset getService() { return null; }
    }
    public static final class WrongServiceProxy implements Profile {
        WrongServiceProxy(Context context, Listener listener) {}
        private Object getService() { return null; }
    }
    public interface UnsafeBusyHeadset {
        int getConnectionPolicy(Device device);
        int getConnectionState(Device device);
        boolean connect(Device device);
        Object getDevicesMatchingConnectionStates(int[] states);
    }
    public static final class UnsafeBusyProxy implements Profile {
        UnsafeBusyProxy(Context context, Listener listener) {}
        private UnsafeBusyHeadset getService() { return null; }
    }

    @Test public void platformGateRetainsLegacySelectionAndAllowsModernLayoutVerification() {
        assertTrue(BluetoothPlatformSupport.verifiedPlatform(29, false));
        assertTrue(BluetoothPlatformSupport.verifiedPlatform(29, true));
        assertTrue(BluetoothPlatformSupport.verifiedPlatform(30, true));
        assertFalse(BluetoothPlatformSupport.verifiedPlatform(30, false));
        for (int sdk : new int[]{31, 32, 33, 34}) {
            assertTrue(BluetoothPlatformSupport.verifiedPlatform(sdk, false));
            assertTrue(BluetoothPlatformSupport.verifiedPlatform(sdk, true));
        }
        for (int sdk : new int[]{0, 28, 35, 36}) {
            assertFalse(BluetoothPlatformSupport.verifiedPlatform(sdk, false));
            assertFalse(BluetoothPlatformSupport.verifiedPlatform(sdk, true));
        }
    }

    @Test public void bothVerifiedVersionsAcceptExactRssiMethodsAndCallbackTransactions()
            throws Exception {
        for (int sdk : new int[]{29, 30}) {
            BluetoothPlatformSupport.RssiLayout layout = rssi(sdk, true, CallbackStub.class);
            assertEquals("registerClient", layout.register.getName());
            assertEquals("readRemoteRssi", layout.read.getName());
            assertEquals("unregisterClient", layout.unregister.getName());
            assertEquals(Callback.class, layout.callbackType);
        }
    }

    @Test public void rssiRejectsUnselectedDi5AndNewerPlatformDespiteMatchingLayout() {
        rejected(() -> rssi(30, false, CallbackStub.class));
        rejected(() -> rssi(31, true, CallbackStub.class));
    }

    @Test public void rssiRetainsTheActualMovedTransactionPair() throws Exception {
        assertTransactions(rssi(30, true, ChangedCallbackStub.class), 1, 13);
        assertTransactions(rssi(30, true, ChangedRegistrationStub.class), 2, 12);
        assertTransactions(rssi(30, true, ShiftedCallbackStub.class), 31, 47);
        assertTransactions(rssi(30, true, LastUserTransactionStub.class), 1, 0x00ffffff);
    }

    @Test public void rssiRejectsMissingOrInvalidTransactionPairsBeforeNativeCalls() {
        for (Class<?> stub : new Class<?>[]{null, MissingCallbackStub.class,
                NonStaticCallbackStub.class, DuplicateCallbackStub.class, ZeroCallbackStub.class,
                NegativeCallbackStub.class, ReservedCallbackStub.class, OverflowCallbackStub.class,
                BoxedCallbackStub.class})
            rejected(() -> rssi(30, true, stub));
    }

    @Test public void android10RssiDoesNotReadNewHiddenCallbackMetadata() throws Exception {
        for (Class<?> metadata : new Class<?>[]{null, MissingCallbackStub.class,
                ChangedCallbackStub.class, ChangedRegistrationStub.class, NonStaticCallbackStub.class}) {
            BluetoothPlatformSupport.RssiLayout layout = rssi(29, false, metadata);
            assertEquals("readRemoteRssi", layout.read.getName());
            assertEquals("registerClient", layout.register.getName());
            assertTransactions(layout, 1, 12);
        }
    }

    @Test public void android10DoesNotInitializeCallbackMetadataToResolveLegacyIds() throws Exception {
        assertEquals(0, MetadataReads.count);
        assertTransactions(rssi(29, false, InitializingCallbackStub.class), 1, 12);
        assertEquals("The legacy branch must not read even valid hidden metadata", 0, MetadataReads.count);
    }

    @Test public void android10RetainsOriginalMethodLookupWithoutNewReturnTypeChecks()
            throws Exception {
        BluetoothPlatformSupport.RssiLayout layout = BluetoothPlatformSupport.verifyRssiLayout(
                29, false, WrongReadManager.class, WrongReadGatt.class, Uuid.class,
                Callback.class, null);
        assertEquals(int.class, layout.read.getReturnType());
        rejected(() -> BluetoothPlatformSupport.verifyRssiLayout(30, true,
                WrongReadManager.class, WrongReadGatt.class, Uuid.class,
                Callback.class, CallbackStub.class));
    }

    @Test public void rssiRejectsChangedRegisterSignatureBeforeAnyRegistration() {
        rejected(() -> BluetoothPlatformSupport.verifyRssiLayout(30, true,
                ChangedManager.class, ChangedGatt.class, Uuid.class,
                Callback.class, CallbackStub.class));
    }

    @Test public void rssiRejectsMissingReadAndUnregisterMethodsOrWrongReturnType() {
        rejected(() -> BluetoothPlatformSupport.verifyRssiLayout(30, true,
                MissingReadManager.class, MissingReadGatt.class, Uuid.class,
                Callback.class, CallbackStub.class));
        rejected(() -> BluetoothPlatformSupport.verifyRssiLayout(30, true,
                WrongReadManager.class, WrongReadGatt.class, Uuid.class,
                Callback.class, CallbackStub.class));
        rejected(() -> BluetoothPlatformSupport.verifyRssiLayout(30, true,
                MissingUnregisterManager.class, MissingUnregisterGatt.class, Uuid.class,
                Callback.class, CallbackStub.class));
    }

    @Test public void rssiRejectsChangedCallbackArgumentsAndManagerReturnType() {
        rejected(() -> BluetoothPlatformSupport.verifyRssiLayout(30, true,
                ChangedCallbackManager.class, ChangedCallbackGatt.class, Uuid.class,
                MissingRegistrationCallback.class, MissingRegistrationCallbackStub.class));
        rejected(() -> BluetoothPlatformSupport.verifyRssiLayout(30, true,
                ChangedManager.class, Gatt.class, Uuid.class, Callback.class, CallbackStub.class));
    }

    @Test public void android10ReadsPriorityFromExistingService() throws Exception {
        assertEquals("getPriority", reconnect(29, false, LegacyHeadset.class, LegacyProxy.class));
    }

    @Test public void di5ReadsConnectionPolicyWithoutRequiringLegacyPriorityOrAnySetter()
            throws Exception {
        assertEquals("getConnectionPolicy",
                reconnect(30, true, Di5Headset.class, Di5Proxy.class));
    }

    @Test public void reconnectRejectsWrongPlatformOrLegacyOnlyAndroid11Service() {
        rejected(() -> reconnect(30, false, Di5Headset.class, Di5Proxy.class));
        rejected(() -> reconnect(31, true, Di5Headset.class, Di5Proxy.class));
        rejected(() -> reconnect(30, true, LegacyHeadset.class, LegacyProxy.class));
    }

    @Test public void android10ReconnectKeepsLegacyCapabilityWithoutNewHiddenPreflight()
            throws Exception {
        assertEquals("getPriority", reconnect(29, false, Di5Headset.class, WrongServiceProxy.class));
        assertEquals("getPriority", BluetoothPlatformSupport.verifyReconnectLayout(29, false,
                null, null, null, null, null, null, null));
        assertEquals("getPriority", BluetoothPlatformSupport.connectionPermissionMethod(29, true));
        rejected(() -> reconnect(30, true, Di5Headset.class, WrongServiceProxy.class));
    }

    @Test public void reconnectRejectsUnknownServiceAccessorAndNonCollectionBusyQuery() {
        rejected(() -> reconnect(30, true, Di5Headset.class, WrongServiceProxy.class));
        rejected(() -> reconnect(30, true, UnsafeBusyHeadset.class, UnsafeBusyProxy.class));
    }

    private static BluetoothPlatformSupport.RssiLayout rssi(int sdk, boolean selected,
            Class<?> stub) throws Exception {
        return BluetoothPlatformSupport.verifyRssiLayout(sdk, selected, Manager.class,
                Gatt.class, Uuid.class, Callback.class, stub);
    }

    private static void assertTransactions(BluetoothPlatformSupport.RssiLayout layout,
            int registration, int rssi) {
        assertEquals(registration, layout.callbackTransactions.registration);
        assertEquals(rssi, layout.callbackTransactions.rssi);
    }

    private static String reconnect(int sdk, boolean selected, Class<?> service, Class<?> proxy)
            throws Exception {
        return BluetoothPlatformSupport.verifyReconnectLayout(sdk, selected, AdapterService.class,
                Device.class, service, proxy, Context.class, Listener.class, Profile.class);
    }

    private interface Check { void run() throws Exception; }
    private static void rejected(Check check) {
        try {
            check.run();
            fail("An unverified Bluetooth layout must be rejected");
        } catch (ReflectiveOperationException | UnsupportedOperationException expected) {
            // Failure must occur during shape validation, before any service operation.
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }
    }
}
