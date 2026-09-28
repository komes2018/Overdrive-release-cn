package com.overdrive.app.surveillance;

/**
 * Actor — A persistent moving subject around the vehicle.
 *
 * One Actor represents one tracked entity (person, vehicle, bike, animal) across
 * multiple frames and possibly multiple cameras. Actors are produced by
 * {@link ActorTracker} from raw YOLO detections and consumed by:
 *  - {@link EventTimelineCollector}    for the per-recording JSON sidecar
 *  - {@link ThumbnailBuffer}          for picking the peak-severity frame
 *  - {@link SeverityClassifier}       for NOTICE/ALERT/CRITICAL gating
 *  - The notification + UI layers      for human-readable summaries
 *
 * No metric distance: everything that quantifies "how close" is a {@link Proximity}
 * band derived from bbox size in the camera frame, NOT calibrated extrinsics.
 * If extrinsics ever become available, swap the proximity classifier without
 * breaking any consumer.
 */
public final class Actor {

    /** Coarse class taxonomy collapsed from COCO so trackers/UIs deal with 5 things, not 80. */
    public enum ClassGroup {
        PERSON,
        VEHICLE,    // car / bus / truck
        BIKE,       // bicycle / motorcycle
        ANIMAL,
        UNKNOWN
    }

    /**
     * Proximity band — pure pixel-relative classification. Derived from bbox height
     * for persons, bbox width for vehicles. No reliance on camera mounting
     * intrinsics / extrinsics.
     */
    public enum Proximity {
        VERY_CLOSE,   // bbox >= 60% of crop dimension
        CLOSE,        // bbox 35–60%
        MID,          // bbox 15–35%
        FAR,          // bbox <  15%
        UNKNOWN
    }

    /** Trajectory trend over the last few frames (purely pixel-relative). */
    public enum Trend {
        APPROACHING,  // bbox area growing
        RECEDING,     // bbox area shrinking
        STABLE,       // change within noise
        UNKNOWN
    }

    /** Severity emitted by SeverityClassifier; mirrors three-tier gating. */
    public enum Severity {
        NOTICE,   // background / passing-by / static parked car
        ALERT,    // person near vehicle, vehicle approaching, etc.
        CRITICAL  // person at very-close, prolonged dwell at very-close, etc.
    }

