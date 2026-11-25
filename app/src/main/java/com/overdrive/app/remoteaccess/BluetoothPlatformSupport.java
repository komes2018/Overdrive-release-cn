package com.overdrive.app.remoteaccess;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;

import com.overdrive.app.camera.dilink5.DiLink5Platform;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;

/** Verifies native Bluetooth layouts without binding services or touching the radio. */
public final class BluetoothPlatformSupport {
    static final int REGISTRATION_CALLBACK = 1;
    static final int RSSI_CALLBACK = 12;

    private BluetoothPlatformSupport() {}

    public static boolean connectedRssiSupported() {
        if (Build.VERSION.SDK_INT == 29) return true; // Preserve the existing capability boundary.
        try {
            requireConnectedRssiLayout();
            return true;
        } catch (Exception | LinkageError unavailable) {
            return false;
        }
    }

    public static boolean reconnectSupported() {
        if (Build.VERSION.SDK_INT == 29) return true; // No new preflight on the working legacy path.
        try {
            verifyReconnectLayout(Build.VERSION.SDK_INT, DiLink5Platform.isSelected(),
                    framework("android.bluetooth.IBluetooth"), BluetoothDevice.class,
                    framework("android.bluetooth.IBluetoothHeadsetClient"),
                    framework("android.bluetooth.BluetoothHeadsetClient"), Context.class,
                    BluetoothProfile.ServiceListener.class, BluetoothProfile.class);
            return true;
        } catch (Exception | LinkageError unavailable) {
            return false;
        }
    }

    static boolean verifiedPlatform(int sdk, boolean diLink5) {
        return sdk == 29 || (sdk == 30 && diLink5) || modernPlatform(sdk);
    }

    static boolean modernPlatform(int sdk) {
        return sdk >= 31 && sdk <= 34;
    }

    static String connectionPermissionMethod(int sdk, boolean diLink5) {
        requirePlatform(sdk, diLink5);
        return sdk >= 30 ? "getConnectionPolicy" : "getPriority";
    }

    static RssiLayout requireConnectedRssiLayout() throws ReflectiveOperationException {
        if (Build.VERSION.SDK_INT == 29) {
            // Resolve exactly the methods used previously. Do not inspect hidden
            // callback metadata or impose DiLink 5 return-type restrictions.
            return legacyRssiLayout(framework("android.bluetooth.IBluetoothManager"),
                    framework("android.bluetooth.IBluetoothGatt"), ParcelUuid.class,
                    framework("android.bluetooth.IBluetoothGattCallback"));
        }
        try {
            if (modernPlatform(Build.VERSION.SDK_INT)) {
                return verifyModernRssiLayout(Build.VERSION.SDK_INT,
                        framework("android.bluetooth.IBluetoothManager"),
                        framework("android.bluetooth.IBluetoothGatt"), ParcelUuid.class,
                        framework("android.bluetooth.IBluetoothGattCallback"),
                        framework("android.bluetooth.IBluetoothGattCallback$Stub"),
                        BluetoothNativeCall.frameworkAttributionType(),
                        BluetoothNativeCall.frameworkReceiverType());
            }
            return verifyRssiLayout(Build.VERSION.SDK_INT, DiLink5Platform.isSelected(),
                    framework("android.bluetooth.IBluetoothManager"),
                    framework("android.bluetooth.IBluetoothGatt"), ParcelUuid.class,
                    framework("android.bluetooth.IBluetoothGattCallback"),
                    framework("android.bluetooth.IBluetoothGattCallback$Stub"));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            throw new UnsupportedOperationException("Unverified connected RSSI framework", unavailable);
        }
    }

