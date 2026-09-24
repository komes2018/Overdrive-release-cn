package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.ChargeSourceClassifier;
import com.overdrive.app.logging.DaemonLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

/** Regression coverage for rejecting the observed AC power-source faults. */
public class VehicleDataMonitorChargingPowerTest {

    private DaemonLogger.Config previousLogConfig;

    @Before
    public void disableAndroidAndFileLogging() {
        previousLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
        ChargeRateResolver.onSessionStarted();
    }

    @After
    public void restoreLogging() {
        ChargeRateResolver.onSessionEnded();
        DaemonLogger.configure(previousLogConfig);
    }

    @Test
    public void rejectsKnown359ClusterSentinelForPhevEvenWithoutGunState() {
        assertTrue(VehicleDataMonitor.isClusterChargePowerIdleGarbage(359.4, true, 2));
        assertTrue(VehicleDataMonitor.isClusterChargePowerIdleGarbage(
                359.4, true, com.overdrive.app.byd.BydVehicleData.UNAVAILABLE));
        assertTrue(VehicleDataMonitor.isClusterChargePowerIdleGarbage(359.0, true, 2));
        assertFalse(VehicleDataMonitor.isClusterChargePowerIdleGarbage(358.99, true, 2));
        assertFalse(VehicleDataMonitor.isClusterChargePowerIdleGarbage(360.0, true, 2));
        assertFalse(VehicleDataMonitor.isClusterChargePowerIdleGarbage(359.4, false, 3));
    }

    @Test
    public void direct359SignatureRequiresSameSessionBevDcCorroboration() {
        assertTrue(VehicleDataMonitor.isDirectChargePowerIdleGarbage(359.4, true, 3));
        assertTrue(VehicleDataMonitor.isDirectChargePowerIdleGarbage(359.4, false, 2));
        assertFalse(VehicleDataMonitor.isDirectChargePowerIdleGarbage(359.4, false, 3));
        assertFalse(VehicleDataMonitor.isDirectChargePowerIdleGarbage(359.4, false, 4));

        assertTrue(Double.isNaN(
                VehicleDataMonitor.resolveDirectChargePower(359.4, Double.NaN)));
        assertTrue(Double.isNaN(
                VehicleDataMonitor.resolveDirectChargePower(359.4, 3.0)));
        assertEquals(359.4,
                VehicleDataMonitor.resolveDirectChargePower(359.4, 359.4), 1e-9);
        // The proof may bridge a sparse poll, but not a physical session boundary.
        assertEquals(359.4,
                VehicleDataMonitor.resolveDirectChargePower(359.4, Double.NaN), 1e-9);
        ChargeRateResolver.onSessionEnded();
        ChargeRateResolver.onSessionStarted();
        assertTrue(Double.isNaN(
                VehicleDataMonitor.resolveDirectChargePower(359.4, Double.NaN)));
    }

    @Test
    public void explicitAcRejectsKnown359AfterSourceResolutionButDcKeepsIt() {
        assertTrue(Double.isNaN(
                VehicleDataMonitor.filterKnownImpossibleAcChargingPower(
                        359.4, 2)));
        assertEquals(359.4,
                VehicleDataMonitor.filterKnownImpossibleAcChargingPower(
                        359.4, 3), 0.0);
        assertEquals(359.4,
                VehicleDataMonitor.filterKnownImpossibleAcChargingPower(
                        359.4, 4), 0.0);
        assertEquals(359.4,
                VehicleDataMonitor.filterKnownImpossibleAcChargingPower(
                        359.4, com.overdrive.app.byd.BydVehicleData.UNAVAILABLE),
                0.0);
        assertEquals(22.0,
                VehicleDataMonitor.filterKnownImpossibleAcChargingPower(
                        22.0, 2), 0.0);
    }

    @Test
    public void matchingCapacitySlopeMakesGenuineBevDc359Measured() {
        long now = System.currentTimeMillis();
        long start = now - 20_000L;
        double capacityDelta = 359.4 * (20_000.0 / 3_600_000.0);

        ChargeRateResolver.observe(ChargeSourceClassifier.SRC_CAPACITY, 0.0, start);
        ChargeRateResolver.observe(
                ChargeSourceClassifier.SRC_CAPACITY, capacityDelta, now);
        double capacityKw = ChargeRateResolver.rateKw(
                ChargeSourceClassifier.SRC_CAPACITY, capacityDelta);
        double directScaleRef = ChargeRateResolver.preferredScaleReference(
                Double.NaN, capacityKw);

        assertFalse(VehicleDataMonitor.isDirectChargePowerIdleGarbage(
                359.4, false, 3));
        assertEquals(359.4,
                VehicleDataMonitor.resolveDirectChargePower(359.4, directScaleRef), 1e-9);
        assertTrue(ChargeRateResolver.isScaleVerified(
                "__packSideDirect", 359.4, directScaleRef));
        assertFalse(VehicleDataMonitor.isCandidateContradictedByReference(
                "__packSideDirect", 359.4, capacityKw));
    }

