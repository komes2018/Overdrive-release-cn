package com.overdrive.app.telenav;

import android.content.Context;
import android.content.Intent;

import com.telenav.app.external.constants.FavoriteType;
import com.telenav.app.external.model.search.Address;
import com.telenav.app.external.model.search.Place;

/**
 * High-level Telenav actions shared by both clients: the on-car RoadSense map
 * (calls these directly, in-process) and the phone via {@link TelenavIpcServer}.
 * One place that maps a plain (name, lat, lng) into a Telenav {@link Place} and
 * invokes the bridge.
 */
public final class TelenavActions {

    private static final long TIMEOUT_MS = 20_000L;

    private static final String TELENAV_PKG = "com.telenav.app.arp";
    private static final String TELENAV_MAP_ACTIVITY = "com.telenav.arp.module.map.MainActivity";
    /** Let Telenav come to the front and its nav service settle before we start guidance. */
    private static final long FOREGROUND_SETTLE_MS = 1200L;

    private TelenavActions() {}

    public static boolean isAvailable(Context ctx) {
        return TelenavClient.isAvailable(ctx);
    }

    public static void validateCoordinates(double lat, double lng) {
        if (Double.isNaN(lat) || Double.isInfinite(lat) || lat < -90.0 || lat > 90.0
                || Double.isNaN(lng) || Double.isInfinite(lng) || lng < -180.0 || lng > 180.0) {
            throw new IllegalArgumentException("invalid latitude/longitude");
        }
    }

    /**
     * Bring Telenav's map to the foreground. Verified live 2026-08-23: Telenav only
     * engages turn-by-turn guidance while it is the foreground app — {@code startNavigation}
     * is accepted and returns success even when Telenav is backgrounded, but nothing
     * is shown. Idempotent (a running task is brought to front, not restarted).
     *
     * <p>This uses {@code startActivity}, so the CALLER MUST be in the foreground (the
     * on-car map is). A backgrounded process is subject to Android's background-activity
     * -launch limits and this silently no-ops; the phone/endpoint path foregrounds from
     * the daemon (UID 2000, {@code am start}) instead — see {@code TelenavDebugApiHandler}.
     */
    public static void foregroundTelenav(Context ctx) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setClassName(TELENAV_PKG, TELENAV_MAP_ACTIVITY);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            ctx.startActivity(i);
            Thread.sleep(FOREGROUND_SETTLE_MS);
        } catch (Exception ignore) {
            // Best effort: if we can't foreground, still attempt the nav command below.
        }
    }

    /** Telenav rejects a null placeId; we store by coordinates, so a synthetic id is fine. */
    public static Place buildPlace(
            String name, double lat, double lng, String favoriteType,
            String placeId, String formattedAddress) {
        validateCoordinates(lat, lng);
        String safeName = (name == null || name.trim().isEmpty()) ? "分享的位置" : name.trim();
        String pid = (placeId == null || placeId.trim().isEmpty())
                ? "OD-" + lat + "_" + lng : placeId.trim();
        String addr = (formattedAddress == null || formattedAddress.trim().isEmpty())
                ? safeName : formattedAddress.trim();
        String type = FavoriteType.Normal.equals(favoriteType) ? favoriteType : FavoriteType.Normal;

        Place p = new Place();
        p.setPlaceId(pid);
        p.setSearchSourceType("ON_BOARD");
        p.setPlaceName(safeName);
        p.setPlaceDisplayLabel(safeName);
        p.setPlaceType("ADDRESS");
        p.setFavoriteType(type);
        p.setGeoLatitude(lat);
        p.setGeoLongitude(lng);
        p.setNavLatitude(lat);
        p.setNavLongitude(lng);
        Address a = new Address();
        a.setFormattedAddress(addr);
        a.setFullAddress(addr);
        p.setAddress(a);
        return p;
    }

    /** Save a place to the favourites (default type Normal = the heart list). Blocking. */
    public static void addFavorite(Context ctx, String name, double lat, double lng, String favoriteType)
            throws Exception {
        String type = FavoriteType.Normal;
        TelenavClient.addFavorite(ctx, TIMEOUT_MS, type, buildPlace(name, lat, lng, type, null, null));
    }

    /**
     * Start turn-by-turn navigation to a place. Foregrounds Telenav first (guidance
     * only engages while Telenav is on screen). Blocking; returns Telenav's result.
     */
    public static boolean navigate(Context ctx, String name, double lat, double lng) throws Exception {
        return navigate(ctx, name, lat, lng, false);
    }

    /**
     * Start navigation to a place, choosing how it combines with any active route.
     *
     * <p>Telenav's external {@code startNavigation(Place)} takes no mode: called while a
     * route is active it ADDS the place as an intermediate waypoint; called idle it starts
     * fresh. To force a fresh single-destination route ({@code replace = true}) we stop the
     * active nav first, then start — verified live 2026-08-23.
     *
     * @param replace {@code true} = fresh route (stopNav + start); {@code false} = add as a
     *                stop when navigating, or start fresh when idle (Telenav's own default).
     */
    public static boolean navigate(Context ctx, String name, double lat, double lng, boolean replace)
            throws Exception {
        foregroundTelenav(ctx);
        if (replace) {
            try { TelenavClient.stopNav(ctx, TIMEOUT_MS); } catch (Exception ignore) {}
            try { Thread.sleep(1200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        return TelenavClient.startNavigation(
                ctx, TIMEOUT_MS, buildPlace(name, lat, lng, FavoriteType.Normal, null, null));
    }
}
