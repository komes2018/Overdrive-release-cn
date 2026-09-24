package com.overdrive.app.parking;

import com.overdrive.app.surveillance.Actor;
import com.overdrive.app.surveillance.DetectionBaseline;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives the neighbour timeline of a parking session from two read-only
 * signals the surveillance pipeline already produces:
 *
 * <ul>
 *   <li>{@link DetectionBaseline} entries — the parked, static vehicles the
 *       detector deliberately suppresses. Seeded entries are cars that were
 *       already there when sentry armed; entries created by a live event
 *       ({@code fromLiveEvent}) are cars that ARRIVED while we watched.</li>
 *   <li>Finalized event actors — a non-static vehicle actor whose bbox sits on
 *       a known neighbour's spot is that neighbour DEPARTING; a close mover
 *       that never parked is a CLOSE_PASS.</li>
 * </ul>
 *
 * <p>Pure bookkeeping over the {@link ParkingStore}: it never touches the
 * detector, and all methods are invoked on the parking worker thread only.
 */
public final class ParkingNeighbourObserver {

    /** Seed entries created within this window of session start are "present on arrival". */
    static final long PRESENT_ON_ARRIVAL_WINDOW_MS = 5L * 60_000L;
    /** An arrival without an event name yet is attached to the next finalize within this window. */
    static final long PENDING_ARRIVAL_ATTACH_MS = 45_000L;
    /** Foot-point distance (quadrant-normalised) for departure matching. */
    static final float DEPARTURE_FOOT_DIST_NORM = 0.15f;
    /** IoU alternative for departure matching. */
    static final float DEPARTURE_IOU_MIN = 0.30f;

    /** Result of one finalize pass: neighbour keys that gained actor links. */
    public static final class Attachment {
        public final String neighbourKey;
        public final List<Long> actorIds;
        Attachment(String key, List<Long> ids) { this.neighbourKey = key; this.actorIds = ids; }
    }

    private final ParkingStore store;
    private ParkingSession session;
    /** Arrivals awaiting the finalize of the event that created them: key → created wall ms. */
    private final Map<String, Long> pendingArrivals = new LinkedHashMap<>();

    public ParkingNeighbourObserver(ParkingStore store) {
        this.store = store;
    }

    public void startSession(ParkingSession s) {
        this.session = s;
        pendingArrivals.clear();
    }

    /** Called when a daemon restart adopts an already-open session. */
    public void adoptSession(ParkingSession s) {
        startSession(s);
    }

    public ParkingSession currentSession() { return session; }

    // ==================== BASELINE SIGNALS ====================

    public void onEntrySeeded(int quadrant, List<DetectionBaseline.EntrySnapshot> entries, long nowMs) {
        if (session == null || entries == null) return;
        boolean onArrival = (nowMs - session.startedMs) <= PRESENT_ON_ARRIVAL_WINDOW_MS;
        for (DetectionBaseline.EntrySnapshot e : entries) {
            if (!isVehicleLike(e.classGroup)) continue;
            String key = keyFor(e);
            if (store.findNeighbourByKey(session.sessionId, key) != null) continue;
            ParkingNeighbour n = baseRow(e, key, nowMs);
            n.status = onArrival ? ParkingNeighbour.STATUS_PRESENT_ON_ARRIVAL
                                 : ParkingNeighbour.STATUS_ARRIVED;
            n.confirmed = true;
            n.firstSeenMs = onArrival ? session.startedMs : nowMs;
            if (!onArrival) n.arrivedMs = nowMs;
            store.upsertNeighbour(n);
        }
    }

    public void onEntryAdded(DetectionBaseline.EntrySnapshot e, String source, long nowMs) {
        if (session == null || e == null || !isVehicleLike(e.classGroup)) return;
        String key = keyFor(e);
        if (store.findNeighbourByKey(session.sessionId, key) != null) return;
        ParkingNeighbour n = baseRow(e, key, nowMs);
        if ("lighting".equals(source)) {
            // Newly visible at dawn/dusk — was almost certainly there already.
            n.status = ParkingNeighbour.STATUS_PRESENT_ON_ARRIVAL;
            n.confirmed = true;
            n.firstSeenMs = session.startedMs;
        } else {
            // event_end / promote: watched arriving. Unconfirmed until the
            // baseline sees it again (3 hits), mirroring the detector's own trust.
            n.status = ParkingNeighbour.STATUS_ARRIVED;
            n.confirmed = e.confirmed;
            n.firstSeenMs = nowMs;
            n.arrivedMs = nowMs;
            pendingArrivals.put(key, nowMs);
        }
        store.upsertNeighbour(n);
    }

