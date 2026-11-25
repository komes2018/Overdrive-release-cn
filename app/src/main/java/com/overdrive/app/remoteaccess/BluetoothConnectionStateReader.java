package com.overdrive.app.remoteaccess;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.SystemClock;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;

/**
 * One direct read of a trusted peer's live connection state. This creates no
 * connection, client, receiver, timer or worker. Native errors remain Unknown.
 */
public final class BluetoothConnectionStateReader {
    private static final int STATE_OFF = 10, STATE_TURNING_ON = 11,
            STATE_ON = 12, STATE_TURNING_OFF = 13;
    // IBluetooth state is a mask: connected=1, encrypted BR/EDR=2, encrypted LE=4.
    private static final int KNOWN_CONNECTION_MASK = 1 | 2 | 4;

    private BluetoothConnectionStateReader() {}

    public static final class Snapshot {
        public final boolean connectionReadKnown, radioReadKnown, radioOn;
        public final String connectionState, connectionModeGeneration, reason;
        public final long connectionObservedAtElapsedMs;
        private final String committedMode;

        private Snapshot(boolean known, String state, boolean radioKnown,
                boolean radioOn, long observed, String generation, String mode, String reason) {
            connectionReadKnown = known;
            connectionState = state;
            radioReadKnown = radioKnown;
            this.radioOn = radioOn;
            connectionObservedAtElapsedMs = observed;
            connectionModeGeneration = generation;
            committedMode = mode;
            this.reason = reason;
        }

        public boolean connected() {
            return connectionReadKnown && "connected".equals(connectionState);
        }

        public static Snapshot unknown(String reason) {
            return new Snapshot(false, "unknown", false, false, -1, "", "", reason);
        }

        public boolean matchesCommittedMode(DiLink5Platform.ActiveModeSnapshot mode) {
            return mode != null && !committedMode.isEmpty()
                    && committedMode.equals(mode.mode)
                    && connectionModeGeneration.equals(mode.generation);
        }
    }

    /** The caller owns the existing worker and supplies its current consent. */
    public static Snapshot read(BluetoothAdapter adapter, String trustedAddress,
            BooleanSupplier authorized) {
        return read(null, adapter, trustedAddress, authorized);
    }

    public static Snapshot read(Context context, BluetoothAdapter adapter, String trustedAddress,
            BooleanSupplier authorized) {
        return read(new AndroidEnvironment(context, adapter, authorized), trustedAddress);
    }

    /** Test seam exercises the same reflective native reads and fences. */
    interface Environment {
        boolean authorized() throws Exception;
        int sdk();
        DiLink5Platform.ActiveModeSnapshot committedMode() throws Exception;
        Object adapterBinding() throws Exception;
        int radioState() throws Exception;
        Object service() throws Exception;
        boolean serviceAlive(Object service) throws Exception;
        Class<?> serviceType() throws Exception;
        Class<?> deviceType();
        String deviceAddress(Object device) throws Exception;
        long elapsedRealtime();
        default Object attributionSource() throws Exception { return null; }
        default Class<?> attributionType() throws Exception {
            return BluetoothNativeCall.frameworkAttributionType();
        }
        default Class<?> resultReceiverType() {
            return BluetoothNativeCall.frameworkReceiverType();
        }
    }

