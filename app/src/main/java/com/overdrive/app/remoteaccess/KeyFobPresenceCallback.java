package com.overdrive.app.remoteaccess;

import java.lang.reflect.Method;

/**
 * Binds the legacy SDK's real listener contract. The SDK delivers each event
 * twice; only its primary onDataChanged callback may advance a search.
 */
final class KeyFobPresenceCallback {
    private final Method dataChanged, error, deviceType, eventType, value;
    private final Class<?> eventClass;
    private final int expectedDevice, expectedFeature;

    KeyFobPresenceCallback(Class<?> listenerClass, Class<?> eventClass,
                           int expectedDevice, int expectedFeature) throws NoSuchMethodException {
        this.eventClass = eventClass;
        this.expectedDevice = expectedDevice;
        this.expectedFeature = expectedFeature;
        dataChanged = listenerClass.getMethod("onDataChanged", eventClass);
        error = listenerClass.getMethod("onError", int.class, String.class);
        if (dataChanged.getReturnType() != void.class || error.getReturnType() != void.class)
            throw new NoSuchMethodException("search_listener_contract_unavailable");
        deviceType = intGetter(eventClass, "getDeviceType");
        eventType = intGetter(eventClass, "getEventType");
        value = intGetter(eventClass, "getValue");
    }

    boolean isError(Method method) {
        return error.equals(method);
    }

    Integer read(Method method, Object[] arguments) throws ReflectiveOperationException {
        if (!dataChanged.equals(method) || arguments == null || arguments.length != 1
                || !eventClass.isInstance(arguments[0]))
            return null;
        Object event = arguments[0];
        if ((Integer) deviceType.invoke(event) != expectedDevice
                || (Integer) eventType.invoke(event) != expectedFeature)
            return null;
        int raw = (Integer) value.invoke(event);
        return raw >= 0 && raw <= 3 ? raw : null;
    }

    private static Method intGetter(Class<?> type, String name) throws NoSuchMethodException {
        Method getter = type.getMethod(name);
        if (getter.getReturnType() != int.class)
            throw new NoSuchMethodException("search_event_contract_unavailable");
        return getter;
    }
}
