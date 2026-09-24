package com.overdrive.app.roadsense.sync

import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.roadsense.detect.ALTITUDE_UNKNOWN
import com.overdrive.app.roadsense.detect.HazardType
import com.overdrive.app.roadsense.detect.RoadSenseHazard
import com.overdrive.app.roadsense.detect.Severity
import com.overdrive.app.roadsense.detect.StoredHazard
import com.overdrive.app.roadsense.detect.altitudeKnown
import com.overdrive.app.roadsense.store.SpatialIndex
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [RoadSenseSyncProvider] over the open-source Cloudflare Workers + D1 backend
 * (D-009, `roadsense-edge/`). Plain HTTPS to a user-configurable Worker URL — no
 * embedded secret. Daemon-side; blocking calls run on the sync tick.
 *
 * ## Wire contract (must match roadsense-edge/src/worker.ts exactly)
 * - **Tiles are STRINGS on the wire.** SpatialIndex packs (latCell<<32 | lngCell)
 *   into a 64-bit Long; that exceeds JS Number's 53-bit exact-integer range, so a
 *   JSON number would silently round into the WRONG tile. We send/receive tile as
 *   `tile.toString()` and parse back with `toLong()`. This is the single most
 *   important interop rule.
 * - `GET /tiles?keys=<csv tile strings>&since=<ms>` → `{ "hazards": [ {...} ] }`,
 *   only confirmed (status=1) rows, `updated_ms > since`. keys capped at 64.
 * - `POST /report` `{ "deviceId", "reports":[ {tile,lat,lng,type,severity,
 *   heading,confidence,observedMs,humanVerified} ] }` → `{accepted,created,
 *   merged,confirmed}`. type/severity ordinals match RoadSenseTypes.
 * - `POST /delete` `{ "deviceId" }` (delete-cloud, R-SET-5).
 *
 * Never throws to the caller — all failures become a non-ok result (offline-first).
 */
