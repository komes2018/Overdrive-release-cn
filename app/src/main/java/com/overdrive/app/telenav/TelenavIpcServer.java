package com.overdrive.app.telenav;

import android.content.Context;
import android.util.Log;

import com.telenav.app.external.constants.FavoriteType;
import com.telenav.app.external.model.search.Address;
import com.telenav.app.external.model.search.Place;
import com.telenav.app.external.model.userservice.UserDataResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Localhost IPC listener that runs in the APP process and binds Telenav's OEM
 * AIDL on behalf of the daemon. The daemon's HTTP server ({@code :8080}) cannot
 * {@code bindService} itself — its synthetic {@code ActivityThread} isn't an
 * AMS-registered app process ("Unable to find app for caller … when binding
 * service") — so {@link TelenavDebugApiHandler} forwards Telenav requests here
 * over {@code 127.0.0.1:19882}, mirroring the app→daemon {@code DaemonIpcClient}
 * socket in reverse.
 *
 * <p>Protocol: one line of JSON request in, one line of JSON response out.
 * Started from {@code OverdriveApplication.onCreate} (main app process).
 */
public final class TelenavIpcServer {

    private static final String TAG = "TelenavIpc";
    public static final int PORT = 19882;

    // Query every bucket: null / "" (unfiltered) plus each named FavoriteType.
    private static final String[] TYPES = {
            null, "", "Home", "Work", "Normal", "School", "Gym", "Daycare", "Custom",
    };

    private static volatile boolean started = false;
    private static Context appCtx;

    private TelenavIpcServer() {}

    public static synchronized void start(Context ctx) {
        if (started) return;
        appCtx = ctx.getApplicationContext();
        if (!TelenavActions.isAvailable(appCtx)) {
            Log.i(TAG, "Telenav navigation service unavailable; IPC disabled");
            return;
        }
        Thread t = new Thread(TelenavIpcServer::serve, "telenav-ipc");
        t.setDaemon(true);
        t.start();
        started = true;
    }

    private static void serve() {
        ServerSocket server = null;
        try {
            server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
            Log.i(TAG, "listening on 127.0.0.1:" + PORT);
            while (true) {
                Socket client = server.accept();
                handle(client);
            }
        } catch (Exception e) {
            // Most likely another process already bound the port (multi-process); harmless.
            Log.w(TAG, "server not running: " + e.getMessage());
        } finally {
            if (server != null) try { server.close(); } catch (Exception ignore) {}
        }
    }

    private static void handle(Socket socket) {
        try {
            socket.setSoTimeout(30_000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            String line = reader.readLine();
            JSONObject resp;
            try {
                JSONObject req = (line == null || line.isEmpty())
                        ? new JSONObject() : new JSONObject(line);
                resp = dispatch(req);
            } catch (Exception e) {
                resp = new JSONObject();
                try {
                    resp.put("success", false);
                    resp.put("error", String.valueOf(e.getMessage()));
                } catch (JSONException ignore) {}
            }
            writer.println(resp.toString());
        } catch (Exception e) {
            Log.e(TAG, "handle failed: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (Exception ignore) {}
        }
    }

    private static JSONObject dispatch(JSONObject req) throws Exception {
        String op = req.optString("op", "");
        if ("getFavorites".equals(op)) {
            return getFavorites();
        }
        if ("addFavorite".equals(op)) {
            return addFavorite(req);
        }
        if ("navigate".equals(op)) {
            return navigate(req);
        }
        if ("navState".equals(op)) {
            JSONObject o = new JSONObject();
            if (appCtx == null) { o.put("success", false); o.put("error", "no app context"); return o; }
            o.put("success", true);
            o.put("navState", TelenavClient.getNavState(appCtx, 20_000));
            return o;
        }
        if ("stopNav".equals(op)) {
            JSONObject o = new JSONObject();
            if (appCtx == null) { o.put("success", false); o.put("error", "no app context"); return o; }
            o.put("success", TelenavClient.stopNav(appCtx, 20_000));
            return o;
        }
        if ("showNavPrompt".equals(op)) {
            // Deferred navigate: the daemon queued this while the car was off and, on
            // ACC-on, asks us (app process) to draw the floating prompt. Must run on the
            // main thread — WindowManager overlay + Telenav bind live here.
            JSONObject o = new JSONObject();
            if (appCtx == null) { o.put("success", false); o.put("error", "no app context"); return o; }
            final String name = req.optString("name", "分享的位置");
            final double lat = req.getDouble("lat");
            final double lng = req.getDouble("lng");
            TelenavActions.validateCoordinates(lat, lng);
            FutureTask<Boolean> show = new FutureTask<>(
                    () -> NavPromptOverlay.show(appCtx, name, lat, lng));
            if (!new android.os.Handler(android.os.Looper.getMainLooper()).post(show)) {
                o.put("success", false);
                o.put("error", "main thread unavailable");
                return o;
            }
            boolean shown = show.get(5, TimeUnit.SECONDS);
            o.put("success", shown);
            if (!shown) o.put("error", "navigation prompt unavailable");
            return o;
        }
        JSONObject o = new JSONObject();
        o.put("success", false);
        o.put("error", "unknown op: " + op);
        return o;
    }

    /** Build a Telenav Place from a request. Coordinates are required; placeId must be non-null. */
    private static Place buildPlace(JSONObject req) throws JSONException {
        return TelenavActions.buildPlace(
                req.optString("name", ""),
                req.getDouble("lat"),
                req.getDouble("lng"),
                FavoriteType.Normal,
                req.optString("placeId", null),
                req.optString("formattedAddress", null));
    }

    private static JSONObject navigate(JSONObject req) throws Exception {
        JSONObject o = new JSONObject();
        if (appCtx == null) {
            o.put("success", false);
            o.put("error", "no app context");
            return o;
        }
        Place place = buildPlace(req);
        if (req.optBoolean("replace", false)) {
            // Force a fresh route (REPLACE): stop any active nav first, then start.
            try { TelenavClient.stopNav(appCtx, 20_000); } catch (Exception ignore) {}
            try {
                Thread.sleep(1200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
        boolean started = TelenavClient.startNavigation(appCtx, 20_000, place);
        o.put("success", started);
        if (!started) o.put("error", "Telenav startNavigation returned false");
        o.put("wrote", new JSONObject()
                .put("name", req.optString("name", ""))
                .put("lat", req.getDouble("lat"))
                .put("lng", req.getDouble("lng")));
        return o;
    }

    private static JSONObject addFavorite(JSONObject req) throws Exception {
        JSONObject o = new JSONObject();
        if (appCtx == null) {
            o.put("success", false);
            o.put("error", "no app context");
            return o;
        }
        final String type = FavoriteType.Normal;
        final String name = req.optString("name", "");
        final double lat = req.getDouble("lat");
        final double lng = req.getDouble("lng");
        final Place place = buildPlace(req);

        // Read back the bucket after adding so we can confirm it landed + its type.
        JSONObject readback = TelenavClient.withUserData(appCtx, 20_000, svc -> {
            svc.addFavorite(type, place);
            JSONObject rb = new JSONObject();
            try {
                UserDataResult r = svc.getFavorites(type);
                rb.put("bucketType", type);
                rb.put("maxCount", r == null ? -1 : r.getMaxCount());
                rb.put("places", placesToJson(r == null ? null : r.getData()));
            } catch (Exception e) {
                rb.put("readbackError", String.valueOf(e.getMessage()));
            }
            return rb;
        });
        o.put("success", true);
        o.put("wrote", new JSONObject()
                .put("favoriteType", type).put("name", name).put("lat", lat).put("lng", lng));
        o.put("readback", readback);
        return o;
    }

    private static JSONObject getFavorites() throws Exception {
        if (appCtx == null) {
            JSONObject o = new JSONObject();
            o.put("success", false);
            o.put("error", "no app context");
            return o;
        }
        JSONObject result = TelenavClient.withUserData(appCtx, 20_000, svc -> {
            JSONObject o = new JSONObject();
            JSONArray buckets = new JSONArray();
            for (String type : TYPES) {
                JSONObject b = new JSONObject();
                b.put("queryType", type == null ? JSONObject.NULL : type);
                try {
                    UserDataResult r = svc.getFavorites(type);
                    b.put("resultType", r == null ? JSONObject.NULL : r.getType());
                    b.put("maxCount", r == null ? -1 : r.getMaxCount());
                    b.put("places", placesToJson(r == null ? null : r.getData()));
                } catch (Exception e) {
                    b.put("error", String.valueOf(e.getMessage()));
                }
                buckets.put(b);
            }
            o.put("favorites", buckets);
            try {
                UserDataResult recent = svc.getRecent();
                JSONObject rc = new JSONObject();
                rc.put("maxCount", recent == null ? -1 : recent.getMaxCount());
                rc.put("places", placesToJson(recent == null ? null : recent.getData()));
                o.put("recent", rc);
            } catch (Exception e) {
                o.put("recentError", String.valueOf(e.getMessage()));
            }
            return o;
        });
        result.put("success", true);
        return result;
    }

    private static JSONArray placesToJson(List<Place> places) throws JSONException {
        JSONArray arr = new JSONArray();
        if (places == null) return arr;
        for (Place p : places) {
            if (p == null) continue;
            JSONObject j = new JSONObject();
            try {
                j.put("placeName", p.getPlaceName());
                j.put("displayLabel", p.getPlaceDisplayLabel());
                j.put("favoriteType", p.getFavoriteType());
                j.put("placeType", p.getPlaceType());
                j.put("placeId", p.getPlaceId());
                j.put("geoLat", p.getGeoLatitude());
                j.put("geoLng", p.getGeoLongitude());
                j.put("navLat", p.getNavLatitude());
                j.put("navLng", p.getNavLongitude());
                Address a = p.getAddress();
                if (a != null) j.put("formattedAddress", a.getFormattedAddress());
            } catch (Throwable t) {
                j.put("parseError", String.valueOf(t.getMessage()));
            }
            arr.put(j);
        }
        return arr;
    }
}
