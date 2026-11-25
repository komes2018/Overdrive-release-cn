package com.overdrive.app.server;

import android.os.SystemClock;
import com.overdrive.app.monitor.BluetoothRadioState;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;

/**
 * One shared, read-only cache for dashboard and proximity requests. Only existing HTTP
 * requests refresh it; there is no background executor, timer or feature dependency.
 */
final class BluetoothRadioStatusReader {
    private static final long CACHE_MS = 2_000L;
    private static final long FAILURE_RETRY_MS = 5_000L;
    private static final Cache CACHE = new Cache(
            SystemClock::elapsedRealtime, BluetoothRadioStatusReader::readBridge);

    private BluetoothRadioStatusReader() {}

    static JSONObject current() {
        return CACHE.current();
    }

    static JSONObject cached() {
        return CACHE.cached();
    }

    static void refreshIfNeeded() {
        CACHE.refreshIfNeeded();
    }

    private static JSONObject readBridge() {
        try {
            RemoteDevViewBridgeClient.Response response = new RemoteDevViewBridgeClient().send(
                    new JSONObject().put("command", "bluetoothStatus"), 150, 300, false);
            return response != null && response.metadata.optBoolean("success")
                    ? response.metadata.optJSONObject("bluetooth") : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Request-driven cache with injectable I/O and clock for concurrency/failure tests. */
    static final class Cache {
        private final BluetoothRadioState.Store store = new BluetoothRadioState.Store();
        private final AtomicBoolean reading = new AtomicBoolean();
        private final LongSupplier clock;
        private final Supplier<JSONObject> read;
        private volatile long nextReadElapsedMs;

        Cache(LongSupplier clock, Supplier<JSONObject> read) {
            this.clock = clock;
            this.read = read;
        }

        JSONObject current() {
            refreshIfNeeded();
            return cached();
        }

        JSONObject cached() {
            return store.snapshot(clock.getAsLong());
        }

        void refreshIfNeeded() {
            long now = clock.getAsLong();
            if (now < nextReadElapsedMs || !reading.compareAndSet(false, true)) return;
            if (clock.getAsLong() < nextReadElapsedMs) {
                reading.set(false);
                return;
            }
            boolean accepted = false;
            try {
                accepted = store.accept(read.get(), clock.getAsLong());
            } catch (Exception ignored) {
                // Keep the original timestamp after transport or adapter read failures.
            } finally {
                nextReadElapsedMs = clock.getAsLong()
                        + (accepted ? CACHE_MS : FAILURE_RETRY_MS);
                reading.set(false);
            }
        }
    }
}
