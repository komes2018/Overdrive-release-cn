package com.overdrive.app.parking;

import org.json.JSONObject;

/**
 * Immutable snapshot of the {@code parking} config section.
 *
 * <pre>
 * "parking": {
 *   "enabled": false,          // master switch — off ⇒ the feature registers nothing
 *   "snapshots": true,         // arrived / returned four-camera stills
 *   "neighbours": true,        // neighbour timeline + best frames
 *   "signage": true,           // v2 garage level / zone / bay reading
 *   "retentionDays": 90,       // session rows older than this are pruned
 *   "storageCapMb": 300,       // parking assets (stills, frames) self-cap
 *   "endTrigger": "return"     // when a session closes: "return" (door/unlock/power-on,
 *                              // first wins), "power_on" (only ACC-on), "drive_away"
 *                              // (ACC-on, then the gear leaving P)
 * }
 * </pre>
 *
 * Notification preferences deliberately live elsewhere: web push per-device
 * mutes in the push subscription store and Telegram in
 * {@code telegram.parkingMessages}, so the Notifications page stays the one
 * place that decides delivery.
 */
public final class ParkingConfig {

    public static final String SECTION = "parking";

    /** {@code endTrigger}: close at the first guarded return signal (today's behavior). */
    public static final String END_TRIGGER_RETURN = "return";
    /** {@code endTrigger}: door/unlock only refresh the returned stills; close at ACC-on. */
    public static final String END_TRIGGER_POWER_ON = "power_on";
    /** {@code endTrigger}: like power_on, then wait for the gear to leave P before closing. */
    public static final String END_TRIGGER_DRIVE_AWAY = "drive_away";

    public final boolean enabled;
    public final boolean snapshots;
    public final boolean neighbours;
    public final boolean signage;
    public final int retentionDays;
    public final int storageCapMb;
    /** One of the END_TRIGGER_* values; unknown input normalises to {@link #END_TRIGGER_RETURN}. */
    public final String endTrigger;

    public ParkingConfig(boolean enabled, boolean snapshots, boolean neighbours,
                         boolean signage, int retentionDays, int storageCapMb,
                         String endTrigger) {
        this.enabled = enabled;
        this.snapshots = snapshots;
        this.neighbours = neighbours;
        this.signage = signage;
        this.retentionDays = clamp(retentionDays, 7, 730);
        this.storageCapMb = clamp(storageCapMb, 50, 4096);
        this.endTrigger = normalizeEndTrigger(endTrigger);
    }

    /** Unknown / null / legacy values fall back to the default so behavior never surprises. */
    public static String normalizeEndTrigger(String v) {
        return END_TRIGGER_POWER_ON.equals(v) || END_TRIGGER_DRIVE_AWAY.equals(v)
                ? v : END_TRIGGER_RETURN;
    }

    /** Parse from the merged section object (null-safe, defaults applied). */
    public static ParkingConfig fromSection(JSONObject section) {
        JSONObject s = section == null ? new JSONObject() : section;
        return new ParkingConfig(
                s.optBoolean("enabled", false),
                s.optBoolean("snapshots", true),
                s.optBoolean("neighbours", true),
                s.optBoolean("signage", true),
                s.optInt("retentionDays", 90),
                s.optInt("storageCapMb", 300),
                s.optString("endTrigger", END_TRIGGER_RETURN));
    }

    /** Defaults: everything off (master switch false). */
    public static ParkingConfig disabled() {
        return fromSection(null);
    }

    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("enabled", enabled);
            j.put("snapshots", snapshots);
            j.put("neighbours", neighbours);
            j.put("signage", signage);
            j.put("retentionDays", retentionDays);
            j.put("storageCapMb", storageCapMb);
            j.put("endTrigger", endTrigger);
        } catch (Exception ignored) {}
        return j;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Where the controller reads its config from and how it learns about
     * flips. Abstracted so JVM tests can drive the lifecycle without the
     * Android-backed {@code UnifiedConfigManager}.
     */
    public interface Source {
        /** Fresh read (may hit disk). */
        ParkingConfig read();
        /**
         * Register a callback fired with the merged section whenever the
         * {@code parking} section is written. Implementations MUST call the
         * callback without blocking (the unified config fires listeners inside
         * its cross-process file lock).
         */
        void addListener(java.util.function.Consumer<JSONObject> onSectionChanged);
        void removeListener(java.util.function.Consumer<JSONObject> onSectionChanged);
    }

    /** Production source backed by the unified config. */
    public static final class UnifiedSource implements Source {
        private final java.util.Map<java.util.function.Consumer<JSONObject>,
                com.overdrive.app.config.UnifiedConfigManager.ConfigChangeListener> bound =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public ParkingConfig read() {
            try {
                JSONObject cfg = com.overdrive.app.config.UnifiedConfigManager.forceReload();
                return fromSection(cfg.optJSONObject(SECTION));
            } catch (Throwable t) {
                return disabled();
            }
        }

        @Override
        public void addListener(final java.util.function.Consumer<JSONObject> onSectionChanged) {
            com.overdrive.app.config.UnifiedConfigManager.ConfigChangeListener l =
                    new com.overdrive.app.config.UnifiedConfigManager.ConfigChangeListener() {
                        @Override
                        public void onConfigChanged(String section, JSONObject config) {
                            // Match ONLY our section (not "all"): saveConfig fires
                            // notifyListeners("all") on every write app-wide.
                            if (!SECTION.equals(section)) return;
                            onSectionChanged.accept(config);
                        }
                    };
            bound.put(onSectionChanged, l);
            com.overdrive.app.config.UnifiedConfigManager.addListener(l);
        }

        @Override
        public void removeListener(java.util.function.Consumer<JSONObject> onSectionChanged) {
            com.overdrive.app.config.UnifiedConfigManager.ConfigChangeListener l =
                    bound.remove(onSectionChanged);
            if (l != null) {
                try { com.overdrive.app.config.UnifiedConfigManager.removeListener(l); }
                catch (Throwable ignored) {}
            }
        }
    }

    /** Convenience for handlers: is the master switch on right now. */
    public static boolean isEnabled() {
        try {
            JSONObject cfg = com.overdrive.app.config.UnifiedConfigManager.loadConfig();
            JSONObject s = cfg == null ? null : cfg.optJSONObject(SECTION);
            return s != null && s.optBoolean("enabled", false);
        } catch (Throwable t) {
            return false;
        }
    }
}
