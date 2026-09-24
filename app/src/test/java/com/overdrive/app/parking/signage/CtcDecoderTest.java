package com.overdrive.app.parking.signage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Greedy CTC decode + PaddleOCR charset construction, plus the box labeller. */
public class CtcDecoderTest {

    private static float[] oneHot(int classes, int idx, float p) {
        float[] v = new float[classes];
        float rest = (1f - p) / (classes - 1);
        Arrays.fill(v, rest);
        v[idx] = p;
        return v;
    }

    @Test
    public void collapsesRepeatsAndBlanks() {
        // charset: 0=blank, 1=B, 2=2, 3=A
        String[] cs = {"", "B", "2", "A"};
        float[][] probs = {
                oneHot(4, 1, 0.9f), oneHot(4, 1, 0.8f),   // B B → B
                oneHot(4, 0, 0.9f),                       // blank
                oneHot(4, 2, 0.7f),                       // 2
                oneHot(4, 2, 0.7f),                       // 2 (repeat, merged)
                oneHot(4, 0, 0.9f), oneHot(4, 2, 0.6f)    // blank then 2 again → second "2"
        };
        CtcDecoder.Decoded d = CtcDecoder.decode(probs, cs, 0, true);
        assertEquals("B22", d.text);
        assertEquals((0.9f + 0.7f + 0.6f) / 3f, d.confidence, 1e-5f);
    }

    @Test
    public void logitsAreSoftmaxedForConfidence() {
        String[] cs = {"", "X"};
        float[][] logits = {{-5f, 5f}};
        CtcDecoder.Decoded d = CtcDecoder.decode(logits, cs, 0, false);
        assertEquals("X", d.text);
        assertTrue(d.confidence > 0.99f);
    }

    @Test
    public void flatBufferAndEmptyInputs() {
        String[] cs = {"", "A", "B"};
        float[] flat = {0.1f, 0.8f, 0.1f,   0.1f, 0.1f, 0.8f};
        assertEquals("AB", CtcDecoder.decodeFlat(flat, 2, 3, cs, 0, true).text);
        assertEquals("", CtcDecoder.decodeFlat(flat, 3, 3, cs, 0, true).text);   // too short
        assertEquals("", CtcDecoder.decode(null, cs, 0, true).text);
    }

    @Test
    public void paddleCharsetLayout() {
        List<String> dict = Arrays.asList("0", "1", "A");
        String[] withSpace = CtcDecoder.charsetFromLines(dict, 5);   // blank + 3 + space
        assertEquals(5, withSpace.length);
        assertEquals("", withSpace[0]);
        assertEquals("A", withSpace[3]);
        assertEquals(" ", withSpace[4]);
        String[] noSpace = CtcDecoder.charsetFromLines(dict, 4);
        assertEquals(4, noSpace.length);
        assertEquals("A", noSpace[3]);
    }

    @Test
    public void connectedComponentsFindTwoBlobs() {
        int w = 8, h = 4;
        float[] score = new float[w * h];
        // blob A: (0..2, 0..1); blob B: (5..7, 2..3); a lone pixel at (4,0) below minArea
        for (int y = 0; y <= 1; y++) for (int x = 0; x <= 2; x++) score[y * w + x] = 0.9f;
        for (int y = 2; y <= 3; y++) for (int x = 5; x <= 7; x++) score[y * w + x] = 0.6f;
        score[0 * w + 4] = 0.95f;
        List<ConnectedComponents.Component> comps = ConnectedComponents.label(score, w, h, 0.3f, 2);
        assertEquals(2, comps.size());
        ConnectedComponents.Component a = comps.get(0);
        assertEquals(0, a.minX); assertEquals(2, a.maxX); assertEquals(0, a.minY); assertEquals(1, a.maxY);
        assertEquals(6, a.area);
        assertEquals(0.9f, a.meanScore(), 1e-6f);
        ConnectedComponents.Component b = comps.get(1);
        assertEquals(5, b.minX); assertEquals(7, b.maxX); assertEquals(3, b.maxY);
        int[] u = ConnectedComponents.unclip(b, 1.6f, w, h);
        assertTrue(u[0] <= b.minX && u[2] >= b.maxX && u[2] <= w - 1 && u[3] <= h - 1);
    }
}
