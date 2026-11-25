package com.overdrive.app.monitor;

import android.bluetooth.BluetoothAdapter;
import android.os.IInterface;
import android.os.SystemClock;
import java.lang.reflect.Field;
import org.json.JSONObject;

/** Radio state only: this never scans, connects, changes the adapter or starts a monitor. */
public final class BluetoothRadioState {
    public static final long MAX_AGE_MS = 15_000L;

    private BluetoothRadioState() {}

    /** Called in the real app process, since the shell daemon cannot reliably read Bluetooth. */
    public static JSONObject readCurrent() {
        BluetoothAdapter adapter;
        try {
            adapter = BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable ignored) {
            return observation(-1, true, System.currentTimeMillis(), SystemClock.elapsedRealtime());
        }
        return readFrom(() -> readManagerState(adapter), adapter != null,
                System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }

    private static Object readManagerState(BluetoothAdapter adapter) throws Exception {
        // API29's public getState() silently returns OFF on RemoteException. Read the
        // manager itself so an IPC failure remains unknown. The manager stays alive
        // while radio OFF; the separate IBluetooth mService legitimately disappears.
        Field field = BluetoothAdapter.class.getDeclaredField("mManagerService");
        field.setAccessible(true);
        Object manager = field.get(adapter);
        if (!(manager instanceof IInterface) || ((IInterface) manager).asBinder() == null
                || !((IInterface) manager).asBinder().isBinderAlive())
            throw new IllegalStateException("Bluetooth manager unavailable");
        return Class.forName("android.bluetooth.IBluetoothManager")
                .getMethod("getState").invoke(manager);
    }

    @FunctionalInterface
    interface StateReader {
        Object read() throws Exception;
    }

    static JSONObject readFrom(StateReader reader, boolean available, long wallMs, long elapsedMs) {
        int state = -1;
        if (available) {
            try {
                Object raw = reader.read();
                if (raw instanceof Number) {
                    double number = ((Number) raw).doubleValue();
                    if (Double.isFinite(number) && number == Math.rint(number)) {
                        if (number >= 10 && number <= 13) state = (int) number;
                        // The framework's public adapter state treats successful LE-only
                        // states as classic Bluetooth OFF. These are native states, not errors.
                        else if (number >= 14 && number <= 16) state = 10;
                    }
                }
            } catch (Throwable ignored) {
                // Reflection/permission/IPC failures never become an observed OFF.
            }
        }
        return observation(state, available, wallMs, elapsedMs);
    }

    public static JSONObject observation(int state, boolean available, long wallMs, long elapsedMs) {
        JSONObject value = new JSONObject();
        try {
            value.put("state", available && known(state) ? state : -1)
                    .put("available", available)
                    .put("updatedAtMs", wallMs)
                    .put("observedAtElapsedMs", elapsedMs);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Cannot encode Bluetooth state", invalid);
        }
        return value;
    }

    public static boolean known(int state) {
        return state >= 10 && state <= 13;
    }

    private static String name(int state, boolean available) {
        if (!available) return "unavailable";
        switch (state) {
            case 10: return "off";
            case 11: return "turning_on";
            case 12: return "on";
            case 13: return "turning_off";
            default: return "unknown";
        }
    }

    /** Immutable observations are shared across requests; reading a snapshot performs no I/O. */
    public static final class Store {
        private volatile Observation current;

        public synchronized boolean accept(JSONObject value, long nowElapsedMs) {
            if (value == null || !(value.opt("available") instanceof Boolean)) return false;
            Long state = integer(value.opt("state"));
            Long wall = integer(value.opt("updatedAtMs"));
            Long elapsed = integer(value.opt("observedAtElapsedMs"));
            if (state == null || wall == null || elapsed == null
                    || wall < 0 || elapsed < 0 || elapsed > nowElapsedMs
                    || (state != -1 && (state < 10 || state > 13))
                    || nowElapsedMs - elapsed > MAX_AGE_MS) return false;
            boolean available = value.optBoolean("available");
            if (!available && state != -1) return false;
            Observation previous = current;
            if (previous != null && elapsed < previous.elapsedMs) return false;
            current = new Observation(state.intValue(), available, wall, elapsed);
            return true;
        }

        public JSONObject snapshot(long nowElapsedMs) {
            Observation observed = current;
            JSONObject value = new JSONObject();
            try {
                long age = observed == null ? -1 : nowElapsedMs - observed.elapsedMs;
                boolean fresh = age >= 0 && age <= MAX_AGE_MS;
                String lastStatus = observed == null ? "unknown" : name(observed.state, observed.available);
                boolean known = observed != null && observed.available && known(observed.state);
                value.put("state", observed == null ? -1 : observed.state)
                        .put("status", observed == null ? "unknown" : fresh ? lastStatus : "stale")
                        .put("lastStatus", lastStatus)
                        .put("available", observed != null && observed.available)
                        .put("enabled", fresh && known ? observed.state == 12 : JSONObject.NULL)
                        .put("fresh", fresh)
                        .put("updatedAtMs", observed == null ? JSONObject.NULL : observed.wallMs)
                        .put("ageMs", age < 0 ? JSONObject.NULL : age)
                        .put("maxAgeMs", MAX_AGE_MS)
                        .put("source", "app_bluetooth_adapter");
            } catch (Exception invalid) {
                throw new IllegalStateException("Cannot encode Bluetooth snapshot", invalid);
            }
            return value;
        }

        private static Long integer(Object raw) {
            if (!(raw instanceof Number)) return null;
            double value = ((Number) raw).doubleValue();
            return Double.isFinite(value) && value == Math.rint(value)
                    && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE
                    ? ((Number) raw).longValue() : null;
        }
    }

    private static final class Observation {
        final int state;
        final boolean available;
        final long wallMs, elapsedMs;
        Observation(int state, boolean available, long wallMs, long elapsedMs) {
            this.state = state;
            this.available = available;
            this.wallMs = wallMs;
            this.elapsedMs = elapsedMs;
        }
    }
}