    @Test
    public void instrumentFailureSignaturesDoNotContaminateDedicatedDevicePower() {
        long now = System.currentTimeMillis();
        long sessionStartedAt = now - 1_000L;
        com.overdrive.app.byd.BydVehicleData junk =
                new com.overdrive.app.byd.BydVehicleData.Builder()
                        .chargingGunState(2)
                        .clusterChargePowerKw(359.4)
                        .clusterChargePowerAtMs(now)
                        .chargingPowerKw(359.4)
                        .chargingPowerAtMs(now)
                        .externalChargingPowerKw(1320.10)
                        .externalChargingPowerAtMs(now)
                        .chargePowerKw(359.4)
                        .chargePowerAtMs(now)
                        .enginePowerKw(-3.0)
                        .enginePowerAtMs(now)
                        .build();

        VehicleDataMonitor.observePhevSessionRateProofs(
                junk, sessionStartedAt, now);

        assertFalse(ChargeRateResolver.isSessionRateCorroborated(
                ChargeSourceClassifier.SRC_CLUSTER, 3.594));
        assertFalse(ChargeRateResolver.isSessionRateCorroborated(
                ChargeSourceClassifier.SRC_DEVICE, 3.594));
        assertFalse(ChargeRateResolver.isSessionRateCorroborated(
                ChargeSourceClassifier.SRC_EXTERNAL, 13.201));
        assertFalse(ChargeRateResolver.isSessionRateCorroborated(
                "__packSideDirect", 3.594));
        assertTrue(VehicleDataMonitor.isClusterChargePowerIdleGarbage(
                1320.10, true, 2));
        assertTrue(VehicleDataMonitor.isDirectChargePowerIdleGarbage(
                1320.10, true, 3));

        com.overdrive.app.byd.BydVehicleData real =
                junk.toBuilder()
                        .clusterChargePowerKw(Double.NaN)
                        .chargingPowerKw(300.0)
                        .chargingPowerAtMs(now)
                        .externalChargingPowerKw(Double.NaN)
                        .chargePowerKw(Double.NaN)
                        .enginePowerKw(-3.0)
                        .enginePowerAtMs(now)
                        .build();
        VehicleDataMonitor.observePhevSessionRateProofs(
                real, sessionStartedAt, now);

        assertEquals(300.0, ChargeRateResolver.resolveSessionRateValue(
                ChargeSourceClassifier.SRC_DEVICE, 300.0, Double.NaN), 1e-9);
        assertTrue(ChargeRateResolver.isScaleVerified(
                ChargeSourceClassifier.SRC_DEVICE, 300.0));
        assertFalse(VehicleDataMonitor.shouldRejectCandidateBeforeSelection(
                ChargeSourceClassifier.SRC_DEVICE, 300.0, 3.0));
    }

    @Test
    public void dedicatedChargingDeviceKeepsItsFrameworkKwContract() {
        assertEquals(7.2, ChargeRateResolver.rateKw(
                ChargeSourceClassifier.SRC_DEVICE, 7.2), 0.0);
        assertEquals(359.4, ChargeRateResolver.rateKw(
                ChargeSourceClassifier.SRC_DEVICE, 359.4), 0.0);
        assertTrue(Double.isNaN(ChargeRateResolver.rateKw(
                ChargeSourceClassifier.SRC_DEVICE, 500.1)));
        assertTrue(ChargeRateResolver.isScaleVerified(
                ChargeSourceClassifier.SRC_DEVICE, 359.4));
        assertFalse(VehicleDataMonitor.shouldRejectCandidateBeforeSelection(
                ChargeSourceClassifier.SRC_DEVICE, 359.4, Double.NaN));
        assertEquals(359.4, VehicleDataMonitor.selectTaperRate(
                7.2, 359.4, 6.8, 6.5), 0.0);
    }

    @Test
    public void scaleProvenRisingBevRateOutranksLaggingCounterForDisplay() {
        long now = System.currentTimeMillis();
        long start = now - 20_000L;
        double capacityDelta = 100.0 * (20_000.0 / 3_600_000.0);
        String direct = "__packSideDirect";

        ChargeRateResolver.observeCounterForScale(
                ChargeSourceClassifier.SRC_CAPACITY, 0.0, start);
        ChargeRateResolver.observeCounterForScale(
                ChargeSourceClassifier.SRC_CAPACITY, capacityDelta, now);
        assertEquals(100.0,
                VehicleDataMonitor.resolveDirectChargePower(100.0, 100.0), 1e-9);
        assertTrue(ChargeRateResolver.hasProvenUnitScale(direct));

        // The source has ramped to 200 kW while the preceding counter interval still averages 100.
        assertEquals(200.0,
                VehicleDataMonitor.resolveDirectChargePower(200.0, 100.0), 1e-9);
        assertFalse(VehicleDataMonitor.shouldRejectCandidateBeforeSelection(
                direct, 200.0, Double.NaN));

        // A source with no independent unit proof remains fail-closed against the same mismatch.
        assertTrue(VehicleDataMonitor.shouldRejectCandidateBeforeSelection(
                "unproven-" + System.nanoTime(), 200.0, Double.NaN));
        assertTrue(ChargeRateResolver.isScaleVerified(direct, 200.0, 100.0));
        assertTrue(VehicleDataMonitor.isCandidateContradictedByReference(
                direct, 200.0, 100.0));
    }