    public void onEntryConfirmed(DetectionBaseline.EntrySnapshot e, long nowMs) {
        if (session == null || e == null) return;
        ParkingNeighbour n = store.findNeighbourByKey(session.sessionId, keyFor(e));
        if (n == null) return;
        n.confirmed = true;
        n.lastSeenMs = nowMs;
        n.cx = e.cx; n.cy = e.cy; n.w = e.w; n.h = e.h;
        n.updatedMs = nowMs;
        store.upsertNeighbour(n);
    }

    public void onEntryRetired(DetectionBaseline.EntrySnapshot e, String reason, long nowMs) {
        if (session == null || e == null) return;
        if (!"expired".equals(reason)) return;   // reseed/reset are lifecycle, not departures
        ParkingNeighbour n = store.findNeighbourByKey(session.sessionId, keyFor(e));
        if (n == null || n.departedMs > 0) return;
        // Not seen for the baseline's expiry window: it left at some point
        // after lastSeen; we cannot say when.
        n.status = ParkingNeighbour.STATUS_LEFT_UNKNOWN;
        n.lastSeenMs = e.lastSeenMs;
        n.updatedMs = nowMs;
        store.upsertNeighbour(n);
    }

    // ==================== EVENT SIGNALS ====================

    /**
     * Correlate a finalized event's actors with the neighbour rows.
     *
     * @return neighbour keys (existing or new close-pass rows) that gained actor
     *         links in this pass, so best-frame candidates can be attached.
     */
    public List<Attachment> onEventFinalized(File mp4, List<Actor> actors, long segmentStartMs,
                                             boolean finalSegment, long nowMs) {
        List<Attachment> out = new ArrayList<>();
        if (session == null) return out;
        String eventName = mp4 != null ? mp4.getName() : null;

        // 1. Stamp the event name on arrivals created by this event's end.
        if (eventName != null && !pendingArrivals.isEmpty()) {
            List<String> done = new ArrayList<>();
            for (Map.Entry<String, Long> p : pendingArrivals.entrySet()) {
                if (nowMs - p.getValue() > PENDING_ARRIVAL_ATTACH_MS) { done.add(p.getKey()); continue; }
                ParkingNeighbour n = store.findNeighbourByKey(session.sessionId, p.getKey());
                if (n != null && n.arrivalEvent == null) {
                    n.arrivalEvent = eventName;
                    n.updatedMs = nowMs;
                    // Link the moving vehicle actors of this event to the arrival
                    // so their best frames land on this neighbour.
                    List<Long> ids = movingActorIdsOnSide(actors, n.side);
                    if (!ids.isEmpty()) {
                        n.actorIds = mergeIds(n.actorIds, ids);
                        out.add(new Attachment(n.neighbourKey, ids));
                    }
                    store.upsertNeighbour(n);
                }
                done.add(p.getKey());
            }
            for (String k : done) pendingArrivals.remove(k);
        }

        if (actors == null || actors.isEmpty()) return out;
        List<ParkingNeighbour> rows = store.listNeighbours(session.sessionId);

        for (Actor a : actors) {
            if (a == null || a.isStaticForTimeline) continue;
            boolean vehicleLike = a.classGroup == Actor.ClassGroup.VEHICLE
                    || a.classGroup == Actor.ClassGroup.BIKE;
            int side = a.peakCamera >= 0 && a.peakCamera < 4 ? a.peakCamera : a.lastCamera;
            if (side < 0 || side > 3) continue;

            // 2. Departure: a mover on a known neighbour's spot — unless the
            //    mover IS that neighbour arriving (same event, already linked,
            //    or the row's arrival is not older than the actor itself).
            if (vehicleLike) {
                ParkingNeighbour match = matchDeparture(rows, a, side);
                if (match != null && isOwnArrival(match, a, eventName)) continue;
                if (match != null) {
                    long departedAt = a.firstSeenWallMs > 0 ? a.firstSeenWallMs
                            : (segmentStartMs > 0 ? segmentStartMs : nowMs);
                    match.status = ParkingNeighbour.STATUS_DEPARTED;
                    match.departedMs = departedAt;
                    match.departureEvent = eventName;
                    match.lastSeenMs = departedAt;
                    List<Long> ids = new ArrayList<>();
                    ids.add(a.actorId);
                    match.actorIds = mergeIds(match.actorIds, ids);
                    match.updatedMs = nowMs;
                    store.upsertNeighbour(match);
                    out.add(new Attachment(match.neighbourKey, ids));
                    continue;
                }
            }

            // 3. Close pass: anything that came close without parking.
            boolean close = a.peakProximity == Actor.Proximity.VERY_CLOSE
                    || a.peakProximity == Actor.Proximity.CLOSE;
            if (!close) continue;
            if (a.classGroup == Actor.ClassGroup.PERSON && !a.confirmed) continue;
            String key = "a" + a.actorId;
            if (store.findNeighbourByKey(session.sessionId, key) != null) continue;
            ParkingNeighbour p = new ParkingNeighbour();
            p.sessionId = session.sessionId;
            p.neighbourKey = key;
            p.side = side;
            p.kind = ParkingNeighbour.KIND_CLOSE_PASS;
            p.classGroup = a.classGroup.name();
            p.status = ParkingNeighbour.STATUS_PASSED;
            p.confirmed = a.confirmed;
            p.firstSeenMs = a.firstSeenWallMs > 0 ? a.firstSeenWallMs : nowMs;
            p.lastSeenMs = a.lastSeenWallMs > 0 ? a.lastSeenWallMs : p.firstSeenMs;
            p.proximity = a.peakProximity.name();
            if (a.peakBboxQuadW > 0 && a.peakBboxQuadH > 0) {
                p.cx = (a.peakBboxX + a.peakBboxW / 2f) / a.peakBboxQuadW;
                p.cy = (a.peakBboxY + a.peakBboxH / 2f) / a.peakBboxQuadH;
                p.w = (float) a.peakBboxW / a.peakBboxQuadW;
                p.h = (float) a.peakBboxH / a.peakBboxQuadH;
            }
            p.arrivalEvent = eventName;
            p.actorIds = Long.toString(a.actorId);
            p.updatedMs = nowMs;
            store.upsertNeighbour(p);
            List<Long> ids = new ArrayList<>();
            ids.add(a.actorId);
            out.add(new Attachment(key, ids));
        }
        return out;
    }

