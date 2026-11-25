package com.overdrive.app.remoteaccess;

import com.overdrive.app.remoteaccess.BluetoothNativeCallTest.Source;
import com.overdrive.app.remoteaccess.BluetoothNativeCallTest.SynchronousResultReceiver;
import com.overdrive.app.remoteaccess.BluetoothPlatformSupportTest.Callback;
import com.overdrive.app.remoteaccess.BluetoothPlatformSupportTest.CallbackStub;
import com.overdrive.app.remoteaccess.BluetoothPlatformSupportTest.Context;
import com.overdrive.app.remoteaccess.BluetoothPlatformSupportTest.Device;
import com.overdrive.app.remoteaccess.BluetoothPlatformSupportTest.Listener;
import com.overdrive.app.remoteaccess.BluetoothPlatformSupportTest.Profile;
import com.overdrive.app.remoteaccess.BluetoothPlatformSupportTest.Uuid;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Mirrors the AOSP S and U native interfaces and invokes the resolved GATT methods. */
public class BluetoothModernPlatformSupportTest {
    public interface GattS {
        void registerClient(Uuid id, Callback callback, boolean eatt, Source source);
        void readRemoteRssi(int client, String address, Source source);
        void unregisterClient(int client, Source source);
    }
    public interface GattU {
        void registerClient(Uuid id, Callback callback, boolean eatt, Source source,
                SynchronousResultReceiver<Object> receiver);
        void readRemoteRssi(int client, String address, Source source,
                SynchronousResultReceiver<Object> receiver);
        void unregisterClient(int client, Source source, SynchronousResultReceiver<Object> receiver);
    }
    public interface ManagerS { GattS getBluetoothGatt(); }
    public interface ManagerU { GattU getBluetoothGatt(); }

    public static final class NativeGattS implements GattS {
        final Source source = new Source();
        final List<String> calls = new ArrayList<>();
        Callback callback;
        @Override public void registerClient(Uuid id, Callback callback, boolean eatt, Source source) {
            assertSame(this.source, source);
            assertFalse("RSSI sampling must not request an enhanced connection", eatt);
            this.callback = callback;
            calls.add("register");
            callback.onClientRegistered(0, 17);
        }
        @Override public void readRemoteRssi(int client, String address, Source source) {
            assertSame(this.source, source);
            assertEquals(17, client);
            calls.add("read");
            callback.onReadRemoteRssi(address, -55, 0);
        }
        @Override public void unregisterClient(int client, Source source) {
            assertSame(this.source, source);
            assertEquals(17, client);
            calls.add("unregister");
        }
    }
    public static final class NativeGattU implements GattU {
        final NativeGattS delegate = new NativeGattS();
        @Override public void registerClient(Uuid id, Callback callback, boolean eatt, Source source,
                SynchronousResultReceiver<Object> receiver) {
            delegate.registerClient(id, callback, eatt, source);
            receiver.send(null);
        }
        @Override public void readRemoteRssi(int client, String address, Source source,
                SynchronousResultReceiver<Object> receiver) {
            delegate.readRemoteRssi(client, address, source);
            receiver.send(null);
        }
        @Override public void unregisterClient(int client, Source source,
                SynchronousResultReceiver<Object> receiver) {
            delegate.unregisterClient(client, source);
            receiver.send(null);
        }
    }
    public static final class RecordingCallback implements Callback {
        int client, rssi;
        @Override public void onClientRegistered(int status, int client) {
            assertEquals(0, status);
            this.client = client;
        }
        @Override public void onReadRemoteRssi(String address, int rssi, int status) {
            assertEquals("00:11:22:33:44:55", address);
            assertEquals(0, status);
            this.rssi = rssi;
        }
    }

