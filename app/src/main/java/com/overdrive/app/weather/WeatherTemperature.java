package com.overdrive.app.weather;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.mqtt.ProxyHelper;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared outside-air temperature from the Open-Meteo weather API, keyed on GPS
 * coordinates. Single source of truth for weather temp across the daemon (the
 * automation temperature fallback, ABRP {@code ext_temp}, …) so there is ONE
 * cache and ONE network path, not a copy per caller.
 *
 * <p>Design for the automation use-case: the value is read from the telemetry
 * poll thread (via BydEvent), which must NEVER block on the network. So the
 * public {@link #getCached()} / {@link #refreshAsync} split is:
 * <ul>
 *   <li>{@link #getCached()} returns the last fetched value instantly (NaN if none
 *       yet / too stale), never touching the network.</li>
 *   <li>{@link #refreshAsync(double, double)} kicks a background fetch if the cache
 *       is stale and no fetch is already in flight. Cheap to call every poll.</li>
 * </ul>
 * On ACC-off the head-unit data interface briefly drops ~17s after park but
 * self-recovers ~3 min later while still parked, so an async fetch that fails is
 * simply retried on a later poll; the cache rides out the transient blackout.
 */
public final class WeatherTemperature {
    private static final DaemonLogger logger = DaemonLogger.getInstance("WeatherTemperature");

    /** Serve the cached value for this long before a refresh is attempted. */
    private static final long CACHE_MS = 10 * 60 * 1000; // 10 min
    /** Discard the cache entirely past this age (a parked car's local weather
     *  doesn't change fast, but a day-old value shouldn't drive an automation). */
    private static final long STALE_MS = 60 * 60 * 1000; // 1 h

    private static volatile double cachedTemp = Double.NaN;
    private static volatile long lastFetchTime = 0;
    private static final AtomicBoolean fetchInFlight = new AtomicBoolean(false);

    // Mean precipitation probability (%) over the next PRECIP_HOURS hours, from the same
    // Open-Meteo fetch (one round-trip serves temp + rain). -1 = not yet fetched/stale.
    // Drives the "rain likely" automation trigger; kept on the same cache clock as temp.
    private static final int PRECIP_HOURS = 6;
    private static volatile int cachedPrecipProb = -1;

    private WeatherTemperature() {}

    /**
     * Mean precipitation probability (%) over roughly the next {@value #PRECIP_HOURS}
     * hours, or -1 if none fetched yet / cache is stale. Never blocks. Pair with
     * {@link #refreshAsync} on the poll thread the same way as {@link #getCached()}.
     */
    public static int getCachedPrecipProbability() {
        if (cachedPrecipProb < 0) return -1;
        if (System.currentTimeMillis() - lastFetchTime > STALE_MS) return -1;
        return cachedPrecipProb;
    }

    /**
     * The last fetched outside temperature (°C), or NaN if none has been fetched
     * yet or the cached value is older than {@link #STALE_MS}. Never blocks.
     */
    public static double getCached() {
        double t = cachedTemp;
        if (Double.isNaN(t)) return Double.NaN;
        if (System.currentTimeMillis() - lastFetchTime > STALE_MS) return Double.NaN;
        return t;
    }

    /**
     * Kick a background refresh if the cache is stale ({@literal >}{@link #CACHE_MS})
     * and no fetch is already running. Returns immediately. Safe to call every poll.
     *
     * @param lat last-known latitude  (parked car doesn't move, so last-known is fine)
     * @param lon last-known longitude
     */
    public static void refreshAsync(final double lat, final double lon) {
        if (lat == 0.0 && lon == 0.0) return; // no location yet
        long now = System.currentTimeMillis();
        if (!Double.isNaN(cachedTemp) && (now - lastFetchTime) < CACHE_MS) return; // fresh enough
        if (!fetchInFlight.compareAndSet(false, true)) return; // one fetch at a time
        Thread t = new Thread(() -> {
            try {
                fetchNow(lat, lon);
            } finally {
                fetchInFlight.set(false);
            }
        }, "weather-temp-fetch");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Synchronous fetch — for callers already off the hot path (e.g. ABRP's own
     * upload thread). Updates the shared cache. Returns the fetched value, or the
     * existing cache (possibly NaN) on failure. Bounded to a few seconds.
     */
    public static double fetchNow(double lat, double lon) {
        if (lat == 0.0 && lon == 0.0) return getCached();
        long now = System.currentTimeMillis();
        // Serve fresh cache without a network round-trip.
        if (!Double.isNaN(cachedTemp) && (now - lastFetchTime) < CACHE_MS) return cachedTemp;
        try {
            // One round-trip serves both signals: current temperature + the next-hours
            // precipitation-probability series (for the "rain likely" automation trigger).
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + "&current=temperature_2m&hourly=precipitation_probability&forecast_days=1&timezone=auto",
                    lat, lon);
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "OverDrive/1.0")
                    .build();
            // Chain selector (proxy → direct) rather than a frozen proxy: a
            // tailnet-only Tailscale listener would otherwise block the fetch
            // while probing healthy, and weather's 3s budget can't afford it.
            OkHttpClient client = new OkHttpClient.Builder()
                    .proxySelector(ProxyHelper.chainProxySelector())
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string();
                    JSONObject root = new JSONObject(body);
                    // Parse precip first so a temp-only success still updates rain (and
                    // vice versa); both share lastFetchTime set once below on any success.
                    int precip = parseMeanPrecip(root);
                    if (precip >= 0) cachedPrecipProb = precip;
                    JSONObject current = root.optJSONObject("current");
                    if (current != null && current.has("temperature_2m")) {
                        double temp = current.getDouble("temperature_2m");
                        cachedTemp = temp;
                        lastFetchTime = System.currentTimeMillis();
                        logger.debug("weather temp=" + String.format(Locale.US, "%.1f", temp)
                                + "°C precip=" + cachedPrecipProb + "%");
                        return temp;
                    }
                    // Temp missing but precip parsed — still a useful refresh.
                    if (precip >= 0) lastFetchTime = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            logger.debug("weather fetch failed: " + e.getMessage());
        }
        return getCached(); // stale cache or NaN
    }

    /**
     * Mean precipitation probability (%) over the next {@value #PRECIP_HOURS} hourly
     * buckets from Open-Meteo's {@code hourly.precipitation_probability} array. The
     * array starts at 00:00 today; we start at the current hour so "next hours" is
     * actually ahead of now. Returns -1 if the field is absent/empty.
     */
    private static int parseMeanPrecip(JSONObject root) {
        try {
            JSONObject hourly = root.optJSONObject("hourly");
            if (hourly == null) return -1;
            org.json.JSONArray times = hourly.optJSONArray("time");
            org.json.JSONArray probs = hourly.optJSONArray("precipitation_probability");
            if (probs == null || probs.length() == 0) return -1;
            // Find the first bucket at/after the current wall-clock hour. Open-Meteo
            // returns local time strings "YYYY-MM-DDTHH:00"; match on the "THH" hour.
            int startIdx = 0;
            if (times != null && times.length() == probs.length()) {
                String hourTag = String.format(Locale.US, "T%02d",
                        java.time.LocalTime.now().getHour());
                for (int i = 0; i < times.length(); i++) {
                    String ts = times.optString(i, "");
                    if (ts.contains(hourTag)) { startIdx = i; break; }
                }
            }
            long sum = 0; int n = 0;
            for (int i = startIdx; i < probs.length() && n < PRECIP_HOURS; i++, n++) {
                int p = probs.optInt(i, -1);
                if (p >= 0) sum += p;
                else n--; // skip nulls without counting them
            }
            if (n <= 0) return -1;
            return (int) Math.round(sum / (double) n);
        } catch (Exception e) {
            return -1;
        }
    }
}
