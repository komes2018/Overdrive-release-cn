package com.overdrive.app.camera.dilink5;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public class DiLink5QCarCamBackendOwnershipTest {

    @Test
    public void cleanReleaseRequiresNativeAndProcessOwnershipToEnd() {
        assertTrue(DiLink5QCarCamBackend.isCompleteOwnershipRelease(
                true, true));
        assertFalse(DiLink5QCarCamBackend.isCompleteOwnershipRelease(
                false, true));
        assertFalse(DiLink5QCarCamBackend.isCompleteOwnershipRelease(
                true, false));
    }

    @Test
    public void aisStallSelfExitClassifiesExactlyCode42() {
        assertTrue(DiLink5QCarCamBackend.isAisStallSelfExit(42));
        // Not the release guard's fail-closed status...
        assertFalse(DiLink5QCarCamBackend.isAisStallSelfExit(125));
        // ...not a clean stop, a kill, or an unknown code.
        assertFalse(DiLink5QCarCamBackend.isAisStallSelfExit(0));
        assertFalse(DiLink5QCarCamBackend.isAisStallSelfExit(137));
        assertFalse(DiLink5QCarCamBackend.isAisStallSelfExit(Integer.MIN_VALUE));
    }

    @Test
    public void cancelledOrRecoveringSessionCannotClaimNativeOwnership() {
        assertFalse(DiLink5QCarCamBackend.canClaimNativeSessionOwner(
                false, false, Long.MIN_VALUE, 11L));
        assertFalse(DiLink5QCarCamBackend.canClaimNativeSessionOwner(
                true, true, Long.MIN_VALUE, 11L));
        assertFalse(DiLink5QCarCamBackend.canClaimNativeSessionOwner(
                true, false, 12L, 11L));
        assertTrue(DiLink5QCarCamBackend.canClaimNativeSessionOwner(
                true, false, Long.MIN_VALUE, 11L));
        assertTrue(DiLink5QCarCamBackend.canClaimNativeSessionOwner(
                true, false, 11L, 11L));
    }

    @Test
    public void recoveryInProgressAlwaysWinsAdmissionClassification() {
        assertEquals(
                DiLink5QCarCamBackend.NativeRecoveryAdmission.BUSY,
                DiLink5QCarCamBackend.decideNativeRecoveryAdmission(
                        true, false, true));
        assertEquals(
                DiLink5QCarCamBackend.NativeRecoveryAdmission.BUSY,
                DiLink5QCarCamBackend.decideNativeRecoveryAdmission(
                        true, true, true));
        assertEquals(
                DiLink5QCarCamBackend.NativeRecoveryAdmission.BUSY,
                DiLink5QCarCamBackend.decideNativeRecoveryAdmission(
                        false, true, false));
        assertEquals(
                DiLink5QCarCamBackend.NativeRecoveryAdmission.ACQUIRED,
                DiLink5QCarCamBackend.decideNativeRecoveryAdmission(
                        true, true, false));
        assertEquals(
                DiLink5QCarCamBackend.NativeRecoveryAdmission.NOT_NEEDED,
                DiLink5QCarCamBackend.decideNativeRecoveryAdmission(
                        true, false, false));
    }

    @Test
    public void reverseWaitsForSpawnPublicationBarrier()
            throws Exception {
        DiLink5QCarCamBackend.HardwareStartupBarrier barrier =
                new DiLink5QCarCamBackend.HardwareStartupBarrier();
        assertTrue(barrier.begin(31L, 7L));

        CountDownLatch waiterEntered = new CountDownLatch(1);
        CountDownLatch waiterFinished = new CountDownLatch(1);
        AtomicBoolean cleared = new AtomicBoolean(false);
        Thread waiter = new Thread(() -> {
            waiterEntered.countDown();
            cleared.set(barrier.awaitClear(2_000L));
            waiterFinished.countDown();
        }, "fastcam-startup-barrier-test");
        waiter.start();

        assertTrue(waiterEntered.await(1, TimeUnit.SECONDS));
        assertFalse(waiterFinished.await(75, TimeUnit.MILLISECONDS));
        barrier.clearIfOwned(99L, 7L);
        assertTrue(barrier.isActive());
        assertFalse(waiterFinished.await(75, TimeUnit.MILLISECONDS));

        barrier.clearIfOwned(31L, 7L);
        assertTrue(waiterFinished.await(1, TimeUnit.SECONDS));
        assertTrue(cleared.get());
        assertFalse(barrier.isActive());
        waiter.join(1_000L);
    }

    @Test
    public void recoveryLifecycleAdmissionHasABoundedWait()
            throws Exception {
        DiLink5QCarCamBackend.LifecycleGate gate =
                new DiLink5QCarCamBackend.LifecycleGate();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        Thread owner = new Thread(() -> {
            gate.lock();
            try {
                ownerEntered.countDown();
                releaseOwner.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                gate.unlock();
            }
        }, "fastcam-lifecycle-owner-test");
        owner.start();

        assertTrue(ownerEntered.await(1, TimeUnit.SECONDS));
        assertFalse(gate.tryLock(75L));
        releaseOwner.countDown();
        owner.join(1_000L);
        assertFalse(owner.isAlive());
        assertTrue(gate.tryLock(500L));
        gate.unlock();
    }
}
