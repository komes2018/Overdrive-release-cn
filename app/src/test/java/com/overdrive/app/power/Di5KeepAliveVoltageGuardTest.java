package com.overdrive.app.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Di5KeepAliveVoltageGuardTest {

    private static final long MAX_AGE = 120_000L;

    @Test
    public void noSampleBlocksUntilFirstValidReading() {
        Di5KeepAliveVoltageGuard g = new Di5KeepAliveVoltageGuard(11.8, 3, MAX_AGE);
        assertEquals(Di5KeepAliveVoltageGuard.Decision.NO_SAMPLE, g.evaluate(1_000L));
        // Non-finite / non-positive readings never count as a sample.
        assertFalse(g.observe(Double.NaN, 1_000L));
        assertFalse(g.observe(0.0, 1_000L));
        assertEquals(Di5KeepAliveVoltageGuard.Decision.NO_SAMPLE, g.evaluate(1_000L));
        assertFalse(g.observe(12.6, 2_000L));
        assertEquals(Di5KeepAliveVoltageGuard.Decision.ALLOW, g.evaluate(2_000L));
    }

    @Test
    public void staleSampleBlocksReasserts() {
        Di5KeepAliveVoltageGuard g = new Di5KeepAliveVoltageGuard(11.8, 3, MAX_AGE);
        g.observe(12.6, 10_000L);
        assertEquals(Di5KeepAliveVoltageGuard.Decision.ALLOW, g.evaluate(10_000L + MAX_AGE));
        assertEquals(Di5KeepAliveVoltageGuard.Decision.STALE, g.evaluate(10_000L + MAX_AGE + 1));
        assertEquals(MAX_AGE + 1, g.sampleAgeMs(10_000L + MAX_AGE + 1));
    }

    @Test
    public void cutoffNeedsConsecutiveLowSamples() {
        Di5KeepAliveVoltageGuard g = new Di5KeepAliveVoltageGuard(11.8, 3, MAX_AGE);
        assertFalse(g.observe(11.7, 1_000L));
        assertFalse(g.observe(11.7, 2_000L));
        // A recovery in between resets the debounce.
        assertFalse(g.observe(12.1, 3_000L));
        assertEquals(0, g.consecutiveLowSamples());
        assertFalse(g.observe(11.8, 4_000L));   // at cutoff counts as low
        assertFalse(g.observe(11.6, 5_000L));
        assertTrue(g.observe(11.5, 6_000L));    // third consecutive → trips (edge)
        assertTrue(g.isLatched());
        assertEquals(Di5KeepAliveVoltageGuard.Decision.CUTOFF_LATCHED, g.evaluate(6_000L));
        assertTrue(g.latchReason().contains("11.5"));
    }

    @Test
    public void latchSurvivesVoltageRebound() {
        Di5KeepAliveVoltageGuard g = new Di5KeepAliveVoltageGuard(11.8, 1, MAX_AGE);
        assertTrue(g.observe(11.0, 1_000L));
        // Surface-charge rebound must NOT resume the hold.
        assertFalse(g.observe(12.8, 2_000L));
        assertFalse(g.observe(12.9, 3_000L));
        assertEquals(Di5KeepAliveVoltageGuard.Decision.CUTOFF_LATCHED, g.evaluate(3_000L));
        // Only an explicit release (ACC ON / confirmed charging) clears it.
        g.releaseLatch("ACC ON");
        assertFalse(g.isLatched());
        assertEquals(Di5KeepAliveVoltageGuard.Decision.ALLOW, g.evaluate(3_000L));
    }

    @Test
    public void reconfigureKeepsHistoryAndLatch() {
        Di5KeepAliveVoltageGuard g = new Di5KeepAliveVoltageGuard(11.8, 2, MAX_AGE);
        g.observe(11.0, 1_000L);
        assertTrue(g.observe(11.0, 2_000L));
        g.reconfigure(11.0, 5, MAX_AGE);
        assertTrue(g.isLatched());
        assertEquals(5, g.cutoffSamples());
        assertEquals(11.0, g.cutoffVoltage(), 1e-9);
    }

    @Test
    public void resetSamplesForgetsReadingsButNotLatch() {
        Di5KeepAliveVoltageGuard g = new Di5KeepAliveVoltageGuard(11.8, 1, MAX_AGE);
        assertTrue(g.observe(11.0, 1_000L));
        g.resetSamples();
        assertFalse(g.hasSample());
        assertTrue(g.isLatched());
        assertEquals(Di5KeepAliveVoltageGuard.Decision.CUTOFF_LATCHED, g.evaluate(2_000L));
    }
}
