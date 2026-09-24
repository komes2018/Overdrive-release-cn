package com.overdrive.app.camera.dilink5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DiLink5CameraSafetyTest {

    /** A foreign same-boot lease is recovered as an unclean release (never a
     *  boot-long block); this pins which leases count as foreign. */
    @Test
    public void onlyForeignSameBootSessionIsTreatedAsStaleLease() {
        assertTrue(DiLink5CameraSafety.isForeignLiveSession(
                "boot-a", "boot-a", "owner-old", "owner-new"));
        assertFalse(DiLink5CameraSafety.isForeignLiveSession(
                "boot-a", "boot-b", "owner-old", "owner-new"));
        assertFalse(DiLink5CameraSafety.isForeignLiveSession(
                "boot-a", "boot-a", "owner-new", "owner-new"));
        assertFalse(DiLink5CameraSafety.isForeignLiveSession(
                null, "boot-a", "owner-old", "owner-new"));
    }

    @Test
    public void crossProcessCooldownIsLongerThanSameProcessCooldown() {
        assertEquals(
                DiLink5CameraSafety.SAME_PROCESS_REACQUIRE_COOLDOWN_MS,
                DiLink5CameraSafety.cooldownForOwners(
                        "same-owner", "same-owner"));
        assertEquals(
                DiLink5CameraSafety.CROSS_PROCESS_REACQUIRE_COOLDOWN_MS,
                DiLink5CameraSafety.cooldownForOwners(
                        "old-owner", "new-owner"));
        assertTrue(
                DiLink5CameraSafety.CROSS_PROCESS_REACQUIRE_COOLDOWN_MS
                        > DiLink5CameraSafety
                                .SAME_PROCESS_REACQUIRE_COOLDOWN_MS);
    }

    @Test
    public void cooldownUsesMonotonicReleaseAgeAndClampsSafely() {
        assertEquals(0L, DiLink5CameraSafety.remainingCooldownMs(
                5_000L, 0L, 10_000L));
        assertEquals(10_000L, DiLink5CameraSafety.remainingCooldownMs(
                5_000L, 6_000L, 10_000L));
        assertEquals(7_000L, DiLink5CameraSafety.remainingCooldownMs(
                8_000L, 5_000L, 10_000L));
        assertEquals(0L, DiLink5CameraSafety.remainingCooldownMs(
                20_000L, 5_000L, 10_000L));
    }

    @Test
    public void systemServiceProbeRequiresExactFoundResult() {
        assertTrue(DiLink5CameraSafety.serviceCheckSucceeded(
                0, "Service activity: found", "activity"));
        assertTrue(DiLink5CameraSafety.serviceCheckSucceeded(
                0, "SERVICE PACKAGE: FOUND", "package"));
        assertFalse(DiLink5CameraSafety.serviceCheckSucceeded(
                1, "Service activity: found", "activity"));
        assertFalse(DiLink5CameraSafety.serviceCheckSucceeded(
                0, "Service activity: not found", "activity"));
        assertFalse(DiLink5CameraSafety.serviceCheckSucceeded(
                0, "Service package_native: found", "package"));
    }

    @Test
    public void cleanReleaseRequiresTheExactAcquisitionLeaseGeneration() {
        assertTrue(DiLink5CameraSafety.matchesAcquisitionLease(7L, 7L));
        assertFalse(DiLink5CameraSafety.matchesAcquisitionLease(8L, 7L));
        assertFalse(DiLink5CameraSafety.matchesAcquisitionLease(
                Long.MIN_VALUE, Long.MIN_VALUE));
    }
}