    public final long actorId;
    public final ClassGroup classGroup;
    public final long firstSeenWallMs;
    public final long lastSeenWallMs;
    public final long firstSeenRelMs;     // relative to recording start (or -1 if pre-trigger)
    public final long lastSeenRelMs;
    public final int  cameraMask;          // bit per quadrant (0=front,1=right,2=rear,3=left)
    public final Proximity peakProximity;  // closest approach across lifetime
    public final Proximity lastProximity;  // most recent
    public final Trend trend;
    public final boolean isStatic;         // bbox area + position stable for >= STATIC_DWELL_FRAMES
    // Timeline/marker static verdict — a SUPERSET of isStatic for non-persons.
    // True when the actor should be treated as background for the SRT timeline,
    // JSON markers, counts, and the Vehicle chip. Covers the sparse-cadence case
    // where a genuinely parked car never accrues the consecutive stable frames
    // for isStatic but demonstrably never moved (everMoved=false). For PERSON
    // this equals isStatic (a static loitering person is NEVER background). The
    // severity path keeps using isStatic (unchanged); only the cosmetic
    // timeline/flag surfaces consult isStaticForTimeline.
    public final boolean isStaticForTimeline;
    // True once the tracker positively determined this actor MOVED — a net
    // centroid jump beyond EVER_MOVED_CENTROID_PX OR net bbox-area growth/shrink
    // past the jitter band over >=2 mosaic frames (ActorTracker everMoved latch).
    // Distinct from !isStaticForTimeline: for a PERSON isStaticForTimeline is just
    // isStatic (a person is NEVER inferred-static from stillness), so a head-on
    // approacher and a truly-still loiterer BOTH read isStaticForTimeline=false —
    // only this raw latch separates "demonstrably moved" from "never moved". Used
    // by the low-conf-FAR-NOTICE suppression gate so a head-on approacher (whose
    // bbox grows but whose centroid barely shifts, making computeTrend read STABLE
    // — see ActorTracker:436-437) is NOT mistaken for a static misclassification.
    public final boolean everMoved;
    // True once the everMoved net-displacement test ran enough to be meaningful
    // (>=2 mosaic test frames past the anchor — everMovedTestFrames>=2). everMoved
    // is the net-motion VERDICT; this is whether that verdict had the data to be
    // trusted. >=2 (not >=1) because the everMoved area-growth latch itself needs
    // 2 consecutive over-band frames, so after a single test frame everMoved
    // cannot have latched and "stillness measured" would be a false claim. The latch only updates on MOSAIC frames, but a moving object
    // runs in the FOVEATED path at steady state (the crop re-centers on it), so a
    // foveated-only track has everMoved=false NOT because it was still but because
    // stillness was never MEASURED. The suppression gate must fail OPEN in that
    // case (treat as moving), mirroring the pre-existing isStaticForTimeline guard
    // (ActorTracker:775 requires everMovedTestFrames>=1). Without this a real far
    // mover seen only on foveated frames is wrongly suppressed.
    public final boolean everMovedTested;
    // True once observed across >= MIN_ESCALATION_FRAMES — i.e. NOT a 1-2 frame
    // YOLO flicker / one-frame misclassification. Consumers that retain an actor
    // into a persistent summary (eventPeakActors) gate PERSON retention on this so
    // a phantom one-frame person can't add a spurious +1 to personCount/caption.
    public final boolean confirmed;
    public final Severity peakSeverity;
    public final long peakSeverityWallMs;
    public final long peakSeverityRelMs;
    public final float peakConfidence;
    public final int peakBboxX;            // bbox at peak severity moment (in crop pixel coords)
    public final int peakBboxY;
    public final int peakBboxW;
    public final int peakBboxH;
    // Crop dimensions the peakBbox is measured against. The pipeline
    // alternates between mosaic (320×240) and foveated (640×640) crops
    // depending on whether the foveated cropper is wired up and whether
    // motion blocks are confirmed in the current frame. peakBbox alone
    // is meaningless without knowing which crop space it's in — readers
    // (ThumbnailBuffer, baseline promotion) MUST use these dims to scale
    // bboxes onto whatever frame they're drawing on. Without this the
    // hero JPEG draws the bbox over a different camera region than the
    // actor actually occupied.
    public final int peakBboxQuadW;
    public final int peakBboxQuadH;
    public final int peakCamera;           // quadrant where peak severity hit
    // Most recent observation — for "what does this actor look like NOW"
    // queries (mid-event baseline promotion, future distance estimation).
    // peakBbox describes the forensic / thumbnail moment; lastBbox the freshest.
    public final int lastBboxX;
    public final int lastBboxY;
    public final int lastBboxW;
    public final int lastBboxH;
    // Quadrant of the MOST RECENT observation. peakCamera is a lifetime
    // high-water latch (the quadrant where peak severity / closest approach
    // hit, never moved back), which makes it wrong for "where is the actor
    // NOW" — captions must use lastCamera so they name the live quadrant, not
    // a quadrant the actor has since left. Thumbnail/forensic paths still use
    // peakCamera.
    public final int lastCamera;

    public Actor(long actorId, ClassGroup classGroup,
                 long firstSeenWallMs, long lastSeenWallMs,
                 long firstSeenRelMs, long lastSeenRelMs,
                 int cameraMask,
                 Proximity peakProximity, Proximity lastProximity,
                 Trend trend, boolean isStatic, boolean isStaticForTimeline,
                 boolean everMoved, boolean everMovedTested, boolean confirmed,
                 Severity peakSeverity, long peakSeverityWallMs, long peakSeverityRelMs,
                 float peakConfidence,
                 int peakBboxX, int peakBboxY, int peakBboxW, int peakBboxH,
                 int peakBboxQuadW, int peakBboxQuadH, int peakCamera,
                 int lastBboxX, int lastBboxY, int lastBboxW, int lastBboxH,
                 int lastCamera) {
        this.actorId = actorId;
        this.classGroup = classGroup;
        this.firstSeenWallMs = firstSeenWallMs;
        this.lastSeenWallMs = lastSeenWallMs;
        this.firstSeenRelMs = firstSeenRelMs;
        this.lastSeenRelMs = lastSeenRelMs;
        this.cameraMask = cameraMask;
        this.peakProximity = peakProximity;
        this.lastProximity = lastProximity;
        this.trend = trend;
        this.isStatic = isStatic;
        this.isStaticForTimeline = isStaticForTimeline;
        this.everMoved = everMoved;
        this.everMovedTested = everMovedTested;
        this.confirmed = confirmed;
        this.peakSeverity = peakSeverity;
        this.peakSeverityWallMs = peakSeverityWallMs;
        this.peakSeverityRelMs = peakSeverityRelMs;
        this.peakConfidence = peakConfidence;
        this.peakBboxX = peakBboxX;
        this.peakBboxY = peakBboxY;
        this.peakBboxW = peakBboxW;
        this.peakBboxH = peakBboxH;
        this.peakBboxQuadW = peakBboxQuadW;
        this.peakBboxQuadH = peakBboxQuadH;
        this.peakCamera = peakCamera;
        this.lastBboxX = lastBboxX;
        this.lastBboxY = lastBboxY;
        this.lastBboxW = lastBboxW;
        this.lastBboxH = lastBboxH;
        this.lastCamera = lastCamera;
    }

