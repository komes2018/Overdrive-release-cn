package com.overdrive.app.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.logging.DaemonLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Field regression: the shared pre-record ring was sized once, by the FIRST
 * encoder of the daemon process, and never resized. When that encoder ran at a
 * lower bitrate than the recorder later used (a 62 s ring allocated at 3 Mbps
 * is 24 MB; the same window at 6 Mbps needs 48 MB), every instant-replay key
 * press was refused with RESTART_REQUIRED — and a cold start reproduced the
 * same undersized allocation. The fix regrows the arena at encoder re-init,
 * the one point where the ring is idle, gated on Java-heap headroom.
 */
public class HardwareEventRecorderGpuSharedRingRegrowTest {
    private static final int SMALL_BUDGET = 8 * 1024 * 1024;
    private static final int LARGER_BUDGET = 16 * 1024 * 1024;
    private static final int RETENTION_SECONDS = 62;
    private static final String ENCODER =
            "app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java";

    private DaemonLogger.Config originalLoggerConfig;

    @Before
    public void setUp() throws Exception {
        originalLoggerConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
        resetSharedRing();
    }

    @After
    public void tearDown() throws Exception {
        resetSharedRing();
        DaemonLogger.configure(originalLoggerConfig);
    }

    @Test
    public void regrowReplacesIdleSharedRingWhenHeapAllows() throws Exception {
        H264ByteRingBuffer small = installSharedRing(SMALL_BUDGET, RETENTION_SECONDS);
        long heapAvailable = LARGER_BUDGET
                + HardwareEventRecorderGpu.SHARED_RING_REGROW_HEAP_MARGIN_BYTES + 1;

        assertTrue(HardwareEventRecorderGpu.regrowSharedRingLocked(
                LARGER_BUDGET, RETENTION_SECONDS, heapAvailable));

        H264ByteRingBuffer regrown = sharedRing();
        assertNotSame(small, regrown);
        assertEquals(LARGER_BUDGET, regrown.getBudgetBytes());
        assertEquals(LARGER_BUDGET, sharedBudgetBytes());
        // The replacement carries the retention window the replay needs, exactly
        // as the reuse path's setMaxDurationUs would have set it.
        assertEquals(RETENTION_SECONDS * 1_000_000L, regrown.getMaxDurationUs());
    }

    @Test
    public void regrowRefusedWithoutHeapHeadroomKeepsOldRing() throws Exception {
        H264ByteRingBuffer small = installSharedRing(SMALL_BUDGET, RETENTION_SECONDS);
        long heapAvailable = LARGER_BUDGET
                + HardwareEventRecorderGpu.SHARED_RING_REGROW_HEAP_MARGIN_BYTES - 1;

        assertFalse(HardwareEventRecorderGpu.regrowSharedRingLocked(
                LARGER_BUDGET, RETENTION_SECONDS, heapAvailable));

        assertSame(small, sharedRing());
        assertEquals(SMALL_BUDGET, sharedBudgetBytes());
    }

    @Test
    public void regrowIsANoOpWhenArenaAlreadyHoldsTheWindow() throws Exception {
        H264ByteRingBuffer ring = installSharedRing(LARGER_BUDGET, RETENTION_SECONDS);

        assertTrue(HardwareEventRecorderGpu.regrowSharedRingLocked(
                SMALL_BUDGET, RETENTION_SECONDS, Long.MAX_VALUE));

        assertSame(ring, sharedRing());
        assertEquals(LARGER_BUDGET, sharedBudgetBytes());
    }

    @Test
    public void regrowRefusedAboveBudgetCeiling() throws Exception {
        H264ByteRingBuffer small = installSharedRing(SMALL_BUDGET, RETENTION_SECONDS);

        assertFalse(HardwareEventRecorderGpu.regrowSharedRingLocked(
                129 * 1024 * 1024, RETENTION_SECONDS, Long.MAX_VALUE));

        assertSame(small, sharedRing());
    }

    @Test
    public void regrowWithoutASharedRingIsRefused() throws Exception {
        assertFalse(HardwareEventRecorderGpu.regrowSharedRingLocked(
                LARGER_BUDGET, RETENTION_SECONDS, Long.MAX_VALUE));
        assertEquals(0, sharedBudgetBytes());
    }

    /**
     * Source contract: the regrow must run on the encoder re-init reuse branch —
     * after the arena is cleared (idle) and BEFORE the "reusing" fallback — and
     * the live-edit path must still never resize a producing arena.
     */
    @Test
    public void reinitReuseBranchRegrowsBeforeFallingBackToTheSmallArena() throws IOException {
        String encoder = readRepositoryFile(ENCODER);

        int clear = encoder.indexOf("sharedPreRecordBuffer.clear();");
        assertTrue(clear >= 0);
        int regrow = encoder.indexOf(
                "regrowSharedRingLocked(desiredBudget, desiredSec,", clear);
        assertTrue("re-init must attempt the regrow on the idle arena", regrow > clear);
        int fallback = encoder.indexOf("Saved replay window needs", regrow);
        assertTrue("small-arena fallback must remain, after the regrow attempt",
                fallback > regrow);

        // Live edits (setManualClipRetentionDuration / setPreRecordDuration on a
        // producing encoder) only move the duration window; the arena swap is
        // confined to the idle re-init point.
        int liveEdit = encoder.indexOf("private void applyPreRecordRetentionWindow(");
        assertTrue(liveEdit >= 0);
        int nextMethod = encoder.indexOf("public long getSegmentDurationMs()", liveEdit);
        String liveBody = encoder.substring(liveEdit, nextMethod);
        assertFalse(liveBody.contains("regrowSharedRingLocked("));
        assertFalse(liveBody.contains("new H264ByteRingBuffer("));
    }

    private static H264ByteRingBuffer installSharedRing(int budgetBytes, int durationSeconds)
            throws Exception {
        H264ByteRingBuffer ring = new H264ByteRingBuffer(budgetBytes, durationSeconds);
        setStaticField("sharedPreRecordBuffer", ring);
        setStaticField("sharedPreRecordBudgetBytes", budgetBytes);
        return ring;
    }

    private static void resetSharedRing() throws Exception {
        setStaticField("sharedPreRecordBuffer", null);
        setStaticField("sharedPreRecordBudgetBytes", 0);
    }

    private static H264ByteRingBuffer sharedRing() throws Exception {
        Field field = HardwareEventRecorderGpu.class.getDeclaredField("sharedPreRecordBuffer");
        field.setAccessible(true);
        return (H264ByteRingBuffer) field.get(null);
    }

    private static int sharedBudgetBytes() throws Exception {
        Field field = HardwareEventRecorderGpu.class.getDeclaredField("sharedPreRecordBudgetBytes");
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void setStaticField(String name, Object value) throws Exception {
        Field field = HardwareEventRecorderGpu.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("Could not locate " + relativePath);
    }
}
