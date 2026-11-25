package com.overdrive.app.byd;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The closed set of LOCAL rails that may feed the DiLink 5 proximity lock gate. Exactly three
 * members: cloud, the daemon CarPropertyBridge and every derived/merged value are structurally
 * excluded because the gate's ledger accepts nothing but this enum.
 *
 * <p>Tags are the persisted {@code di5LockRails} setting vocabulary and the bridge
 * {@code lockReadings[].rail} vocabulary; they are case-sensitive and never change.
 */
public enum DiLink5LockRail {
    /** App producer, typed cabin SDK ({@code BYDAutoDoorLockDevice.getDoorLockStatus(1)}). */
    TYPED_CABIN("typed-cabin"),
    /** App producer, OTA SDK ({@code BYDAutoOtaDevice.getLFDoorLockState}), debounced. */
    OTA("ota"),
    /** Daemon, {@code dumpsys car_service} LF lock property ({@code 0x21404627}). */
    CARSVC("carsvc");

    private final String tag;

    DiLink5LockRail(String tag) {
        this.tag = tag;
    }

    /** Persisted / bridge tag: {@code typed-cabin}, {@code ota} or {@code carsvc}. */
    public String tag() {
        return tag;
    }

    /** The rail for an exact tag, or null for anything else (case-sensitive, no trimming). */
    public static DiLink5LockRail fromTag(String tag) {
        if (tag == null) return null;
        for (DiLink5LockRail rail : values()) {
            if (rail.tag.equals(tag)) return rail;
        }
        return null;
    }

    /**
     * Parses a comma-separated tag list into a canonical unmodifiable set (enum order). Tokens are
     * trimmed. Returns null on any invalid token, an empty list, a duplicate or more than three
     * entries, so callers fall back explicitly instead of enabling a partial set.
     */
    public static Set<DiLink5LockRail> parseTags(String list) {
        if (list == null) return null;
        String[] tokens = list.split(",", -1);
        if (tokens.length == 0 || tokens.length > values().length) return null;
        EnumSet<DiLink5LockRail> result = EnumSet.noneOf(DiLink5LockRail.class);
        for (String token : tokens) {
            DiLink5LockRail rail = fromTag(token.trim());
            if (rail == null || !result.add(rail)) return null;
        }
        return Collections.unmodifiableSet(result);
    }

    /** Canonical comma list in enum order ({@code typed-cabin,ota,carsvc}); "" for an empty set. */
    public static String toTags(Set<DiLink5LockRail> rails) {
        if (rails == null || rails.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (DiLink5LockRail rail : values()) {
            if (!rails.contains(rail)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(rail.tag);
        }
        return sb.toString();
    }
}
