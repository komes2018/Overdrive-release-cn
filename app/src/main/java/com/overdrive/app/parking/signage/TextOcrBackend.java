package com.overdrive.app.parking.signage;

import java.io.Closeable;
import java.util.List;

/**
 * Pluggable scene-text OCR used by the v2 garage-signage reader.
 *
 * <p>The production implementation is {@link TfliteTextOcrBackend} (a
 * PaddleOCR-style text detector + CRNN/CTC recogniser exported to TFLite by
 * {@code dev/export_signage_ocr.py}). The interface exists so the grammar,
 * the session-level reader and the deferred queue are testable on the JVM
 * with a scripted backend, and so the models stay OPTIONAL: when the assets
 * are absent the backend reports {@link #isReady()} == false and the session
 * is marked {@code signageState = unavailable} instead of failing.
 *
 * <p>Nothing here is plate OCR. The reader only ever feeds this the owner's
 * own four stills / last drive seconds, and the grammar keeps only
 * level / zone / bay tokens.
 */
public interface TextOcrBackend extends Closeable {

    /** One recognised text line with its frame-normalised box. */
    final class TextLine {
        public final String text;
        public final float confidence;
        /** Box centre and size normalised to the source frame (0..1). */
        public final float cx, cy, w, h;

        public TextLine(String text, float confidence, float cx, float cy, float w, float h) {
            this.text = text;
            this.confidence = confidence;
            this.cx = cx; this.cy = cy; this.w = w; this.h = h;
        }

        @Override public String toString() {
            return "'" + text + "'@" + Math.round(confidence * 100) + "%";
        }
    }

    /** True when both detector and recogniser are loaded and usable. */
    boolean isReady();

    /**
     * Detect and recognise all text lines in a JPEG frame. Blocking — the
     * parking worker is the only caller. Returns an empty list on any failure.
     */
    List<TextLine> read(byte[] jpeg);

    @Override
    void close();
}