    /** Confidence floor for the low-conf-FAR-NOTICE misclassification profile. */
    private static final float FAR_NOTICE_MIN_CONF = 0.50f;

    /**
     * Class-agnostic core of the low-confidence FAR NOTICE misclassification test
     * (a parked motorcycle read as "person · far" @0.44 — or any far, low-conf,
     * never-escalated, provably-still detection). True ONLY when ALL hold:
     *  - peakSeverity == NOTICE     — never suppress an actor that escalated.
     *  - peakProximity == FAR       — a monotone closest-latch, so an actor that
     *                                 ever reached MID/CLOSE/VERY_CLOSE is exempt.
     *  - peakConfidence < 0.50.
     *  - trend not APPROACHING/RECEDING — catches LATERAL motion.
     *  - motion evidence — qualifies via EITHER (1) the net-motion test ran and
     *    found no motion (everMovedTested && !everMoved), OR (2) an unconfirmed
     *    1-2 frame flicker that has not been shown to move (!confirmed &&
     *    !everMoved). Clause (2) closes the single-observation fail-open a
     *    background parked car exploited (seen on one frame → test never ran →
     *    old everMovedTested-only gate kept it). !everMoved in BOTH clauses means
     *    a provably-moving far actor (lateral OR head-on) is never dropped; a
     *    CONFIRMED actor (>=3 frames) whose test simply hasn't run still fails
     *    open, so only genuine low-evidence flickers are newly suppressed.
     *
     * NOTE the SURFACE SPLIT — PERSON is handled differently per surface, because
     * the FP and a genuinely-motionless distant person produce byte-IDENTICAL
     * Actor fields (person/FAR/STABLE/NOTICE/low-conf/!everMoved), so no signal
     * separates them and the two surfaces have different costs:
     *  - {@link #suppressFromHero}: applies to ALL classes incl. PERSON. A wrong
     *    bbox stamped on a bike (the reported bug) is the most visible error, and
     *    the hero degrades to a real MP4 keyframe — losing nothing.
     *  - {@link #suppressFromSummary}: EXEMPTS PERSON. Counts / SRT / chip /
     *    caption must honour the hard invariant "a person is never inferred-static
     *    from stillness — a loiterer keeps its count/SRT/chip". A real far still
     *    person stays surfaced there; only non-person FPs (car/bike/animal) drop.
     */
    private static boolean isLowConfFarNoticeCore(Actor a) {
        if (a == null
                || a.peakSeverity != Severity.NOTICE
                || a.peakProximity != Proximity.FAR
                || a.peakConfidence >= FAR_NOTICE_MIN_CONF
                || a.trend == Trend.APPROACHING
                || a.trend == Trend.RECEDING) {
            return false;
        }
        // MOTION EVIDENCE. Two disjoint ways to qualify as the FP profile:
        //
        //  (1) The net-motion test RAN and found no motion (everMovedTested &&
        //      !everMoved). This is the original signal — a track observed on
        //      >=2 frames whose centroid/area never left the jitter band.
        //
        //  (2) The track is an UNCONFIRMED flicker (!confirmed — seen on
        //      < MIN_ESCALATION_FRAMES frames) that has not (yet) been shown to
        //      move. This closes the single-observation fail-open: a background
        //      parked car incidentally caught by ANOTHER object's motion (the
        //      on-car case: event_20260701_111811 — a parked car glimpsed on ONE
        //      frame, first==last==peakWallMs, so everMovedTested=false) never
        //      accumulates the >=2 frames the motion test needs, so clause (1)
        //      failed open and the car won an otherwise-empty event's hero. A
        //      real object seen this briefly at FAR + low-conf + NOTICE + no
        //      motion is overwhelmingly a misclassified/background FP, not a
        //      threat. everMoved still exempts it the instant motion IS observed
        //      (a moving actor that flickered for 1 frame but jumped is kept).
        //
        // Note the surface split preserves the still-distant-person invariant:
        // suppressFromSummary EXEMPTS PERSON, so a motionless far person keeps its
        // count/SRT/chip regardless of this widening; only the hero (cosmetic,
        // degrades to a real MP4 keyframe) drops a person-shaped 1-frame flicker.
        boolean motionTestedStill = a.everMovedTested && !a.everMoved;
        // Clause (2) targets the SINGLE-frame background flicker specifically
        // (first==last==peakWallMs — the parked car glimpsed on one frame that
        // clause (1) misses because the net-motion test needs >=2 frames). The
        // lastSeenWallMs<=firstSeenWallMs guard restricts it to exactly that
        // one-observation profile. Without it, a real >=2-frame FAR head-on
        // approacher — which cannot latch everMoved (centroid barely drifts,
        // area latch needs 2 consecutive over-band frames) nor computeTrend
        // APPROACHING (needs dCy>=5) nor confirmed (needs 3 frames) — was being
        // dropped from the summary surfaces (count/chip/caption/SRT label),
        // a forensic regression vs HEAD (which had no clause 2). A >=2-frame
        // track advances lastSeenWallMs past firstSeenWallMs so it now fails
        // open and keeps its summary presence, matching HEAD; the multi-frame
        // parked-object hero FP is still caught by clause (1).
        boolean unconfirmedFlickerStill = !a.confirmed && !a.everMoved
                && a.lastSeenWallMs <= a.firstSeenWallMs;
        return motionTestedStill || unconfirmedFlickerStill;
    }