    public interface AdapterS {
        Device[] getBondedDevices(Source source);
        boolean isDiscovering(Source source);
        int getAdapterConnectionState();
        int getConnectionState(Device device);
    }
    public interface AdapterU {
        void getBondedDevices(Source source, SynchronousResultReceiver<Device[]> receiver);
        void isDiscovering(Source source, SynchronousResultReceiver<Boolean> receiver);
        void getAdapterConnectionState(SynchronousResultReceiver<Integer> receiver);
        void getConnectionState(Device device, Source source,
                SynchronousResultReceiver<Integer> receiver);
    }
    public interface HeadsetS {
        int getConnectionPolicy(Device device, Source source);
        int getConnectionState(Device device, Source source);
        boolean connect(Device device, Source source);
        List<Device> getDevicesMatchingConnectionStates(int[] states, Source source);
    }
    public interface HeadsetU {
        void getConnectionPolicy(Device device, Source source,
                SynchronousResultReceiver<Integer> receiver);
        void getConnectionState(Device device, Source source,
                SynchronousResultReceiver<Integer> receiver);
        void connect(Device device, Source source, SynchronousResultReceiver<Boolean> receiver);
        void getDevicesMatchingConnectionStates(int[] states, Source source,
                SynchronousResultReceiver<List<Device>> receiver);
    }
    public static final class Adapter {}
    public static final class Connector<T> {
        private final T service;
        Connector(T service) { this.service = service; }
        T getService() { return service; }
    }
    public static final class ProxyS implements Profile {
        private final Connector<HeadsetS> mProfileConnector = new Connector<>(null);
        ProxyS(Context context, Listener listener, Adapter adapter) {}
    }
    public static final class ProxyU implements Profile {
        ProxyU(Context context, Listener listener, Adapter adapter) {}
        private HeadsetU getService() { return null; }
    }
    public static final class OldConstructorProxy implements Profile {
        OldConstructorProxy(Context context, Listener listener) {}
        private HeadsetU getService() { return null; }
    }

    private static BluetoothPlatformSupport.RssiLayout gatt(int sdk, Class<?> stub)
            throws Exception {
        return BluetoothPlatformSupport.verifyModernRssiLayout(sdk,
                sdk <= 32 ? ManagerS.class : ManagerU.class,
                sdk <= 32 ? GattS.class : GattU.class,
                Uuid.class, Callback.class, stub, Source.class,
                sdk <= 32 ? null : SynchronousResultReceiver.class);
    }

