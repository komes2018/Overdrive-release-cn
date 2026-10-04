package com.overdrive.app.overlay

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.overdrive.app.config.UnifiedConfigManager
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Off-looper writer for the `statusOverlay` config section, shared by the
 * Settings screens' status-pill switches.
 *
 * Why this exists (audit Aug 2026):
 *  - [UnifiedConfigManager.updateSection] is documented off-UI-looper-only:
 *    an app-UID write does a blocking localhost IPC round-trip (1500ms
 *    connect + 5000ms read, plus reconcile retries) followed by a full JSON
 *    rewrite. The overlay switches used to call it straight from their
 *    checked-change listeners — the same ANR pattern SettingsSecurityFragment
 *    already routes through a dedicated executor
 *    (per memory feedback_no_unified_writes_on_ui_thread).
 *  - The write can legitimately return false (daemon unreachable AND the
 *    stable .lock inode not yet provisioned — app-UID local writes are
 *    deliberately deferred rather than truncation-prone). Callers previously
 *    ignored the boolean, so the switch stayed where the user put it while
 *    the config kept the old value: a silent no-op. Callers now receive the
 *    result on the main thread and revert the switch on failure, matching
 *    the RoadSense switch's existing revert-on-failure contract.
 *
 * One bounded retry: the daemon's IPC server rebinds within ~3s of a
 * transient bind failure, and a daemon restart is the most common reason for
 * a routed write to decline. A single delayed retry converts that whole
 * window from "toggle silently reverts" to "toggle just works", without
 * unbounded queuing.
 */
object StatusOverlayUiWriter {
    private const val TAG = "StatusOverlayUiWriter"
    private const val RETRY_DELAY_MS = 1200L

    // Single shared daemon thread: writes are rare (user toggles), serialized
    // so two rapid flips of the same key can't commit out of order.
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "StatusOverlayIO").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Persist one `statusOverlay` boolean off the UI thread.
     *
     * @param onResult delivered on the MAIN thread with the final outcome
     *   (after the internal retry). Callers revert their switch on false;
     *   they must guard their checked-change listener against the revert
     *   re-entering this writer.
     */
    @JvmStatic
    fun write(key: String, value: Boolean, onResult: (Boolean) -> Unit) {
        submit("statusOverlay.$key", onResult) {
            UnifiedConfigManager.setStatusOverlay(JSONObject().put(key, value))
        }
    }

    /** Same contract as [write], for the icon opacity and size sliders. */
    @JvmStatic
    fun writeInt(key: String, value: Int, onResult: (Boolean) -> Unit) {
        submit("statusOverlay.$key", onResult) {
            UnifiedConfigManager.setStatusOverlay(JSONObject().put(key, value))
        }
    }

    /**
     * Persist one arbitrary config boolean off the UI thread, with the same
     * retry + main-thread result contract as [write].
     *
     * Exists because the RoadSense overlay switch wrote
     * [com.overdrive.app.roadsense.config.RoadSenseConfig.setOverlayVisible]
     * STRAIGHT FROM its checked-change listener: a blocking app-UID write on
     * the looper, and — because an app-UID local write is deliberately deferred
     * until the daemon has provisioned the stable `.lock` inode — one that
     * legitimately returns false early after boot. With no retry the switch
     * just sprang back and RoadSense could not be enabled or disabled at all
     * (the reported "overlay settings for enable/disable road sense not
     * working"). The sibling camera/trip switches were migrated here in the
     * Aug 2026 audit; this one was missed.
     *
     * @param label log label for the write, e.g. "roadSense.overlayVisible".
     * @param onResult delivered on the MAIN thread after the internal retry.
     * @param body the actual write; must return whether it committed.
     */
    @JvmStatic
    fun writeWith(label: String, onResult: (Boolean) -> Unit, body: () -> Boolean) {
        submit(label, onResult, body)
    }

    private fun submit(label: String, onResult: (Boolean) -> Unit, body: () -> Boolean) {
        executor.execute {
            var ok = try {
                body()
            } catch (t: Throwable) {
                Log.w(TAG, "$label write threw: ${t.message}")
                false
            }
            if (!ok) {
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                ok = try {
                    body()
                } catch (t: Throwable) {
                    Log.w(TAG, "$label retry threw: ${t.message}")
                    false
                }
            }
            if (!ok) {
                Log.w(TAG, "$label not committed after retry; reverting UI")
            }
            val result = ok
            mainHandler.post { onResult(result) }
        }
    }
}
