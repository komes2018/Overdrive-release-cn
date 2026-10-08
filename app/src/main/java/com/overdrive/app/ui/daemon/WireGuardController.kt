package com.overdrive.app.ui.daemon

import android.content.Context
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.launcher.AdbShellExecutor
import com.overdrive.app.launcher.WireGuardLauncher
import com.overdrive.app.logging.LogManager
import com.overdrive.app.mqtt.ProxyHelper
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.wireguard.WireGuardPaths
import org.json.JSONObject

/**
 * Controller for the WireGuard tunnel (userspace wgproxy with a loopback SOCKS5
 * listener).
 */
class WireGuardController(
    private val context: Context,
    private val adbLauncher: AdbDaemonLauncher
) : DaemonController {

    override val type = DaemonType.WIREGUARD_TUNNEL

    private val wireGuardLauncher by lazy {
        WireGuardLauncher(
            context,
            AdbShellExecutor(context),
            LogManager.getInstance()
        )
    }

    override fun start(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STARTING, "Starting WireGuard tunnel...")
        ProxyHelper.invalidateCache()

        wireGuardLauncher.launch(object : WireGuardLauncher.WireGuardCallback {
            override fun onLog(message: String) =
                callback.onStatusChanged(DaemonStatus.STARTING, message)

            override fun onStarted(summary: String?) {
                ProxyHelper.invalidateCache()
                callback.onStatusChanged(DaemonStatus.RUNNING, summary ?: "")
            }

            override fun onStopped() {}

            override fun onError(error: String) {
                ProxyHelper.invalidateCache()
                callback.onError(error)
            }
        })
    }

    override fun stop(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STOPPING, "Stopping WireGuard tunnel...")

        wireGuardLauncher.stop(object : WireGuardLauncher.WireGuardCallback {
            override fun onLog(message: String) =
                callback.onStatusChanged(DaemonStatus.STOPPING, message)

            override fun onStarted(summary: String?) {}

            override fun onStopped() {
                ProxyHelper.invalidateCache()
                callback.onStatusChanged(DaemonStatus.STOPPED, "WireGuard tunnel stopped")
            }

            override fun onError(error: String) {
                ProxyHelper.invalidateCache()
                callback.onError(error)
            }
        })
    }

    override fun isRunning(callback: (Boolean) -> Unit) {
        wireGuardLauncher.isRunning(callback)
    }

    fun hasConfig(callback: (Boolean) -> Unit) {
        wireGuardLauncher.hasConfig(callback)
    }

    /** Parsed status.json written by wgproxy, or null when missing/unreadable. */
    fun readStatus(callback: (JSONObject?) -> Unit) {
        wireGuardLauncher.readStatus(callback)
    }

    override fun cleanup() {
        // ps+awk+kill instead of pkill -f. executeShellCommand wraps in
        // `sh -c "<cmd>"`; the wrapper's argv contains the literal
        // "wgproxy" → toybox pkill -f would SIGKILL the calling shell
        // before `echo done` runs. Filter by PID list and exclude $$.
        adbLauncher.executeShellCommand(
            "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F ${WireGuardPaths.PROCESS_NAME} | grep -v grep " +
                "| awk '{print \$1}' | while read pid; do " +
                "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done; " +
                "echo done",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {}
            }
        )
        ProxyHelper.invalidateCache()
    }
}
