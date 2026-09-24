package com.overdrive.app.analytics

import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.updater.AppUpdater
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Fire-and-forget daily "this install is active" ping for the privacy-preserving
 * DAU/MAU counter (analytics-edge). Daemon-side; called from a scheduled tick in
 * [com.overdrive.app.daemon.CameraDaemon].
 *
 * ## Contract (must match analytics-edge/src/worker.ts)
 *   POST /ping  { "id": "<rotating install uuid>", "day": "YYYY-MM-DD", "ver": "alpha-v30.5" }
 *     → 204. Records (day, id) once (idempotent on the (day,id) PK).
 *
 * ## Privacy / minimalism
 *   - The id is [AnalyticsId] — a rotating anonymous install UUID, NOT device data.
 *   - The ONLY other fields are the calendar day and the app version label. No IP
 *     is sent by us (Cloudflare sees the TLS peer as any HTTPS call does, and the
 *     Worker stores none); no location, no device identifiers.
 *   - At most ONE ping per install per UTC day (guarded by `analytics.lastPingDay`
 *     in UnifiedConfig). Same-day repeats are also no-ops server-side (PK), so this
 *     guard is purely to save the network round-trip, not for correctness.
 *   - Honors an `analytics.enabled` kill-switch (default true): a single flag lets
 *     a user, or you (remotely, no rebuild), disable the whole feature.
 *
 * Never throws to the caller — analytics must never affect app behavior. All
 * failures (offline, proxy down, bad URL, HTTP error) are swallowed; the day guard
 * is only advanced on a confirmed success so a failed day retries next tick.
 */
object AnalyticsPinger {

    private const val SECTION = "analytics"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WORKER_URL = "workerUrl"
    private const val KEY_LAST_PING_DAY = "lastPingDay"

    /** Project default backend. A self-hoster overrides `analytics.workerUrl`. */
    private const val DEFAULT_WORKER_URL = "https://analytics-edge.yash321sri.workers.dev"

    private val logger = DaemonLogger.getInstance("Analytics/Ping")
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** UTC calendar day "YYYY-MM-DD" — matches the Worker's UTC day bucketing. */
    private fun dayOf(nowMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(nowMs))
    }

    /**
     * Send the daily ping if due. Safe to call frequently (e.g. hourly): it
     * short-circuits cheaply when already pinged today or disabled. Blocking I/O
     * on success — call from a background scheduler thread, never the UI/warn path.
     */
    fun maybePing(nowMs: Long) {
        try {
            val section = try {
                UnifiedConfigManager.forceReload().optJSONObject(SECTION)
            } catch (_: Throwable) { null }

            // Kill-switch (default ON). Absent section/flag ⇒ enabled.
            if (section != null && !section.optBoolean(KEY_ENABLED, true)) return

            val today = dayOf(nowMs)
            if (section?.optString(KEY_LAST_PING_DAY, "") == today) return // already pinged today

            val base = (section?.optString(KEY_WORKER_URL, "")?.trim()
                ?.takeIf { it.isNotEmpty() } ?: DEFAULT_WORKER_URL).trimEnd('/')

            // Pass the section we already loaded so AnalyticsId doesn't do a second
            // forceReload() disk/cross-UID read on this same tick.
            val id = AnalyticsId.current(nowMs, section)
            val ver = try { AppUpdater.getInstalledVersion() } catch (_: Throwable) { null }

            val payload = JSONObject().put("id", id).put("day", today)
            if (!ver.isNullOrEmpty()) payload.put("ver", ver)

            val req = Request.Builder()
                .url("$base/ping")
                .header("X-Device-Id", id)
                .post(payload.toString().toRequestBody(JSON))
                .build()

            val ok = proxiedClient().newCall(req).execute().use { it.isSuccessful }
            if (ok) {
                // Advance the day guard ONLY on success so a failed day retries.
                UnifiedConfigManager.updateSection(
                    SECTION,
                    JSONObject().put(KEY_LAST_PING_DAY, today),
                )
            }
        } catch (t: Throwable) {
            // Analytics is best-effort; never let it surface. Log at debug only.
            logger.warn("ping skipped: ${t.message}")
        }
    }

    /** Proxy-aware OkHttp client: routes through sing-box/Tailscale when one is up,
     *  with a DIRECT fallback leg (ProxyHelper.chainProxySelector()) so a
     *  tailnet-only Tailscale listener can't wedge the ping — same convention as
     *  CloudflareEdgeSyncProvider / ABRP / AppUpdater. Rebuilt per call because the
     *  proxy comes and goes with ACC/network state and pings are rare. */
    private fun proxiedClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .proxySelector(com.overdrive.app.mqtt.ProxyHelper.chainProxySelector())
        .build()
}
