package com.overdrive.app.server;

import com.overdrive.app.monitor.BluetoothRadioState;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BluetoothRadioStatusReaderTest {
    @Test public void dashboardAndProximityRequestsReuseOneNativeRead() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        AtomicInteger reads = new AtomicInteger();
        BluetoothRadioStatusReader.Cache cache = new BluetoothRadioStatusReader.Cache(now::get, () -> {
            reads.incrementAndGet();
            return BluetoothRadioState.observation(10, true, 5000, now.get());
        });
        assertEquals("off", cache.current().getString("status"));
        now.set(2999);
        assertEquals("off", cache.current().getString("status"));
        assertEquals(1, reads.get());
        now.set(3000);
        cache.current();
        assertEquals(2, reads.get());
    }

    @Test public void cacheReadsDoNotCauseAnyRefreshWithoutAStatusRequest() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        AtomicInteger reads = new AtomicInteger();
        BluetoothRadioStatusReader.Cache cache = new BluetoothRadioStatusReader.Cache(now::get, () -> {
            reads.incrementAndGet();
            return BluetoothRadioState.observation(12, true, 5000, now.get());
        });
        assertEquals("unknown", cache.cached().getString("status"));
        assertEquals(0, reads.get());
        cache.current();
        now.set(30_000);
        assertEquals("stale", cache.cached().getString("status"));
        assertEquals(1, reads.get());
    }

    @Test public void aMissingBridgeIsThrottledAndRemainsUnknown() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        AtomicInteger reads = new AtomicInteger();
        BluetoothRadioStatusReader.Cache cache = new BluetoothRadioStatusReader.Cache(now::get, () -> {
            reads.incrementAndGet();
            return null;
        });
        assertEquals("unknown", cache.current().getString("status"));
        now.set(5999);
        assertTrue(cache.current().isNull("enabled"));
        assertEquals(1, reads.get());
        now.set(6000);
        cache.current();
        assertEquals(2, reads.get());
    }

    @Test public void failedRefreshDoesNotMakeLastOnObservationFreshAgain() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        AtomicInteger reads = new AtomicInteger();
        BluetoothRadioStatusReader.Cache cache = new BluetoothRadioStatusReader.Cache(now::get, () -> {
            if (reads.getAndIncrement() == 0)
                return BluetoothRadioState.observation(12, true, 5000, now.get());
            throw new IllegalStateException("App unavailable");
        });
        assertTrue(cache.current().getBoolean("enabled"));
        now.set(3000);
        assertEquals(2000, cache.current().getLong("ageMs"));
        now.set(17_000);
        JSONObject state = cache.current();
        assertEquals("stale", state.getString("status"));
        assertTrue(state.isNull("enabled"));
        assertEquals(5000, state.getLong("updatedAtMs"));
    }

    @Test public void concurrentRequestsDoNotWaitForOrDuplicateTheNativeRead() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        BluetoothRadioStatusReader.Cache cache = new BluetoothRadioStatusReader.Cache(now::get, () -> {
            reads.incrementAndGet();
            entered.countDown();
            try {
                if (!finish.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return BluetoothRadioState.observation(10, true, 5000, now.get());
        });
        Thread first = new Thread(() -> {
            try { cache.current(); } catch (Throwable failure) { error.set(failure); }
        });
        first.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals("unknown", cache.current().getString("status"));
            assertEquals(1, reads.get());
        } finally {
            finish.countDown();
            first.join(2000);
        }
        assertFalse(first.isAlive());
        assertNull(error.get());
        assertEquals("off", cache.cached().getString("status"));
        now.set(2999);
        cache.current();
        assertEquals(1, reads.get());
        now.set(3000);
        cache.current();
        assertEquals("A competing request must preserve the successful two-second deadline",
                2, reads.get());
    }
}
