package com.overdrive.app.remoteaccess;

import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

/** Exercises the real reflection/receiver path without Android binder mocks. */
public class BluetoothNativeCallTest {
    public static final class Source {}
    public static final class Device {}

    public static final class SynchronousResultReceiver<T> {
        private Result<T> completed;
        public static <T> SynchronousResultReceiver<T> get() {
            return new SynchronousResultReceiver<>();
        }
        public void send(T value) { completed = new Result<>(value, null); }
        public void fail(RuntimeException error) { completed = new Result<>(null, error); }
        public Result<T> awaitResultNoInterrupt(Duration timeout) throws TimeoutException {
            assertTrue(timeout.toMillis() > 0 && timeout.toMillis() <= 1500);
            if (completed == null) throw new TimeoutException("native reply did not arrive");
            return completed;
        }
    }

    public static final class Result<T> {
        private final T value;
        private final RuntimeException error;
        Result(T value, RuntimeException error) { this.value = value; this.error = error; }
        public T getValue(T fallback) {
            assertNull("An absence state must never be supplied as a default", fallback);
            if (error != null) throw error;
            return value;
        }
    }

    public static final class Android12 {
        public final Source expected = new Source();
        int reads;
        public int attributedState(Device device, Source source) {
            assertSame(expected, source);
            reads++;
            return 3;
        }
        public int getConnectionState(Device device) { reads++; return 1; }
    }

    public static final class Android14 {
        public final Source expected = new Source();
        Integer value = 3;
        RuntimeException error;
        boolean reply = true;
        int reads;
        public void getConnectionState(Device device, Source source,
                SynchronousResultReceiver<Integer> receiver) {
            assertSame(expected, source);
            reads++;
            if (error != null) receiver.fail(error);
            else if (reply) receiver.send(value);
        }
        public void getAdapterConnectionState(SynchronousResultReceiver<Integer> receiver) {
            reads++;
            receiver.send(0);
        }
    }

    public static final class Boxed {
        public Integer getConnectionState(Device device, Source source) { return null; }
    }

    public static final class StaticGetter {
        public static int getConnectionState(Device device, Source source) { return 0; }
    }

    private static BluetoothNativeCall state(Class<?> owner) throws Exception {
        return BluetoothNativeCall.verify(owner, "getConnectionState", int.class,
                Source.class, SynchronousResultReceiver.class, Device.class);
    }

    @Test public void android12PassesTheActualCallerAttribution() throws Exception {
        Android12 service = new Android12();
        BluetoothNativeCall call = BluetoothNativeCall.verify(Android12.class,
                "attributedState", int.class, Source.class, null, Device.class);
        assertTrue(call.requiresAttribution());
        assertEquals(3, call.invoke(service, service.expected, new Device()));
        assertEquals(1, service.reads);
    }

    @Test public void android12ConnectionStateStillUsesItsDirectSignature() throws Exception {
        Android12 service = new Android12();
        BluetoothNativeCall call = state(Android12.class);
        assertFalse(call.requiresAttribution());
        assertEquals(1, call.invoke(service, service.expected, new Device()));
    }

    @Test public void android14WaitsForAndUnwrapsTheNativeReply() throws Exception {
        Android14 service = new Android14();
        assertEquals(3, state(Android14.class).invoke(service, service.expected, new Device()));
        assertEquals(1, service.reads);
    }

    @Test public void android14AggregateStateHasAReceiverWithoutAttribution() throws Exception {
        Android14 service = new Android14();
        BluetoothNativeCall call = BluetoothNativeCall.verify(Android14.class,
                "getAdapterConnectionState", int.class, Source.class,
                SynchronousResultReceiver.class);
        assertFalse(call.requiresAttribution());
        assertEquals(0, call.invoke(service, null));
    }

    @Test public void aNullReplyRemainsNullRatherThanDisconnected() throws Exception {
        Android14 service = new Android14();
        service.value = null;
        assertNull(state(Android14.class).invoke(service, service.expected, new Device()));
    }

    @Test public void permissionFailurePropagatesInsteadOfSupplyingAState() throws Exception {
        Android14 service = new Android14();
        SecurityException denied = new SecurityException("caller permission denied");
        service.error = denied;
        assertSame(denied, assertThrows(SecurityException.class,
                () -> state(Android14.class).invoke(service, service.expected, new Device())));
    }

    @Test public void timeoutPropagatesInsteadOfSupplyingAState() throws Exception {
        Android14 service = new Android14();
        service.reply = false;
        assertThrows(TimeoutException.class,
                () -> state(Android14.class).invoke(service, service.expected, new Device()));
    }

    @Test public void missingOrIncorrectAttributionMakesZeroNativeCalls() throws Exception {
        Android14 service = new Android14();
        BluetoothNativeCall call = state(Android14.class);
        assertThrows(IllegalStateException.class, () -> call.invoke(service, null, new Device()));
        assertThrows(IllegalStateException.class,
                () -> call.invoke(service, new Object(), new Device()));
        assertEquals(0, service.reads);
    }

    @Test public void boxedAndStaticNativeSignaturesRemainUnsupported() {
        assertThrows(UnsupportedOperationException.class, () -> state(Boxed.class));
        assertThrows(UnsupportedOperationException.class, () -> state(StaticGetter.class));
    }
}