    @Test
    public void externalCounterCannotUseItsOwnScaleReference() {
        ChargeRateResolver.observeCounterForScale(
                ChargeSourceClassifier.SRC_EXTERNAL, 100.0);
        assertTrue(Double.isNaN(ChargeRateResolver.counterScaleReferenceForSource(
                ChargeSourceClassifier.SRC_EXTERNAL, Double.NaN, 300.0)));
        assertEquals(3.0, ChargeRateResolver.counterScaleReferenceForSource(
                ChargeSourceClassifier.SRC_EXTERNAL, 3.0, 300.0), 0.0);
    }

    @Test
    public void heldCounterRateExpiresAfterParkedPollMargin() {
        long observedAt = 1_000L;
        assertTrue(ChargeRateResolver.isHeldRateFresh(
                observedAt, observedAt + ChargeRateResolver.MAX_HELD_RATE_MS));
        assertFalse(ChargeRateResolver.isHeldRateFresh(
                observedAt, observedAt + ChargeRateResolver.MAX_HELD_RATE_MS + 1L));
    }

    @Test
    public void suspectCounterStopsBeingTheScaleReferenceImmediately() {
        String source = "suspectScaleReference-" + System.nanoTime();
        long now = System.currentTimeMillis();
        long start = now - 20_000L;
        double delta = 6.0 * (20_000.0 / 3_600_000.0);
        ChargeRateResolver.observeCounterForScale(source, 0.0, start);
        ChargeRateResolver.observeCounterForScale(source, delta, now);
        assertEquals(6.0, ChargeRateResolver.referenceRateKw(), 1e-9);

        double counter = 10.0;
        double reference = 40.0;
        long calibrationStart = 1_000_000L;
        for (int minute = 0; minute <= 8; minute++) {
            com.overdrive.app.charging.CounterScaleCalibrator.observePaired(
                    source, counter, reference,
                    calibrationStart + minute * 60_000L);
            counter += 5.62 / 60.0;
            reference += 10.63 / 60.0;
        }

        assertTrue(com.overdrive.app.charging.CounterScaleCalibrator
                .isScaleSuspect(source));
        assertTrue(Double.isNaN(ChargeRateResolver.referenceRateKw()));
    }

    @Test
    public void oversizedCounterSlopeCalibratesWithoutEverPublishingRawValue() throws Exception {
        String source = "testHectowattCounter-" + System.nanoTime();
        classifyAsCounter(source);
        long now = System.currentTimeMillis();
        long start = now - 20_000L;
        double externalDelta = 6.5 * (20_000.0 / 3_600_000.0) * 100.0;
        double capacityDelta = 6.5 * (20_000.0 / 3_600_000.0);

        ChargeRateResolver.observe(source, 0.0, start);
        ChargeRateResolver.observeCounterForScale(
                ChargeSourceClassifier.SRC_CAPACITY, 0.0, start);
        ChargeRateResolver.observe(source, externalDelta, now);

        // The unresolved 650 kW slope is calibration evidence only.
        assertTrue(Double.isNaN(ChargeRateResolver.rateKw(source, externalDelta)));

        ChargeRateResolver.observeCounterForScale(
                ChargeSourceClassifier.SRC_CAPACITY, capacityDelta, now);
        assertEquals(100.0, ChargeRateResolver.counterUnitDivisor(source), 0.0);
        assertEquals(6.5, ChargeRateResolver.rateKw(source, externalDelta), 1e-9);
        assertTrue(ChargeRateResolver.isScaleVerified(source, 6.5));

        // Counter units are firmware-scoped. A later external-only session can reuse the proven
        // divisor, but the raw 650 kW slope still never escapes.
        ChargeRateResolver.onSessionEnded();
        ChargeRateResolver.onSessionStarted();
        ChargeRateResolver.observeCounterForScale(source, 0.0, start);
        ChargeRateResolver.observe(source, 0.0, start);
        ChargeRateResolver.observeCounterForScale(source, externalDelta, now);
        ChargeRateResolver.observe(source, externalDelta, now);
        assertEquals(100.0, ChargeRateResolver.counterUnitDivisor(source), 0.0);
        assertEquals(6.5, ChargeRateResolver.referenceRateKw(), 1e-9);
        assertEquals(6.5, ChargeRateResolver.rateKw(source, externalDelta), 1e-9);
    }

