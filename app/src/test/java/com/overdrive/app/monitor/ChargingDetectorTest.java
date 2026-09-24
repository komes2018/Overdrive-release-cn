package com.overdrive.app.monitor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.byd.BydVehicleData;
import java.lang.reflect.Method;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ChargingDetectorTest {

    private DaemonLogger.Config previousLogConfig;

    @Before
    public void disableAndroidAndFileLogging() {
        previousLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
    }

    @After
    public void restoreLogging() {
        com.overdrive.app.byd.ChargeSourceClassifier.onSessionEnded();
        DaemonLogger.configure(previousLogConfig);
    }

    private static ChargingDetector newDetector() {
        return new ChargingDetector(30L, 30L, 40L);
    }

    private static void awaitStopped(ChargingDetector detector) throws Exception {
        long deadline = System.currentTimeMillis() + 1_000L;
        while (detector.isCharging() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
        assertFalse(detector.isCharging());
    }

    @Test
    public void onlyAnObservedParkGearPassesTheParkGuard() {
        assertTrue(ChargingDetector.isObservedParkGear(
                GearMonitor.GEAR_P, GearMonitor.GEAR_P));
        assertFalse(ChargingDetector.isObservedParkGear(
                BydVehicleData.UNAVAILABLE, GearMonitor.GEAR_P));
        assertFalse(ChargingDetector.isObservedParkGear(
                GearMonitor.GEAR_D, GearMonitor.GEAR_P));
    }

    @Test
    public void freshExternalVerdictSurvivesLocalRecomputeThenExpires() throws Exception {
        ChargingDetector detector = newDetector();

        detector.acceptExternalVerdict(true, "vehicle-telemetry", 40L);
        detector.updateAccState(false);
        assertTrue(detector.isCharging());

        awaitStopped(detector);
    }

    @Test
    public void refreshedExternalVerdictRenewsItsLease() throws Exception {
        ChargingDetector detector = newDetector();

        detector.acceptExternalVerdict(true, "vehicle-telemetry", 50L);
        Thread.sleep(30L);
        detector.acceptExternalVerdict(true, "vehicle-telemetry", 50L);
        Thread.sleep(30L);
        detector.updateAccState(true);
        assertTrue(detector.isCharging());

        awaitStopped(detector);
    }

    @Test
    public void clearingExternalVerdictReleasesTheLease() throws Exception {
        ChargingDetector detector = newDetector();

        detector.acceptExternalVerdict(true, "vehicle-telemetry", 5_000L);
        detector.clearExternalVerdict();

        awaitStopped(detector);
    }

    @Test
    public void explicitTerminalBmsStateStopsImmediately() throws Exception {
        ChargingDetector detector = newDetector();

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        assertTrue(detector.isCharging());

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        assertFalse(detector.isCharging());
    }

    @Test
    public void ambiguousBmsLossStillUsesOffDebounce() throws Exception {
        ChargingDetector detector = newDetector();

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        assertTrue(detector.isCharging());

        detector.updateBmsState(com.overdrive.app.byd.BydVehicleData.UNAVAILABLE);
        assertTrue(detector.isCharging());
    }

    @Test
    public void idleAfterTerminalCannotReusePreviousSessionEvidence() throws Exception {
        ChargingDetector detector = newDetector();

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updatePowerIsCharging(Boolean.TRUE);
        assertTrue(detector.isCharging());

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        assertFalse(detector.isCharging());

        // A delayed positive from the completed session arrives before FINISHED becomes IDLE.
        detector.updatePowerIsCharging(Boolean.TRUE);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
        assertFalse(detector.isCharging());

        // L2 carries no session id, so even a false -> true pair cannot release the completed
        // session barrier by itself. A reconnect or independently moving pack flow is required.
        detector.updatePowerIsCharging(Boolean.FALSE);
        detector.updatePowerIsCharging(Boolean.TRUE);
        assertFalse(detector.isCharging());
    }

    @Test
    public void everyExplicitTerminalStateBlocksDelayedEvidence() {
        int[] terminalStates = {
                ChargingStateData.CHARGING_BATTERY_STATE_READY,
                ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH,
                ChargingStateData.CHARGING_BATTERY_STATE_CHARG_TERMINATE,
                ChargingStateData.CHARGING_BATTERY_STATE_DISCHARG_FINISH
        };
        for (int terminal : terminalStates) {
            ChargingDetector detector = newDetector();
            detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
            detector.updateBmsState(terminal);
            detector.updatePowerIsCharging(Boolean.TRUE);
            detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
            assertFalse("terminal=" + terminal, detector.isCharging());
        }
    }

    @Test
    public void breakdownAndTimeoutStatesStopEvenWhenL2RemainsTrue() {
        int[] failedStates = {
                ChargingStateData.CHARGING_BATTERY_STATE_BREAKDOWN_C10,
                ChargingStateData.CHARGING_BATTERY_STATE_BREAKDOWN_CHARGING_GUN,
                ChargingStateData.CHARGING_BATTERY_STATE_BREAKDOWN_AC,
                ChargingStateData.CHARGING_BATTERY_STATE_BREAKDOWN_CHARGER,
                ChargingStateData.CHARGING_BATTERY_STATE_TIMEOUT
        };
        for (int failed : failedStates) {
            ChargingDetector detector = newDetector();
            detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
            detector.updatePowerIsCharging(Boolean.TRUE);
            detector.confirmBmsState(failed);
            assertFalse("failed=" + failed, detector.isCharging());
        }
    }

    @Test
    public void readyDuringL2StartupDoesNotFenceFollowingChargingCallback() {
        ChargingDetector detector = newDetector();
        detector.updatePowerIsCharging(Boolean.TRUE);
        assertTrue(detector.isCharging());

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_READY);
        assertFalse(detector.isCharging());
        assertEquals(0L, detector.getTerminalBarrierSinceMs());

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        assertTrue(detector.isCharging());
    }

    @Test
    public void readyFencesCachedL2FromUnavailableBmsSessionBeforeIdle() {
        ChargingDetector detector = newDetector();

        detector.updatePowerIsCharging(Boolean.TRUE);
        assertTrue(detector.isCharging());

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_READY);
        assertFalse(detector.isCharging());
        assertEquals(0L, detector.getTerminalBarrierSinceMs());

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
        assertFalse(detector.isCharging());
    }

    @Test
    public void delayedBmsChargingCannotReopenCompletedSessionByItself() {
        ChargingDetector detector = newDetector();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);

        assertFalse(detector.isCharging());
        assertTrue(detector.getTerminalBarrierSinceMs() > 0);
    }

    @Test
    public void atomicPositivePollStartsScheduledRestartWithoutL3() {
        ChargingDetector detector = newDetector();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);

        // Individually delivered callbacks have no shared poll identity and remain fenced.
        detector.updateConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updatePowerIsCharging(Boolean.TRUE);
        assertFalse(detector.isCharging());

        // Cached positive levels alone cannot distinguish a restart from the completed session.
        // A current negative phase arms a later same-cable positive poll.
        BydVehicleData inactive = new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .build();
        detector.updatePollObservation(inactive, 4, 4,
                true, false, true,
                ChargingStateData.CHARGING_BATTERY_STATE_IDLE,
                true, Boolean.FALSE);

        BydVehicleData scheduledRestart = new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingGunState(2)
                .build();
        detector.updatePollObservation(scheduledRestart, 4, 4,
                true, false, true,
                ChargingStateData.CHARGING_BATTERY_STATE_CHARGING,
                true, Boolean.TRUE);

        assertTrue(detector.isCharging());
        assertEquals(0L, detector.getTerminalBarrierSinceMs());
    }

    @Test
    public void ignoredL2TrueConsumesRestartArm() {
        ChargingDetector detector = newDetector();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);

        detector.updatePowerIsCharging(Boolean.FALSE);
        detector.updatePowerIsCharging(Boolean.TRUE); // ignored while BMS is explicitly terminal
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
        detector.updatePowerIsCharging(Boolean.TRUE); // repeated TRUE is not a new false->true edge

        assertFalse(detector.isCharging());
    }

    @Test
    public void disconnectLatchRejectsLateCallbacksUntilReconnect() throws Exception {
        ChargingDetector detector = newDetector();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
        detector.updatePowerIsCharging(Boolean.TRUE);
        assertTrue(detector.isCharging());

        detector.updateConnectionState(1, false);
        assertFalse(detector.isCharging());
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updatePowerIsCharging(Boolean.TRUE);
        Thread.sleep(80L);
        detector.updateAccState(true);
        assertFalse(detector.isCharging());

        detector.updateConnectionState(2, false); // callback is only a candidate
        detector.confirmConnectionState(2, false);
        assertFalse(detector.isCharging());
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        assertTrue(detector.isCharging());
    }

    @Test
    public void oneStalePositivePollCannotClearAuthoritativeDisconnect() {
        ChargingDetector detector = newDetector();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateConnectionState(1, false);

        detector.confirmConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);

        assertFalse(detector.isCharging());
    }

    @Test
    public void repeatedStalePositivePollsCannotNominateReconnectFromDisconnectOrV2l() {
        for (int authoritativeGun : new int[] {1, 5}) {
            ChargingDetector detector = newDetector();
            detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
            detector.updateConnectionState(authoritativeGun, authoritativeGun == 5);

            BydVehicleData cached = new BydVehicleData.Builder()
                    .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                    .chargingGunState(2)
                    .build();
            for (int i = 0; i < 3; i++) {
                detector.updatePollObservation(cached, 4, 4,
                        true, true, true,
                        ChargingStateData.CHARGING_BATTERY_STATE_CHARGING,
                        true, Boolean.TRUE);
            }

            assertFalse("authoritativeGun=" + authoritativeGun, detector.isCharging());
            assertTrue("authoritativeGun=" + authoritativeGun,
                    detector.isTerminalSessionBarrierActive());
        }
    }

    @Test
    public void confirmedReconnectClearsPreviousFinishedState() {
        ChargingDetector detector = newDetector();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        detector.updateConnectionState(1, false);

        detector.updateConnectionState(2, false);
        detector.confirmConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);

        assertTrue(detector.isCharging());
    }

    @Test
    public void delayedTerminalCallbackAfterReconnectNeedsCurrentPollConfirmation() {
        ChargingDetector detector = newDetector();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateConnectionState(1, false);
        detector.updateConnectionState(2, false);
        detector.confirmConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        assertTrue(detector.isCharging());

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        ChargingDetector.StateSnapshot pending = detector.getStateSnapshot();
        assertTrue(pending.pendingTerminalStop);
        assertFalse(ChargingDetector.isPublicationWindowStable(pending, pending));
        BydVehicleData callbackSnapshot = new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH)
                .chargingGunState(2)
                .build();
        detector.updatePollEvidence(callbackSnapshot, 4, 4);
        assertTrue(detector.isCharging());

        detector.confirmBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        assertFalse(detector.isCharging());
    }

    @Test
    public void twoCohesivePositivePollsOverrideDelayedTerminalCallback() {
        ChargingDetector detector = reconnectIntoActiveSession();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        assertTrue(detector.getStateSnapshot().pendingTerminalStop);

        publishCohesivePositivePoll(detector);
        assertTrue(detector.getStateSnapshot().pendingTerminalStop);
        publishCohesivePositivePoll(detector);

        assertTrue(detector.isCharging());
        assertFalse(detector.isTerminalSessionBarrierActive());
    }

    @Test
    public void twoCohesivePositivePollsRecoverAfterDelayedCallbackBarrierCommits()
            throws Exception {
        ChargingDetector detector = reconnectIntoActiveSession();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        awaitStopped(detector);
        assertTrue(detector.getStateSnapshot().terminalBarrier);

        publishCohesivePositivePoll(detector);
        assertFalse(detector.isCharging());
        publishCohesivePositivePoll(detector);

        assertTrue(detector.isCharging());
        assertFalse(detector.isTerminalSessionBarrierActive());
    }

    @Test
    public void stalePositivePollCannotClearDisconnectLatch() {
        ChargingDetector detector = newDetector();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateConnectionState(1, false);

        BydVehicleData stale = new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingGunState(2)
                .enginePowerKw(-3.0)
                .build();
        detector.updatePollEvidence(stale, 4, 4);

        assertFalse(detector.isCharging());
    }

    @Test
    public void duplicateConnectedBroadcastCannotClearTerminalBarrier() {
        ChargingDetector detector = newDetector();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);

        detector.onPowerConnected();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);

        assertFalse(detector.isCharging());
    }

    @Test
    public void freshPostTerminalPackFlowCanStartScheduledRestartWithoutSampledL2False()
            throws Exception {
        ChargingDetector detector = newDetector();
        detector.updateConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingGunState(2)
                .enginePowerKw(-2.5)
                .build(), 4, 4);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
        Thread.sleep(2L);
        detector.observeRawChargingSignal(
                com.overdrive.app.byd.ChargeSourceClassifier.SRC_CLUSTER, 2.7);
        detector.observeRawChargingSignal(
                com.overdrive.app.byd.ChargeSourceClassifier.SRC_CLUSTER, 3.0);

        BydVehicleData restart = new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingGunState(2)
                .enginePowerKw(-3.0)
                .build();
        detector.updatePollEvidence(restart, 4, 4);

        assertTrue(detector.isCharging());
    }

    @Test
    public void heldEngineLevelCannotReopenCompletedSessionWithoutMovingFlow() {
        ChargingDetector detector = newDetector();
        detector.updateConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingGunState(2)
                .enginePowerKw(-3.0)
                .build(), 4, 4);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);

        BydVehicleData stale = new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingGunState(2)
                .enginePowerKw(-3.0)
                .build();
        detector.updatePollEvidence(stale, 4, 4);

        assertFalse(detector.isCharging());
        assertTrue(detector.getTerminalBarrierSinceMs() > 0);
    }

    @Test
    public void pollStartBoundaryIncludesSamplesThatCausedTheTransition() {
        ChargingDetector detector = newDetector();
        BydVehicleData sample = new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingGunState(2)
                .clusterChargePowerKw(2.7)
                .enginePowerKw(-3.0)
                .build();

        detector.updatePollEvidence(sample, 4, 4);

        assertTrue(detector.isCharging());
        assertTrue(detector.getLastSessionStartedAtMs() <= sample.clusterChargePowerAtMs);
        assertTrue(detector.getLastSessionStartedAtMs() <= sample.enginePowerAtMs);
    }

    @Test
    public void pollSamplesRemainInSessionWhenBmsConfirmationArrivesFirst() throws Exception {
        ChargingDetector detector = newDetector();
        BydVehicleData sample = new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingGunState(2)
                .clusterChargePowerKw(2.7)
                .enginePowerKw(-3.0)
                .build();

        Thread.sleep(2L);
        detector.confirmBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updatePollEvidence(sample, 4, 4);

        assertTrue(detector.getLastSessionStartedAtMs() <= sample.clusterChargePowerAtMs);
        assertTrue(detector.getLastSessionStartedAtMs() <= sample.enginePowerAtMs);
    }

    @Test
    public void v2lIsAuthoritativeOff() {
        ChargingDetector detector = newDetector();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        assertTrue(detector.isCharging());

        detector.updateConnectionState(5, true);
        detector.updatePowerIsCharging(Boolean.TRUE);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        assertFalse(detector.isCharging());
    }

    @Test
    public void v2lFalseWithoutPositiveGunKeepsAuthoritativeOffFence() {
        ChargingDetector detector = newDetector();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateConnectionState(5, true);

        detector.confirmV2lState(false);
        detector.confirmV2lState(false);
        detector.updatePowerIsCharging(Boolean.TRUE);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);

        assertFalse(detector.isCharging());
        assertTrue(detector.isTerminalSessionBarrierActive());
    }

    @Test
    public void staleSourceTimestampRetainsItsAgeInElapsedTime() {
        long wallNowMs = 1_000_000L;
        long elapsedNowMs = 100_000L;
        long sourceWallMs = wallNowMs - 20_000L;

        long sourceElapsedMs = ChargingDetector.sourceWallTimeToElapsed(
                sourceWallMs, wallNowMs, elapsedNowMs);

        assertEquals(elapsedNowMs - 20_000L, sourceElapsedMs);
        assertTrue(elapsedNowMs - sourceElapsedMs > 15_000L);
    }

    @Test
    public void staleL2TrueExpiresWithoutAnotherInput() throws Exception {
        ChargingDetector detector = new ChargingDetector(20L, 20L, 25L);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
        detector.updatePowerIsCharging(Boolean.TRUE);
        assertTrue(detector.isCharging());
        awaitStopped(detector);
    }

    @Test
    public void offDebounceCommitsAtDeadlineWithoutAnotherInput() throws Exception {
        ChargingDetector detector = new ChargingDetector(20L, 25L, 500L);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
        detector.updatePowerIsCharging(Boolean.TRUE);
        detector.updatePowerIsCharging(Boolean.FALSE);
        assertTrue(detector.isCharging());
        awaitStopped(detector);
    }

    @Test
    public void corroboratedL3PreventsL2FalseFromOverridingBmsCharging() throws Exception {
        ChargingDetector detector = new ChargingDetector(30L, 20L, 500L);
        detector.updateConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingGunState(2)
                .build(), 4, 4);
        detector.updatePowerIsCharging(Boolean.FALSE);

        detector.observeRawChargingSignal("l3-bms-corroboration", 2.0);
        detector.observeRawChargingSignal("l3-bms-corroboration", 2.1);
        detector.observeRawChargingSignal("l3-bms-corroboration", 2.2);
        detector.observeRawChargingSignal("l3-bms-corroboration", 2.3);
        Thread.sleep(90L);

        assertTrue(detector.isCharging());
        assertEquals("l3-corroborates-l1", detector.lastSource());
    }

    @Test
    public void corroboratedL3OverridesFalseL2WhenBmsIsAmbiguous() {
        ChargingDetector detector = new ChargingDetector(30L, 20L, 500L);
        detector.updateConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
        detector.updatePowerIsCharging(Boolean.FALSE);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .socPercent(50)
                .build(), 4, 4);

        detector.observeRawChargingSignal("l3-idle-l2-false", 2.0);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .socPercent(51)
                .build(), 4, 4);
        detector.observeRawChargingSignal("l3-idle-l2-false", 2.2);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .socPercent(52)
                .build(), 4, 4);
        detector.observeRawChargingSignal("l3-idle-l2-false", 2.4);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .socPercent(53)
                .build(), 4, 4);
        detector.observeRawChargingSignal("l3-idle-l2-false", 2.6);

        assertTrue(detector.lastSource(), detector.isCharging());
        assertEquals("l3-overrides-ambiguous-l2", detector.lastSource());
    }

    @Test
    public void uncorroboratedBmsChargingYieldsToSustainedL2False() throws Exception {
        ChargingDetector detector = new ChargingDetector(30L, 20L, 500L);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updatePowerIsCharging(Boolean.FALSE);

        awaitStopped(detector);
    }

    @Test
    public void listenerCallbacksStayInStateTransitionOrder() throws Exception {
        ChargingDetector detector = newDetector();
        CountDownLatch onEntered = new CountDownLatch(1);
        CountDownLatch releaseOn = new CountDownLatch(1);
        List<Boolean> completed = new CopyOnWriteArrayList<>();
        detector.addFusedStateListener((charging, source) -> {
            if (charging) {
                onEntered.countDown();
                try {
                    releaseOn.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            completed.add(charging);
        });

        Thread start = new Thread(() -> detector.updateBmsState(
                ChargingStateData.CHARGING_BATTERY_STATE_CHARGING));
        start.start();
        assertTrue(onEntered.await(1, TimeUnit.SECONDS));

        Thread stop = new Thread(() -> detector.updateBmsState(
                ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH));
        stop.start();
        Thread.sleep(20L);
        releaseOn.countDown();
        start.join(1_000L);
        stop.join(1_000L);

        assertEquals(Arrays.asList(Boolean.TRUE, Boolean.FALSE), completed);
        assertFalse(detector.isCharging());
    }

    @Test
    public void listenerErrorDoesNotWedgeFutureTransitions() {
        ChargingDetector detector = newDetector();
        List<Boolean> completed = new CopyOnWriteArrayList<>();
        detector.addFusedStateListener((charging, source) -> {
            throw new AssertionError("listener failure");
        });
        detector.addFusedStateListener((charging, source) -> completed.add(charging));

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);

        assertEquals(Arrays.asList(Boolean.TRUE, Boolean.FALSE), completed);
    }

    @Test
    public void coldStartTerminalStateDoesNotBlockLaterScheduledCharge() {
        ChargingDetector detector = newDetector();

        detector.confirmBmsState(ChargingStateData.CHARGING_BATTERY_STATE_READY);
        assertFalse(detector.isCharging());

        detector.confirmBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        assertTrue(detector.isCharging());
    }

    @Test
    public void staleL2NegativeIsClearedBeforeReconnectEpoch() throws Exception {
        ChargingDetector detector = new ChargingDetector(20L, 20L, 500L);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updatePowerIsCharging(Boolean.FALSE);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        detector.updateConnectionState(1, false);

        detector.updateConnectionState(2, false);
        detector.confirmConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        Thread.sleep(80L);

        assertTrue(detector.isCharging());
    }

    @Test
    public void genuineTerminalCallbackAfterReconnectStopsWithoutPoll() throws Exception {
        ChargingDetector detector = new ChargingDetector(20L, 20L, 500L);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateConnectionState(1, false);
        detector.updateConnectionState(2, false);
        detector.confirmConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        assertTrue(detector.isCharging());

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        assertTrue(detector.isCharging());
        awaitStopped(detector);
    }

    @Test
    public void delayedUnversionedChargingCannotCancelPendingTerminalCallback() throws Exception {
        ChargingDetector detector = new ChargingDetector(20L, 20L, 500L);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateConnectionState(1, false);
        detector.updateConnectionState(2, false);
        detector.confirmConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        assertTrue(detector.isCharging());

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);

        awaitStopped(detector);
    }

    @Test
    public void readyEndsEstablishedL2OnlySessionWithoutResurrection() {
        ChargingDetector detector = newDetector();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
        detector.updatePowerIsCharging(Boolean.TRUE);
        assertTrue(detector.isCharging());

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_READY);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);

        assertFalse(detector.isCharging());
        assertTrue(detector.isTerminalSessionBarrierActive());
    }

    @Test
    public void accOffDoesNotImmediatelyDropFreshEngineOnlyPhevCharge() throws Exception {
        ChargingDetector detector = new ChargingDetector(20L, 20L, 500L);
        detector.updateConnectionState(2, false);
        for (double engineKw : new double[] {-2.0, -2.2, -2.4}) {
            detector.updatePollEvidence(new BydVehicleData.Builder()
                    .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                    .chargingGunState(2)
                    .enginePowerKw(engineKw)
                    .build(), 4, 4);
        }
        assertTrue(detector.isCharging());

        detector.updateAccState(false);
        Thread.sleep(50L);

        assertTrue(detector.isCharging());
    }

    @Test
    public void reconnectCannotInheritPreviousSessionBmsTimestamp() {
        ChargingDetector detector = newDetector();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateConnectionState(1, false);
        detector.updateConnectionState(2, false);
        long reconnectFloor = System.currentTimeMillis();

        long staleBmsAt = reconnectFloor - 60_000L;
        BydVehicleData reconnect = new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingStateAtMs(staleBmsAt)
                .chargingGunState(2)
                .build();
        detector.updatePollObservation(reconnect, 4, 4,
                true, false, true,
                ChargingStateData.CHARGING_BATTERY_STATE_CHARGING,
                true, Boolean.TRUE);

        assertTrue(detector.isCharging());
        assertTrue(detector.getLastSessionStartedAtMs() >= reconnectFloor);
    }

    @Test
    public void terminalCallbackAfterReconnectCannotReusePreTerminalL3Credit() throws Exception {
        ChargingDetector detector = new ChargingDetector(20L, 20L, 500L);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateConnectionState(1, false);
        detector.updateConnectionState(2, false);
        detector.confirmConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        assertTrue(detector.isCharging());

        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingGunState(2)
                .build(), 4, 4);
        detector.observeRawChargingSignal("terminal-rate", 1.0);
        detector.observeRawChargingSignal("terminal-rate", 1.1);
        detector.observeRawChargingSignal("terminal-rate", 1.2);
        detector.observeRawChargingSignal("terminal-rate", 1.3);

        // This is deliberately an unconfirmed callback in a post-reconnect epoch. It must debounce,
        // but old raw/L3 activity cannot keep cancelling that debounce forever.
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        assertTrue(detector.isCharging());
        awaitStopped(detector);
    }

    @Test
    public void rawMovementCannotCancelPostReconnectTerminalCallback() throws Exception {
        ChargingDetector detector = new ChargingDetector(20L, 20L, 500L);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateConnectionState(1, false);
        detector.updateConnectionState(2, false);
        detector.confirmConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        assertTrue(detector.isCharging());

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        assertTrue(detector.isTerminalSessionBarrierActive());
        detector.observeRawChargingSignal("post-terminal-rate", 1.0);
        detector.observeRawChargingSignal("post-terminal-rate", 1.2);
        detector.observeRawChargingSignal("post-terminal-rate", 1.4);
        detector.observeRawChargingSignal("post-terminal-rate", 1.6);

        awaitStopped(detector);
        assertTrue(detector.getTerminalBarrierSinceMs() > 0L);
    }

    @Test
    public void laggingPositivePollCannotEraseGenuinePendingFinished()
            throws Exception {
        ChargingDetector detector = new ChargingDetector(20L, 20L, 500L);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateConnectionState(1, false);
        detector.updateConnectionState(2, false);
        detector.confirmConnectionState(2, false);

        BydVehicleData charging = new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingGunState(2)
                .build();
        detector.updatePollObservation(charging, 4, 4,
                true, false, true,
                ChargingStateData.CHARGING_BATTERY_STATE_CHARGING,
                false, null);
        assertTrue(detector.isCharging());

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        assertTrue(detector.isCharging());

        // Both getters may lag on the last positive level. Neither level is a lifecycle edge, so
        // this poll must not erase the newer FINISHED candidate.
        detector.updatePollObservation(charging, 4, 4,
                true, false, true,
                ChargingStateData.CHARGING_BATTERY_STATE_CHARGING,
                true, Boolean.TRUE);
        awaitStopped(detector);
        assertTrue(detector.getTerminalBarrierSinceMs() > 0L);

        // Repeated cached positives remain fenced after OFF; they cannot immediately manufacture a
        // scheduled restart from the same stale levels.
        for (int i = 0; i < 3; i++) {
            detector.updatePollObservation(charging, 4, 4,
                    true, false, true,
                    ChargingStateData.CHARGING_BATTERY_STATE_CHARGING,
                    true, Boolean.TRUE);
        }
        assertFalse(detector.isCharging());

        BydVehicleData inactive = new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .build();
        detector.updatePollObservation(inactive, 4, 4,
                true, false, true,
                ChargingStateData.CHARGING_BATTERY_STATE_IDLE,
                true, Boolean.FALSE);
        detector.updatePollObservation(charging, 4, 4,
                true, false, true,
                ChargingStateData.CHARGING_BATTERY_STATE_CHARGING,
                true, Boolean.TRUE);
        assertTrue(detector.isCharging());
    }

    @Test
    public void idlePollCannotErasePendingTerminalWithoutPositiveEvidence() throws Exception {
        ChargingDetector detector = new ChargingDetector(20L, 20L, 500L);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateConnectionState(1, false);
        detector.updateConnectionState(2, false);
        detector.confirmConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        assertTrue(detector.isCharging());

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        BydVehicleData idle = new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .build();
        detector.updatePollObservation(idle, 4, 4,
                true, false, true, ChargingStateData.CHARGING_BATTERY_STATE_IDLE,
                false, null);

        awaitStopped(detector);
        assertTrue(detector.getTerminalBarrierSinceMs() > 0);
        detector.updatePowerIsCharging(Boolean.TRUE);
        assertFalse(detector.isCharging());
    }

    @Test
    public void dischargingBmsStatesAreAuthoritativeOff() {
        int[] discharging = {
                ChargingStateData.CHARGING_BATTERY_STATE_DISCHARG,
                ChargingStateData.CHARGING_BATTERY_STATE_DISCHARG_CBU
        };
        for (int state : discharging) {
            ChargingDetector detector = newDetector();
            List<String> stops = new CopyOnWriteArrayList<>();
            detector.addAuthoritativeStopListener(stops::add);
            detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);

            detector.updateBmsState(state);

            assertFalse("state=" + state, detector.isCharging());
            assertEquals(Arrays.asList("v2l-export"), stops);
        }
    }

    @Test
    public void postReconnectDischargingCallbackNotifiesOnceAfterDebouncedOff()
            throws Exception {
        int[] discharging = {
                ChargingStateData.CHARGING_BATTERY_STATE_DISCHARG,
                ChargingStateData.CHARGING_BATTERY_STATE_DISCHARG_CBU
        };
        for (int state : discharging) {
            ChargingDetector detector = new ChargingDetector(20L, 20L, 500L);
            detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
            detector.updateConnectionState(1, false);
            detector.updateConnectionState(2, false);
            detector.confirmConnectionState(2, false);
            detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
            assertTrue("state=" + state, detector.isCharging());

            List<String> events = new CopyOnWriteArrayList<>();
            CountDownLatch stopped = new CountDownLatch(1);
            detector.addFusedStateListener((charging, source) -> {
                if (!charging) events.add("off:" + source);
            });
            detector.addAuthoritativeStopListener(source -> {
                events.add("stop:" + source);
                stopped.countDown();
            });

            detector.updateBmsState(state);
            assertTrue("state=" + state, detector.isCharging());
            assertTrue("state=" + state, stopped.await(1, TimeUnit.SECONDS));
            assertFalse("state=" + state, detector.isCharging());
            detector.updateAccState(true);
            Thread.sleep(50L);

            assertEquals("state=" + state, Arrays.asList(
                    "off:v2l-export-callback", "stop:v2l-export-callback"), events);
        }
    }

    @Test
    public void rawCallbackMovementCanLatchL3WithoutWaitingForAnotherPoll() {
        ChargingDetector detector = newDetector();
        detector.updateConnectionState(2, false);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .build(), 4, 4);

        detector.observeRawChargingSignal("test-rate", 1.0);
        detector.observeRawChargingSignal("test-rate", 1.1);
        detector.observeRawChargingSignal("test-rate", 1.2);
        detector.observeRawChargingSignal("test-rate", 1.3);

        assertTrue(detector.isCharging());
    }

    @Test
    public void tinyRawJitterWhilePluggedAndIdleOnlyWakesCollectorAndDoesNotLatchL3() {
        ChargingDetector detector = newDetector();
        detector.updateConnectionState(2, false);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .build(), 4, 4);

        detector.observeRawChargingSignal("jitter-rate", 10.000);
        detector.observeRawChargingSignal("jitter-rate", 10.001);
        detector.observeRawChargingSignal("jitter-rate", 9.999);
        detector.observeRawChargingSignal("jitter-rate", 10.002);
        detector.observeRawChargingSignal("jitter-rate", 10.000);

        assertTrue(detector.hasRecentRawChargingSignal());
        assertFalse(detector.isCharging());
    }

    @Test
    public void materialRetainedRateJitterCannotOverrideNegativeL2AfterStop() {
        ChargingDetector detector = new ChargingDetector(30L, 20L, 500L);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
        detector.updatePowerIsCharging(Boolean.FALSE);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(BydVehicleData.UNAVAILABLE)
                .socPercent(50)
                .build(), 4, 4);

        detector.observeRawChargingSignal("retained-rate-jitter", 3.00);
        detector.observeRawChargingSignal("retained-rate-jitter", 3.06);
        detector.observeRawChargingSignal("retained-rate-jitter", 2.99);
        detector.observeRawChargingSignal("retained-rate-jitter", 3.06);

        assertTrue(detector.hasRecentRawChargingSignal());
        assertFalse(detector.isCharging());
    }

    @Test
    public void steadyUnknownRateBootstrapsWithoutGunFromDistinctSocRises() throws Exception {
        String source = "steady-gun-unavailable-" + System.nanoTime();
        ChargingDetector detector = new ChargingDetector(20L, 20L, 500L, 1L, 5_000L);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
        detector.updatePowerIsCharging(Boolean.FALSE);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(BydVehicleData.UNAVAILABLE)
                .socPercent(50)
                .build(), 4, 4);

        detector.observeRawChargingSignal(source, 3.0);
        detector.observeRawChargingSignal(source, 3.0);
        backdateSteadyRun(detector, source);

        for (int soc = 51; soc <= 53; soc++) {
            detector.updatePollEvidence(new BydVehicleData.Builder()
                    .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                    .chargingGunState(BydVehicleData.UNAVAILABLE)
                    .socPercent(soc)
                    .build(), 4, 4);
            detector.observeRawChargingSignal(source, 3.0);
            if (soc < 53) assertFalse(detector.isCharging());
        }

        assertTrue(detector.lastSource(), detector.isCharging());
        assertEquals("l3-overrides-ambiguous-l2", detector.lastSource());
    }

    @Test
    public void oldSocRiseCanCorroborateTinyRawMovementOnlyOnce() {
        ChargingDetector detector = newDetector();
        detector.updateConnectionState(2, false);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .socPercent(50)
                .build(), 4, 4);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .socPercent(51)
                .build(), 4, 4);

        detector.observeRawChargingSignal("soc-jitter-rate", 10.000);
        detector.observeRawChargingSignal("soc-jitter-rate", 10.001);
        detector.observeRawChargingSignal("soc-jitter-rate", 9.999);
        detector.observeRawChargingSignal("soc-jitter-rate", 10.002);
        detector.observeRawChargingSignal("soc-jitter-rate", 10.000);

        assertFalse(detector.isCharging());
    }

    @Test
    public void frozenCounterCannotUseOldSocRiseAsSteadyRate() throws Exception {
        ChargingDetector detector = new ChargingDetector(20L, 25L, 500L, 2L);
        detector.updateConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingGunState(2)
                .socPercent(50)
                .build(), 4, 4);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingGunState(2)
                .socPercent(51)
                .build(), 4, 4);
        detector.observeRawChargingSignal(
                com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY, 4.2);

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
        Thread.sleep(3L);
        for (int i = 0; i < 5; i++) {
            detector.observeRawChargingSignal(
                    com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY, 4.2);
            Thread.sleep(2L);
        }

        awaitStopped(detector);
    }

    @Test
    public void frozenRateCannotReuseSocRiseFromBeforeSteadyRun() throws Exception {
        String source = "detector-steady-old-soc-rate";
        double[] classificationSamples = {1.0, 1.2, 1.1, 1.3, 1.2, 1.4, 1.3, 1.5, 1.4};
        for (double sample : classificationSamples) {
            com.overdrive.app.byd.ChargeSourceClassifier.observeWhileCharging(source, sample);
        }
        assertEquals(com.overdrive.app.byd.ChargeSourceClassifier.Kind.RATE,
                com.overdrive.app.byd.ChargeSourceClassifier.kindOf(source));

        ChargingDetector detector = new ChargingDetector(20L, 25L, 500L, 2L);
        detector.updateConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingGunState(2)
                .socPercent(50)
                .build(), 4, 4);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingGunState(2)
                .socPercent(51)
                .build(), 4, 4);
        detector.observeRawChargingSignal(source, 3.2);

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
        Thread.sleep(3L);
        for (int i = 0; i < 5; i++) {
            detector.observeRawChargingSignal(source, 3.2);
            Thread.sleep(2L);
        }

        awaitStopped(detector);
    }

    @Test
    public void riseThenPlateauUsesOnlyDistinctPostPlateauSocRisesForL3() throws Exception {
        String source = prepareRampThenPlateauSource(
                "detector-ramp-plateau-rate-", 2.7, 3.2);
        ChargingDetector detector = new ChargingDetector(20L, 20L, 500L, 1L, 5_000L);
        detector.updateConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
        detector.updatePowerIsCharging(Boolean.FALSE);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .socPercent(50)
                .build(), 4, 4);

        detector.observeRawChargingSignal(source, 2.7);
        detector.observeRawChargingSignal(source, 3.2); // Negative L2 keeps this as weak wake-up data.
        assertFalse(detector.isCharging());
        detector.observeRawChargingSignal(source, 3.2); // Starts detector plateau; no corroboration.
        backdateSteadyRun(detector, source);
        assertFalse(detector.isCharging());

        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .socPercent(51)
                .build(), 4, 4);
        detector.observeRawChargingSignal(source, 3.2); // Fresh SoC rise: L3 credit 1.
        assertEquals(com.overdrive.app.byd.ChargeSourceClassifier.Kind.RATE,
                com.overdrive.app.byd.ChargeSourceClassifier.kindOf(source));
        assertFalse(detector.isCharging());

        // Reusing the same SoC rise cannot manufacture another hysteresis observation.
        for (int i = 0; i < 4; i++) {
            detector.observeRawChargingSignal(source, 3.2);
        }
        assertFalse(detector.isCharging());

        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .socPercent(52)
                .build(), 4, 4);
        detector.observeRawChargingSignal(source, 3.2); // Distinct SoC rise: L3 credit 2.
        assertFalse(detector.isCharging());

        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .socPercent(53)
                .build(), 4, 4);
        detector.observeRawChargingSignal(source, 3.2); // Distinct SoC rise: L3 credit 3.

        assertTrue(detector.lastSource(), detector.isCharging());
        assertEquals("l3-overrides-ambiguous-l2", detector.lastSource());
    }

    @Test
    public void finishedBarrierRejectsCorroboratedFrozenCounterPlateau() throws Exception {
        String source = prepareRampThenPlateauSource(
                "detector-finished-frozen-counter-", 9.0, 10.0);
        ChargingDetector detector = new ChargingDetector(20L, 20L, 500L, 1L, 5_000L);
        detector.updateConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingGunState(2)
                .socPercent(50)
                .build(), 4, 4);
        detector.observeRawChargingSignal(source, 9.0);
        detector.observeRawChargingSignal(source, 10.0);
        assertTrue(detector.isCharging());

        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
        assertFalse(detector.isCharging());
        assertTrue(detector.isTerminalSessionBarrierActive());

        // Even a post-FINISHED gauge rise cannot turn the retained counter total into a live rate.
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .socPercent(50)
                .build(), 4, 4);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .socPercent(51)
                .build(), 4, 4);
        detector.observeRawChargingSignal(source, 10.0);
        backdateSteadyRun(detector, source);
        for (int i = 0; i < 4; i++) {
            detector.observeRawChargingSignal(source, 10.0);
        }

        assertEquals(com.overdrive.app.byd.ChargeSourceClassifier.Kind.UNKNOWN,
                com.overdrive.app.byd.ChargeSourceClassifier.kindOf(source));
        assertFalse(detector.isCharging());
        assertTrue(detector.isTerminalSessionBarrierActive());
    }

    @Test
    public void repeatedFrozenEngineLevelDoesNotLatchL3() {
        ChargingDetector detector = newDetector();
        detector.updateConnectionState(2, false);

        for (int i = 0; i < 4; i++) {
            detector.updatePollEvidence(new BydVehicleData.Builder()
                    .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                    .chargingGunState(2)
                    .enginePowerKw(-3.0)
                    .build(), 4, 4);
        }

        assertFalse(detector.isCharging());
    }

    @Test
    public void l3TurnsOffWhenRawMovementFreshnessExpires() throws Exception {
        ChargingDetector detector = new ChargingDetector(10L, 10L, 500L, 5L, 25L);
        detector.updateConnectionState(2, false);
        detector.updatePollEvidence(new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
                .chargingGunState(2)
                .build(), 4, 4);
        detector.observeRawChargingSignal("expiring-rate", 1.0);
        detector.observeRawChargingSignal("expiring-rate", 1.2);
        detector.observeRawChargingSignal("expiring-rate", 1.4);
        detector.observeRawChargingSignal("expiring-rate", 1.6);
        assertTrue(detector.isCharging());

        awaitStopped(detector);
    }

    @Test
    public void authoritativeStopStaysOrderedBetweenOffAndReconnectOn() throws Exception {
        ChargingDetector detector = newDetector();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        CountDownLatch offEntered = new CountDownLatch(1);
        CountDownLatch releaseOff = new CountDownLatch(1);
        List<String> completed = new CopyOnWriteArrayList<>();
        detector.addFusedStateListener((charging, source) -> {
            completed.add(charging ? "on" : "off");
            if (!charging) {
                offEntered.countDown();
                try {
                    releaseOff.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        detector.addAuthoritativeStopListener(source -> completed.add("stop"));

        Thread unplug = new Thread(detector::onPowerDisconnected);
        unplug.start();
        assertTrue(offEntered.await(1, TimeUnit.SECONDS));

        detector.onPowerConnected();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        releaseOff.countDown();
        unplug.join(1_000L);

        assertEquals(Arrays.asList("off", "stop", "on"), completed);
        assertTrue(detector.isCharging());
    }

    @Test
    public void publicationSnapshotRejectsAReadSpanningAnUnplug() {
        ChargingDetector detector = newDetector();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        ChargingDetector.StateSnapshot before = detector.getStateSnapshot();
        assertTrue(before.charging);
        assertTrue(ChargingDetector.isPublicationWindowStable(before, before));

        detector.onPowerDisconnected();
        ChargingDetector.StateSnapshot after = detector.getStateSnapshot();

        assertFalse(after.charging);
        assertTrue(after.physicalStop);
        assertFalse(ChargingDetector.isPublicationWindowStable(before, after));
    }

    @Test
    public void physicalStopInvalidatesSnapshotEvenWhenVerdictWasAlreadyOff() {
        ChargingDetector detector = newDetector();
        ChargingDetector.StateSnapshot before = detector.getStateSnapshot();
        assertFalse(before.charging);

        detector.onPowerDisconnected();
        ChargingDetector.StateSnapshot after = detector.getStateSnapshot();

        assertTrue(after.generation > before.generation);
        assertTrue(after.physicalStop);
        assertFalse(ChargingDetector.isPublicationWindowStable(before, after));
        assertFalse(ChargingDetector.isPublicationWindowStable(after, after));
    }

    @Test
    public void componentPublicationAcceptsCoherentStoppedState() {
        ChargingDetector detector = newDetector();
        detector.onPowerDisconnected();
        ChargingDetector.StateSnapshot stopped = detector.getStateSnapshot();

        assertTrue(ChargingDetector.isComponentPublicationWindowStable(stopped, stopped));
        assertFalse(ChargingDetector.isPublicationWindowStable(stopped, stopped));
    }

    @Test
    public void externalPublicationMutationInvalidatesSpanningReadButNotLaterStableRead() {
        ChargingDetector detector = newDetector();
        ChargingDetector.StateSnapshot before = detector.getStateSnapshot();
        ChargingDetector.StateSnapshot during;
        try (ChargingDetector.PublicationMutation ignored =
                     ChargingDetector.beginPublicationMutation()) {
            during = detector.getStateSnapshot();
            assertTrue(during.externalWriters > 0);
            assertFalse(ChargingDetector.isPublicationWindowStable(during, during));
        }
        ChargingDetector.StateSnapshot after = detector.getStateSnapshot();

        assertFalse(ChargingDetector.isPublicationWindowStable(before, after));
        assertTrue(ChargingDetector.isPublicationWindowStable(after, after));
    }

    @Test
    public void stablePublicationCommitBlocksAWriterUntilDerivedStateIsPublished()
            throws Exception {
        ChargingDetector detector = newDetector();
        ChargingDetector.StateSnapshot before = detector.getStateSnapshot();
        CountDownLatch commitEntered = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        CountDownLatch writerAttempted = new CountDownLatch(1);
        CountDownLatch writerFinished = new CountDownLatch(1);
        AtomicBoolean committed = new AtomicBoolean();

        Thread commit = new Thread(() -> committed.set(
                detector.commitIfComponentPublicationWindowStable(before, () -> {
                    commitEntered.countDown();
                    try {
                        return releaseCommit.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                })));
        commit.start();
        assertTrue(commitEntered.await(2, TimeUnit.SECONDS));

        Thread writer = new Thread(() -> {
            writerAttempted.countDown();
            try (ChargingDetector.PublicationMutation ignored =
                         ChargingDetector.beginPublicationMutation()) {
                // Reaching this block means beginPublicationMutation acquired the fence.
            } finally {
                writerFinished.countDown();
            }
        });
        writer.start();
        assertTrue(writerAttempted.await(2, TimeUnit.SECONDS));
        assertFalse(writerFinished.await(100, TimeUnit.MILLISECONDS));

        releaseCommit.countDown();
        commit.join(2_000L);
        writer.join(2_000L);
        assertFalse(commit.isAlive());
        assertFalse(writer.isAlive());
        assertTrue(committed.get());
        assertTrue(writerFinished.getCount() == 0);
    }

    @Test
    public void stalePublicationCommitDoesNotRunDerivedSideEffects() {
        ChargingDetector detector = newDetector();
        ChargingDetector.StateSnapshot before = detector.getStateSnapshot();
        detector.onPowerDisconnected();
        AtomicBoolean callbackRan = new AtomicBoolean();

        assertFalse(detector.commitIfComponentPublicationWindowStable(before, () -> {
            callbackRan.set(true);
            return true;
        }));
        assertFalse(callbackRan.get());
    }

    @Test
    public void benignRecomputeDoesNotInvalidatePublicationGeneration() {
        ChargingDetector detector = newDetector();
        ChargingDetector.StateSnapshot before = detector.getStateSnapshot();

        detector.updateAccState(true);
        ChargingDetector.StateSnapshot after = detector.getStateSnapshot();

        assertEquals(before.generation, after.generation);
        assertTrue(ChargingDetector.isPublicationWindowStable(before, after));
    }

    private static String prepareRampThenPlateauSource(
            String prefix, double initialValue, double plateauValue) throws Exception {
        String source = prefix + System.nanoTime();
        Method observeAt = com.overdrive.app.byd.ChargeSourceClassifier.class.getDeclaredMethod(
                "observeWhileCharging", String.class, double.class, long.class);
        observeAt.setAccessible(true);
        long start = System.currentTimeMillis() - 11 * 60_000L;
        observeAt.invoke(null, source, initialValue, start);
        observeAt.invoke(null, source, plateauValue, start + 1_000L);
        for (int i = 0; i < 6; i++) {
            observeAt.invoke(null, source, plateauValue,
                    start + 2_000L + i * 120_000L);
        }
        assertTrue(com.overdrive.app.byd.ChargeSourceClassifier.isSteadyRateCandidate(
                source, plateauValue, System.currentTimeMillis()));
        assertEquals(com.overdrive.app.byd.ChargeSourceClassifier.Kind.UNKNOWN,
                com.overdrive.app.byd.ChargeSourceClassifier.kindOf(source));
        return source;
    }

    private static ChargingDetector reconnectIntoActiveSession() {
        ChargingDetector detector = newDetector();
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        detector.updateConnectionState(1, false);
        detector.updateConnectionState(2, false);
        detector.confirmConnectionState(2, false);
        detector.updateBmsState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        assertTrue(detector.isCharging());
        return detector;
    }

    private static void publishCohesivePositivePoll(ChargingDetector detector) {
        BydVehicleData positive = new BydVehicleData.Builder()
                .chargingState(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                .chargingGunState(2)
                .build();
        detector.updatePollObservation(
                positive, 4, 4,
                true, true, true,
                ChargingStateData.CHARGING_BATTERY_STATE_CHARGING,
                true, Boolean.TRUE);
    }

    @SuppressWarnings("unchecked")
    private static void backdateSteadyRun(ChargingDetector detector, String source)
            throws Exception {
        java.lang.reflect.Field field =
                ChargingDetector.class.getDeclaredField("steadyRawSince");
        field.setAccessible(true);
        ((java.util.Map<String, Long>) field.get(detector)).put(source, 1L);
    }

}
