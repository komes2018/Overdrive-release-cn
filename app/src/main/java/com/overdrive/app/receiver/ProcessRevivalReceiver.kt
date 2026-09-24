package com.overdrive.app.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.overdrive.app.services.DaemonKeepaliveService

/**
 * Out-of-process revival watchdog.
 *
 * AlarmManager fires this receiver every [INTERVAL_MS] regardless of whether
 * the app process is alive — Android resurrects the process to deliver the
 * broadcast. The single job here is to (re)start DaemonKeepaliveService,
 * which in turn brings the daemon stack back up via DaemonStartupManager.
 *
 * MCU wake / WiFi-cut prevention / BMS sampling are all handled by
 * AccSentryDaemon's in-process loops once the process is alive again, so this
 * receiver does not duplicate any of that.
 *
 * Chain repair: every alarm fire re-arms the next one before doing any work,
 * so a crash mid-handler does not break the chain. A primary + backup pair
 * is scheduled at all times — if the OS drops the primary, the backup fires
 * [BACKUP_OFFSET_MS] later and re-seeds both.
 */
class ProcessRevivalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        Log.i(TAG, "Revival alarm fired (data=${intent.dataString})")

        // ABSOLUTE parked gate: while the "Vehicle ON only" parked-shutdown marker exists
        // the stack was deliberately terminated and this wake-alarm must not resurrect it
        // — regardless of what the (fail-open) config read below says. Cancel the chain;
        // the ACC-on recovery re-arms it via BootReceiver → schedule().
        try {
            if (java.io.File(com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH).exists()) {
                Log.i(TAG, "parked-shutdown marker present — cancelling revival chain, not re-arming")
                com.overdrive.app.ui.daemon.DaemonStartupManager.noteParkObserved()
                try { cancel(appContext) } catch (e: Exception) {
                    Log.w(TAG, "cancel failed: ${e.message}")
                }
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "parked-marker check failed (${e.message}) — falling through to mode gate")
        }

        // GATE (G6): in "Vehicle ON only" mode the out-of-process revival chain must
        // stand down — its whole purpose is to WAKE the head unit (RTC_WAKEUP) every
        // ~5 min to resurrect the parked daemon stack, which is exactly the post-OFF
        // system-waking onOnly must eliminate. If a pending alarm still fires after the
        // user flipped to onOnly, tear the chain down and do NOT re-arm or restart the
        // keepalive service. (schedule() below is also a no-op+cancel in onOnly, but the
        // explicit return here also skips the DaemonKeepaliveService.start.)
        if (com.overdrive.app.config.UnifiedConfigManager.isVehicleOnOnlyMode()) {
            Log.i(TAG, "onOnly mode — cancelling revival chain, not re-arming")
            try { cancel(appContext) } catch (e: Exception) {
                Log.w(TAG, "cancel failed: ${e.message}")
            }
            return
        }

        // Re-arm first so a crash below doesn't break the chain. Pass the
        // onOnly value we just read above (line ~42) so schedule() doesn't
        // re-read the config file — same decision, one fewer disk read + parse
        // per 5-minute fire.
        try {
            schedule(appContext, false)
        } catch (e: Exception) {
            Log.w(TAG, "Re-arm failed: ${e.message}")
        }

        // Kick the keepalive service. Its onStartCommand calls
        // DaemonStartupManager.startOnBoot(), which on a freshly-revived
        // process actually runs (bootStarted was reset by process death).
        try {
            DaemonKeepaliveService.start(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "DaemonKeepaliveService.start failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ProcessRevival"
        private const val ACTION = "com.overdrive.app.action.PROCESS_REVIVAL"

        // 5 minutes — comfortably inside the BYD MCU's ~10-15 min WiFi-cut
        // budget AND inside the in-process health-check rhythm. With the
        // SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM permissions declared in the
        // manifest, setExactAndAllowWhileIdle on Android 12+ fires on time
        // (the doze ~9-min maintenance-window floor only applies to inexact
        // alarms; exact-allow-while-idle is granted maintenance window
        // exemption when those permissions are held).
        //
        // Tightened from 8 min → 5 min so worst-case "MainActivity +
        // watchdog + daemon all dead" recovery is 5-8 min instead of
        // 8-13 min. Wake cost: ~288 wakes/day at 5min vs ~180/day at 8min;
        // each wake is ~50-100ms CPU + radio init, so the delta is tens
        // of seconds of additional CPU per day on a parked car —
        // negligible on the 12V auxiliary.
        //
        // Note: AccSentryDaemon's in-process 10s loop dominates when the
        // daemon process is alive, so this interval ONLY matters during
        // the everything-died-simultaneously dead-process window.
        private const val INTERVAL_MS = 5 * 60 * 1000L

        // Backup fires this much later than the primary. If the primary
        // alarm is dropped by the OS, the backup re-seeds the chain.
        // Tightened from 4min → 3min to match the smaller primary interval;
        // total worst-case revival is now 5+3 = 8 min (was 8+4 = 12 min).
        private const val BACKUP_OFFSET_MS = 3 * 60 * 1000L

        private const val PRIMARY_REQUEST_CODE = 0xD001
        private const val BACKUP_REQUEST_CODE = 0xD002

        private fun buildPendingIntent(
            context: Context,
            requestCode: Int,
            tag: String,
            flags: Int,
        ): PendingIntent? {
            val intent = Intent(context, ProcessRevivalReceiver::class.java).apply {
                action = ACTION
                // Distinct data URIs so primary/backup PendingIntents don't
                // collide under PendingIntent equality rules.
                data = Uri.parse("overdrive://revival/$tag")
            }
            return PendingIntent.getBroadcast(context, requestCode, intent, flags)
        }

        /**
         * Schedule (or reschedule) the primary + backup revival alarms.
         * Safe to call repeatedly — FLAG_UPDATE_CURRENT replaces in place.
         */
        fun schedule(context: Context) = schedule(context, null)

        /**
         * @param onOnlyKnown when non-null, an already-read isVehicleOnOnlyMode()
         *   value from the immediate caller — avoids a second config read (which
         *   can be a full disk read + JSON parse) in the same code path. The
         *   public [schedule] overload passes null and reads it itself, so every
         *   existing caller behaves exactly as before.
         */
        fun schedule(context: Context, onOnlyKnown: Boolean?) {
            // GATE (G6): "Vehicle ON only" mode — never arm the revival wake-alarms.
            // This single check covers ALL callers (MainActivity.onCreate, BootReceiver
            // .startDaemons, DaemonKeepaliveService.onCreate) AND the app-update relaunch
            // path (MainActivity re-runs schedule() after MY_PACKAGE_REPLACED), so the
            // mode is honoured across respawns and updates with no per-caller edit. We
            // also cancel() any alarms a prior onAndOff session left pending so a mid-park
            // toggle-to-onOnly tears the chain down at the next schedule() call.
            if (onOnlyKnown ?: com.overdrive.app.config.UnifiedConfigManager.isVehicleOnOnlyMode()) {
                Log.i(TAG, "onOnly mode — not arming revival alarms; cancelling any pending")
                cancel(context)
                return
            }
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager == null) {
                Log.w(TAG, "AlarmManager unavailable")
                return
            }
            val now = System.currentTimeMillis()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

            buildPendingIntent(context, PRIMARY_REQUEST_CODE, "primary", flags)?.let { pi ->
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, now + INTERVAL_MS, pi,
                    )
                } catch (e: SecurityException) {
                    // API 31+ without SCHEDULE_EXACT_ALARM — fall back to inexact.
                    Log.w(TAG, "Exact alarm denied, falling back to inexact: ${e.message}")
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, now + INTERVAL_MS, pi,
                    )
                }
            }

            buildPendingIntent(context, BACKUP_REQUEST_CODE, "backup", flags)?.let { pi ->
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, now + INTERVAL_MS + BACKUP_OFFSET_MS, pi,
                    )
                } catch (e: SecurityException) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, now + INTERVAL_MS + BACKUP_OFFSET_MS, pi,
                    )
                }
            }
        }

        /**
         * Cancel both revival alarms. Used by "Vehicle ON only" mode (G6) to stand the
         * wake chain down. Rebuilds the primary + backup PendingIntents with
         * FLAG_NO_CREATE (returns null if none exists — nothing to cancel) so we don't
         * accidentally create a new intent just to cancel it.
         */
        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return
            val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            buildPendingIntent(context, PRIMARY_REQUEST_CODE, "primary", flags)?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
            buildPendingIntent(context, BACKUP_REQUEST_CODE, "backup", flags)?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }
    }
}