    private static void invokeGatt(BluetoothPlatformSupport.RssiLayout layout, Object service,
            Source source, NativeGattS recording) throws Exception {
        BluetoothRssiCallbacks callbacks = new BluetoothRssiCallbacks(new Object(),
                "00:11:22:33:44:55", id -> {
                    try { layout.unregisterCall.invoke(service, source, id); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                });
        BluetoothRssiCallbacks.Dispatch dispatch = callbacks.dispatch(layout.callbackTransactions);
        Callback callback = (Callback) Proxy.newProxyInstance(Callback.class.getClassLoader(),
                new Class<?>[]{Callback.class}, dispatch.proxyHandler(new Object()));
        layout.registerCall.invoke(service, source, new Uuid(), callback, false);
        assertTrue("Synchronous local registration must reach the production state",
                callbacks.awaitRegistration(0));
        BluetoothRssiCallbacks.Read read = callbacks.beginRead();
        assertNotNull(read);
        layout.readCall.invoke(service, source, read.clientId, "00:11:22:33:44:55");
        assertTrue("Synchronous local RSSI must complete the real pending read", read.await(0));
        assertEquals(Integer.valueOf(-55), callbacks.readResult());
        callbacks.finishRead();
        callbacks.close();
        assertEquals(Arrays.asList("register", "read", "unregister"), recording.calls);
    }

    @Test public void android31And32GattRegistrationReadAndCleanupUseTheSameCaller() throws Exception {
        for (int sdk : new int[]{31, 32}) {
            NativeGattS service = new NativeGattS();
            invokeGatt(gatt(sdk, CallbackStub.class), service, service.source, service);
        }
    }

    @Test public void android33And34GattWaitForNativeAcknowledgementsAndRssiCallback() throws Exception {
        for (int sdk : new int[]{33, 34}) {
            NativeGattU service = new NativeGattU();
            invokeGatt(gatt(sdk, CallbackStub.class), service, service.delegate.source,
                    service.delegate);
        }
    }

    @Test public void vendorSdk32CanUseVerifiedAndroid14Signatures() throws Exception {
        NativeGattU service = new NativeGattU();
        BluetoothPlatformSupport.RssiLayout layout =
                BluetoothPlatformSupport.verifyModernRssiLayout(32, ManagerU.class, GattU.class,
                        Uuid.class, Callback.class, CallbackStub.class, Source.class,
                        SynchronousResultReceiver.class);
        invokeGatt(layout, service, service.delegate.source, service.delegate);
        assertEquals("getConnectionPolicy",
                BluetoothPlatformSupport.verifyModernReconnectLayout(32, AdapterU.class,
                        Device.class, HeadsetU.class, ProxyU.class, Context.class, Listener.class,
                        Profile.class, Adapter.class, Source.class, SynchronousResultReceiver.class));
    }

    @Test public void movedCallbackTransactionsRetainEveryModernCallShape() throws Exception {
        for (int sdk : new int[]{31, 32, 33, 34}) {
            BluetoothPlatformSupport.RssiLayout layout = gatt(sdk,
                    BluetoothPlatformSupportTest.ShiftedCallbackStub.class);
            assertEquals(31, layout.callbackTransactions.registration);
            assertEquals(47, layout.callbackTransactions.rssi);
            if (sdk <= 32) {
                NativeGattS service = new NativeGattS();
                invokeGatt(layout, service, service.source, service);
            } else {
                NativeGattU service = new NativeGattU();
                invokeGatt(layout, service, service.delegate.source, service.delegate);
            }
        }
    }

    @Test public void invalidCallbackTransactionsAreRejectedBeforeRegistration() throws Exception {
        for (int sdk : new int[]{31, 32, 33, 34})
            for (Class<?> stub : new Class<?>[]{null,
                    BluetoothPlatformSupportTest.MissingCallbackStub.class,
                    BluetoothPlatformSupportTest.NonStaticCallbackStub.class,
                    BluetoothPlatformSupportTest.DuplicateCallbackStub.class,
                    BluetoothPlatformSupportTest.ZeroCallbackStub.class,
                    BluetoothPlatformSupportTest.NegativeCallbackStub.class,
                    BluetoothPlatformSupportTest.ReservedCallbackStub.class,
                    BluetoothPlatformSupportTest.OverflowCallbackStub.class,
                    BluetoothPlatformSupportTest.BoxedCallbackStub.class}) {
                try {
                    gatt(sdk, stub);
                    fail("Invalid callback metadata must not authorize a registration");
                } catch (ReflectiveOperationException | UnsupportedOperationException expected) {
                    // No service call has taken place.
                }
            }
    }

    @Test public void modernRegistrationCannotProceedWithoutCallerAttribution() throws Exception {
        NativeGattS service = new NativeGattS();
        assertThrows(IllegalStateException.class, () -> gatt(32, CallbackStub.class).registerCall
                .invoke(service, null, new Uuid(), new RecordingCallback(), false));
        assertTrue(service.calls.isEmpty());
    }

    @Test public void android14LayoutRequiresTheActualResultReceiver() {
        assertThrows(ReflectiveOperationException.class, () ->
                BluetoothPlatformSupport.verifyModernRssiLayout(34, ManagerU.class,
                        GattU.class, Uuid.class, Callback.class, CallbackStub.class,
                        Source.class, null));
    }

    @Test public void android32ReconnectVerifiesTheConnectorAndThreeArgumentConstructor()
            throws Exception {
        assertEquals("getConnectionPolicy", reconnect(32, AdapterS.class,
                HeadsetS.class, ProxyS.class));
    }

    @Test public void android14ReconnectVerifiesTheAsynchronousNativeMethods() throws Exception {
        assertEquals("getConnectionPolicy", reconnect(34, AdapterU.class,
                HeadsetU.class, ProxyU.class));
    }

    @Test public void modernReconnectRejectsOldConstructorsAndUnattributedAdapterMethods() {
        assertThrows(ReflectiveOperationException.class, () ->
                reconnect(34, AdapterU.class, HeadsetU.class, OldConstructorProxy.class));
        assertThrows(UnsupportedOperationException.class, () ->
                reconnect(32, BluetoothPlatformSupportTest.AdapterService.class,
                        HeadsetS.class, ProxyS.class));
    }

    private static String reconnect(int sdk, Class<?> adapter, Class<?> headset, Class<?> proxy)
            throws Exception {
        return BluetoothPlatformSupport.verifyModernReconnectLayout(sdk, adapter,
                Device.class, headset, proxy, Context.class, Listener.class, Profile.class,
                Adapter.class, Source.class, sdk <= 32 ? null : SynchronousResultReceiver.class);
    }
}
