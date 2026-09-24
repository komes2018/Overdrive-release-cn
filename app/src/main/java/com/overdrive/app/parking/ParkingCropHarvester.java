package com.overdrive.app.parking;

import com.overdrive.app.surveillance.Actor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the sharpest clean frames of vehicles that move near the parked
 * car during a sentry event, so a neighbour's arrival / departure can be
 * identified by a human (make, colour, plate if legible) without scrubbing
 * the clip.
 *
 * <p>Bounded by design:
 * <ul>
 *   <li>Only ticks while a sentry event is actually recording AND a non-static
 *       vehicle/bike actor is live — idle sentry costs two volatile reads per tick.</li>
 *   <li>At most one full-resolution tile capture per {@link #MIN_CAPTURE_GAP_MS}
 *       and {@link #MAX_CAPTURES_PER_EVENT} per event.</li>
 *   <li>Keeps only the top {@link #KEEP_PER_ACTOR} candidates per actor in memory
 *       (JPEG bytes, ~100–200 KB each); everything else is dropped immediately.</li>
 *   <li>Frames are written to disk only for actors that the observer linked to a
 *       neighbour or close pass at event end; unclaimed candidates are discarded.</li>
 * </ul>
 *
 * <p>Runs exclusively on the parking worker thread. The tile capture is the same
 * recording-safe shared-EGL sampler the camera-mapping dialog uses.
 */
public final class ParkingCropHarvester {

    /**
     * Each capture is a synchronous FBO render + glReadPixels + JPEG encode on
     * the pipeline's shared EGL context, competing with the event encoder. One
     * every 2.5 s is plenty for "the sharpest frame of the car next to me";
     * 16 per event bounds the transient heap to ≈16 × 1 MB worst case.
     */
    static final long MIN_CAPTURE_GAP_MS = 2_500L;
    static final int MAX_CAPTURES_PER_EVENT = 16;
    static final int KEEP_PER_ACTOR = 6;
    static final int JPEG_MAX_BYTES = 1_000_000;

    /** One candidate frame. */
    static final class Candidate {
        final byte[] jpeg;
        final double score;
        final long ms;
        final int quadrant;
        Candidate(byte[] jpeg, double score, long ms, int quadrant) {
            this.jpeg = jpeg; this.score = score; this.ms = ms; this.quadrant = quadrant;
        }
    }

    private final ParkingEnvironment env;
    private final Map<Long, List<Candidate>> byActor = new HashMap<>();
    private long lastCaptureMs;
    private int capturesThisEvent;
    private boolean wasRecording;

    public ParkingCropHarvester(ParkingEnvironment env) {
        this.env = env;
    }

    /** Periodic tick from the worker (≈1 Hz). Cheap when nothing is recording. */
    public void tick(long nowMs) {
        boolean recording;
        try { recording = env.isEventRecording(); } catch (Throwable t) { recording = false; }
        if (!recording) {
            wasRecording = false;
            return;
        }
        if (!wasRecording) {
            wasRecording = true;
            capturesThisEvent = 0;
        }
        if (capturesThisEvent >= MAX_CAPTURES_PER_EVENT) return;
        if (nowMs - lastCaptureMs < MIN_CAPTURE_GAP_MS) return;

        List<Actor> actors;
        try { actors = env.lastActors(); } catch (Throwable t) { return; }
        if (actors == null || actors.isEmpty()) return;

        Actor target = null;
        for (Actor a : actors) {
            if (a == null || a.isStaticForTimeline) continue;
            if (a.classGroup != Actor.ClassGroup.VEHICLE && a.classGroup != Actor.ClassGroup.BIKE) continue;
            if (a.lastCamera < 0 || a.lastCamera > 3) continue;
            // Prefer the closest live vehicle: closer ⇒ larger ⇒ more legible.
            if (target == null || rank(a) > rank(target)) target = a;
        }
        if (target == null) return;

        lastCaptureMs = nowMs;
        capturesThisEvent++;
        byte[] jpeg;
        try { jpeg = env.captureQuadrantJpeg(target.lastCamera); }
        catch (Throwable t) { return; }
        if (jpeg == null || jpeg.length == 0 || jpeg.length > JPEG_MAX_BYTES) return;

        double score = 1.0;
        ParkingEnvironment.ImageOps ops = env.imageOps();
        if (ops != null) {
            float[] region = null;
            if (target.peakCamera == target.lastCamera
                    && target.peakBboxQuadW > 0 && target.peakBboxQuadH > 0) {
                region = new float[] {
                        clamp01((float) target.peakBboxX / target.peakBboxQuadW),
                        clamp01((float) target.peakBboxY / target.peakBboxQuadH),
                        clamp01((float) target.peakBboxW / target.peakBboxQuadW),
                        clamp01((float) target.peakBboxH / target.peakBboxQuadH) };
            }
            try { score = ops.frameScore(jpeg, region); } catch (Throwable t) { score = 0.5; }
        }
        offer(target.actorId, new Candidate(jpeg, score, nowMs, target.lastCamera));
    }

    private static int rank(Actor a) {
        switch (a.lastProximity) {
            case VERY_CLOSE: return 4;
            case CLOSE: return 3;
            case MID: return 2;
            case FAR: return 1;
            default: return 0;
        }
    }

    /** Visible for tests. Keeps the top-K by score for the actor. */
    void offer(long actorId, Candidate c) {
        List<Candidate> list = byActor.get(actorId);
        if (list == null) {
            list = new ArrayList<>(KEEP_PER_ACTOR + 1);
            byActor.put(actorId, list);
        }
        list.add(c);
        list.sort((x, y) -> Double.compare(y.score, x.score));
        while (list.size() > KEEP_PER_ACTOR) list.remove(list.size() - 1);
    }

    int candidateCount(long actorId) {
        List<Candidate> l = byActor.get(actorId);
        return l == null ? 0 : l.size();
    }

    /**
     * Persist the candidates of the given actors into {@code sessionDir} as
     * {@code frame_<key>_<n>.jpg} and return the frames JSON for the neighbour
     * row. Persisted candidates are removed from memory.
     */
    public String persistFrames(File sessionDir, String neighbourKey, Collection<Long> actorIds,
                                String existingFramesJson) {
        JSONArray frames = new JSONArray();
        if (existingFramesJson != null && !existingFramesJson.isEmpty()) {
            try { frames = new JSONArray(existingFramesJson); } catch (Exception ignored) {}
        }
        if (sessionDir == null || actorIds == null) return frames.toString();
        if (!ParkingSnapshotter.ensureDir(sessionDir)) return frames.toString();
        String safeKey = neighbourKey.replaceAll("[^A-Za-z0-9]", "_");
        List<Candidate> all = new ArrayList<>();
        for (Long id : actorIds) {
            List<Candidate> l = byActor.remove(id);
            if (l != null) all.addAll(l);
        }
        all.sort((x, y) -> Double.compare(y.score, x.score));
        int start = frames.length();
        int n = 0;
        for (Candidate c : all) {
            if (start + n >= KEEP_PER_ACTOR) break;
            String name = "frame_" + safeKey + "_" + (start + n) + ".jpg";
            File f = new File(sessionDir, name);
            if (!ParkingSnapshotter.writeAtomic(f, c.jpeg)) continue;
            try {
                JSONObject j = new JSONObject();
                j.put("name", name);
                j.put("score", Math.round(c.score * 1000.0) / 1000.0);
                j.put("ms", c.ms);
                j.put("side", ParkingNeighbour.sideName(c.quadrant));
                frames.put(j);
            } catch (Exception ignored) {}
            n++;
        }
        return frames.toString();
    }

    /** Drop everything (session ended / feature stopped). */
    public void clear() {
        byActor.clear();
        capturesThisEvent = 0;
        wasRecording = false;
    }

    /** Drop candidates of actors nobody claimed after an event closed. */
    public void dropUnclaimed() {
        byActor.clear();
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
