package com.overdrive.app.launcher

import android.content.Context
import com.overdrive.app.BuildConfig
import com.overdrive.app.logging.LogManager
import com.overdrive.app.mqtt.ProxyHelper
import com.overdrive.app.wireguard.WireGuardPaths
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Launches the userspace WireGuard client (wgproxy) via ADB shell.
 *
 * wgproxy exposes a loopback SOCKS5 listener; destinations inside the peers'
 * AllowedIPs go through the tunnel, everything else is dialled directly or via
 * sing-box when it is running. The private key lives in [WireGuardPaths.CONFIG]
 * (0600) and is never echoed into logs or callbacks.
 */
class WireGuardLauncher(
    private val context: Context,
    private val adbShellExecutor: AdbShellExecutor,
    private val logManager: LogManager
) {
    companion object {
        private const val TAG = "WireGuardLauncher"

        private const val DEPLOYMENT_CURRENT = "current"
        private const val DEPLOYMENT_STALE = "stale"

        // sing-box SOCKS/HTTP port used as upstream for non-tunnel destinations
        private const val UPSTREAM_PORT = 8119

        private const val STATUS_POLL_ATTEMPTS = 8
        private const val STATUS_POLL_DELAY_MS = 1000L

        // Daemon thread: only holds the short post-launch status poll.
        private val pollScheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "WireGuardStatusPoll").apply { isDaemon = true }
            }

        /**
         * Shell-side deployment probe shared by every wgproxy launch path. The
         * version stamp is written last, so a partial copy stays stale.
         */
        @JvmStatic
        fun deploymentStatusCommand(): String {
            val expected = BuildConfig.VERSION_CODE.toLong()
            return "if test -x ${WireGuardPaths.BINARY} && " +
                "[ \"\$(cat ${WireGuardPaths.VERSION_FILE} 2>/dev/null)\" = \"$expected\" ]; then " +
                "printf '$DEPLOYMENT_CURRENT\\n'; else printf '$DEPLOYMENT_STALE\\n'; fi"
        }

        /**
         * Detached launch command shared with the Telegram path. The loop
         * restarts wgproxy only when it exits with the reload code (config
         * changed); any other exit, including SIGTERM, ends the loop.
         */
        @JvmStatic
        fun buildLaunchCommand(useUpstream: Boolean): String {
            val upstream = if (useUpstream) " -upstream 127.0.0.1:$UPSTREAM_PORT" else ""
            return "nohup sh -c 'while :; do ${WireGuardPaths.BINARY} " +
                "-config ${WireGuardPaths.CONFIG} " +
                "-socks 127.0.0.1:${WireGuardPaths.SOCKS_PORT} " +
                "-status ${WireGuardPaths.STATUS}$upstream; " +
                "[ \$? -eq ${WireGuardPaths.RELOAD_EXIT_CODE} ] || break; done' " +
                "> ${WireGuardPaths.LOG} 2>&1 &"
        }

        /** Shell snippet that records the proxy opt-in state (world-readable). */
        @JvmStatic
        fun buildProxyFlagCommand(enabled: Boolean): String =
            "mkdir -p ${WireGuardPaths.HOME} && chmod 711 ${WireGuardPaths.HOME} && " +
                "echo $enabled > ${WireGuardPaths.PROXY_FLAG} && " +
                "chmod 644 ${WireGuardPaths.PROXY_FLAG}"

        /**
         * Kill the given PIDs, but only when their cmdline really is wgproxy.
         * [pids] must be normalized decimal PIDs.
         */
        @JvmStatic
        fun buildKillPidsCommand(pids: List<String>): String {
            val safePids = pids.filter { it.isNotEmpty() && it.all(Char::isDigit) }
            if (safePids.isEmpty()) return "echo no-safe-wgproxy-pid"
            return buildString {
                append("for pid in ")
                append(safePids.joinToString(" "))
                append("; do ")
                append("if [ -r /proc/\$pid/cmdline ] && ")
                append("tr '\\000' ' ' < /proc/\$pid/cmdline | grep -q '/${WireGuardPaths.PROCESS_NAME}'; ")
                append("then kill \$pid 2>/dev/null; fi; ")
                append("done; sleep 1; ")
                // Anything that ignored SIGTERM
                append("for pid in ")
                append(safePids.joinToString(" "))
                append("; do ")
                append("if [ -r /proc/\$pid/cmdline ] && ")
                append("tr '\\000' ' ' < /proc/\$pid/cmdline | grep -q '/${WireGuardPaths.PROCESS_NAME}'; ")
                append("then kill -9 \$pid 2>/dev/null; fi; ")
                append("done; echo stopped")
            }
        }

        /** Peer endpoint (or tunnel address) to show next to "connected". */
        @JvmStatic
        fun describeEndpoint(status: JSONObject): String? {
            val peers = status.optJSONArray("peers")
            if (peers != null) {
                for (i in 0 until peers.length()) {
                    val endpoint = peers.optJSONObject(i)?.optString("endpoint", "").orEmpty()
                    if (endpoint.isNotEmpty()) return endpoint
                }
            }
            val addresses = status.optJSONArray("addresses")
            val first = addresses?.optString(0, "").orEmpty()
            return first.takeIf { it.isNotEmpty() }
        }
    }

    interface WireGuardCallback {
        fun onLog(message: String)
        fun onStarted(summary: String?)
        fun onStopped()
        fun onError(error: String)
    }

    fun launch(callback: WireGuardCallback) {
        hasConfig { configured ->
            if (!configured) {
                logManager.warn(TAG, "No WireGuard configuration")
                callback.onError("No WireGuard configuration")
                return@hasConfig
            }
            // Check the deployed payload before the running fast path: an app
            // update does not stop the UID-2000 wgproxy process.
            isDeploymentCurrent { deploymentCurrent ->
                getPids { pids ->
                    when {
                        pids.isNotEmpty() && deploymentCurrent ->
                            writeProxyFlag(true, callback) { reportStatus(callback) }

                        pids.isNotEmpty() ->
                            redeployRunning(pids, callback)

                        deploymentCurrent ->
                            launchInstalled(callback)

                        else ->
                            install(callback) { launchInstalled(callback) }
                    }
                }
            }
        }
    }

    private fun isDeploymentCurrent(callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = deploymentStatusCommand(),
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim().lineSequence().lastOrNull() == DEPLOYMENT_CURRENT)
                }

                override fun onError(error: String) {
                    // A probe failure must never bless an unknown payload.
                    callback(false)
                }
            }
        )
    }

    private fun redeployRunning(
        pids: List<String>,
        callback: WireGuardCallback,
        attempt: Int = 0
    ) {
        if (attempt >= 3) {
            val error = "Could not stop the stale WireGuard process for update"
            logManager.error(TAG, error)
            callback.onError(error)
            return
        }
        callback.onLog("Updating WireGuard for this app version...")
        adbShellExecutor.execute(
            command = buildKillPidsCommand(pids),
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    // Re-check: a Telegram or watchdog start can race the stop.
                    isDeploymentCurrent { deploymentCurrent ->
                        getPids { current ->
                            when {
                                current.isEmpty() ->
                                    install(callback) { launchInstalled(callback) }

                                deploymentCurrent ->
                                    writeProxyFlag(true, callback) { reportStatus(callback) }

                                else -> redeployRunning(current, callback, attempt + 1)
                            }
                        }
                    }
                }

                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to stop stale WireGuard process: $error")
                    callback.onError("Failed to update WireGuard: $error")
                }
            }
        )
    }

    private fun install(callback: WireGuardCallback, onComplete: () -> Unit) {
        val src = "${context.applicationInfo.nativeLibraryDir}/${WireGuardPaths.NATIVE_LIB}"
        val expectedVersion = BuildConfig.VERSION_CODE.toLong()
        val tmp = "${WireGuardPaths.BINARY}.new"

        callback.onLog("Installing WireGuard...")

        // Copy to a temp name and rename, so a binary that is still mapped
        // never hits "text file busy". The stamp is written last.
        adbShellExecutor.execute(
            command = "test -f $src && mkdir -p ${WireGuardPaths.HOME} && " +
                "chmod 711 ${WireGuardPaths.HOME} && " +
                "cp -f $src $tmp && chmod 755 $tmp && " +
                "mv -f $tmp ${WireGuardPaths.BINARY} && " +
                "printf '$expectedVersion\\n' > ${WireGuardPaths.VERSION_FILE} && " +
                "chmod 644 ${WireGuardPaths.VERSION_FILE}",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback.onLog("WireGuard installed")
                    onComplete()
                }

                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to install WireGuard: $error")
                    callback.onError("Failed to install WireGuard: $error")
                }
            }
        )
    }

    private fun launchInstalled(callback: WireGuardCallback) {
        val useUpstream = ProxyHelper.probePort(UPSTREAM_PORT)
        // Clear the previous run's status so the poll below only sees fresh state.
        writeProxyFlag(true, callback, "rm -f ${WireGuardPaths.STATUS}; ") {
            adbShellExecutor.execute(
                command = buildLaunchCommand(useUpstream),
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        logManager.info(TAG, "WireGuard process started (upstream=$useUpstream)")
                        callback.onLog("WireGuard process started")
                        pollStatus(0, callback)
                    }

                    override fun onError(error: String) {
                        logManager.error(TAG, "Failed to start WireGuard: $error")
                        callback.onError("Failed to start WireGuard: $error")
                    }
                }
            )
        }
    }

    private fun writeProxyFlag(
        enabled: Boolean,
        callback: WireGuardCallback,
        prefix: String = "",
        onDone: () -> Unit
    ) {
        adbShellExecutor.execute(
            command = prefix + buildProxyFlagCommand(enabled),
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) = onDone()

                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to write proxy flag: $error")
                    callback.onError("Failed to start WireGuard: $error")
                }
            }
        )
    }

    private fun pollStatus(attempt: Int, callback: WireGuardCallback) {
        readStatus { status ->
            val state = status?.optString("state", "").orEmpty()
            when {
                state == "connected" -> callback.onStarted(describeEndpoint(status!!))
                state == "error" -> {
                    val error = status!!.optString("error", "").ifEmpty { "WireGuard error" }
                    logManager.error(TAG, "WireGuard reported error: $error")
                    callback.onError(error)
                }
                attempt + 1 >= STATUS_POLL_ATTEMPTS -> {
                    // Still handshaking (or no status yet): the process is up,
                    // the 30s refresh shows the final state.
                    callback.onStarted(status?.let { describeEndpoint(it) })
                }
                else -> pollScheduler.schedule(
                    { pollStatus(attempt + 1, callback) },
                    STATUS_POLL_DELAY_MS,
                    TimeUnit.MILLISECONDS
                )
            }
        }
    }

    private fun reportStatus(callback: WireGuardCallback) {
        readStatus { status ->
            val state = status?.optString("state", "").orEmpty()
            if (state == "error") {
                callback.onError(status!!.optString("error", "").ifEmpty { "WireGuard error" })
            } else {
                callback.onLog("WireGuard already running")
                callback.onStarted(status?.let { describeEndpoint(it) })
            }
        }
    }

    fun stop(callback: WireGuardCallback) {
        logManager.info(TAG, "Stopping WireGuard tunnel...")
        callback.onLog("Stopping WireGuard tunnel...")
        // Flag first so consumers stop expecting the proxy. Config and status stay.
        adbShellExecutor.execute(
            command = buildProxyFlagCommand(false),
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) = killAll(callback)
                override fun onError(error: String) = killAll(callback)
            }
        )
    }

    private fun killAll(callback: WireGuardCallback) {
        // pidof instead of pkill -f: the executor wraps commands in `sh -c`,
        // whose argv contains "wgproxy" and would be killed with it.
        getPids { pids ->
            val finish = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) = done()
                override fun onError(error: String) {
                    // Even on error, consider it stopped
                    logManager.warn(TAG, "WireGuard stop finished with warning: $error")
                    done()
                }

                private fun done() {
                    logManager.info(TAG, "WireGuard tunnel stopped")
                    callback.onStopped()
                }
            }
            adbShellExecutor.execute(
                command = buildKillPidsCommand(pids) + "; rm -f ${WireGuardPaths.LOG}",
                callback = finish
            )
        }
    }

    private fun getPids(callback: (List<String>) -> Unit) {
        adbShellExecutor.execute(
            command = "pidof ${WireGuardPaths.PROCESS_NAME} 2>/dev/null || " +
                "ps -A | grep ${WireGuardPaths.PROCESS_NAME} | grep -v grep | awk '{print \$2}'",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(
                        output.trim()
                            .split(Regex("\\s+"))
                            .filter { it.isNotEmpty() && it.all(Char::isDigit) }
                            .distinct()
                            .sortedBy { it.toLongOrNull() ?: Long.MAX_VALUE }
                    )
                }

                override fun onError(error: String) {
                    callback(emptyList())
                }
            }
        )
    }

    fun isRunning(callback: (Boolean) -> Unit) {
        getPids { callback(it.isNotEmpty()) }
    }

    fun hasConfig(callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = "test -s ${WireGuardPaths.CONFIG} && echo yes",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim() == "yes")
                }

                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }

    fun readStatus(callback: (JSONObject?) -> Unit) {
        adbShellExecutor.execute(
            command = "cat ${WireGuardPaths.STATUS} 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(
                        try {
                            JSONObject(output.trim())
                        } catch (_: Exception) {
                            null
                        }
                    )
                }

                override fun onError(error: String) {
                    callback(null)
                }
            }
        )
    }
}