    /** Hero-pool / per-actor-thumbnail suppression: drop the FP profile for ANY
     *  class INCLUDING PERSON. This is DELIBERATE and must NOT be changed to
     *  exempt PERSON: the reported on-car FP (event_20260630_164722) was a parked
     *  motorcycle that YOLO classified as classGroup=PERSON @0.44, so a PERSON
     *  exemption here would reopen the exact phantom-box bug (a grey "person·far"
     *  box drawn on a bike with no person present). The cost of gating PERSON is
     *  only cosmetic: the hero falls back to a real MP4 keyframe seeked to the
     *  motion moment, which for a genuinely-present still person STILL depicts
     *  that person — just without a drawn box on a <15%-of-frame figure — and the
     *  person remains fully surfaced in counts / SRT / chip / caption (those use
     *  {@link #suppressFromSummary}, which DOES exempt PERSON). So a real person
     *  loses only the bbox overlay, never their presence; the FP loses its
     *  misleading box. Net: correct for both, hence the asymmetry with summary. */
    public static boolean suppressFromHero(Actor a) {
        return isLowConfFarNoticeCore(a);
    }

    /** Summary-surface suppression (JSON counts / SRT line / events-page class
     *  chip / notification caption): drop the FP profile EXCEPT for PERSON. A
     *  real motionless distant person is indistinguishable from the bike-as-person
     *  FP, and the hard invariant requires a person keep its count/SRT/chip, so
     *  PERSON is never summary-suppressed; only non-person FPs (car/bike/animal)
     *  drop here. (The hero may still drop a person-FP box via suppressFromHero —
     *  that only changes the THUMBNAIL, not the person's presence in the summary.) */
    public static boolean suppressFromSummary(Actor a) {
        return a != null
                && a.classGroup != ClassGroup.PERSON
                && isLowConfFarNoticeCore(a);
    }

    /** Map a COCO class ID to a coarse group. */
    public static ClassGroup groupOf(int cocoClassId) {
        if (cocoClassId == 0) return ClassGroup.PERSON;
        if (cocoClassId == 2 || cocoClassId == 5 || cocoClassId == 7) return ClassGroup.VEHICLE;
        if (cocoClassId == 1 || cocoClassId == 3) return ClassGroup.BIKE;
        if (cocoClassId >= 14 && cocoClassId <= 23) return ClassGroup.ANIMAL;
        return ClassGroup.UNKNOWN;
    }

    public static String groupLabel(ClassGroup g) {
        switch (g) {
            case PERSON:  return "person";
            case VEHICLE: return "vehicle";
            case BIKE:    return "bike";
            case ANIMAL:  return "animal";
            default:      return "object";
        }
    }

    public static String proximityLabel(Proximity p) {
        switch (p) {
            case VERY_CLOSE: return "very close";
            case CLOSE:      return "close";
            case MID:        return "mid";
            case FAR:        return "far";
            default:         return "unknown";
        }
    }

    public static String severityLabel(Severity s) {
        switch (s) {
            case CRITICAL: return "CRITICAL";
            case ALERT:    return "ALERT";
            default:       return "NOTICE";
        }
    }

    public static String groupLabelZh(ClassGroup g) {
        switch (g) {
            case PERSON:  return "人";
            case VEHICLE: return "车辆";
            case BIKE:    return "骑行";
            case ANIMAL:  return "动物";
            default:      return "物体";
        }
    }

    public static String proximityLabelZh(Proximity p) {
        switch (p) {
            case VERY_CLOSE: return "极近";
            case CLOSE:      return "近";
            case MID:        return "中等";
            case FAR:        return "远";
            default:         return "未知";
        }
    }

    public static String severityLabelZh(Severity s) {
        switch (s) {
            case CRITICAL: return "严重告警";
            case ALERT:    return "一般告警";
            default:       return "常规提示";
        }
    }
}