    @Test
    public void oversizedUnresolvedCounterCannotPermanentlyLatchUnitOne() throws Exception {
        String source = "testUnresolvedCounter-" + System.nanoTime();
        classifyAsCounter(source);
        long now = System.currentTimeMillis();
        long start = now - 20_000L;
        double rawDelta = 6.5 * (20_000.0 / 3_600_000.0) * 100.0;
        double wrongCapacityDelta = 100.0 * (20_000.0 / 3_600_000.0);

        ChargeRateResolver.observe(source, 0.0, start);
        ChargeRateResolver.observeCounterForScale(
                ChargeSourceClassifier.SRC_CAPACITY, 0.0, start);
        ChargeRateResolver.observe(source, rawDelta, now);
        ChargeRateResolver.observeCounterForScale(
                ChargeSourceClassifier.SRC_CAPACITY, wrongCapacityDelta, now);
        assertEquals(1.0, ChargeRateResolver.counterUnitDivisor(source), 0.0);
        assertTrue(Double.isNaN(ChargeRateResolver.rateKw(source, rawDelta)));

        ChargeRateResolver.onSessionEnded();
        ChargeRateResolver.onSessionStarted();
        ChargeRateResolver.observe(source, 0.0, start);
        ChargeRateResolver.observeCounterForScale(
                ChargeSourceClassifier.SRC_CAPACITY, 0.0, start);
        ChargeRateResolver.observe(source, rawDelta, now);
        ChargeRateResolver.observeCounterForScale(
                ChargeSourceClassifier.SRC_CAPACITY,
                6.5 * (20_000.0 / 3_600_000.0), now);
        assertEquals(100.0, ChargeRateResolver.counterUnitDivisor(source), 0.0);
        assertEquals(6.5, ChargeRateResolver.rateKw(source, rawDelta), 1e-9);
    }

    @Test
    public void detectsOnlyBevCounterNearTheKnownHalfScaleRatio() {
        assertTrue(VehicleDataMonitor.isLikelyHalfScaleCapacityRate(3.30, 6.49, false));
        assertFalse(VehicleDataMonitor.isLikelyHalfScaleCapacityRate(3.30, 6.49, true));
        assertFalse(VehicleDataMonitor.isLikelyHalfScaleCapacityRate(3.90, 6.49, false));
        assertFalse(VehicleDataMonitor.isLikelyHalfScaleCapacityRate(
                Double.NaN, 6.49, false));
    }

    @Test
    public void sessionScaleSuspicionKeepsIndependentBevFallbackSelected() {
        assertTrue(VehicleDataMonitor.shouldWithholdCapacityRate(
                Double.NaN, 5.59, false, true));
        assertFalse(VehicleDataMonitor.shouldWithholdCapacityRate(
                Double.NaN, 5.59, true, true));
        assertFalse(VehicleDataMonitor.shouldWithholdCapacityRate(
                3.22, 5.59, false, false));
        assertFalse(VehicleDataMonitor.isLikelyHalfScaleCapacityRate(
                Double.NaN, 191.5, false));
    }

    @Test
    public void verifiesPhevClusterRateOnlyWhenPackFlowCorroboratesIt() {
        // Captured PHEV session: dash 2.7-3.2 kW, pack flow 3.0 kW.
        assertTrue(VehicleDataMonitor.isPhevClusterRateCorroboratedByPackFlow(
                2.7, 10_000, 3.0, 10_010, true));
        assertTrue(VehicleDataMonitor.isPhevClusterRateCorroboratedByPackFlow(
                3.2, 10_000, 3.0, 10_010, true));

        assertFalse(VehicleDataMonitor.isPhevClusterRateCorroboratedByPackFlow(
                2.1, 10_000, 3.0, 10_010, true));
        assertFalse(VehicleDataMonitor.isPhevClusterRateCorroboratedByPackFlow(
                4.1, 10_000, 3.0, 10_010, true));
        assertFalse(VehicleDataMonitor.isPhevClusterRateCorroboratedByPackFlow(
                3.0, 10_000, 3.0, 10_010, false));
        assertFalse(VehicleDataMonitor.isPhevClusterRateCorroboratedByPackFlow(
                3.0, 10_000, 3.0, 30_000, true));
        assertFalse(VehicleDataMonitor.isPhevClusterRateCorroboratedByPackFlow(
                Double.NaN, 10_000, 3.0, 10_010, true));
    }

    @Test
    public void rejectsCorroborationFromBeforeCurrentSession() {
        assertFalse(VehicleDataMonitor.isPhevClusterRateCorroboratedByPackFlow(
                2.7, 10_000, 3.0, 10_010, true, 10_011));
        assertTrue(VehicleDataMonitor.isPhevClusterRateCorroboratedByPackFlow(
                2.7, 10_020, 3.0, 10_030, true, 10_011));
    }

