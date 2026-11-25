package com.overdrive.app.remoteaccess;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/** Callback dispatch and reader state, independent of Android's Binder/Parcel implementation. */
final class BluetoothRssiCallbacks {
    static final String DESCRIPTOR = "android.bluetooth.IBluetoothGattCallback";

    /** A verified pair belongs to one registration, never to a mutable global callback map. */
    static final class Transactions {
        final int registration;
        final int rssi;

        Transactions(int registration, int rssi) {
            // Binder's user transaction range excludes interface/management transactions.
            if (registration < 1 || registration > 0x00ffffff
                    || rssi < 1 || rssi > 0x00ffffff || registration == rssi)
                throw new UnsupportedOperationException("Unverified Bluetooth callback transactions");
            this.registration = registration;
            this.rssi = rssi;
        }
    }

    interface ParcelData {
        void enforceInterface(String descriptor);
        int readInt();
        String readString();
    }

    static final class Read {
        final int clientId;
        private final CountDownLatch completed;

        Read(int clientId, CountDownLatch completed) {
            this.clientId = clientId;
            this.completed = completed;
        }

        boolean await(long timeoutMs) throws InterruptedException {
            return completed.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    private final Object lock;
    private final String address;
    private final IntConsumer unregister;
    private final CountDownLatch registered = new CountDownLatch(1);
    private volatile CountDownLatch pending;
    private volatile boolean closed;
    private volatile Integer result;
    private boolean reading;
    private int clientId;
    private int registrationStatus = -1;

    BluetoothRssiCallbacks(Object lock, String address, IntConsumer unregister) {
        this.lock = lock;
        this.address = address;
        this.unregister = unregister;
    }

    boolean isClosed() {
        return closed;
    }

    boolean isRegistered() {
        synchronized (lock) { return !closed && clientId > 0; }
    }

    boolean awaitRegistration(long timeoutMs) throws InterruptedException {
        if (!registered.await(timeoutMs, TimeUnit.MILLISECONDS)) return false;
        synchronized (lock) {
            return registrationStatus == 0 && !closed && clientId > 0;
        }
    }

    Read beginRead() {
        synchronized (lock) {
            if (closed || clientId <= 0) return null;
            if (reading) throw new IllegalStateException("RSSI read already in progress");
            reading = true;
            pending = new CountDownLatch(1);
            result = null;
            return new Read(clientId, pending);
        }
    }

    Integer readResult() {
        synchronized (lock) { return closed ? null : result; }
    }

    void finishRead() {
        synchronized (lock) { pending = null; reading = false; }
    }

    void clientRegistered(int status, int id) {
        boolean dispose;
        synchronized (lock) {
            registrationStatus = status;
            dispose = closed;
            if (!closed && status == 0 && id > 0) clientId = id;
        }
        if (dispose && status == 0 && id > 0) unregister.accept(id);
        registered.countDown();
    }

    void readRemoteRssi(String source, int value, int status) {
        CountDownLatch waiting = pending;
        synchronized (lock) {
            // A callback waiting for this lock must not overwrite a replacement read.
            if (closed || waiting == null || pending != waiting || !address.equalsIgnoreCase(source))
                return;
            result = status == 0 && value >= -127 && value <= 20 ? value : null;
        }
        waiting.countDown();
    }

    void close() {
        int id;
        CountDownLatch waiting;
        synchronized (lock) {
            closed = true;
            id = clientId;
            clientId = 0;
            result = null;
            waiting = pending;
        }
        registered.countDown();
        if (waiting != null) waiting.countDown();
        if (id > 0) unregister.accept(id);
    }

    Dispatch dispatch(Transactions transactions) {
        return new Dispatch(transactions);
    }

    /** Both the real Binder adapter and a local interface proxy use this dispatch. */
    final class Dispatch {
        private final Transactions transactions;

        Dispatch(Transactions transactions) {
            this.transactions = transactions;
        }

        boolean onTransaction(int code, ParcelData data) {
            if (code == transactions.registration) {
                data.enforceInterface(DESCRIPTOR);
                int status = data.readInt(), id = data.readInt();
                clientRegistered(status, id);
                return true;
            }
            if (code == transactions.rssi) {
                data.enforceInterface(DESCRIPTOR);
                String source = data.readString();
                int value = data.readInt(), status = data.readInt();
                readRemoteRssi(source, value, status);
                return true;
            }
            // Retain the existing acknowledgement of unrelated legacy callbacks.
            if (code >= 1 && code <= 16) {
                data.enforceInterface(DESCRIPTOR);
                return true;
            }
            return false;
        }

        InvocationHandler proxyHandler(Object binder) {
            return (proxy, method, args) -> {
                switch (method.getName()) {
                    case "asBinder": return binder;
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return args != null && args.length == 1 && proxy == args[0];
                    case "toString": return "OverDriveConnectedSignalCallback";
                    case "onClientRegistered":
                        if (signature(method, int.class, int.class)
                                && args != null && args.length == 2
                                && args[0] instanceof Integer && args[1] instanceof Integer)
                            clientRegistered((Integer) args[0], (Integer) args[1]);
                        return null;
                    case "onReadRemoteRssi":
                        if (signature(method, String.class, int.class, int.class)
                                && args != null && args.length == 3
                                && (args[0] == null || args[0] instanceof String)
                                && args[1] instanceof Integer && args[2] instanceof Integer)
                            readRemoteRssi((String) args[0], (Integer) args[1], (Integer) args[2]);
                        return null;
                    default: return null;
                }
            };
        }
    }

    private static boolean signature(Method method, Class<?>... parameters) {
        return method.getReturnType() == void.class
                && Arrays.equals(method.getParameterTypes(), parameters);
    }
}