    /** Session closed: whatever was never seen leaving is still there. */
    public void endSession(ParkingSession s, long endMs) {
        if (s == null) return;
        finalizeRows(store, s, endMs);
        pendingArrivals.clear();
        session = null;
    }

    /**
     * Store-only part of {@link #endSession}: relabel the session's arrivals
     * that were never seen leaving as STILL_THERE. Usable for a leftover row of
     * a previous daemon run without touching this observer's live session.
     */
    static void finalizeRows(ParkingStore store, ParkingSession s, long endMs) {
        if (store == null || s == null) return;
        for (ParkingNeighbour n : store.listNeighbours(s.sessionId)) {
            if (!ParkingNeighbour.KIND_NEIGHBOUR.equals(n.kind)) continue;
            if (n.departedMs > 0) continue;
            if (ParkingNeighbour.STATUS_LEFT_UNKNOWN.equals(n.status)) continue;
            if (ParkingNeighbour.STATUS_PRESENT_ON_ARRIVAL.equals(n.status)
                    || ParkingNeighbour.STATUS_ARRIVED.equals(n.status)) {
                n.status = ParkingNeighbour.STATUS_STILL_THERE;
                n.lastSeenMs = endMs;
                n.updatedMs = endMs;
                store.upsertNeighbour(n);
            }
        }
    }

    // ==================== MATCHING ====================

