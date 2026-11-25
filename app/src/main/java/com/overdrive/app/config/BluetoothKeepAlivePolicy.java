package com.overdrive.app.config;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;

/** Canonical preference and legacy migration for the shared parked Bluetooth radio. */
public final class BluetoothKeepAlivePolicy {
    public static final String SECTION = "surveillance";
    public static final String KEY = "bluetoothKeepAlive";
    public static final String LEGACY_SECTION = "remoteAccess";
    public static final String LEGACY_KEY = "keepBluetoothOn";

    private BluetoothKeepAlivePolicy() {}

    /**
     * Old proximity installations already opted in to keeping the radio awake.
     * A legacy default on an unused proximity feature does not count as consent.
     */
    public static boolean enabled(JSONObject config) {
        if (config == null) return false;
        JSONObject surveillance = config.optJSONObject(SECTION);
        if (surveillance != null && surveillance.has(KEY)) {
            return Boolean.TRUE.equals(surveillance.opt(KEY));
        }
        JSONObject remoteAccess = config.optJSONObject(LEGACY_SECTION);
        return remoteAccess != null
                && Boolean.TRUE.equals(remoteAccess.opt("enabled"))
                && (!remoteAccess.has(LEGACY_KEY)
                    || Boolean.TRUE.equals(remoteAccess.opt(LEGACY_KEY)));
    }

    /** Defaults and imported backups use the canonical key when it is present. */
    public static void ensureCanonical(JSONObject config) throws JSONException {
        boolean enabled = enabled(config);
        JSONObject surveillance = config.optJSONObject(SECTION);
        if (surveillance == null) {
            surveillance = new JSONObject();
            config.put(SECTION, surveillance);
        }
        surveillance.put(KEY, enabled);
        JSONObject remoteAccess = config.optJSONObject(LEGACY_SECTION);
        if (remoteAccess != null) remoteAccess.put(LEGACY_KEY, enabled);
    }

    /** Reject coercible strings/numbers before any of a section write is committed. */
    public static boolean validPatch(String section, JSONObject patch) {
        String key = SECTION.equals(section) ? KEY
                : LEGACY_SECTION.equals(section) ? LEGACY_KEY : null;
        return key == null || patch == null || !patch.has(key)
                || patch.opt(key) instanceof Boolean;
    }

    /**
     * Called within the existing atomic config mutation. Only keys actually accepted
     * by the mutation sequence guard can alter the canonical preference.
     */
    public static void synchronizeSectionWrite(JSONObject config, String section,
                                               Collection<String> appliedKeys)
            throws JSONException {
        String key = SECTION.equals(section) ? KEY
                : LEGACY_SECTION.equals(section) ? LEGACY_KEY : null;
        if (key != null && appliedKeys.contains(key)) {
            JSONObject source = config.optJSONObject(section);
            JSONObject surveillance = config.optJSONObject(SECTION);
            if (surveillance == null) {
                surveillance = new JSONObject();
                config.put(SECTION, surveillance);
            }
            surveillance.put(KEY, source != null
                    && Boolean.TRUE.equals(source.opt(key)));
        }
        ensureCanonical(config);
    }
}
