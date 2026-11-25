package com.overdrive.app.remoteaccess;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProximityCooldownPolicyTest {
    @Test public void firstAttemptAndExactExpiryAreAllowed() {
        assertFalse(ProximityCooldownPolicy.blocks("unlock", "", 0, -1, 30_000, false, 2));
        assertTrue(ProximityCooldownPolicy.blocks("unlock", "unlock", 30_099, 100, 30_000, false, 2));
        assertFalse(ProximityCooldownPolicy.blocks("unlock", "unlock", 30_100, 100, 30_000, false, 2));
    }

    @Test public void freshReturnInOppositeDirectionIsAllowed() {
        assertFalse(ProximityCooldownPolicy.blocks("unlock", "lock", 101, 100, 30_000, true, 2));
        assertFalse(ProximityCooldownPolicy.blocks("lock", "unlock", 101, 100, 30_000, true, 1));
    }

    @Test public void confirmedCommandThenManualFobLockAllowsNewUnlock() {
        assertFalse(ProximityCooldownPolicy.blocks("unlock", "unlock", 101, 100, 30_000, true, 2));
        assertFalse(ProximityCooldownPolicy.blocks("lock", "lock", 101, 100, 30_000, true, 1));
    }

    @Test public void failedOrUnconfirmedSameActionCannotBypassCooldown() {
        assertTrue(ProximityCooldownPolicy.blocks("unlock", "unlock", 101, 100, 30_000, false, 2));
        assertTrue(ProximityCooldownPolicy.blocks("lock", "lock", 101, 100, 30_000, false, 1));
    }

    @Test public void UnknownOrUnchangedLockStateDoesNotCreateNewCycle() {
        for (int state : new int[]{-1, 0, 1, 3})
            assertTrue(ProximityCooldownPolicy.blocks("unlock", "unlock", 101, 100, 30_000, true, state));
        for (int state : new int[]{-1, 0, 2, 3})
            assertTrue(ProximityCooldownPolicy.blocks("lock", "lock", 101, 100, 30_000, true, state));
    }

    /**
     * S5 (18 Oct): with the 10 s default cooldown an UNCONFIRMED command in one direction never holds
     * a fresh decision in the same direction once the cooldown has passed — the confirm job that
     * followed the first dispatch has long ended by then (its readback window is 4 s). Within the
     * cooldown the unconfirmed repeat is still held, exactly as before.
     */
    @Test public void unconfirmedArrivalThenFreshArrivalElevenSecondsLaterDispatchesAgain() {
        long dispatched = 100_000;
        long cooldownMs = RemoteAccessSettings.defaults().optLong("cooldownMs");
        assertEquals(10_000, cooldownMs);
        // Car still locked (2), first unlock unconfirmed: 9 s later → held, 11 s later → dispatches.
        assertTrue(ProximityCooldownPolicy.blocks("unlock", "unlock", dispatched + 9_000, dispatched, cooldownMs, false, 2));
        assertFalse(ProximityCooldownPolicy.blocks("unlock", "unlock", dispatched + 11_000, dispatched, cooldownMs, false, 2));
        assertFalse(ProximityCooldownPolicy.blocks("unlock", "unlock", dispatched + 10_000, dispatched, cooldownMs, false, 2));
        // Same for a departure lock that was not confirmed (car still unlocked, 1).
        assertTrue(ProximityCooldownPolicy.blocks("lock", "lock", dispatched + 9_000, dispatched, cooldownMs, false, 1));
        assertFalse(ProximityCooldownPolicy.blocks("lock", "lock", dispatched + 11_000, dispatched, cooldownMs, false, 1));
        // The 5 s floor shortens the hold; the ceiling keeps it.
        assertFalse(ProximityCooldownPolicy.blocks("unlock", "unlock", dispatched + 5_000, dispatched, 5_000, false, 2));
        assertTrue(ProximityCooldownPolicy.blocks("unlock", "unlock", dispatched + 11_000, dispatched, 300_000, false, 2));
    }

    @Test public void InvalidActionsAndClockRegressionsFailClosed() {
        assertTrue(ProximityCooldownPolicy.blocks("invalid", "unlock", 101, 100, 30_000, true, 2));
        assertTrue(ProximityCooldownPolicy.blocks("unlock", "invalid", 101, 100, 30_000, true, 2));
        assertTrue(ProximityCooldownPolicy.blocks("unlock", "lock", 99, 100, 30_000, true, 2));
        assertTrue(ProximityCooldownPolicy.blocks("unlock", "lock", -1, -1, 30_000, true, 2));
        assertTrue(ProximityCooldownPolicy.blocks("unlock", "lock", 101, 100, 0, true, 2));
        assertTrue(ProximityCooldownPolicy.blocks("unlock", "lock", 101, -2, 30_000, true, 2));
    }
}