    @Test
    public void detectsSymmetricLowScaleMismatchAgainstPackFlow() {
        assertTrue(VehicleDataMonitor.isPhevClusterPackFlowMismatch(
                0.32, 10_000, 3.2, 10_010, true, 9_000));
        assertFalse(VehicleDataMonitor.isPhevClusterPackFlowMismatch(
                3.0, 10_000, 3.2, 10_010, true, 9_000));
    }

    @Test
    public void rejectsContradictedCandidateBeforeItCanBlockMeasuredFallback() {
        assertTrue(VehicleDataMonitor.isCandidateContradictedByFreshPackFlow(
                0.32, 3.2));
        assertTrue(VehicleDataMonitor.isCandidateContradictedByFreshPackFlow(
                32.0, 3.2));
        assertFalse(VehicleDataMonitor.isCandidateContradictedByFreshPackFlow(
                3.0, 3.2));
    }

    @Test
    public void heldCounterCannotVetoAValidLowerTaperRate() {
        assertFalse(VehicleDataMonitor.isCandidateContradictedByReference(
                com.overdrive.app.byd.ChargeSourceClassifier.SRC_CLUSTER, 0.32, 3.2));
        assertTrue(VehicleDataMonitor.isCandidateContradictedByReference(
                com.overdrive.app.byd.ChargeSourceClassifier.SRC_CLUSTER, 32.0, 3.2));
        assertFalse(VehicleDataMonitor.isCandidateContradictedByReference(
                com.overdrive.app.byd.ChargeSourceClassifier.SRC_CLUSTER, 3.0, 3.2));
        assertFalse(VehicleDataMonitor.isCandidateContradictedByReference(
                com.overdrive.app.byd.ChargeSourceClassifier.SRC_CLUSTER, 3.0, 7.0));
        assertFalse(VehicleDataMonitor.isCandidateContradictedByReference(
                com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY, 0.32, 3.2));
    }

    @Test
    public void frozenPostFinishClusterNeedsIndependentLiveFlow() {
        long finishedAt = 10_000L;
        assertFalse(VehicleDataMonitor.isFreshPostFinishPackFlow(
                2.7, 10_010, Double.NaN, 0, finishedAt, 10_020, true));
        assertFalse(VehicleDataMonitor.isFreshPostFinishPackFlow(
                2.7, 9_990, 3.0, 10_000, finishedAt, 10_020, true));
        assertTrue(VehicleDataMonitor.isFreshPostFinishPackFlow(
                2.7, 10_010, 3.0, 10_015, finishedAt, 10_020, true));
        assertFalse(VehicleDataMonitor.isFreshPostFinishPackFlow(
                2.7, 10_010, 3.0, 10_015, finishedAt, 30_000, true));
        // Small stale-value movement is not independent evidence.
        assertFalse(VehicleDataMonitor.isFreshPostFinishPackFlow(
                2.6, 10_010, Double.NaN, 0, finishedAt, 10_020, true));
    }

    @Test
    public void packFlowReferenceCalibratesHectowattRateWithoutEnergyCounter() {
        assertEquals(6.5,
                ChargeRateResolver.resolveRateValueAgainstReference(650.0, 6.5), 1e-9);
        assertEquals(7.0,
                ChargeRateResolver.resolveRateValueAgainstReference(700.0, 7.0), 1e-9);
        assertTrue(Double.isNaN(
                ChargeRateResolver.resolveRateValueAgainstReference(650.0, Double.NaN)));
        assertTrue(Double.isNaN(
                ChargeRateResolver.resolveRateValueAgainstReference(650.0, 3.0)));
    }

    @Test
    public void freshPackFlowResolvesOnlyOutOfBandUnknownHectowattRate() {
        String source = "testUnknownHectowatt-" + System.nanoTime();
        assertEquals(ChargeSourceClassifier.Kind.UNKNOWN,
                ChargeSourceClassifier.kindOf(source));

        assertEquals(6.5, ChargeRateResolver.rateKw(source, 650.0, 6.5), 1e-9);
        assertEquals(7.0,
                ChargeRateResolver.resolveUnknownRateAgainstPackFlow(700.0, 7.0), 1e-9);
        assertEquals(6.5, ChargeRateResolver.rateKw(source, 650.0), 1e-9);
        assertTrue(Double.isNaN(
                ChargeRateResolver.resolveUnknownRateAgainstPackFlow(65.0, 0.65)));
        assertTrue(Double.isNaN(
                ChargeRateResolver.resolveUnknownRateAgainstPackFlow(650.0, 3.0)));
    }