class CloudflareEdgeSyncProvider(
    private val workerUrlSupplier: () -> String?,
    /** Builds the HTTP client for a call. Default is proxy-aware (routes through
     *  sing-box/Tailscale when ProxyHelper reports one — mirrors AbrpTelemetryService /
     *  AppUpdater). Rebuilt per call because the proxy comes and goes with ACC/network
     *  state and sync is infrequent (every few hours), so caching a stale proxy is the
     *  bigger risk. Injectable for tests. */
    private val clientFactory: () -> OkHttpClient = { proxiedClient() },
) : RoadSenseSyncProvider {

    private val httpClient: OkHttpClient get() = clientFactory()

    override fun download(tiles: List<Long>, sinceByTile: Map<Long, Long>): RoadSenseSyncProvider.DownloadResult {
        val base = baseUrl() ?: return fail("no worker URL configured")
        if (tiles.isEmpty()) return RoadSenseSyncProvider.DownloadResult(true, emptyList(), emptyMap())

        // The server takes a single `since` for the whole query (then filters
        // updated_ms > since). We use the OLDEST per-tile cursor as the floor so we
        // never miss a tile that's further behind; tiles already current just
        // return nothing. Cap at 64 tiles/request (server rejects more).
        val capped = tiles.take(MAX_TILES_PER_REQUEST)
        val since = capped.minOf { sinceByTile[it] ?: 0L }
        val keysCsv = capped.joinToString(",") { it.toString() } // STRINGS (precision!)
        val url = "$base/tiles?keys=$keysCsv&since=$since"

        return try {
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return fail("tiles HTTP ${resp.code}")
                val body = resp.body?.string() ?: return fail("empty tiles body")
                parseDownload(body)
            }
        } catch (t: Throwable) {
            fail(t.message ?: "download error")
        }
    }

    private fun parseDownload(body: String): RoadSenseSyncProvider.DownloadResult {
        val root = JSONObject(body)
        val more = root.optBoolean("more", false)
        val arr = root.optJSONArray("hazards") ?: JSONArray()
        val out = ArrayList<RoadSenseHazard>(arr.length())
        val highWater = HashMap<Long, Long>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val tile = o.optString("tile").toLongOrNull() ?: continue // STRING → Long
            val updated = o.optLong("updatedMs", 0L)
            out.add(
                RoadSenseHazard(
                    lat = o.optDouble("lat"),
                    lng = o.optDouble("lng"),
                    type = typeFromOrdinal(o.optInt("type", 2)),
                    severity = severityFromLevel(o.optInt("severity", 1)),
                    // Heading may be null (unknown) on a cloud row — default to the
                    // -1 sentinel, NOT 0.0, so the road-match gate treats it as
                    // "unknown" (cone-only) rather than a real due-north heading.
                    headingDeg = if (o.isNull("heading")) -1f else o.optDouble("heading", -1.0).toFloat(),
                    confidence = o.optDouble("confidence", 0.0).toFloat(),
                    speedKmh = 0f,            // cloud rows don't carry the original speed
                    aVertPeak = 0f,           // nor the raw peak — not needed for warnings
                    tMs = o.optLong("createdMs", updated),
                    // Altitude may be null (unknown level) → fail-open sentinel.
                    altitudeM = if (o.isNull("altitude")) ALTITUDE_UNKNOWN
                        else o.optDouble("altitude", ALTITUDE_UNKNOWN),
                )
            )
            val cur = highWater[tile] ?: 0L
            if (updated > cur) highWater[tile] = updated
        }
        return RoadSenseSyncProvider.DownloadResult(true, out, highWater, more = more)
    }

    override fun upload(hazards: List<StoredHazard>, deviceId: String): RoadSenseSyncProvider.UploadResult {
        val base = baseUrl() ?: return RoadSenseSyncProvider.UploadResult(false, 0, "no worker URL")
        if (hazards.isEmpty()) return RoadSenseSyncProvider.UploadResult(true, 0)

        return try {
            val reports = JSONArray()
            for (sh in hazards.take(MAX_REPORTS_PER_BATCH)) {
                val h = sh.hazard
                reports.put(
                    JSONObject()
                        .put("tile", SpatialIndex.tileKey(h.lat, h.lng).toString()) // STRING
                        .put("lat", h.lat)
                        .put("lng", h.lng)
                        .put("type", h.type.ordinal)
                        .put("severity", h.severity.level)
                        .put("heading", h.headingDeg.toDouble())
                        .put("confidence", h.confidence.toDouble())
                        // Altitude: send the real value or JSON null (unknown level).
                        .put("altitude", if (altitudeKnown(h.altitudeM)) h.altitudeM else JSONObject.NULL)
                        .put("observedMs", sh.updatedMs)
                        .put("humanVerified", sh.humanVerified)
                )
            }
            val payload = JSONObject().put("deviceId", deviceId).put("reports", reports)
            val req = Request.Builder()
                .url("$base/report")
                .header("X-Device-Id", deviceId)
                .post(payload.toString().toRequestBody(JSON))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 429) return RoadSenseSyncProvider.UploadResult(false, 0, "rate limited")
                if (!resp.isSuccessful) return RoadSenseSyncProvider.UploadResult(false, 0, "HTTP ${resp.code}")
                val accepted = JSONObject(resp.body?.string() ?: "{}").optInt("accepted", 0)
                RoadSenseSyncProvider.UploadResult(true, accepted)
            }
        } catch (t: Throwable) {
            RoadSenseSyncProvider.UploadResult(false, 0, t.message ?: "upload error")
        }
    }

    override fun deleteOwnUploads(deviceId: String): Boolean {
        val base = baseUrl() ?: return false
        return try {
            val req = Request.Builder()
                .url("$base/delete")
                .post(JSONObject().put("deviceId", deviceId).toString().toRequestBody(JSON))
                .build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (t: Throwable) {
            logger.warn("deleteOwnUploads failed: ${t.message}")
            false
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Normalized base URL (trailing slash stripped), or null if unset/blank. */
    private fun baseUrl(): String? {
        val u = workerUrlSupplier()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return u.trimEnd('/')
    }

    private fun fail(msg: String): RoadSenseSyncProvider.DownloadResult {
        logger.warn("download failed: $msg")
        return RoadSenseSyncProvider.DownloadResult(false, emptyList(), emptyMap(), msg)
    }

    private fun typeFromOrdinal(o: Int): HazardType =
        HazardType.values().getOrElse(o) { HazardType.UNKNOWN }

    private fun severityFromLevel(level: Int): Severity = when (level) {
        3 -> Severity.SEVERE
        2 -> Severity.MODERATE
        else -> Severity.MINOR
    }

    companion object {
        private val logger = DaemonLogger.getInstance("RoadSense/Sync")
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_TILES_PER_REQUEST = 64   // server rejects more
        private const val MAX_REPORTS_PER_BATCH = 256  // server cap

        /** Proxy-aware OkHttp client: routes through sing-box/Tailscale when one is
         *  up, with a DIRECT fallback leg (ProxyHelper.chainProxySelector()) so a
         *  tailnet-only Tailscale listener can't wedge crowdsource sync — same
         *  convention as ABRP/updater. */
        private fun proxiedClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .proxySelector(com.overdrive.app.mqtt.ProxyHelper.chainProxySelector())
            .build()
    }
}