    static RssiLayout verifyRssiLayout(int sdk, boolean diLink5, Class<?> managerType,
            Class<?> gattType, Class<?> uuidType, Class<?> callbackType,
            Class<?> callbackStub) throws ReflectiveOperationException {
        requirePlatform(sdk, diLink5);
        if (modernPlatform(sdk))
            throw new UnsupportedOperationException("Modern RSSI requires caller attribution");
        if (sdk == 29) return legacyRssiLayout(managerType, gattType, uuidType, callbackType);
        if (callbackType == null || callbackStub == null || !callbackType.isInterface()
                || !callbackType.isAssignableFrom(callbackStub))
            throw new UnsupportedOperationException("Unverified Bluetooth callback interface");
        Method getGatt = requireMethod(managerType, "getBluetoothGatt", gattType);
        Method register = requireMethod(gattType, "registerClient", void.class,
                uuidType, callbackType);
        Method read = requireMethod(gattType, "readRemoteRssi", void.class,
                int.class, String.class);
        Method unregister = requireMethod(gattType, "unregisterClient", void.class, int.class);
        requireMethod(callbackType, "onClientRegistered", void.class, int.class, int.class);
        requireMethod(callbackType, "onReadRemoteRssi", void.class,
                String.class, int.class, int.class);
        BluetoothRssiCallbacks.Transactions transactions = callbackTransactions(callbackStub);
        return new RssiLayout(callbackType, getGatt, register, read, unregister, transactions);
    }

    static RssiLayout verifyModernRssiLayout(int sdk, Class<?> managerType, Class<?> gattType,
            Class<?> uuidType, Class<?> callbackType, Class<?> callbackStub,
            Class<?> attributionType, Class<?> receiverType) throws ReflectiveOperationException {
        if (!modernPlatform(sdk) || attributionType == null
                || callbackType == null || callbackStub == null
                || !callbackType.isInterface() || !callbackType.isAssignableFrom(callbackStub))
            throw new UnsupportedOperationException("Unverified modern Bluetooth framework");
        Method getGatt = requireMethod(managerType, "getBluetoothGatt", gattType);
        BluetoothNativeCall register = BluetoothNativeCall.verify(gattType, "registerClient",
                void.class, attributionType, receiverType, uuidType, callbackType, boolean.class);
        BluetoothNativeCall read = BluetoothNativeCall.verify(gattType, "readRemoteRssi",
                void.class, attributionType, receiverType, int.class, String.class);
        BluetoothNativeCall unregister = BluetoothNativeCall.verify(gattType, "unregisterClient",
                void.class, attributionType, receiverType, int.class);
        if (!register.requiresAttribution() || !read.requiresAttribution()
                || !unregister.requiresAttribution())
            throw new UnsupportedOperationException("Modern GATT caller attribution missing");
        requireMethod(callbackType, "onClientRegistered", void.class, int.class, int.class);
        requireMethod(callbackType, "onReadRemoteRssi", void.class,
                String.class, int.class, int.class);
        BluetoothRssiCallbacks.Transactions transactions = callbackTransactions(callbackStub);
        return new RssiLayout(callbackType, getGatt, register, read, unregister, transactions);
    }

    static String verifyReconnectLayout(int sdk, boolean diLink5, Class<?> adapterService,
            Class<?> deviceType, Class<?> headsetService, Class<?> proxyType,
            Class<?> contextType, Class<?> listenerType, Class<?> profileType)
            throws ReflectiveOperationException {
        String permission = connectionPermissionMethod(sdk, diLink5);
        if (sdk == 29) return permission; // Original HFP reads validate live values when used.
        if (modernPlatform(sdk)) {
            return verifyModernReconnectLayout(sdk, adapterService, deviceType, headsetService,
                    proxyType, contextType, listenerType, profileType, BluetoothAdapter.class,
                    BluetoothNativeCall.frameworkAttributionType(),
                    BluetoothNativeCall.frameworkReceiverType());
        }
        requireMethod(adapterService, "getBondedDevices",
                java.lang.reflect.Array.newInstance(deviceType, 0).getClass());
        requireMethod(adapterService, "isDiscovering", boolean.class);
        requireMethod(adapterService, "getAdapterConnectionState", int.class);
        requireMethod(adapterService, "getConnectionState", int.class, deviceType);
        requireMethod(headsetService, permission, int.class, deviceType);
        requireMethod(headsetService, "getConnectionState", int.class, deviceType);
        requireMethod(headsetService, "connect", boolean.class, deviceType);
        Method busy = headsetService.getMethod("getDevicesMatchingConnectionStates", int[].class);
        if (!Collection.class.isAssignableFrom(busy.getReturnType())
                || Modifier.isStatic(busy.getModifiers()))
            throw new UnsupportedOperationException("Unverified HFP busy-device query");
        if (!profileType.isAssignableFrom(proxyType))
            throw new UnsupportedOperationException("Unverified HFP profile interface");
        proxyType.getDeclaredConstructor(contextType, listenerType);
        Method service = proxyType.getDeclaredMethod("getService");
        if (service.getReturnType() != headsetService || Modifier.isStatic(service.getModifiers()))
            throw new UnsupportedOperationException("Unverified HFP service accessor");
        return permission;
    }

