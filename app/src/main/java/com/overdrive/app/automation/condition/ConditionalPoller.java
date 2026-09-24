package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * A fixed-delay poll that exists only while an enabled automation references its signal.
 *
 * <p>All fast automation pollers share one bounded executor. Cancelled tasks are removed
 * immediately and core threads time out, so disabling the last relevant rule leaves no
 * per-signal scheduler waking in the background.
 */
final class ConditionalPoller {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final AtomicInteger threadIds = new AtomicInteger();
    private static final ThreadFactory threadFactory = runnable -> {
        Thread thread = new Thread(
                runnable, "AutomationPoll-" + threadIds.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };
    private static final ScheduledThreadPoolExecutor executor =
            new ScheduledThreadPoolExecutor(4, threadFactory);

    static {
        executor.setRemoveOnCancelPolicy(true);
        executor.setKeepAliveTime(5L, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
    }

    private final String name;
    private final long periodMs;
    private final BooleanSupplier shouldRun;
    private final Runnable task;
    private final boolean vehicleState;

    private ScheduledFuture<?> future;
    private long generation;

    ConditionalPoller(
            String name, long periodMs, BooleanSupplier shouldRun, Runnable task) {
        this(name, periodMs, shouldRun, task, true);
    }

    ConditionalPoller(
            String name, long periodMs, BooleanSupplier shouldRun, Runnable task,
            boolean vehicleState) {
        if (periodMs <= 0L) throw new IllegalArgumentException("periodMs must be positive");
        this.name = name;
        this.periodMs = periodMs;
        this.shouldRun = shouldRun;
        this.task = task;
        this.vehicleState = vehicleState;
    }

    /** Start, keep, or cancel this poll according to its current reference predicate. */
    void refresh() {
        boolean wanted = safelyWanted();
        synchronized (this) {
            if (!wanted) {
                cancelLocked();
                return;
            }
            if (future != null && !future.isDone() && !future.isCancelled()) return;

            long token = ++generation;
            AtomicBoolean first = new AtomicBoolean(true);
            future = executor.scheduleWithFixedDelay(() -> {
                if (!isCurrent(token)) return;
                if (!safelyWanted()) {
                    cancel(token);
                    return;
                }
                try {
                    Runnable currentTask = task;
                    if (vehicleState && DiLink5Platform.isSelected()) {
                        currentTask = () -> BydDataCollector.getInstance()
                                .runWithDiLink5AutomationFreshness(task);
                    }
                    if (first.getAndSet(false)) {
                        // First observation after enabling is a baseline, not a vehicle edge.
                        Automations.runSilentSeed(currentTask);
                    } else {
                        currentTask.run();
                    }
                } catch (Throwable t) {
                    logger.error(name + " poll failed", t);
                }
            }, 0L, periodMs, TimeUnit.MILLISECONDS);
        }
    }

    private boolean safelyWanted() {
        try {
            return shouldRun.getAsBoolean();
        } catch (Throwable t) {
            logger.warn(name + " reference check failed: " + t.getMessage());
            return false;
        }
    }

    private synchronized boolean isCurrent(long token) {
        return token == generation;
    }

    private synchronized void cancel(long token) {
        if (token == generation) cancelLocked();
    }

    private void cancelLocked() {
        generation++;
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }

    synchronized boolean isScheduledForTest() {
        return future != null && !future.isDone() && !future.isCancelled();
    }

    synchronized long generationForTest() {
        return generation;
    }
}