    static Snapshot read(Environment environment, String trustedAddress) {
        DiLink5Platform.ActiveModeSnapshot before = null;
        Object adapter = null, service = null;
        int radioBefore = -1, radioAfter = -1;
        long observed = -1;
        String state = "unknown", reason = "connection_state_unavailable";
        boolean nativeKnown = false, bindingHealthy = false;
        try {
            if (!environment.authorized()) return Snapshot.unknown("monitor_disabled");
            if (trustedAddress == null
                    || !trustedAddress.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))
                return Snapshot.unknown("trusted_peer_invalid");
            before = environment.committedMode();
            if (before == null || !BluetoothPlatformSupport.verifiedPlatform(
                    environment.sdk(), "dilink5".equals(before.mode)))
                return Snapshot.unknown("connection_platform_unavailable");
            adapter = environment.adapterBinding();
            if (adapter == null) return Snapshot.unknown("bluetooth_unavailable");
            radioBefore = environment.radioState();
            if (radioBefore != STATE_ON) {
                reason = "radio_off";
            } else {
                service = environment.service();
                Class<?> type = environment.serviceType(), peerType = environment.deviceType();
                if (service == null || !type.isInstance(service)
                        || !environment.serviceAlive(service))
                    throw new IllegalStateException("Bluetooth binder unavailable");
                boolean modern = BluetoothPlatformSupport.modernPlatform(environment.sdk());
                Class<?> sourceType = modern ? environment.attributionType() : null;
                Class<?> receiverType = modern ? environment.resultReceiverType() : null;
                Object source = modern ? environment.attributionSource() : null;
                BluetoothNativeCall paired = BluetoothNativeCall.verify(type, "getBondedDevices",
                        Array.newInstance(peerType, 0).getClass(), sourceType, receiverType);
                BluetoothNativeCall connected = BluetoothNativeCall.verify(type,
                        "getConnectionState", int.class, sourceType, receiverType, peerType);
                if (modern && !paired.requiresAttribution())
                    throw new UnsupportedOperationException("Modern Bluetooth attribution missing");
                Object peer = trustedPeer(paired.invoke(service, source), peerType,
                        trustedAddress, environment);
                if (peer == null) {
                    reason = "device_not_paired";
                } else {
                    Object raw = connected.invoke(service, source, peer);
                    observed = environment.elapsedRealtime();
                    if (!(raw instanceof Integer)
                            || (Integer) raw < 0 || (((Integer) raw) & ~KNOWN_CONNECTION_MASK) != 0)
                        throw new IllegalStateException("Bluetooth connection state unavailable");
                    state = ((Integer) raw) == 0 ? "disconnected" : "connected";
                    // Pairing can be revoked while the native query is in flight.
                    nativeKnown = trustedPeer(paired.invoke(service, source), peerType,
                            trustedAddress, environment) != null;
                    reason = nativeKnown ? state : "device_not_paired";
                }
                bindingHealthy = environment.service() == service
                        && environment.serviceAlive(service);
            }
        } catch (Exception | LinkageError unavailable) {
            nativeKnown = false;
            reason = "connection_state_unavailable";
        }
        DiLink5Platform.ActiveModeSnapshot after = null;
        boolean authorized = false, sameAdapter = false;
        try {
            radioAfter = environment.radioState();
            after = environment.committedMode();
            authorized = environment.authorized();
            sameAdapter = adapter != null && environment.adapterBinding() == adapter;
            if (service != null)
                bindingHealthy &= environment.service() == service
                        && environment.serviceAlive(service);
        } catch (Exception | LinkageError unavailable) {
            nativeKnown = false;
        }
        boolean radioKnown = knownRadioState(radioBefore) && knownRadioState(radioAfter);
        boolean radioOn = radioKnown && radioBefore == STATE_ON && radioAfter == STATE_ON;
        boolean sameMode = before != null && before.matches(after);
        boolean known = nativeKnown && bindingHealthy && authorized && sameAdapter
                && sameMode && radioOn && observed >= 0;
        if (!authorized) reason = "monitor_disabled";
        else if (!sameMode) reason = "connection_mode_changed";
        else if (!radioOn) reason = radioKnown ? "radio_off" : "radio_state_unavailable";
        else if (!sameAdapter || !bindingHealthy) reason = "connection_binding_changed";
        return new Snapshot(known, known ? state : "unknown", radioKnown, radioOn,
                observed, sameMode ? before.generation : "", sameMode ? before.mode : "", reason);
    }

    private static Object trustedPeer(Object value, Class<?> deviceType,
            String address, Environment environment) throws Exception {
        if (value == null || value.getClass() != Array.newInstance(deviceType, 0).getClass())
            throw new IllegalStateException("Bonded inventory unavailable");
        Object peer = null;
        for (int i = 0; i < Array.getLength(value); i++) {
            Object device = Array.get(value, i);
            if (device == null) throw new IllegalStateException("Bonded inventory unavailable");
            String currentAddress = environment.deviceAddress(device);
            if (currentAddress == null)
                throw new IllegalStateException("Bonded inventory unavailable");
            if (address.equalsIgnoreCase(currentAddress)) peer = device;
        }
        return peer;
    }

    private static boolean knownRadioState(int state) {
        return state >= STATE_OFF && state <= STATE_TURNING_OFF;
    }

    private static final class AndroidEnvironment implements Environment {
        private final BluetoothAdapter adapter;
        private final Context context;
        private final BooleanSupplier authorized;

        AndroidEnvironment(Context context, BluetoothAdapter adapter, BooleanSupplier authorized) {
            this.context = context;
            this.adapter = adapter;
            this.authorized = authorized;
        }
        @Override public boolean authorized() { return authorized != null && authorized.getAsBoolean(); }
        @Override public int sdk() { return Build.VERSION.SDK_INT; }
        @Override public DiLink5Platform.ActiveModeSnapshot committedMode() {
            return DiLink5Platform.currentActiveModeSnapshot();
        }
        @Override public Object adapterBinding() {
            return adapter != null && BluetoothAdapter.getDefaultAdapter() == adapter
                    ? adapter : null;
        }
        @Override public int radioState() { return adapter == null ? -1 : adapter.getState(); }
        @Override public Object service() throws Exception {
            Field field = BluetoothAdapter.class.getDeclaredField("mService");
            field.setAccessible(true);
            return field.get(adapter);
        }
        @Override public boolean serviceAlive(Object service) {
            if (!(service instanceof IInterface)) return false;
            IBinder binder = ((IInterface) service).asBinder();
            return binder != null && binder.isBinderAlive();
        }
        @Override public Class<?> serviceType() throws Exception {
            return Class.forName("android.bluetooth.IBluetooth", false,
                    BluetoothConnectionStateReader.class.getClassLoader());
        }
        @Override public Class<?> deviceType() { return BluetoothDevice.class; }
        @Override public String deviceAddress(Object device) {
            return ((BluetoothDevice) device).getAddress();
        }
        @Override public long elapsedRealtime() { return SystemClock.elapsedRealtime(); }
        @Override public Object attributionSource() throws Exception {
            return BluetoothNativeCall.attributionSource(context);
        }
    }
}
