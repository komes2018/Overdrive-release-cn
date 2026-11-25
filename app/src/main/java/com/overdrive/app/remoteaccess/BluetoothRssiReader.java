package com.overdrive.app.remoteaccess;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.RemoteException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * Reads an existing connection; never scans, pairs or opens another radio link.
 * Android 12–14 also require application attribution and, on newer frameworks, result receivers.
 * Runtime signatures and callback transactions must match before registering.
 */
public final class BluetoothRssiReader implements AutoCloseable {
    private final Object lock = new Object();
    private final BluetoothRssiCallbacks callbacks;
    private final String address;
    private final Context context;
    private volatile Object gatt;
    private volatile Method unregister;
    private Method read;
    private BluetoothNativeCall readCall, unregisterCall;
    private Object attributionSource;

    public BluetoothRssiReader(String address) { this(null, address); }

    public BluetoothRssiReader(Context context, String address) {
        this.context = context;
        this.address = address;
        callbacks = new BluetoothRssiCallbacks(lock, address, this::unregister);
    }

    public boolean isRegistered() {
        return callbacks.isRegistered();
    }

    public Integer read() throws Exception {
        if (callbacks.isClosed()) return null;
        if (!isRegistered()) register();
        if (!isRegistered() || callbacks.isClosed()) return null;
        BluetoothRssiCallbacks.Read request;
        Object service;
        Method readMethod;
        BluetoothNativeCall nativeRead;
        Object source;
        synchronized (lock) {
            request = callbacks.beginRead();
            if (request == null) return null;
            service = gatt;
            readMethod = read;
            nativeRead = readCall;
            source = attributionSource;
        }
        try {
            if (callbacks.isClosed()) return null;
            if (nativeRead == null) readMethod.invoke(service, request.clientId, address);
            else nativeRead.invoke(service, source, request.clientId, address);
            if (!request.await(3000)) {
                close(); // an old late callback must never satisfy a future read
                return null;
            }
            return callbacks.readResult();
        } catch (Exception failure) {
            close();
            throw failure;
        } finally {
            callbacks.finishRead();
        }
    }

    private void register() throws Exception {
        BluetoothPlatformSupport.RssiLayout layout =
                BluetoothPlatformSupport.requireConnectedRssiLayout();
        IBinder managerBinder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "bluetooth_manager");
        Object manager = Class.forName("android.bluetooth.IBluetoothManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, managerBinder);
        Object service = layout.getGatt.invoke(manager);
        if (service == null) throw new IllegalStateException("Bluetooth service unavailable");
        Object source = layout.registerCall == null ? null
                : BluetoothNativeCall.attributionSource(context);
        Class<?> callbackType = layout.callbackType;
        BluetoothRssiCallbacks.Dispatch dispatch = callbacks.dispatch(layout.callbackTransactions);
        final Binder binder = new Callback(dispatch);
        Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(),
                new Class<?>[]{callbackType}, dispatch.proxyHandler(binder));
        synchronized (lock) {
            if (callbacks.isClosed()) return;
            gatt = service;
            unregister = layout.unregister;
            read = layout.read;
            readCall = layout.readCall;
            unregisterCall = layout.unregisterCall;
            attributionSource = source;
        }
        ParcelUuid id = new ParcelUuid(UUID.randomUUID());
        if (layout.registerCall == null) layout.register.invoke(service, id, callback);
        else layout.registerCall.invoke(service, source, id, callback, false);
        if (!callbacks.awaitRegistration(3000)) {
            close();
            throw new IllegalStateException("Bluetooth RSSI registration unavailable");
        }
    }

    private final class Callback extends Binder {
        private final BluetoothRssiCallbacks.Dispatch dispatch;

        Callback(BluetoothRssiCallbacks.Dispatch dispatch) {
            this.dispatch = dispatch;
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(BluetoothRssiCallbacks.DESCRIPTOR);
                return true;
            }
            if (dispatch.onTransaction(code, new BluetoothRssiCallbacks.ParcelData() {
                @Override public void enforceInterface(String descriptor) {
                    data.enforceInterface(descriptor);
                }
                @Override public int readInt() { return data.readInt(); }
                @Override public String readString() { return data.readString(); }
            })) return true;
            return super.onTransact(code, data, reply, flags);
        }
    }

    @Override public void close() {
        callbacks.close();
    }

    private void unregister(int id) {
        Object service = gatt;
        Method method = unregister;
        if (service != null && method != null) {
            try {
                if (unregisterCall == null) method.invoke(service, id);
                else unregisterCall.invoke(service, attributionSource, id);
            } catch (Exception ignored) {}
        }
    }
}
