package com.overdrive.app.parking;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A vehicle (or other actor) that shared the parking bay's surroundings during
 * a session: either a parked NEIGHBOUR derived from the detection baseline, or
 * a CLOSE_PASS — a mover that came close without parking.
 */
public final class ParkingNeighbour {

    public static final String KIND_NEIGHBOUR = "NEIGHBOUR";
    public static final String KIND_CLOSE_PASS = "CLOSE_PASS";

    public static final String STATUS_PRESENT_ON_ARRIVAL = "PRESENT_ON_ARRIVAL";
    public static final String STATUS_ARRIVED = "ARRIVED";
    public static final String STATUS_DEPARTED = "DEPARTED";
    public static final String STATUS_STILL_THERE = "STILL_THERE";
    public static final String STATUS_LEFT_UNKNOWN = "LEFT_UNKNOWN";
    public static final String STATUS_PASSED = "PASSED";

    public static final String[] SIDE_NAMES = {"front", "right", "rear", "left"};

    public long id;                 // store identity (0 = not yet persisted)
    public String sessionId;
    public String neighbourKey;     // "q<quadrant>:e<entryId>" for baseline-derived rows, "a<actorId>" for passes
    public int side;                // 0=front,1=right,2=rear,3=left
    public String kind = KIND_NEIGHBOUR;
    public String classGroup;       // VEHICLE / BIKE / PERSON / ANIMAL
    public String status;
    public boolean confirmed;
    public long firstSeenMs;
    public long arrivedMs;
    public long departedMs;
    public long lastSeenMs;
    public float cx, cy, w, h;      // quadrant-normalised bbox (centre + size)
    public String proximity;        // for passes: VERY_CLOSE / CLOSE
    public String arrivalEvent;     // mp4 filename
    public String departureEvent;   // mp4 filename
    public String actorIds;         // CSV of engine actor ids linked to this row
    public String framesJson;       // [{"name":..,"score":..,"ms":..}]
    public long updatedMs;

    public static String sideName(int side) {
        return side >= 0 && side < SIDE_NAMES.length ? SIDE_NAMES[side] : "unknown";
    }

    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("id", id);
            j.put("sessionId", sessionId);
            j.put("key", neighbourKey);
            j.put("side", side);
            j.put("sideName", sideName(side));
            j.put("kind", kind);
            if (classGroup != null) j.put("classGroup", classGroup);
            if (status != null) j.put("status", status);
            j.put("confirmed", confirmed);
            if (firstSeenMs > 0) j.put("firstSeenMs", firstSeenMs);
            if (arrivedMs > 0) j.put("arrivedMs", arrivedMs);
            if (departedMs > 0) j.put("departedMs", departedMs);
            if (lastSeenMs > 0) j.put("lastSeenMs", lastSeenMs);
            JSONObject box = new JSONObject();
            box.put("cx", cx); box.put("cy", cy); box.put("w", w); box.put("h", h);
            j.put("bbox", box);
            if (proximity != null) j.put("proximity", proximity);
            if (arrivalEvent != null) j.put("arrivalEvent", arrivalEvent);
            if (departureEvent != null) j.put("departureEvent", departureEvent);
            if (actorIds != null && !actorIds.isEmpty()) j.put("actorIds", actorIds);
            JSONArray frames = new JSONArray();
            if (framesJson != null && !framesJson.isEmpty()) {
                try { frames = new JSONArray(framesJson); } catch (Exception ignored) {}
            }
            j.put("frames", frames);
            j.put("updatedMs", updatedMs);
        } catch (Exception ignored) {}
        return j;
    }
}
