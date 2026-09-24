package com.overdrive.app.parking.signage;

/**
 * Greedy CTC decoding for a CRNN-style recogniser output.
 *
 * <p>Input is the per-timestep class distribution {@code [T][C]} (softmax
 * probabilities or logits — only the argmax and its value are used, so
 * logits work as long as {@link #decode} is told the values are not
 * probabilities). The blank symbol is collapsed and repeated consecutive
 * symbols are merged, which is the standard "best path" decode and what
 * PaddleOCR's own postprocess does.
 */
public final class CtcDecoder {

    /** Decoded text plus a confidence (mean emitted-symbol probability). */
    public static final class Decoded {
        public final String text;
        public final float confidence;
        Decoded(String text, float confidence) { this.text = text; this.confidence = confidence; }
    }

    private CtcDecoder() {}

    /**
     * @param probs        {@code [T][C]} per-timestep distribution
     * @param charset      symbol table indexed by class id; the blank slot's
     *                     entry is ignored
     * @param blankIndex   class id of the CTC blank (PaddleOCR: 0)
     * @param isProbability when false the values are treated as raw logits and
     *                     confidence is computed from a per-step softmax
     */
    public static Decoded decode(float[][] probs, String[] charset, int blankIndex, boolean isProbability) {
        if (probs == null || probs.length == 0 || charset == null) return new Decoded("", 0f);
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        double confSum = 0.0;
        int emitted = 0;
        for (float[] step : probs) {
            if (step == null || step.length == 0) { prev = -1; continue; }
            int best = 0;
            float bestV = step[0];
            for (int c = 1; c < step.length; c++) {
                if (step[c] > bestV) { bestV = step[c]; best = c; }
            }
            float p = isProbability ? bestV : softmaxAt(step, best);
            if (best != blankIndex && best != prev) {
                if (best >= 0 && best < charset.length && charset[best] != null) {
                    sb.append(charset[best]);
                    confSum += p;
                    emitted++;
                }
            }
            prev = best;
        }
        float conf = emitted == 0 ? 0f : (float) (confSum / emitted);
        return new Decoded(sb.toString(), conf);
    }

    /** Convenience for a flat row-major {@code [T*C]} buffer. */
    public static Decoded decodeFlat(float[] flat, int steps, int classes, String[] charset,
                                     int blankIndex, boolean isProbability) {
        if (flat == null || steps <= 0 || classes <= 0 || flat.length < steps * classes) {
            return new Decoded("", 0f);
        }
        float[][] probs = new float[steps][classes];
        for (int t = 0; t < steps; t++) {
            System.arraycopy(flat, t * classes, probs[t], 0, classes);
        }
        return decode(probs, charset, blankIndex, isProbability);
    }

    static float softmaxAt(float[] logits, int idx) {
        float max = Float.NEGATIVE_INFINITY;
        for (float v : logits) if (v > max) max = v;
        double sum = 0.0;
        for (float v : logits) sum += Math.exp(v - max);
        return sum <= 0 ? 0f : (float) (Math.exp(logits[idx] - max) / sum);
    }

    /**
     * Build the PaddleOCR-style symbol table: blank at index 0, then one
     * symbol per line of the dictionary, then a trailing space when the model
     * has exactly one more class than {@code lines + 1}.
     */
    public static String[] charsetFromLines(java.util.List<String> lines, int modelClasses) {
        int n = lines == null ? 0 : lines.size();
        int size = Math.max(modelClasses, n + 1);
        String[] cs = new String[size];
        cs[0] = "";  // blank
        for (int i = 0; i < n; i++) cs[i + 1] = lines.get(i);
        if (modelClasses == n + 2) cs[n + 1] = " ";
        for (int i = 0; i < size; i++) if (cs[i] == null) cs[i] = "";
        return cs;
    }
}
