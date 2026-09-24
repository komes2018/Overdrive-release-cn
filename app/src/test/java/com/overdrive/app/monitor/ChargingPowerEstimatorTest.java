package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;

import org.junit.Test;

/** Regression coverage for counter-source selection in {@link ChargingPowerEstimator}. */
public class ChargingPowerEstimatorTest {

    private static final int GUN_DISCONNECTED = 1;
    private static final int GUN_AC = 2;
    private static final int GUN_DC = 3;

    @Test
    public void attoAcTraceLatchesSmoothCapacityCounter() throws Exception {
        ChargingPowerEstimator estimator = newEstimator();
        long t = System.currentTimeMillis() - 4 * 60_000L;

        // Field trace shape: BMS announces charging while the gun byte still says disconnected.
        estimator.sample(t, 0.011, 1.6, 8, Double.NaN,
                true, true, GUN_DISCONNECTED);
        assertTrue(Double.isNaN(estimator.estimatePowerKw()));

        // One minute later remain implies ~11 kW, while the cumulative session counter implies
        // ~1.5 kW. The explicit AC cross-check must choose the latter before either reaches CPS.
        estimator.sample(t + 65_000L, 0.038, 1.8, 8, Double.NaN,
                true, true, GUN_AC);
        assertEquals(1.495, estimator.estimatePowerKw(), 0.03);

        estimator.sample(t + 130_000L, 0.062, 2.0, 8, Double.NaN,
                true, true, GUN_AC);

        // The real failure: remain jumps by several kWh in one poll and implies >100 kW.
        // Once capacity is latched, that jump must neither replace nor EMA-poison the estimate.
        estimator.sample(t + 195_000L, 0.088, 6.6, 8, Double.NaN,
                true, true, GUN_AC);
        double power = estimator.estimatePowerKw();
        assertTrue("expected granny-charge power, got " + power, power > 1.0 && power < 2.0);
    }

    @Test
    public void confirmedAcWaitsForPresentCapacityCounterToMove() throws Exception {
        ChargingPowerEstimator estimator = newEstimator();
        long t = System.currentTimeMillis() - 4 * 60_000L;

        estimator.sample(t, 1.0, 1.0, 20, Double.NaN, true, true, GUN_AC);
        estimator.sample(t + 60_000L, 1.0, 1.1, 20, Double.NaN, true, true, GUN_AC);

        // Both raw counters exist, but only remain has a slope. Publishing it before the
        // comparison is exactly how the first bad sample poisoned the Atto session peak.
        assertTrue(Double.isNaN(estimator.estimatePowerKw()));

        estimator.sample(t + 120_000L, 1.025, 1.2, 20, Double.NaN,
                true, true, GUN_AC);
        assertEquals(0.75, estimator.estimatePowerKw(), 0.03);
    }

    @Test
    public void knownHalfScaleCapacityDoesNotDisplaceRemain() throws Exception {
        ChargingPowerEstimator estimator = newEstimator();
        long t = System.currentTimeMillis() - 2 * 60_000L;

        estimator.sample(t, 1.0, 10.0, 50, Double.NaN, true, true, GUN_AC);
        estimator.sample(t + 60_000L, 1.05, 10.1, 50, Double.NaN,
                true, true, GUN_AC);

        // Seal evidence: capacity can be half-scale. A 2:1 disagreement stays remain-first.
        assertEquals(6.0, estimator.estimatePowerKw(), 0.03);
    }

    @Test
    public void dcRetainsLegacyRemainFirstSelection() throws Exception {
        ChargingPowerEstimator estimator = newEstimator();
        long t = System.currentTimeMillis() - 2 * 60_000L;

        estimator.sample(t, 1.0, 10.0, 50, Double.NaN, true, true, GUN_DC);
        estimator.sample(t + 60_000L, 1.025, 12.0, 50, Double.NaN,
                true, true, GUN_DC);

        // A genuine 120 kW DC slope must not be capped by AC-only validation.
        assertEquals(120.0, estimator.estimatePowerKw(), 0.03);
    }

