package com.overdrive.app.remoteaccess;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Pairing authorization at the settings boundary, independent of radio availability. */
public final class RemoteAccessPhoneSavePolicy {
    public interface PairingReader {
        JSONObject read() throws Exception;
    }

    private RemoteAccessPhoneSavePolicy() {}

    /**
     * Validated settings retain already enabled phone identities across unrelated
     * rule edits. First enabling monitoring or enabling a new identity requires a
     * live paired-device check. Returns null when no device lookup was necessary.
     */
    public static JSONArray validateRequiredPairings(JSONObject current, JSONObject next,
                                                      PairingReader reader) throws Exception {
        if (!next.optBoolean("enabled", false)) return null;
        Set<String> required = enabledAddresses(next);
        if (current.optBoolean("enabled", false)) required.removeAll(enabledAddresses(current));
        if (required.isEmpty()) return null;

        JSONObject available = reader.read();
        if (available == null || !available.optBoolean("success", false))
            throw new IllegalArgumentException("Open the car app before enabling monitoring");
        JSONArray devices = available.optJSONArray("devices");
        Set<String> paired = new LinkedHashSet<>();
        if (devices != null) for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.optJSONObject(i);
            if (device != null) paired.add(normalize(device.optString("address", "")));
        }
        if (!paired.containsAll(required))
            throw new IllegalArgumentException("Every enabled trusted phone must be paired with the car");
        return devices;
    }

    private static Set<String> enabledAddresses(JSONObject settings) {
        Set<String> enabled = new LinkedHashSet<>();
        JSONArray phones = settings.optJSONArray("phones");
        if (phones != null) for (int i = 0; i < phones.length(); i++) {
            JSONObject phone = phones.optJSONObject(i);
            if (phone != null && phone.optBoolean("enabled", false))
                enabled.add(normalize(phone.optString("address", "")));
        }
        return enabled;
    }

    private static String normalize(String address) {
        return address.trim().toUpperCase(Locale.US);
    }
}
