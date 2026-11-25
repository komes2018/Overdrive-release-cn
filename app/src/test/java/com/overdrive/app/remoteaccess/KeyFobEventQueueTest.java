package com.overdrive.app.remoteaccess;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class KeyFobEventQueueTest {
    @Test public void shortZeroFramesCannotReplaceAwayAndNearEdges() {
        KeyFobEventQueue queue = new KeyFobEventQueue();
        queue.reset(7);
        queue.offer(7, 4, 100);
        queue.offer(7, 0, 284);
        queue.offer(7, 0, 300);
        queue.offer(7, 8, 500);
        queue.offer(7, 0, 684);
        assertEquals(4, queue.poll().rawMask);
        assertEquals(0, queue.poll().rawMask);
        KeyFobEventQueue.Event near = queue.poll();
        assertEquals(8, near.rawMask);
        assertEquals(500, near.observedAtMs);
        assertEquals(4, near.sequence);
        assertEquals(0, queue.poll().rawMask);
        assertNull(queue.poll());
    }

    @Test public void generationResetNeverRepeatsCachedNearAsFresh() {
        KeyFobEventQueue queue = new KeyFobEventQueue();
        queue.reset(1);
        queue.offer(1, 8, 300);
        queue.reset(2);
        assertNull(queue.poll());
        assertEquals(-1, queue.latest().rawMask);
        assertEquals(-1, queue.latest().observedAtMs);
        assertEquals(0, queue.latest().sequence);
        assertEquals(2, queue.latest().generation);
        queue.offer(2, 8, 400);
        assertEquals(1, queue.poll().sequence);
    }

    @Test public void overflowFailsClosedRatherThanDroppingAnEdgeSilently() {
        KeyFobEventQueue queue = new KeyFobEventQueue();
        queue.reset(1);
        for (int i = 0; i <= KeyFobEventQueue.CAPACITY; i++)
            queue.offer(1, i % 2 == 0 ? 4 : 8, i);
        assertTrue(queue.hasOverflow());
        assertNull(queue.poll());
        queue.reset(2);
        assertFalse(queue.hasOverflow());
        assertEquals(-1, queue.latest().rawMask);
    }

    @Test public void repeatedCallbacksOnlyRefreshRealEventTimestamp() {
        KeyFobEventQueue queue = new KeyFobEventQueue();
        queue.reset(1);
        queue.offer(1, 4, 100);
        queue.offer(1, 4, 500);
        assertEquals(100, queue.poll().observedAtMs);
        assertNull(queue.poll());
        assertEquals(500, queue.latest().observedAtMs);
        assertEquals(2, queue.latest().sequence);
    }

    @Test public void sparseFirstEdgeSurvivesSetupButCannotSurviveFailedSetupGeneration() {
        KeyFobEventQueue successful = new KeyFobEventQueue();
        successful.reset(10);
        // The first native frame precedes the ADD acknowledgment by 100 ms.
        successful.offer(10, 4, 100);
        // No later frame is required to retain this sparse edge for relay.
        assertEquals(100, successful.poll().observedAtMs);
        assertNull(successful.poll());

        KeyFobEventQueue failed = new KeyFobEventQueue();
        failed.reset(10);
        failed.offer(10, 8, 100);
        // ADD, scope validation or consent failed: teardown changes generation.
        failed.reset(11);
        assertNull(failed.poll());
        assertEquals(-1, failed.latest().rawMask);
        assertEquals(0, failed.latest().sequence);
        assertEquals(-1, failed.latest().observedAtMs);
    }

    @Test public void delayedOldCallbackCannotBecomeAnEventInNewGeneration() throws Exception {
        KeyFobEventQueue queue = new KeyFobEventQueue();
        queue.reset(1);
        CountDownLatch callbackPassedInitialGuard = new CountDownLatch(1);
        CountDownLatch resumeCallback = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(true);
        Thread callback = new Thread(() -> {
            long listenerGeneration = 1;
            callbackPassedInitialGuard.countDown();
            try {
                resumeCallback.await();
                accepted.set(queue.offer(listenerGeneration, 8, 500));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        callback.start();
        assertTrue(callbackPassedInitialGuard.await(1, TimeUnit.SECONDS));
        queue.reset(2); // Receiver is torn down and then re-registered.
        resumeCallback.countDown();
        callback.join(1000);
        assertFalse(callback.isAlive());
        assertFalse(accepted.get());
        assertNull(queue.poll());
        assertEquals(0, queue.latest().sequence);
        assertEquals(-1, queue.latest().rawMask);
        assertTrue(queue.offer(2, 4, 600));
        assertEquals(1, queue.poll().sequence);
    }

    @Test public void oldCallbackFailureCannotRevokeNewGeneration() {
        KeyFobEventQueue queue = new KeyFobEventQueue();
        queue.reset(1);
        queue.reset(2);
        assertFalse(queue.fail(1, "native_callback_error"));
        assertNull(queue.failure());
        assertTrue(queue.offer(2, 8, 500));
        assertTrue(queue.fail(2, "native_callback_shape"));
        assertEquals("native_callback_shape", queue.failure());
        assertFalse(queue.offer(2, 4, 600));
        queue.reset(3);
        assertNull(queue.failure());
        assertTrue(queue.offer(3, 4, 700));
    }
}