    @Test
    public void corroboratedRateSurvivesSparsePollGapButNotSessionBoundary() {
        String source = "testSessionRateProof-" + System.nanoTime();
        classifyAsRate(source);

        assertEquals(6.5, ChargeRateResolver.rateKw(source, 650.0, 6.5), 1e-9);
        assertEquals(6.5, ChargeRateResolver.rateKw(source, 650.0), 1e-9);
        assertTrue(ChargeRateResolver.isSessionRateCorroborated(source, 6.5));

        ChargeRateResolver.onSessionEnded();
        ChargeRateResolver.onSessionStarted();

        assertTrue(Double.isNaN(ChargeRateResolver.rateKw(source, 650.0)));
        assertFalse(ChargeRateResolver.isScaleVerified(source, 6.5));
        assertFalse(ChargeRateResolver.isSessionRateCorroborated(source, 6.5));
    }

    @Test
    public void corroboratedRateProofExpiresAfterParkedPollGrace() {
        String source = "testExpiringSessionRateProof-" + System.nanoTime();
        classifyAsRate(source);
        assertEquals(6.5, ChargeRateResolver.rateKw(source, 650.0, 6.5), 1e-9);
        long future = System.currentTimeMillis()
                + ChargeRateResolver.SESSION_RATE_PROOF_MAX_AGE_MS + 1;
        assertFalse(ChargeRateResolver.isSessionRateCorroborated(source, 6.5, future));
    }

    @Test
    public void currentPackFlowOutranksExistingRateDivisorAndHeldCounterChoice() {
        String source = "testRateRelatch-" + System.nanoTime();
        classifyAsRate(source);

        // First observation establishes plain kW. A later raw-register observation must still be
        // re-evaluated against current pack flow instead of blindly applying the old divisor.
        assertEquals(6.5, ChargeRateResolver.rateKw(source, 6.5, 6.5), 1e-9);
        assertEquals(6.5, ChargeRateResolver.rateKw(source, 650.0, 6.5), 1e-9);
        assertEquals(6.5,
                ChargeRateResolver.preferredScaleReference(6.5, 3.0), 1e-9);
        assertEquals(3.0,
                ChargeRateResolver.preferredScaleReference(Double.NaN, 3.0), 1e-9);
    }

    @Test
    public void stalePowerObservationCannotUseCurrentSessionPackFlow() {
        long sessionStartedAt = 10_000L;
        assertFalse(VehicleDataMonitor.isCurrentSessionPowerObservation(
                9_999L, sessionStartedAt));
        assertFalse(VehicleDataMonitor.isCurrentSessionPowerObservation(
                10_001L, 0L));
        assertTrue(VehicleDataMonitor.isCurrentSessionPowerObservation(
                10_001L, sessionStartedAt));

        assertTrue(Double.isNaN(VehicleDataMonitor.packFlowReferenceForSource(
                650.0, 9_999L, 6.5, 10_002L, sessionStartedAt)));
        assertTrue(Double.isNaN(VehicleDataMonitor.packFlowReferenceForSource(
                650.0, 10_001L, 6.5, 30_000L, sessionStartedAt)));
        assertEquals(6.5, VehicleDataMonitor.packFlowReferenceForSource(
                650.0, 10_001L, 6.5, 10_002L, sessionStartedAt), 1e-9);
        assertEquals(6.0, VehicleDataMonitor.packFlowReferenceForSource(
                6.3, 10_001L, 6.0, 10_002L, sessionStartedAt), 1e-9);
        assertFalse(VehicleDataMonitor.shouldRejectCandidateBeforeSelection(
                "__packSideDirect", 6.3, 6.0));
    }

    @Test
    public void steadyPhevObservationBridgesParkedPollButGenuinelyStaleValueExpires() {
        long now = 1_000_000L;
        long sessionStartedAt = now - 10 * 60_000L;

        // Freshness follows successful observation time, not numeric movement.
        assertTrue(VehicleDataMonitor.isFreshPowerObservation(
                now - 90_000L, sessionStartedAt, now));
        assertTrue(VehicleDataMonitor.isFreshPowerObservation(
                now - VehicleDataMonitor.CHARGING_POWER_OBSERVATION_MAX_AGE_MS,
                sessionStartedAt, now));
        assertFalse(VehicleDataMonitor.isFreshPowerObservation(
                now - VehicleDataMonitor.CHARGING_POWER_OBSERVATION_MAX_AGE_MS - 1L,
                sessionStartedAt, now));
        assertFalse(VehicleDataMonitor.isFreshPowerObservation(
                sessionStartedAt - 1L, sessionStartedAt, now));
        assertFalse(VehicleDataMonitor.isFreshPowerObservation(
                now + 1L, sessionStartedAt, now));
    }