    @Test
    public void confirmedAcUsesRemainWhenCapacityCounterIsAbsent() throws Exception {
        ChargingPowerEstimator estimator = newEstimator();
        long t = System.currentTimeMillis() - 2 * 60_000L;

        estimator.sample(t, Double.NaN, 10.0, 50, Double.NaN,
                true, true, GUN_AC);
        estimator.sample(t + 60_000L, Double.NaN, 10.1, 50, Double.NaN,
                true, true, GUN_AC);

        assertEquals(6.0, estimator.estimatePowerKw(), 0.03);
    }

    @Test
    public void confirmedAcRejectsImpossibleRemainSlopeWithoutFallback() throws Exception {
        ChargingPowerEstimator estimator = newEstimator();
        long t = System.currentTimeMillis() - 2 * 60_000L;

        estimator.sample(t, Double.NaN, 10.0, 50, Double.NaN,
                true, true, GUN_AC);
        estimator.sample(t + 60_000L, Double.NaN, 12.0, 50, Double.NaN,
                true, true, GUN_AC);

        assertTrue(Double.isNaN(estimator.estimatePowerKw()));
    }

    @Test
    public void nonFiniteCounterCannotEnterOrRefreshEstimatorRing()
            throws Exception {
        ChargingPowerEstimator estimator = newEstimator();
        long start = System.currentTimeMillis();
        estimator.sample(start, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, false, false, GUN_DISCONNECTED);
        estimator.sample(start + 1_000L, Double.NaN, 1.0,
                Double.NaN, Double.NaN, true, true, GUN_DC);
        estimator.sample(start + 61_000L, Double.NaN, 1.2,
                Double.NaN, Double.NaN, true, true, GUN_DC);

        assertTrue(Double.isFinite(estimator.estimatePowerKw()));
        assertEquals(2, remainRing(estimator).size());

        estimator.sample(start + 121_000L,
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                true, true, GUN_DC);

        assertEquals(2, remainRing(estimator).size());
        assertTrue(Double.isFinite(estimator.estimatePowerKw()));

        estimator.sample(start + 122_000L, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, false, false, GUN_DISCONNECTED);
    }

    @Test
    public void smallPhevDitherToleranceScalesWithPack() throws Exception {
        ChargingPowerEstimator estimator = newEstimator();
        long start = System.currentTimeMillis();

        estimator.sample(start, Double.NaN, Double.NaN,
                50.0, 5.0, true, true, GUN_DC);
        estimator.sample(start + 60_000L, Double.NaN, Double.NaN,
                51.0, 5.0, true, true, GUN_DC);
        assertEquals(2, socRing(estimator).size());

        // One 1%-SOC wobble is dither; a four-point drop on a 5 kWh pack is a reset.
        estimator.sample(start + 120_000L, Double.NaN, Double.NaN,
                50.0, 5.0, true, true, GUN_DC);
        assertEquals(2, socRing(estimator).size());
        estimator.sample(start + 180_000L, Double.NaN, Double.NaN,
                47.0, 5.0, true, true, GUN_DC);
        assertEquals(1, socRing(estimator).size());
    }

    @SuppressWarnings("unchecked")
    private static ArrayDeque<long[]> remainRing(
            ChargingPowerEstimator estimator) throws Exception {
        Field field =
                ChargingPowerEstimator.class.getDeclaredField("remainRing");
        field.setAccessible(true);
        return (ArrayDeque<long[]>) field.get(estimator);
    }

    @SuppressWarnings("unchecked")
    private static ArrayDeque<long[]> socRing(
            ChargingPowerEstimator estimator) throws Exception {
        Field field = ChargingPowerEstimator.class.getDeclaredField("socRing");
        field.setAccessible(true);
        return (ArrayDeque<long[]>) field.get(estimator);
    }

    private static ChargingPowerEstimator newEstimator() throws Exception {
        Constructor<ChargingPowerEstimator> constructor =
                ChargingPowerEstimator.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        ChargingPowerEstimator estimator = constructor.newInstance();

        // Keep local JVM tests away from android.util.Log without changing production logging.
        Field lastLog = ChargingPowerEstimator.class.getDeclaredField("lastLogMs");
        lastLog.setAccessible(true);
        lastLog.setLong(estimator, Long.MAX_VALUE);
        return estimator;
    }
}
