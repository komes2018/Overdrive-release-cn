package com.overdrive.app.remoteaccess;

import android.hardware.IBYDAutoEvent;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class KeyFobPresenceCallbackTest {
    private static final int INSTRUMENT = 1007, RESULT = 1077215250;

    // Shape from the vehicle's IBYDAutoListener, not the empty compile-only stub.
    public interface NativeListener {
        void onDataChanged(IBYDAutoEvent event);
        void onDataEventChanged(int feature, NativeValue value);
        void onError(int code, String reason);
    }

    public interface IncorrectListener {
        void onEventChanged(IBYDAutoEvent event);
        void onError(int code, String reason);
    }

    public interface IncorrectEvent {
        int getDeviceType();
        int getEventType();
        Object getValue();
    }

    public interface IncorrectEventListener {
        void onDataChanged(IncorrectEvent event);
        void onError(int code, String reason);
    }

    public static final class NativeValue {
        public int intValue;
        NativeValue(int raw) { intValue = raw; }
    }

    public static final class Event implements IBYDAutoEvent {
        final int device, feature, raw;
        Event(int device, int feature, int raw) {
            this.device = device;
            this.feature = feature;
            this.raw = raw;
        }
        @Override public int getDeviceType() { return device; }
        @Override public int getEventType() { return feature; }
        @Override public int getValue() { return raw; }
        @Override public double getDoubleValue() { return 0; }
        @Override public byte[] getBufferData() { return null; }
        @Override public Object getData() { return null; }
        @Override public void setData(Object data) {}
    }

    private static KeyFobPresenceCallback decoder() throws Exception {
        return new KeyFobPresenceCallback(NativeListener.class, IBYDAutoEvent.class,
                INSTRUMENT, RESULT);
    }

    @Test public void actualSdkPrimaryCallbackReachesProductionDecoder() throws Exception {
        KeyFobPresenceCallback decoder = decoder();
        List<Integer> observed = new ArrayList<>();
        NativeListener listener = (NativeListener) Proxy.newProxyInstance(
                NativeListener.class.getClassLoader(), new Class<?>[]{NativeListener.class},
                (proxy, method, args) -> {
                    Integer raw = decoder.read(method, args);
                    if (raw != null) observed.add(raw);
                    return null;
                });
        for (int raw = 0; raw <= 3; raw++) {
            // AbsBYDAutoDevice posts both, in this order, for every native event.
            listener.onDataChanged(new Event(INSTRUMENT, RESULT, raw));
            listener.onDataEventChanged(RESULT, new NativeValue(raw));
        }
        assertEquals(java.util.Arrays.asList(0, 1, 2, 3), observed);
    }

    @Test public void wrongDeviceFeatureInvalidValuesAndMalformedArgumentsAreIgnored() throws Exception {
        KeyFobPresenceCallback decoder = decoder();
        Method callback = NativeListener.class.getMethod("onDataChanged", IBYDAutoEvent.class);
        assertNull(decoder.read(callback, new Object[]{new Event(1001, RESULT, 3)}));
        assertNull(decoder.read(callback, new Object[]{new Event(INSTRUMENT, RESULT + 1, 3)}));
        for (int raw : new int[]{Integer.MIN_VALUE, -2147482648, -1, 4, Integer.MAX_VALUE})
            assertNull(decoder.read(callback, new Object[]{new Event(INSTRUMENT, RESULT, raw)}));
        assertNull(decoder.read(callback, null));
        assertNull(decoder.read(callback, new Object[]{null}));
        assertNull(decoder.read(callback, new Object[]{new Object()}));
        assertNull(decoder.read(callback, new Object[]{new Event(INSTRUMENT, RESULT, 3), 3}));
    }

    @Test public void incompatibleListenerOrEventShapeFailsBeforeRegistering() throws Exception {
        try {
            new KeyFobPresenceCallback(IncorrectListener.class, IBYDAutoEvent.class,
                    INSTRUMENT, RESULT);
            fail("The old impossible callback name must fail binding");
        } catch (NoSuchMethodException expected) {}
        try {
            new KeyFobPresenceCallback(IncorrectEventListener.class, IncorrectEvent.class,
                    INSTRUMENT, RESULT);
            fail("Non-integer native values must fail binding");
        } catch (NoSuchMethodException expected) {}
    }

    @Test public void nativeErrorsMatchOnlyTheirVerifiedSignature() throws Exception {
        KeyFobPresenceCallback decoder = decoder();
        assertTrue(decoder.isError(NativeListener.class.getMethod("onError", int.class, String.class)));
        assertFalse(decoder.isError(NativeListener.class.getMethod("onDataChanged", IBYDAutoEvent.class)));
        assertFalse(decoder.isError(IncorrectListener.class.getMethod("onError", int.class, String.class)));
    }

    @Test public void doubledSdkCallbackCompletesOneAcceptedSearchOnce() throws Exception {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long id = cycle.begin(1_000, -2147482648);
        assertTrue(cycle.requestStarted(1, id, 1_100));
        assertFalse(cycle.acceptRequest(1, id, 1_101));
        KeyFobPresenceCallback decoder = decoder();
        long[] clock = {1_200};
        NativeListener listener = (NativeListener) Proxy.newProxyInstance(
                NativeListener.class.getClassLoader(), new Class<?>[]{NativeListener.class},
                (proxy, method, args) -> {
                    Integer raw = decoder.read(method, args);
                    if (raw != null) cycle.observe(1, id, raw, clock[0]);
                    return null;
                });
        listener.onDataChanged(new Event(INSTRUMENT, RESULT, 0));
        listener.onDataEventChanged(RESULT, new NativeValue(0));
        clock[0] = 1_300;
        listener.onDataChanged(new Event(INSTRUMENT, RESULT, 1));
        listener.onDataEventChanged(RESULT, new NativeValue(1));
        clock[0] = 1_400;
        listener.onDataChanged(new Event(INSTRUMENT, RESULT, 3));
        assertEquals("present", cycle.snapshot().state);
        assertEquals(1, cycle.snapshot().observationId);
        assertEquals(1_300, cycle.snapshot().observedMs);
    }

    @Test public void matchingEventGetterFailureCannotBecomeAbsence() throws Exception {
        KeyFobPresenceCallback decoder = decoder();
        IBYDAutoEvent broken = (IBYDAutoEvent) Proxy.newProxyInstance(
                IBYDAutoEvent.class.getClassLoader(), new Class<?>[]{IBYDAutoEvent.class},
                (proxy, method, args) -> { throw new IllegalStateException("unavailable"); });
        try {
            decoder.read(NativeListener.class.getMethod("onDataChanged", IBYDAutoEvent.class),
                    new Object[]{broken});
            fail("A failed native getter must not return a presence result");
        } catch (ReflectiveOperationException expected) {}
    }
}
