package com.overdrive.app.surveillance;

import org.json.JSONObject;
import java.util.UUID;

/**
 * Safe Location — a circular geofence zone where surveillance is suppressed.
 * When the car is parked inside any enabled safe zone, the camera pipeline
 * never starts, saving 100% of camera/GPU/encoder resources.
 */
public class SafeLocation {

    private String id;
    private String name;
    private double latitude;
    private double longitude;
    // Persist only invalid provenance. Numeric getters retain their existing
    // surveillance contract, but a parser's default zero cannot authorize a lock.
    private boolean latitudeExplicit, longitudeExplicit;
    private static final String LATITUDE_VALID = "proximityLatitudeValid";
    private static final String LONGITUDE_VALID = "proximityLongitudeValid";
    private int radiusMeters;  // 15-500m
    private boolean enabled;
    private long createdAt;

    public SafeLocation(String name, double latitude, double longitude, int radiusMeters) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.latitudeExplicit = validCoordinate(latitude, -90, 90);
        this.longitudeExplicit = validCoordinate(longitude, -180, 180);
        this.radiusMeters = Math.max(15, Math.min(500, radiusMeters));
        this.enabled = true;
        this.createdAt = System.currentTimeMillis();
    }

    /** Deserialize from JSON */
    public SafeLocation(JSONObject json) {
        this.id = json.optString("id", UUID.randomUUID().toString().substring(0, 8));
        this.name = json.optString("name", "Unnamed");
        this.latitude = json.optDouble("lat", 0.0);
        this.longitude = json.optDouble("lng", 0.0);
        this.latitudeExplicit = explicitCoordinate(json, "lat", LATITUDE_VALID, -90, 90);
        this.longitudeExplicit = explicitCoordinate(json, "lng", LONGITUDE_VALID, -180, 180);
        // Clamp on load too (not just the value ctor / setter): the config file is
        // world-writable, so an out-of-range radiusM (e.g. 1 = unreachable, or a huge
        // earth-covering value = permanent suppression) could otherwise be honored.
        this.radiusMeters = Math.max(15, Math.min(500, json.optInt("radiusM", 150)));
        this.enabled = json.optBoolean("enabled", true);
        this.createdAt = json.optLong("createdAt", System.currentTimeMillis());
    }

    /** Original API/IPC input, preserving new-zone identity and legacy parsing. */
    public static SafeLocation fromNewZoneInput(JSONObject json) {
        SafeLocation zone = new SafeLocation(json.optString("name", "Unnamed"),
                json.optDouble("lat", 0), json.optDouble("lng", 0), json.optInt("radiusM", 150));
        zone.latitudeExplicit = explicitCoordinate(json, "lat", LATITUDE_VALID, -90, 90);
        zone.longitudeExplicit = explicitCoordinate(json, "lng", LONGITUDE_VALID, -180, 180);
        return zone;
    }

    /** Serialize to JSON */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("lat", finite(latitude) ? latitude : 0.0);
            json.put("lng", finite(longitude) ? longitude : 0.0);
            if (!latitudeExplicit) json.put(LATITUDE_VALID, false);
            if (!longitudeExplicit) json.put(LONGITUDE_VALID, false);
            json.put("radiusM", radiusMeters);
            json.put("enabled", enabled);
            json.put("createdAt", createdAt);
        } catch (Exception ignored) {}
        return json;
    }

    // Getters
    public String getId() { return id; }
    public String getName() { return name; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public int getRadiusMeters() { return radiusMeters; }
    public boolean isEnabled() { return enabled; }
    public long getCreatedAt() { return createdAt; }
    public boolean hasValidProximityGeometry() {
        return latitudeExplicit && longitudeExplicit
                && validCoordinate(latitude, -90, 90) && validCoordinate(longitude, -180, 180)
                && (latitude != 0 || longitude != 0);
    }

    // Setters
    public void setName(String name) { this.name = name; }
    public void setLatitude(double lat) {
        this.latitude = lat;
        this.latitudeExplicit = validCoordinate(lat, -90, 90);
    }
    public void setLongitude(double lng) {
        this.longitude = lng;
        this.longitudeExplicit = validCoordinate(lng, -180, 180);
    }
    public void setRadiusMeters(int r) { this.radiusMeters = Math.max(15, Math.min(500, r)); }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** Preserve per-axis provenance on raw partial updates; unrelated edits cannot repair it. */
    void applyCoordinateUpdates(JSONObject updates) {
        if (updates.has("lat")) {
            latitude = updates.optDouble("lat");
            latitudeExplicit = explicitCoordinate(updates, "lat", LATITUDE_VALID, -90, 90);
        }
        if (updates.has("lng")) {
            longitude = updates.optDouble("lng");
            longitudeExplicit = explicitCoordinate(updates, "lng", LONGITUDE_VALID, -180, 180);
        }
    }

    private static boolean explicitCoordinate(JSONObject json, String key, String marker, double min, double max) {
        Object value = json.opt(key);
        if (!(value instanceof Number) || !validCoordinate(((Number) value).doubleValue(), min, max)) return false;
        return !json.has(marker) || Boolean.TRUE.equals(json.opt(marker));
    }

    private static boolean validCoordinate(double value, double min, double max) {
        return finite(value) && value >= min && value <= max;
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
}
