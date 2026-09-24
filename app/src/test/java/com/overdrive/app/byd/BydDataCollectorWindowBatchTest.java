package com.overdrive.app.byd;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class BydDataCollectorWindowBatchTest {

    @Test
    public void allSideWindowFeedbackRequiresFourValidPercentages() {
        assertTrue(BydDataCollector.hasCompleteSideWindowPositionFeedback(
                new int[] {0, 15, 50, 100}));

        assertFalse(BydDataCollector.hasCompleteSideWindowPositionFeedback(null));
        assertFalse(BydDataCollector.hasCompleteSideWindowPositionFeedback(
                new int[] {0, 15, 50}));
        assertFalse(BydDataCollector.hasCompleteSideWindowPositionFeedback(
                new int[] {0, 15, -1, 100}));
        assertFalse(BydDataCollector.hasCompleteSideWindowPositionFeedback(
                new int[] {0, 15, 101, 100}));
    }

    @Test
    public void allSideWindowsAreStartedWithOneFourSlotCommandVector() {
        assertArrayEquals(
                new int[] {1, 1, 1, 1},
                BydDataCollector.planSideWindowCommands(
                        new int[] {0, 0, 0, 0}, 15, 2));

        assertArrayEquals(
                new int[] {0, 2, 1, 2},
                BydDataCollector.planSideWindowCommands(
                        new int[] {15, 25, 10, 100}, 15, 2));
    }

    @Test
    public void batchTargetUsesDirectionAwareStopThresholds() {
        assertEquals(0, BydDataCollector.sideWindowCommandTowardTarget(
                14, 15, 2));
        assertEquals(1, BydDataCollector.sideWindowCommandTowardTarget(
                10, 15, 2));
        assertEquals(2, BydDataCollector.sideWindowCommandTowardTarget(
                25, 15, 2));

        assertFalse(BydDataCollector.hasReachedSideWindowTarget(
                12, 15, 1, 2));
        assertTrue(BydDataCollector.hasReachedSideWindowTarget(
                13, 15, 1, 2));
        assertFalse(BydDataCollector.hasReachedSideWindowTarget(
                18, 15, 2, 2));
        assertTrue(BydDataCollector.hasReachedSideWindowTarget(
                17, 15, 2, 2));
    }

    @Test
    public void liveBatchRetriesBusyStopAndLeavesEveryPaneStopped()
            throws Exception {
        BydDataCollector collector = newCollector();
        SimulatedBodyworkDevice bodywork =
                new SimulatedBodyworkDevice(70.0, true);
        setBodyworkDevice(collector, bodywork);

        try {
            assertTrue(collector.moveAllSideWindowsToPercent(15));
            assertTrue("batch never reached final grouped stop",
                    bodywork.awaitFinalStop(4, TimeUnit.SECONDS));

            Thread.sleep(200L);
            assertArrayEquals(
                    new int[] {1, 1, 1, 1},
                    bodywork.firstMotionCommand());
            assertEquals(1, bodywork.rejectedSparseStops());
            assertTrue(bodywork.allMotorsStopped());
            for (int percent : bodywork.positions()) {
                assertTrue("window stopped too low at " + percent,
                        percent >= 10);
                assertTrue("window overshot vent target at " + percent,
                        percent <= 25);
            }
        } finally {
            shutdownWindowExecutors(collector);
        }
    }

    @Test
    public void newerCloseAllCannotBeOverwrittenByStaleVentStop()
            throws Exception {
        BydDataCollector collector = newCollector();
        SimulatedBodyworkDevice bodywork =
                new SimulatedBodyworkDevice(35.0, false);
        setBodyworkDevice(collector, bodywork);

        try {
            assertTrue(collector.moveAllSideWindowsToPercent(15));
            assertTrue("vent start was never issued",
                    bodywork.awaitStart(2, TimeUnit.SECONDS));

            assertTrue(collector.setAllWindowsCommand(2));
            Thread.sleep(250L);

            assertArrayEquals(
                    new int[] {2, 2, 2, 2},
                    bodywork.lastCommand());
        } finally {
            shutdownWindowExecutors(collector);
        }
    }

    private static BydDataCollector newCollector() throws Exception {
        Constructor<BydDataCollector> constructor =
                BydDataCollector.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void setBodyworkDevice(
            BydDataCollector collector, Object device) throws Exception {
        Field field = BydDataCollector.class.getDeclaredField(
                "bodyworkDevice");
        field.setAccessible(true);
        field.set(collector, device);
    }

    private static void shutdownWindowExecutors(
            BydDataCollector collector) throws Exception {
        Field allField = BydDataCollector.class.getDeclaredField(
                "allSideWindowExecutor");
        allField.setAccessible(true);
        Object all = allField.get(collector);
        if (all instanceof ThreadPoolExecutor) {
            ((ThreadPoolExecutor) all).shutdownNow();
        }

        Field perAreaField = BydDataCollector.class.getDeclaredField(
                "windowExecutors");
        perAreaField.setAccessible(true);
        ThreadPoolExecutor[] perArea =
                (ThreadPoolExecutor[]) perAreaField.get(collector);
        assertNotNull(perArea);
        for (ThreadPoolExecutor executor : perArea) {
            if (executor != null) executor.shutdownNow();
        }
    }

    public static final class SimulatedBodyworkDevice {
        private static final int COMMAND_BUSY = -2147482647;

        private final double percentPerSecond;
        private final boolean rejectFirstSparseStop;
        private final double[] positions = new double[4];
        private final int[] directions = new int[4];
        private final List<int[]> commands = new ArrayList<>();
        private final CountDownLatch startSeen = new CountDownLatch(1);
        private final CountDownLatch finalStopSeen = new CountDownLatch(1);
        private long lastAdvanceNanos = System.nanoTime();
        private boolean sawMotionStart;
        private int rejectedSparseStops;

        SimulatedBodyworkDevice(
                double percentPerSecond,
                boolean rejectFirstSparseStop) {
            this.percentPerSecond = percentPerSecond;
            this.rejectFirstSparseStop = rejectFirstSparseStop;
            if (rejectFirstSparseStop) {
                // Ensure the panes cross the target on different poll cycles,
                // forcing a sparse STOP vector before the final grouped STOP.
                positions[1] = 1.0;
                positions[2] = 2.0;
                positions[3] = 3.0;
            }
        }

        public synchronized int setAllWindowState(
                int lf, int rf, int lr, int rr) {
            advance();
            int[] command = new int[] {lf, rf, lr, rr};
            commands.add(command.clone());

            boolean anyStop = false;
            boolean allStop = true;
            boolean anyMotion = false;
            for (int value : command) {
                anyStop |= value == 3;
                allStop &= value == 3;
                anyMotion |= value == 1 || value == 2;
            }
            if (rejectFirstSparseStop
                    && anyStop && !allStop
                    && rejectedSparseStops == 0) {
                rejectedSparseStops++;
                return COMMAND_BUSY;
            }

            for (int i = 0; i < command.length; i++) {
                if (command[i] == 1) directions[i] = 1;
                else if (command[i] == 2) directions[i] = -1;
                else if (command[i] == 3) directions[i] = 0;
            }
            if (anyMotion) {
                sawMotionStart = true;
                startSeen.countDown();
            }
            if (sawMotionStart && allStop) finalStopSeen.countDown();
            return 0;
        }

        public synchronized int getWindowOpenPercent(int area) {
            advance();
            return (int) Math.round(positions[area - 1]);
        }

        synchronized int[] positions() {
            advance();
            int[] result = new int[4];
            for (int i = 0; i < result.length; i++) {
                result[i] = (int) Math.round(positions[i]);
            }
            return result;
        }

        synchronized boolean allMotorsStopped() {
            for (int direction : directions) {
                if (direction != 0) return false;
            }
            return true;
        }

        synchronized int rejectedSparseStops() {
            return rejectedSparseStops;
        }

        synchronized int[] firstMotionCommand() {
            for (int[] command : commands) {
                for (int value : command) {
                    if (value == 1 || value == 2) return command.clone();
                }
            }
            return null;
        }

        synchronized int[] lastCommand() {
            if (commands.isEmpty()) return null;
            return commands.get(commands.size() - 1).clone();
        }

        boolean awaitStart(long timeout, TimeUnit unit)
                throws InterruptedException {
            return startSeen.await(timeout, unit);
        }

        boolean awaitFinalStop(long timeout, TimeUnit unit)
                throws InterruptedException {
            return finalStopSeen.await(timeout, unit);
        }

        private void advance() {
            long now = System.nanoTime();
            double elapsedSeconds =
                    (now - lastAdvanceNanos) / 1_000_000_000.0;
            lastAdvanceNanos = now;
            for (int i = 0; i < positions.length; i++) {
                positions[i] += directions[i]
                        * percentPerSecond * elapsedSeconds;
                if (positions[i] < 0.0) positions[i] = 0.0;
                if (positions[i] > 100.0) positions[i] = 100.0;
            }
        }
    }
}
