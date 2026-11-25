package com.overdrive.app.remoteaccess;

import org.junit.Test;
import static org.junit.Assert.*;

public class KeyFobPresenceCycleTest {
    @Test public void staleInitialResultAndProgressZeroNeverBecomePresenceOrAbsence() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long id = cycle.begin(1_000, 3);
        assertFalse(cycle.acceptRequest(1, id, 1_010));
        assertFalse(cycle.observe(1, id, 3, 1_050));
        assertFalse(cycle.observe(1, id, 0, 1_100));
        assertTrue(cycle.observe(1, id, 1, 1_200));
        assertEquals("present", cycle.snapshot().state);
        assertEquals(1_200, cycle.snapshot().observedMs);
    }

    @Test public void changedFreshAbsentIsAvailableWithoutTreatingInitialAbsentAsDeparture() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long id = cycle.begin(1_000, 1);
        assertFalse(cycle.acceptRequest(1, id, 1_010));
        assertTrue(cycle.observe(1, id, 3, 1_200));
        assertEquals("absent", cycle.snapshot().state);
        // The separate server evaluator decides whether a previous PRESENT armed this.
        assertEquals(1, cycle.snapshot().observationId);
    }

    @Test public void timeoutInvalidatesKnownObservationAndKeepsOriginalDeadline() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long first = cycle.begin(1_000, 3);
        assertFalse(cycle.acceptRequest(1, first, 1_010));
        assertTrue(cycle.observe(1, first, 1, 1_200));
        long second = cycle.begin(11_000, 1);
        assertFalse(cycle.expire(16_999));
        assertTrue(cycle.expire(17_100));
        assertEquals("unknown", cycle.snapshot().state);
        assertEquals(17_000, cycle.snapshot().observedMs);
        assertEquals(2, cycle.snapshot().observationId);
        assertFalse(cycle.observe(1, second, 3, 17_200));
    }

    @Test public void originalCompletedTupleDoesNotChangeWhileNextSearchIsPending() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long first = cycle.begin(1_000, 3);
        assertFalse(cycle.acceptRequest(1, first, 1_010));
        assertTrue(cycle.observe(1, first, 1, 1_200));
        long second = cycle.begin(11_000, 1);
        assertTrue(second > first);
        KeyFobPresenceCycle.Snapshot pending = cycle.snapshot();
        assertEquals("pending", pending.status);
        assertEquals(first, pending.cycleId);
        assertEquals(1_000, pending.startedMs);
        assertEquals(1_200, pending.observedMs);
        assertEquals(1, pending.observationId);
    }

    @Test public void startsAreSpacedTenSecondsEvenAfterFailure() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long first = cycle.begin(1_000, 1);
        assertTrue(cycle.fail(1, first, 1_010));
        assertFalse(cycle.due(10_999));
        assertEquals(0, cycle.begin(10_999, 1));
        assertTrue(cycle.due(11_000));
        assertTrue(cycle.begin(11_000, 1) > first);
    }

    @Test public void resetRejectsLateCallbackAndClearsPreviousKnownResult() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long old = cycle.begin(1_000, 1);
        cycle.reset();
        assertFalse(cycle.observe(1, old, 3, 1_200));
        assertEquals(2, cycle.snapshot().generation);
        assertEquals("unknown", cycle.snapshot().state);
        assertEquals(0, cycle.snapshot().observationId);
        long next = cycle.begin(2_000, 3);
        assertFalse(cycle.acceptRequest(2, next, 2_010));
        assertFalse(cycle.observe(1, next, 1, 2_100));
        assertTrue(cycle.observe(2, next, 1, 2_100));
    }

    @Test public void callbackAtDeadlineOrClockRegressionIsUnknown() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long id = cycle.begin(1_000, 1);
        assertTrue(cycle.observe(1, id, 3, 7_000));
        assertEquals("unknown", cycle.snapshot().state);
        assertFalse(cycle.snapshot().pending);
        long next = cycle.begin(11_000, 1);
        assertTrue(cycle.observe(1, next, 3, 10_999));
        assertEquals("unknown", cycle.snapshot().state);
    }

    @Test public void callbackDuringSetterIsPublishedOnlyAfterSuccessfulAcknowledgement() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long id = cycle.begin(1_000, 3);
        assertFalse(cycle.observe(1, id, 0, 1_050));
        assertFalse(cycle.observe(1, id, 1, 1_100));
        assertEquals("unknown", cycle.snapshot().state);
        assertEquals(0, cycle.snapshot().observationId);
        assertTrue(cycle.acceptRequest(1, id, 1_200));
        assertEquals("present", cycle.snapshot().state);
        assertEquals(1_100, cycle.snapshot().observedMs);
    }

    @Test public void failedSetterDiscardsSynchronousKnownCallback() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long id = cycle.begin(1_000, 1);
        assertFalse(cycle.observe(1, id, 3, 1_100));
        assertTrue(cycle.fail(1, id, 1_200));
        assertEquals("unknown", cycle.snapshot().state);
        assertEquals(1, cycle.snapshot().observationId);
        assertFalse(cycle.acceptRequest(1, id, 1_300));
    }

    @Test public void delayedPreparationStillSpacesActualNativeCallsByTenSeconds() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long id = cycle.begin(1_000, 3);
        assertTrue(cycle.requestStarted(1, id, 4_000));
        assertFalse(cycle.acceptRequest(1, id, 4_010));
        assertTrue(cycle.observe(1, id, 1, 4_100));
        assertEquals(4_000, cycle.snapshot().startedMs);
        assertFalse(cycle.due(13_999));
        assertTrue(cycle.due(14_000));
    }

    @Test public void onlyConfirmedOffAndLockedScopePausesSearch() {
        assertTrue(KeyFobPresenceCycle.shouldPause(0, 2));
        assertFalse(KeyFobPresenceCycle.shouldPause(0, 1));
        assertFalse(KeyFobPresenceCycle.shouldPause(0, -1));
        assertFalse(KeyFobPresenceCycle.shouldPause(-1, 2));
        assertFalse(KeyFobPresenceCycle.shouldPause(1, 2));
        assertFalse(KeyFobPresenceCycle.shouldPause(3, 2));
    }

    @Test public void lockedPauseCancelsPendingAndRejectsItsLateCallback() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long old = cycle.begin(1_000, 1);
        assertFalse(cycle.acceptRequest(1, old, 1_010));
        assertTrue(cycle.pauseLocked());
        KeyFobPresenceCycle.Snapshot paused = cycle.snapshot();
        assertEquals("paused_locked", paused.status);
        assertEquals("unknown", paused.state);
        assertFalse(paused.pending);
        assertEquals(2, paused.generation);
        assertEquals(0, paused.observationId);
        assertEquals(-1, paused.observedMs);
        assertFalse(cycle.observe(1, old, 3, 1_200));
        assertFalse(cycle.expire(7_000));
    }

    @Test public void repeatedLockedTicksNeitherRestartQueriesNorAdvanceGeneration() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        assertTrue(cycle.pauseLocked());
        long generation = cycle.snapshot().generation;
        for (long now : new long[]{1_000, 11_000, 61_000, 601_000}) {
            assertFalse(cycle.pauseLocked());
            assertFalse(cycle.due(now));
            assertEquals(0, cycle.begin(now, 1));
            assertEquals(generation, cycle.snapshot().generation);
            assertEquals("paused_locked", cycle.snapshot().status);
        }
    }

    @Test public void resumeDoesNotRetainAbsenceOrShortenTenSecondCallSpacing() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long first = cycle.begin(1_000, 1);
        assertTrue(cycle.requestStarted(1, first, 4_000));
        assertFalse(cycle.acceptRequest(1, first, 4_010));
        assertTrue(cycle.observe(1, first, 3, 4_100));
        assertEquals("absent", cycle.snapshot().state);
        assertTrue(cycle.pauseLocked());
        cycle.resume();
        assertEquals("unknown", cycle.snapshot().state);
        assertEquals("idle", cycle.snapshot().status);
        assertEquals(0, cycle.snapshot().observationId);
        assertFalse(cycle.due(13_999));
        assertEquals(0, cycle.begin(13_999, 1));
        long resumed = cycle.begin(14_000, 3);
        assertTrue(resumed > first);
        assertFalse(cycle.observe(1, resumed, 1, 14_100));
        assertFalse(cycle.acceptRequest(2, resumed, 14_110));
        assertTrue(cycle.observe(2, resumed, 1, 14_120));
        assertEquals("present", cycle.snapshot().state);
        assertEquals(1, cycle.snapshot().observationId);
    }

    @Test public void revocationDuringLockedPauseRetiresItsGenerationAndPauseState() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        cycle.pauseLocked();
        cycle.reset();
        assertEquals(3, cycle.snapshot().generation);
        assertEquals("idle", cycle.snapshot().status);
        assertFalse(cycle.snapshot().pausedLocked);
        assertEquals("unknown", cycle.snapshot().state);
    }

    @Test public void unavailableCachePermitsAQueryButNeverMakesFirstNonzeroCallbackFresh() {
        for (int unavailable : new int[]{-1, -2147482648, Integer.MIN_VALUE, 4, Integer.MAX_VALUE}) {
            for (int first : new int[]{1, 2, 3}) {
                KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
                long id = cycle.begin(1_000, unavailable);
                assertTrue(id > 0);
                assertTrue(cycle.requestStarted(1, id, 1_100));
                assertFalse(cycle.acceptRequest(1, id, 1_110));
                assertFalse(cycle.observe(1, id, first, 1_200));
                assertEquals("unknown", cycle.snapshot().state);
                assertEquals(0, cycle.snapshot().observationId);
                assertTrue(cycle.expire(7_100));
                assertEquals("unknown", cycle.snapshot().state);
            }
        }
    }

    @Test public void unavailableCacheNeedsPostRequestProgressForEachKnownResult() {
        for (int result : new int[]{1, 2, 3}) {
            KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
            long id = cycle.begin(1_000, -1);
            assertTrue(cycle.requestStarted(1, id, 1_100));
            assertFalse(cycle.acceptRequest(1, id, 1_110));
            assertFalse(cycle.observe(1, id, 0, 1_200));
            assertEquals(0, cycle.snapshot().observationId);
            assertTrue(cycle.observe(1, id, result, 1_300));
            assertEquals(result == 1 ? "present" : "absent", cycle.snapshot().state);
            assertEquals(1_100, cycle.snapshot().startedMs);
            assertEquals(1_300, cycle.snapshot().observedMs);
        }
    }

    @Test public void preparationProgressCannotArmAnUnavailableCacheQuery() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long id = cycle.begin(1_000, -1);
        assertFalse(cycle.observe(1, id, 0, 1_050));
        assertTrue(cycle.requestStarted(1, id, 1_100));
        assertFalse(cycle.acceptRequest(1, id, 1_110));
        assertFalse(cycle.observe(1, id, 3, 1_200));
        assertEquals("unknown", cycle.snapshot().state);
    }

    @Test public void unavailableCacheSynchronousResultNeedsSuccessfulSetterAcknowledgement() {
        for (boolean accepted : new boolean[]{false, true}) {
            KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
            long id = cycle.begin(1_000, -1);
            assertTrue(cycle.requestStarted(1, id, 1_100));
            assertFalse(cycle.observe(1, id, 0, 1_200));
            assertFalse(cycle.observe(1, id, 3, 1_300));
            assertEquals("unknown", cycle.snapshot().state);
            assertTrue(accepted ? cycle.acceptRequest(1, id, 1_400)
                    : cycle.fail(1, id, 1_400));
            assertEquals(accepted ? "absent" : "unknown", cycle.snapshot().state);
        }
    }

    @Test public void retiredOrOutOfWindowProgressCannotArmNewQuery() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long old = cycle.begin(1_000, -1);
        assertFalse(cycle.acceptRequest(1, old, 1_010));
        assertFalse(cycle.observe(1, old, 0, 1_100));
        assertTrue(cycle.expire(7_000));
        long next = cycle.begin(11_000, -1);
        assertFalse(cycle.acceptRequest(1, next, 11_010));
        assertFalse(cycle.observe(1, old, 0, 11_100));
        assertFalse(cycle.observe(1, next, 3, 11_200));
        assertTrue(cycle.observe(1, next, 0, 17_000));
        assertEquals("unknown", cycle.snapshot().state);
        assertFalse(cycle.observe(1, next, 3, 17_001));
    }

    @Test public void synchronousAbsentThenPresentPublishesLatestPresentAtOriginalCallbackTime() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long id = cycle.begin(1_000, -1);
        assertTrue(cycle.requestStarted(1, id, 1_100));
        assertFalse(cycle.observe(1, id, 0, 1_200));
        assertFalse(cycle.observe(1, id, 3, 1_300));
        assertFalse(cycle.observe(1, id, 1, 1_400));
        assertEquals("unknown", cycle.snapshot().state);
        assertTrue(cycle.acceptRequest(1, id, 1_500));
        assertEquals("present", cycle.snapshot().state);
        assertEquals(1_400, cycle.snapshot().observedMs);
        assertEquals(1_100, cycle.snapshot().startedMs);
        assertEquals(1, cycle.snapshot().observationId);
    }

    @Test public void synchronousPresentThenAbsentPublishesLatestAbsentAtOriginalCallbackTime() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long id = cycle.begin(1_000, -1);
        assertFalse(cycle.observe(1, id, 0, 1_100));
        assertFalse(cycle.observe(1, id, 1, 1_200));
        assertFalse(cycle.observe(1, id, 3, 1_300));
        assertTrue(cycle.acceptRequest(1, id, 1_500));
        assertEquals("absent", cycle.snapshot().state);
        assertEquals(1_300, cycle.snapshot().observedMs);
        assertEquals(1, cycle.snapshot().observationId);
    }

    @Test public void renewedProgressRetiresPendingAbsenceAndWaitsForNewKnownResult() {
        for (boolean resultArrives : new boolean[]{false, true}) {
            KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
            long id = cycle.begin(1_000, -1);
            assertFalse(cycle.observe(1, id, 0, 1_100));
            assertFalse(cycle.observe(1, id, 3, 1_200));
            assertFalse(cycle.observe(1, id, 0, 1_300));
            assertFalse(cycle.acceptRequest(1, id, 1_400));
            assertEquals("unknown", cycle.snapshot().state);
            assertTrue(cycle.snapshot().pending);
            assertEquals(0, cycle.snapshot().observationId);
            if (resultArrives) {
                assertTrue(cycle.observe(1, id, 1, 1_500));
                assertEquals("present", cycle.snapshot().state);
                assertEquals(1_500, cycle.snapshot().observedMs);
            } else {
                assertTrue(cycle.expire(7_000));
                assertEquals("unknown", cycle.snapshot().state);
                assertEquals(7_000, cycle.snapshot().observedMs);
            }
        }
    }

    @Test public void rejectedSetterRetiresLatestSynchronousCandidateAsUnknown() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long id = cycle.begin(1_000, -1);
        assertFalse(cycle.observe(1, id, 0, 1_100));
        assertFalse(cycle.observe(1, id, 3, 1_200));
        assertFalse(cycle.observe(1, id, 1, 1_300));
        assertTrue(cycle.fail(1, id, 1_400));
        assertEquals("unknown", cycle.snapshot().state);
        assertFalse(cycle.acceptRequest(1, id, 1_500));
        assertFalse(cycle.observe(1, id, 3, 1_600));
    }

    @Test public void latestSynchronousCandidateCannotEscapeDeadlineOrRetiredGeneration() {
        for (boolean reset : new boolean[]{false, true}) {
            KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
            long id = cycle.begin(1_000, -1);
            assertFalse(cycle.observe(1, id, 0, 1_100));
            assertFalse(cycle.observe(1, id, 3, 1_200));
            assertFalse(cycle.observe(1, id, 1, 1_300));
            if (reset) {
                cycle.reset();
                assertFalse(cycle.acceptRequest(1, id, 1_400));
                assertEquals(0, cycle.snapshot().observationId);
            } else {
                assertTrue(cycle.acceptRequest(1, id, 7_000));
                assertEquals(7_000, cycle.snapshot().observedMs);
            }
            assertEquals("unknown", cycle.snapshot().state);
        }
    }

    @Test public void modeResetRetiresEvidenceAndPreservesNativeTenSecondSpacing() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long old = cycle.begin(1_000, -1);
        assertTrue(cycle.requestStarted(1, old, 4_000));
        assertFalse(cycle.acceptRequest(1, old, 4_001));
        assertFalse(cycle.observe(1, old, 0, 4_100));
        assertTrue(cycle.observe(1, old, 1, 4_200));
        cycle.resetForModeChange();
        assertEquals(2, cycle.snapshot().generation);
        assertEquals("unknown", cycle.snapshot().state);
        assertEquals(0, cycle.snapshot().observationId);
        assertFalse(cycle.observe(1, old, 3, 4_300));
        assertFalse(cycle.due(13_999));
        assertEquals(0, cycle.begin(13_999, 1));
        assertTrue(cycle.begin(14_000, -1) > old);
    }

    @Test public void configuredIntervalSpacesStartsAndWakeUps() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle(5_000);
        assertEquals(5_000, cycle.intervalMs());
        long first = cycle.begin(1_000, 1);
        assertTrue(cycle.fail(1, first, 1_010));
        assertFalse(cycle.due(5_999));
        assertEquals(0, cycle.begin(5_999, 1));
        assertTrue(cycle.due(6_000));
        assertEquals(4_000, cycle.wakeInMs(2_000));
        long second = cycle.begin(6_000, 3);
        assertTrue(second > first);
        // Preparation time does not shorten the configured spacing either.
        assertTrue(cycle.requestStarted(1, second, 9_000));
        assertFalse(cycle.acceptRequest(1, second, 9_010));
        assertTrue(cycle.observe(1, second, 1, 9_100));
        assertFalse(cycle.due(13_999));
        assertTrue(cycle.due(14_000));
        // The no-arg cycle keeps the ten-second default.
        assertEquals(10_000, new KeyFobPresenceCycle().intervalMs());
        assertEquals(10_000, KeyFobPresenceCycle.DEFAULT_INTERVAL_MS);
    }

    @Test public void nonPositiveIntervalIsRejected() {
        for (long interval : new long[] {0, -1, -10_000}) {
            try { new KeyFobPresenceCycle(interval); fail("Accepted interval " + interval); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void modeResetDiscardsSynchronousCandidateAndNeverReusesGeneration() {
        KeyFobPresenceCycle cycle = new KeyFobPresenceCycle();
        long old = cycle.begin(1_000, -1);
        cycle.observe(1, old, 0, 1_100);
        cycle.observe(1, old, 1, 1_200);
        cycle.resetForModeChange();
        cycle.resetForModeChange();
        assertEquals(3, cycle.snapshot().generation);
        assertFalse(cycle.acceptRequest(1, old, 1_300));
        assertFalse(cycle.due(10_999));
        long current = cycle.begin(11_000, -1);
        assertFalse(cycle.acceptRequest(3, current, 11_010));
        assertFalse(cycle.observe(1, old, 0, 11_100));
        assertFalse(cycle.observe(3, current, 3, 11_200));
        assertEquals("unknown", cycle.snapshot().state);
    }
}
