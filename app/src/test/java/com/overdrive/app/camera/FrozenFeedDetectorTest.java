package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public class FrozenFeedDetectorTest {

    private static final class RecordingListener implements FrozenFeedDetector.Listener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onFrozenFeed(int identicalSamples, long frozenForMs) {
            events.add("frozen:" + identicalSamples);
        }

        @Override
        public void onFeedRecovered(long frozenForMs) {
            events.add("recovered");
        }
    }

    private static byte[] frame(long seed) {
        byte[] f = new byte[640 * 480 * 3];
        new Random(seed).nextBytes(f);
        return f;
    }

    @Test
    public void identicalStreakTripsOncePerEpisodeAndRecovers() {
        RecordingListener l = new RecordingListener();
        FrozenFeedDetector d = new FrozenFeedDetector(l, 2_000L, 5);
        byte[] frozen = frame(1);
        long t = 100_000L;
        // First sample establishes the hash; 5 identical follow-ups trip it.
        for (int i = 0; i <= 5; i++) {
            d.observe(frozen, t);
            t += 2_000L;
        }
        assertEquals(java.util.Collections.singletonList("frozen:5"), l.events);
        assertTrue(d.isFrozen());
        // Staying frozen does not re-fire.
        d.observe(frozen, t); t += 2_000L;
        assertEquals(1, l.events.size());
        // A different frame ends the episode.
        d.observe(frame(2), t);
        assertEquals("recovered", l.events.get(1));
        assertFalse(d.isFrozen());
        assertEquals(1, d.episodesTotal());
    }

    @Test
    public void changingContentNeverTrips() {
        RecordingListener l = new RecordingListener();
        FrozenFeedDetector d = new FrozenFeedDetector(l, 2_000L, 5);
        long t = 0L;
        for (int i = 0; i < 50; i++) {
            d.observe(frame(i), t);
            t += 2_000L;
        }
        assertTrue(l.events.isEmpty());
        assertFalse(d.isFrozen());
    }

    @Test
    public void samplingIntervalGatesTheHashing() {
        RecordingListener l = new RecordingListener();
        FrozenFeedDetector d = new FrozenFeedDetector(l, 2_000L, 5);
        byte[] frozen = frame(3);
        long t = 0L;
        // 20 fps for 8 s = 160 frames but only 4 samples are hashed
        // (t=0 baseline, then 2/4/6 s → identical streak 3, below the trip).
        for (int i = 0; i < 160; i++) {
            d.observe(frozen, t);
            t += 50L;
        }
        assertTrue(l.events.isEmpty());
        // Two more sampled identical frames (8 s, 10 s) cross the threshold.
        d.observe(frozen, 8_000L);
        assertTrue(l.events.isEmpty());
        d.observe(frozen, 10_000L);
        assertEquals(1, l.events.size());
    }

    @Test
    public void resetForgetsTheStreakAndTheHash() {
        RecordingListener l = new RecordingListener();
        FrozenFeedDetector d = new FrozenFeedDetector(l, 2_000L, 2);
        byte[] frozen = frame(4);
        long t = 0L;
        d.observe(frozen, t);
        d.observe(frozen, t + 2_000L);
        d.reset();
        // Post-reset, the same frame must re-establish a baseline first…
        d.observe(frozen, t + 4_000L);
        assertTrue(l.events.isEmpty());
        // …and only a fresh streak trips.
        d.observe(frozen, t + 6_000L);
        d.observe(frozen, t + 8_000L);
        assertEquals(1, l.events.size());
    }

    @Test
    public void listenerFailureNeverPropagates() {
        FrozenFeedDetector d = new FrozenFeedDetector(new FrozenFeedDetector.Listener() {
            @Override
            public void onFrozenFeed(int identicalSamples, long frozenForMs) {
                throw new IllegalStateException("listener bug");
            }

            @Override
            public void onFeedRecovered(long frozenForMs) {
                throw new IllegalStateException("listener bug");
            }
        }, 1_000L, 1);
        byte[] frozen = frame(5);
        d.observe(frozen, 0L);
        d.observe(frozen, 1_000L);   // trips; listener throws; must not escape
        assertTrue(d.isFrozen());
        d.observe(frame(6), 2_000L); // recovers; listener throws; must not escape
        assertFalse(d.isFrozen());
    }

    @Test
    public void differentLengthIsNeverIdentical() {
        byte[] a = new byte[1000];
        byte[] b = new byte[2000];
        assertNotEquals(FrozenFeedDetector.hash(a), FrozenFeedDetector.hash(b));
    }
}
