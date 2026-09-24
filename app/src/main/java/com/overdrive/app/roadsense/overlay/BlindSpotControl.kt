package com.overdrive.app.roadsense.overlay

import android.content.Context
import android.util.Log
import com.overdrive.app.config.UnifiedConfigManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

/**
 * Thin app-side control for the NATIVE blind-spot lane.
 *
 * The blind-spot visual is rendered entirely in the daemon (UID 2000) onto a
 * SurfaceControl layer — there is NO app-process overlay/decoder/WebSocket. The
 * app's only job is to tell the daemon to ARM or DISARM the lane when the feature
 * toggle changes, via the daemon's loopback HTTP control surface. The daemon then
 * owns show/hide (turn-trigger) and positioning (config-driven geometry).
 *
 * Replaces the deleted BlindSpotOverlayService (app-process decoder/WS overlay).
 */
object BlindSpotControl {

    private const val TAG = "BlindSpot/Control"
    private const val BASE_URL = "http://127.0.0.1:8080"

    /** Hard cap on the re-arm loop so it always converges (covers daemon cold-start + pano cold-start). */
    private const val REARM_DEADLINE_MS = 60_000L
    /** Backoff ceiling between re-arm polls. */
    private const val REARM_MAX_BACKOFF_MS = 2_000L

    /**
     * Monotonic arm-loop token. Bumped on EVERY sync() (both branches) so any
     * in-flight re-arm loop carrying an older token is invalidated. Gives both
     * cancellation (a disable supersedes a running arm loop) and dedup (a fresh
     * enable supersedes a previous one — only the newest loop survives).
     */
    private val armGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Arm or disarm the daemon BS lane to match the `blindspot.enabled` flag
     * (or debugPreview, which also needs the lane up). Runs the loopback POST on a
     * background thread so it never blocks the caller (UI thread / receiver).
     * Idempotent daemon-side: /api/bs/enable no-ops once the lane is armed.
     */
    @JvmStatic
    fun sync(context: Context) {
        // Capture a fresh token on EVERY sync, before dispatching. Bumping it here
        // (both enable AND disable branches) invalidates any still-running arm loop:
        // a disable can no longer be silently undone by a stale re-arm, and rapid
        // toggles collapse to the newest loop instead of piling up threads.
        val gen = armGeneration.incrementAndGet()
        Thread({
            try {
                val bs = UnifiedConfigManager.forceReload().optJSONObject("blindspot")
                val enabled = bs?.optBoolean("enabled", false) ?: false
                val preview = bs?.optBoolean("debugPreview", false) ?: false
                if (enabled || preview) {
                    // ARM path: a single POST is not enough. On cold ACC-on the daemon's
                    // HTTP port (8080) may not be listening yet, or the pano pipeline may
                    // still be cold-starting (/api/bs/enable returns starting:true), so the
                    // lane is not armed by one shot. Drive a bounded re-arm loop that keeps
                    // re-POSTing /api/bs/enable and polling /api/bs/status until the lane
                    // reports enabled:true (or we hit the deadline). Idempotent daemon-side.
                    armWithRetry(gen)
                } else {
                    post("/api/bs/disable")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "sync failed: ${t.message}")
            }
        }, "BlindSpotControl-sync").start()
    }

    /**
     * Bounded, self-converging re-arm loop. POSTs /api/bs/enable, then polls
     * /api/bs/status with 500ms→2s backoff, re-POSTing enable each pass, until the
     * lane reports enabled:true or [REARM_DEADLINE_MS] elapses. Survives the initial
     * sync() call by running on its own daemon thread; converges (no infinite loop —
     * hard deadline).
     *
     * Cancellable + intent-aware: [gen] is the arm token captured by the sync() that
     * spawned this loop. Each iteration first bails if a newer sync (a disable OR a
     * fresh enable) has bumped [armGeneration] past [gen] — giving cancellation and
     * dedup — then re-reads the persisted intent and exits WITHOUT POSTing enable the
     * moment blindspot.{enabled,debugPreview} are both false, so a user disable can
     * never be silently undone by a stale re-arm.
     */
    private fun armWithRetry(gen: Int) {
        Thread({
            // The head unit can correct wall time from GPS/network during
            // boot. Use a monotonic deadline so that correction cannot end the
            // arm loop early or extend its HTTP/config polling unexpectedly.
            val deadline = android.os.SystemClock.elapsedRealtime() + REARM_DEADLINE_MS
            var delayMs = 500L
            var attempts = 0
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                // Superseded by a newer sync (disable OR fresh enable): stop, do not POST.
                if (armGeneration.get() != gen) {
                    return@Thread
                }
                // Re-read intent every pass: if the feature was turned off while we were
                // cold-starting, exit before re-arming the lane the user just disabled.
                val bs = UnifiedConfigManager.forceReload().optJSONObject("blindspot")
                if (!(bs?.optBoolean("enabled", false) ?: false) &&
                    !(bs?.optBoolean("debugPreview", false) ?: false)) {
                    return@Thread
                }
                attempts++
                post("/api/bs/enable")
                if (isLaneEnabled()) {
                    return@Thread
                }
                try {
                    Thread.sleep(delayMs)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
                delayMs = (delayMs * 2).coerceAtMost(REARM_MAX_BACKOFF_MS)
            }
            Log.w(TAG, "armWithRetry: lane not enabled after $attempts attempts (${REARM_DEADLINE_MS}ms); giving up")
        }, "BlindSpotRetry").apply { isDaemon = true }.start()
    }

    /** GET /api/bs/status and return true iff it reports enabled:true. */
    private fun isLaneEnabled(): Boolean {
        return try {
            val req = Request.Builder()
                .url("$BASE_URL/api/bs/status")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return false
                org.json.JSONObject(body).optBoolean("enabled", false)
            }
        } catch (t: Throwable) {
            // Connection refused / timeout while daemon still cold-starting: not enabled yet.
            false
        }
    }

    private fun post(path: String) {
        try {
            val req = Request.Builder()
                .url("$BASE_URL$path")
                .post(RequestBody.create(null, ByteArray(0)))
                .build()
            http.newCall(req).execute().use { it.body?.string() }
        } catch (t: Throwable) {
            Log.w(TAG, "post $path failed: ${t.message}")
        }
    }
}