    /** Visible for tests. */
    static ParkingNeighbour matchDeparture(List<ParkingNeighbour> rows, Actor a, int side) {
        if (rows == null || a == null || a.peakBboxQuadW <= 0 || a.peakBboxQuadH <= 0) return null;
        float acx = (a.peakBboxX + a.peakBboxW / 2f) / a.peakBboxQuadW;
        float acy = (a.peakBboxY + a.peakBboxH / 2f) / a.peakBboxQuadH;
        float aw = (float) a.peakBboxW / a.peakBboxQuadW;
        float ah = (float) a.peakBboxH / a.peakBboxQuadH;
        float afootY = acy + ah / 2f;
        ParkingNeighbour best = null;
        float bestDist = Float.MAX_VALUE;
        for (ParkingNeighbour n : rows) {
            if (n == null || !ParkingNeighbour.KIND_NEIGHBOUR.equals(n.kind)) continue;
            if (n.side != side || n.departedMs > 0) continue;
            if (!(ParkingNeighbour.STATUS_PRESENT_ON_ARRIVAL.equals(n.status)
                    || ParkingNeighbour.STATUS_ARRIVED.equals(n.status))) continue;
            if (n.w <= 0f || n.h <= 0f) continue;
            float nfootY = n.cy + n.h / 2f;
            float dx = acx - n.cx;
            float dy = afootY - nfootY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float iou = iou(acx, acy, aw, ah, n.cx, n.cy, n.w, n.h);
            if (dist <= DEPARTURE_FOOT_DIST_NORM || iou >= DEPARTURE_IOU_MIN) {
                if (dist < bestDist) { bestDist = dist; best = n; }
            }
        }
        return best;
    }

    static float iou(float cx1, float cy1, float w1, float h1,
                     float cx2, float cy2, float w2, float h2) {
        float l1 = cx1 - w1 / 2, r1 = cx1 + w1 / 2, t1 = cy1 - h1 / 2, b1 = cy1 + h1 / 2;
        float l2 = cx2 - w2 / 2, r2 = cx2 + w2 / 2, t2 = cy2 - h2 / 2, b2 = cy2 + h2 / 2;
        float il = Math.max(l1, l2), ir = Math.min(r1, r2), it = Math.max(t1, t2), ib = Math.min(b1, b2);
        if (ir <= il || ib <= it) return 0f;
        float inter = (ir - il) * (ib - it);
        float union = w1 * h1 + w2 * h2 - inter;
        return union > 0 ? inter / union : 0f;
    }

    private static List<Long> movingActorIdsOnSide(List<Actor> actors, int side) {
        List<Long> ids = new ArrayList<>();
        if (actors == null) return ids;
        for (Actor a : actors) {
            if (a == null || a.isStaticForTimeline) continue;
            if (a.classGroup != Actor.ClassGroup.VEHICLE && a.classGroup != Actor.ClassGroup.BIKE) continue;
            if ((a.cameraMask & (1 << side)) == 0 && a.lastCamera != side && a.peakCamera != side) continue;
            ids.add(a.actorId);
        }
        return ids;
    }

    /** True when {@code a} is the vehicle that created neighbour row {@code n}, not one leaving it. */
    static boolean isOwnArrival(ParkingNeighbour n, Actor a, String eventName) {
        if (n == null || a == null) return false;
        if (eventName != null && eventName.equals(n.arrivalEvent)) return true;
        if (n.actorIds != null) {
            for (String s : n.actorIds.split(",")) {
                if (s.equals(Long.toString(a.actorId))) return true;
            }
        }
        // The row's arrival was stamped when the baseline promoted the parked
        // car (event end); a departing vehicle must have arrived BEFORE the
        // mover first appeared.
        return n.arrivedMs > 0 && a.firstSeenWallMs > 0 && n.arrivedMs >= a.firstSeenWallMs - 1000L;
    }

    static String mergeIds(String existing, List<Long> add) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (existing != null) for (String s : existing.split(",")) if (!s.isEmpty()) set.add(s);
        if (add != null) for (Long id : add) set.add(Long.toString(id));
        StringBuilder sb = new StringBuilder();
        for (String s : set) { if (sb.length() > 0) sb.append(','); sb.append(s); }
        return sb.toString();
    }

    static boolean isVehicleLike(String group) {
        return "VEHICLE".equals(group) || "BIKE".equals(group);
    }

    static String keyFor(DetectionBaseline.EntrySnapshot e) {
        return "q" + e.quadrant + ":e" + e.entryId;
    }

    private ParkingNeighbour baseRow(DetectionBaseline.EntrySnapshot e, String key, long nowMs) {
        ParkingNeighbour n = new ParkingNeighbour();
        n.sessionId = session.sessionId;
        n.neighbourKey = key;
        n.side = e.quadrant;
        n.kind = ParkingNeighbour.KIND_NEIGHBOUR;
        n.classGroup = e.classGroup;
        n.cx = e.cx; n.cy = e.cy; n.w = e.w; n.h = e.h;
        n.lastSeenMs = nowMs;
        n.updatedMs = nowMs;
        return n;
    }
}
