package com.overdrive.app.remoteaccess;

import android.content.Context;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;

/**
 * The Android 12 attribution and Android 13/14 result-receiver variants of a native
 * Bluetooth call. Native failures and timeouts propagate; a default "disconnected"
 * result must never become evidence for automatic locking.
 */
final class BluetoothNativeCall {
    private static final Duration RESULT_TIMEOUT = Duration.ofMillis(1_500L);
    final Method method;
    private final Class<?> attributionType;
    private final Receiver receiver;

    private BluetoothNativeCall(Method method, Class<?> attributionType, Receiver receiver) {
        this.method = method;
        this.attributionType = attributionType;
        this.receiver = receiver;
    }

    boolean requiresAttribution() {
        return attributionType != null;
    }

    static BluetoothNativeCall verify(Class<?> owner, String name, Class<?> result,
            Class<?> sourceType, Class<?> receiverType, Class<?>... arguments)
            throws ReflectiveOperationException {
        if (sourceType != null) {
            Method attributed = find(owner, name, result, append(arguments, sourceType));
            if (attributed != null) return new BluetoothNativeCall(attributed, sourceType, null);
            if (receiverType != null) {
                Method asynchronous = find(owner, name, void.class,
                        append(arguments, sourceType, receiverType));
                if (asynchronous != null)
                    return new BluetoothNativeCall(asynchronous, sourceType,
                            new Receiver(receiverType));
            }
        }
        if (receiverType != null) {
            // Android 13/14 getAdapterConnectionState has a receiver without attribution.
            Method asynchronous = find(owner, name, void.class, append(arguments, receiverType));
            if (asynchronous != null)
                return new BluetoothNativeCall(asynchronous, null, new Receiver(receiverType));
        }
        Method direct = find(owner, name, result, arguments);
        if (direct != null) return new BluetoothNativeCall(direct, null, null);
        throw new NoSuchMethodException("Unverified native Bluetooth method: " + name);
    }

    Object invoke(Object service, Object source, Object... arguments) throws Exception {
        if (attributionType != null && !attributionType.isInstance(source))
            throw new IllegalStateException("Bluetooth caller attribution unavailable");
        Object resultReceiver = receiver == null ? null : call(receiver.create, null);
        if (receiver != null && !receiver.type.isInstance(resultReceiver))
            throw new IllegalStateException("Bluetooth result receiver unavailable");
        Object[] supplied = Arrays.copyOf(arguments, arguments.length
                + (attributionType == null ? 0 : 1) + (receiver == null ? 0 : 1));
        int index = arguments.length;
        if (attributionType != null) supplied[index++] = source;
        if (receiver != null) supplied[index] = resultReceiver;
        Object direct = call(method, service, supplied);
        if (receiver == null) return direct;
        Object completed = call(receiver.await, resultReceiver, RESULT_TIMEOUT);
        if (!receiver.resultType.isInstance(completed))
            throw new IllegalStateException("Bluetooth native result unavailable");
        // The platform throws delivered exceptions here. Null is not a default state.
        return call(receiver.value, completed, new Object[]{null});
    }

    static Class<?> frameworkAttributionType() throws ClassNotFoundException {
        return Class.forName("android.content.AttributionSource", false,
                BluetoothNativeCall.class.getClassLoader());
    }

    static Class<?> frameworkReceiverType() {
        for (String name : new String[]{
                "com.android.modules.utils.SynchronousResultReceiver",
                "com.android.bluetooth.x.com.android.modules.utils.SynchronousResultReceiver",
                "android.bluetooth.SynchronousResultReceiver"}) {
            try {
                return Class.forName(name, false, BluetoothNativeCall.class.getClassLoader());
            } catch (ClassNotFoundException absent) {
                // Android 12 uses direct return values; vendor modules may relocate the class.
            }
        }
        return null;
    }

    static Object attributionSource(Context context) throws ReflectiveOperationException {
        if (context == null) throw new IllegalStateException("Bluetooth application context unavailable");
        Object value = Context.class.getMethod("getAttributionSource").invoke(context);
        if (!frameworkAttributionType().isInstance(value))
            throw new IllegalStateException("Bluetooth caller attribution unavailable");
        return value;
    }

    private static Class<?>[] append(Class<?>[] arguments, Class<?>... suffix) {
        Class<?>[] combined = Arrays.copyOf(arguments, arguments.length + suffix.length);
        System.arraycopy(suffix, 0, combined, arguments.length, suffix.length);
        return combined;
    }

    private static Method find(Class<?> owner, String name, Class<?> result, Class<?>... arguments)
            throws ReflectiveOperationException {
        try {
            Method method = owner.getMethod(name, arguments);
            if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers()))
                throw new UnsupportedOperationException("Unverified Bluetooth method: " + name);
            return method;
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static Object call(Method method, Object target, Object... arguments) throws Exception {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw wrapped;
        }
    }

    private static final class Receiver {
        final Class<?> type, resultType;
        final Method create, await, value;

        Receiver(Class<?> type) throws ReflectiveOperationException {
            this.type = type;
            create = type.getMethod("get");
            await = type.getMethod("awaitResultNoInterrupt", Duration.class);
            resultType = await.getReturnType();
            value = resultType.getMethod("getValue", Object.class);
            if (!Modifier.isStatic(create.getModifiers()) || create.getReturnType() != type
                    || Modifier.isStatic(await.getModifiers())
                    || Modifier.isStatic(value.getModifiers()) || value.getReturnType() != Object.class)
                throw new UnsupportedOperationException("Unverified Bluetooth result receiver");
        }
    }
}