    @Test
    public void chargingStateCarriesAndClearsPowerProvenance() {
        ChargingStateData state = new ChargingStateData(
                ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        state.updateChargingPower(
                3.1, "cluster", 12345L,
                ChargingStateData.PowerQuality.MEASURED, 0.95);

        assertEquals(3.1, state.chargingPowerKW, 0.0);
        assertEquals("cluster", state.powerSource);
        assertEquals(12345L, state.powerObservedAtMs);
        assertEquals(ChargingStateData.PowerQuality.MEASURED,
                state.powerQuality);
        assertEquals(0.95, state.powerConfidence, 0.0);

        state.clearChargingPower();
        assertEquals(0.0, state.chargingPowerKW, 0.0);
        assertEquals("none", state.powerSource);
        assertEquals(0L, state.powerObservedAtMs);
        assertEquals(ChargingStateData.PowerQuality.UNKNOWN,
                state.powerQuality);
        assertEquals(0.0, state.powerConfidence, 0.0);
    }

    @Test
    public void chargingStateRejectsOutOfDomainPowerAndConfidence() {
        ChargingStateData state = new ChargingStateData(
                ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        state.updateChargingPower(
                Double.POSITIVE_INFINITY, "chargingDevice", 12345L,
                ChargingStateData.PowerQuality.MEASURED, 1.0);
        assertEquals(0.0, state.chargingPowerKW, 0.0);
        assertEquals("none", state.powerSource);
        assertEquals(ChargingStateData.PowerQuality.UNKNOWN,
                state.powerQuality);

        state.updateChargingPower(
                500.01, "chargingDevice", 12345L,
                ChargingStateData.PowerQuality.MEASURED, 1.0);
        assertEquals(0.0, state.chargingPowerKW, 0.0);
        assertEquals("none", state.powerSource);

        state.updateChargingPower(
                6.1, "chargingDevice", 12345L,
                ChargingStateData.PowerQuality.MEASURED, Double.NaN);
        assertEquals(6.1, state.chargingPowerKW, 0.0);
        assertEquals(0.0, state.powerConfidence, 0.0);
    }

    @Test
    public void engineFallbackExpiresWithItsSourceTimestamp() {
        long sessionStartedAt = 10_000L;
        assertEquals(3.2, VehicleDataMonitor.freshNegativeEnginePackFlow(
                -3.2, 10_010L, sessionStartedAt, 20_000L), 0.0);
        assertTrue(Double.isNaN(VehicleDataMonitor.freshNegativeEnginePackFlow(
                -3.2, 10_010L, sessionStartedAt, 30_000L)));
        assertTrue(Double.isNaN(VehicleDataMonitor.freshNegativeEnginePackFlow(
                -3.2, 9_999L, sessionStartedAt, 10_020L)));
    }

    @Test
    public void directRawRegisterNeedsPackFlowBeforeResolving() {
        assertEquals(6.5, VehicleDataMonitor.resolveDirectChargePower(650.0, 6.5), 1e-9);
        assertEquals(7.0, VehicleDataMonitor.resolveDirectChargePower(700.0, 7.0), 1e-9);
        assertEquals(6.5,
                VehicleDataMonitor.resolveDirectChargePower(650.0, Double.NaN), 1e-9);
        ChargeRateResolver.onSessionEnded();
        ChargeRateResolver.onSessionStarted();
        assertEquals(6.5,
                VehicleDataMonitor.resolveDirectChargePower(6.5, Double.NaN), 1e-9);
    }

    @Test
    public void inBandDirectRawScaleRequiresIndependentProof() {
        String direct = "__packSideDirect";

        assertEquals(3.2,
                VehicleDataMonitor.resolveDirectChargePower(320.0, 3.2), 1e-9);
        assertEquals(3.2,
                VehicleDataMonitor.resolveDirectChargePower(320.0, Double.NaN), 1e-9);
        assertTrue(ChargeRateResolver.isScaleVerified(direct, 3.2));
        assertTrue(ChargeRateResolver.isSessionRateCorroborated(direct, 3.2));

        ChargeRateResolver.onSessionEnded();
        ChargeRateResolver.onSessionStarted();

        assertEquals(320.0,
                VehicleDataMonitor.resolveDirectChargePower(320.0, Double.NaN), 1e-9);
        assertFalse(ChargeRateResolver.isScaleVerified(direct, 320.0));
        assertTrue(VehicleDataMonitor.shouldWithholdUnverifiedDirectRate(
                320.0, Double.NaN, true));
        assertFalse(VehicleDataMonitor.shouldWithholdUnverifiedDirectRate(
                320.0, Double.NaN, false));
    }

    @Test
    public void dedicatedChargingDeviceOutranksAmbiguousFallbackFields() {
        assertEquals(7.2, VehicleDataMonitor.selectTaperRate(
                6.2, 7.2, 6.8, 6.5), 0.0);
        assertEquals(6.2, VehicleDataMonitor.selectTaperRate(
                6.2, Double.NaN, 6.8, 6.5), 0.0);
    }

    @Test
    public void pollTimeCorroborationSurvivesUntilTheNextParkedPoll() {
        long now = System.currentTimeMillis();
        long sessionStartedAt = now - 1_000L;
        com.overdrive.app.byd.BydVehicleData sample =
                new com.overdrive.app.byd.BydVehicleData.Builder()
                        .chargingGunState(2)
                        .clusterChargePowerKw(320.0)
                        .clusterChargePowerAtMs(now)
                        .enginePowerKw(-3.2)
                        .enginePowerAtMs(now)
                        .build();

        VehicleDataMonitor.observePhevSessionRateProofs(
                sample, sessionStartedAt, now);

        assertEquals(3.2,
                ChargeRateResolver.resolveSessionRateValue(
                        ChargeSourceClassifier.SRC_CLUSTER, 320.0, Double.NaN),
                1e-9);
        assertTrue(ChargeRateResolver.isSessionRateCorroborated(
                ChargeSourceClassifier.SRC_CLUSTER, 3.2));
    }

    @Test
    public void taperScaleAdmissionRequiresPostFinishSourceAndIndependentFlow() {
        long finishedAt = 10_000L;
        assertTrue(VehicleDataMonitor.hasFreshPostFinishIndependentEvidence(
                10_010L, 6.5, 10_012L,
                finishedAt, 10_020L, true));
        assertFalse(VehicleDataMonitor.hasFreshPostFinishIndependentEvidence(
                9_999L, 6.5, 10_012L,
                finishedAt, 10_020L, true));
        assertFalse(VehicleDataMonitor.hasFreshPostFinishIndependentEvidence(
                10_010L, 6.5, 9_999L,
                finishedAt, 10_020L, true));
        assertFalse(VehicleDataMonitor.hasFreshPostFinishIndependentEvidence(
                10_010L, 6.5, 10_012L,
                finishedAt, 30_000L, true));
    }

    @Test
    public void taperRateMovementMustBePostFinishFreshAndNonFuture() {
        long finishedAt = 10_000L;
        assertTrue(VehicleDataMonitor.hasFreshPostFinishRateMovement(
                10_010L, finishedAt, 10_020L));
        assertFalse(VehicleDataMonitor.hasFreshPostFinishRateMovement(
                10_000L, finishedAt, 10_020L));
        assertFalse(VehicleDataMonitor.hasFreshPostFinishRateMovement(
                9_999L, finishedAt, 10_020L));
        assertFalse(VehicleDataMonitor.hasFreshPostFinishRateMovement(
                10_021L, finishedAt, 10_020L));
        assertFalse(VehicleDataMonitor.hasFreshPostFinishRateMovement(
                10_010L, finishedAt,
                10_010L + VehicleDataMonitor.CHARGING_POWER_OBSERVATION_MAX_AGE_MS + 1L));
    }

    @Test
    public void unverifiedPhevExternalRateDefersToGroundedEstimator() {
        assertTrue(VehicleDataMonitor.shouldDeferExternalToGroundedEstimate(
                7.13, Double.NaN, true, true));
        assertFalse(VehicleDataMonitor.shouldDeferExternalToGroundedEstimate(
                7.13, Double.NaN, true, false));
        assertFalse(VehicleDataMonitor.shouldDeferExternalToGroundedEstimate(
                7.13, Double.NaN, false, true));

        assertEquals(3.0, ChargeRateResolver.resolveSessionRateValue(
                ChargeSourceClassifier.SRC_EXTERNAL, 3.0, 3.0), 0.0);
        assertFalse(VehicleDataMonitor.shouldDeferExternalToGroundedEstimate(
                3.0, 3.0, true, true));
    }

    @Test
    public void directPackSideRateCanCarryPhevFinishedTaper() {
        assertEquals(2.5, VehicleDataMonitor.selectTaperRate(
                Double.NaN, Double.NaN, Double.NaN, 2.5), 0.0);
    }

    private static void classifyAsRate(String source) {
        double[] values = {1.0, 2.0, 3.0, 2.5, 3.5, 3.0, 4.0, 5.0, 6.0};
        for (double value : values) {
            ChargeSourceClassifier.observeWhileCharging(source, value);
        }
        assertEquals(ChargeSourceClassifier.Kind.RATE,
                ChargeSourceClassifier.kindOf(source));
    }

    private static void classifyAsCounter(String source) throws Exception {
        Method observeAt = ChargeSourceClassifier.class.getDeclaredMethod(
                "observeWhileCharging", String.class, double.class, long.class);
        observeAt.setAccessible(true);
        long start = System.currentTimeMillis() - 30 * 60_000L;
        for (int i = 0; i <= 10; i++) {
            observeAt.invoke(null, source, (double) i, start + i * 3 * 60_000L);
        }
        assertEquals(ChargeSourceClassifier.Kind.COUNTER,
                ChargeSourceClassifier.kindOf(source));
    }
}