    static String verifyModernReconnectLayout(int sdk, Class<?> adapterService, Class<?> deviceType,
            Class<?> headsetService, Class<?> proxyType, Class<?> contextType,
            Class<?> listenerType, Class<?> profileType, Class<?> adapterType,
            Class<?> attributionType, Class<?> receiverType) throws ReflectiveOperationException {
        if (!modernPlatform(sdk) || attributionType == null)
            throw new UnsupportedOperationException("Unverified modern Bluetooth framework");
        BluetoothNativeCall bonded = BluetoothNativeCall.verify(adapterService, "getBondedDevices",
                java.lang.reflect.Array.newInstance(deviceType, 0).getClass(),
                attributionType, receiverType);
        BluetoothNativeCall discovery = BluetoothNativeCall.verify(adapterService,
                "isDiscovering", boolean.class,
                attributionType, receiverType);
        BluetoothNativeCall.verify(adapterService, "getAdapterConnectionState", int.class,
                attributionType, receiverType);
        BluetoothNativeCall.verify(adapterService, "getConnectionState", int.class,
                attributionType, receiverType, deviceType);
        BluetoothNativeCall policy = BluetoothNativeCall.verify(headsetService,
                "getConnectionPolicy", int.class,
                attributionType, receiverType, deviceType);
        BluetoothNativeCall state = BluetoothNativeCall.verify(headsetService,
                "getConnectionState", int.class,
                attributionType, receiverType, deviceType);
        BluetoothNativeCall connect = BluetoothNativeCall.verify(headsetService,
                "connect", boolean.class,
                attributionType, receiverType, deviceType);
        BluetoothNativeCall busy = BluetoothNativeCall.verify(headsetService,
                "getDevicesMatchingConnectionStates",
                java.util.List.class, attributionType, receiverType, int[].class);
        for (BluetoothNativeCall call : new BluetoothNativeCall[]{
                bonded, discovery, policy, state, connect, busy}) {
            if (!call.requiresAttribution())
                throw new UnsupportedOperationException("Modern HFP caller attribution missing");
        }
        if (!profileType.isAssignableFrom(proxyType))
            throw new UnsupportedOperationException("Unverified HFP profile interface");
        proxyType.getDeclaredConstructor(contextType, listenerType, adapterType);
        profileServiceAccessor(proxyType, headsetService);
        return "getConnectionPolicy";
    }

    /** Android 12 uses its connector field; Android 14 also supplies getService(). */
    static Object profileService(Object proxy, Class<?> serviceType) throws Exception {
        Object value;
        try {
            Method getter = proxy.getClass().getDeclaredMethod("getService");
            if (getter.getReturnType() != serviceType || Modifier.isStatic(getter.getModifiers()))
                throw new UnsupportedOperationException("Unverified HFP service accessor");
            getter.setAccessible(true);
            value = getter.invoke(proxy);
        } catch (NoSuchMethodException connectorLayout) {
            Field field = profileServiceAccessor(proxy.getClass(), serviceType);
            field.setAccessible(true);
            Object connector = field.get(proxy);
            if (connector == null) throw new IllegalStateException("HFP connector unavailable");
            Method getter = field.getType().getDeclaredMethod("getService");
            getter.setAccessible(true);
            value = getter.invoke(connector);
        }
        if (!serviceType.isInstance(value))
            throw new IllegalStateException("HFP service unavailable");
        return value;
    }

    private static Field profileServiceAccessor(Class<?> proxyType, Class<?> serviceType)
            throws ReflectiveOperationException {
        try {
            Method getter = proxyType.getDeclaredMethod("getService");
            if (getter.getReturnType() != serviceType || Modifier.isStatic(getter.getModifiers()))
                throw new UnsupportedOperationException("Unverified HFP service accessor");
            return null;
        } catch (NoSuchMethodException connectorLayout) {
            Field field = proxyType.getDeclaredField("mProfileConnector");
            if (Modifier.isStatic(field.getModifiers())
                    || !(field.getGenericType() instanceof ParameterizedType))
                throw new UnsupportedOperationException("Unverified HFP connector");
            ParameterizedType generic = (ParameterizedType) field.getGenericType();
            if (generic.getActualTypeArguments().length != 1
                    || generic.getActualTypeArguments()[0] != serviceType)
                throw new UnsupportedOperationException("Unverified HFP connector service");
            Method getter = field.getType().getDeclaredMethod("getService");
            if (getter.getReturnType() != Object.class || Modifier.isStatic(getter.getModifiers()))
                throw new UnsupportedOperationException("Unverified HFP connector accessor");
            return field;
        }
    }

    private static RssiLayout legacyRssiLayout(Class<?> managerType, Class<?> gattType,
            Class<?> uuidType, Class<?> callbackType) throws ReflectiveOperationException {
        return new RssiLayout(callbackType, managerType.getMethod("getBluetoothGatt"),
                gattType.getMethod("registerClient", uuidType, callbackType),
                gattType.getMethod("readRemoteRssi", int.class, String.class),
                gattType.getMethod("unregisterClient", int.class),
                new BluetoothRssiCallbacks.Transactions(REGISTRATION_CALLBACK, RSSI_CALLBACK));
    }

    private static void requirePlatform(int sdk, boolean diLink5) {
        if (!verifiedPlatform(sdk, diLink5))
            throw new UnsupportedOperationException(
                    "Connected Bluetooth proximity requires a verified Android 10–14 framework");
    }

    private static Class<?> framework(String name) throws ClassNotFoundException {
        return Class.forName(name, false, BluetoothPlatformSupport.class.getClassLoader());
    }

    private static Method requireMethod(Class<?> type, String name, Class<?> result,
            Class<?>... arguments) throws ReflectiveOperationException {
        Method method = type.getMethod(name, arguments);
        if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers()))
            throw new UnsupportedOperationException("Unverified Bluetooth method: " + name);
        return method;
    }

    private static BluetoothRssiCallbacks.Transactions callbackTransactions(Class<?> stub)
            throws ReflectiveOperationException {
        int registration = transaction(stub, "TRANSACTION_onClientRegistered");
        int rssi = transaction(stub, "TRANSACTION_onReadRemoteRssi");
        return new BluetoothRssiCallbacks.Transactions(registration, rssi);
    }

    private static int transaction(Class<?> stub, String name)
            throws ReflectiveOperationException {
        Field field = stub.getDeclaredField(name);
        if (field.getType() != int.class || !Modifier.isStatic(field.getModifiers()))
            throw new UnsupportedOperationException("Unverified Bluetooth callback: " + name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    static final class RssiLayout {
        final Class<?> callbackType;
        final Method getGatt;
        final Method register;
        final Method read;
        final Method unregister;
        final BluetoothNativeCall registerCall, readCall, unregisterCall;
        final BluetoothRssiCallbacks.Transactions callbackTransactions;

        RssiLayout(Class<?> callbackType, Method getGatt, Method register, Method read,
                Method unregister, BluetoothRssiCallbacks.Transactions transactions) {
            this.callbackType = callbackType;
            this.getGatt = getGatt;
            this.register = register;
            this.read = read;
            this.unregister = unregister;
            registerCall = readCall = unregisterCall = null;
            callbackTransactions = transactions;
        }

        RssiLayout(Class<?> callbackType, Method getGatt, BluetoothNativeCall register,
                BluetoothNativeCall read, BluetoothNativeCall unregister,
                BluetoothRssiCallbacks.Transactions transactions) {
            this.callbackType = callbackType;
            this.getGatt = getGatt;
            this.register = register.method;
            this.read = read.method;
            this.unregister = unregister.method;
            registerCall = register;
            readCall = read;
            unregisterCall = unregister;
            callbackTransactions = transactions;
        }
    }
}
