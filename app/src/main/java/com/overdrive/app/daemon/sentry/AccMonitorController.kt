package com.overdrive.app.daemon.sentry

import com.overdrive.app.logging.DaemonLogger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Controls ACC (Accessory) mode monitoring for sentry mode.
 * 
 * Monitors sys.accanim.status property to detect ACC ON/OFF transitions.
 * When ACC goes OFF, triggers sentry mode entry.
 * 
 * Extracted from SentryDaemon for better separation of concerns.
 */
class AccMonitorController(
    private val onAccOff: () -> Unit,
    private val onAccOn: () -> Unit
) {
    
    companion object {
        private val logger = DaemonLogger.getInstance("AccMonitorController")
        
        // Power levels from BYDAutoBodyworkDevice
        const val POWER_LEVEL_OFF = 0
        const val POWER_LEVEL_ACC = 1
        const val POWER_LEVEL_ON = 2
        const val POWER_LEVEL_OK = 3
    }
    
    @Volatile
    private var running = true
    
    @Volatile
    private var lastAccAnimStatus = "0"
    
    private var pollingThread: Thread? = null
    
    /**
     * Start polling mode for ACC status monitoring.
     */
    fun startPolling() {
        logger.info("Starting polling mode (checking state every 500ms)...")
        
        // Log initial state
        logAllPowerSources()
        
        pollingThread = Thread({
            var pollCount = 0
            
            // Get initial state - treat empty/"0" as ACC ON
            lastAccAnimStatus = execShell("getprop sys.accanim.status").trim()
            if (lastAccAnimStatus.isEmpty()) {
                lastAccAnimStatus = "0" // Empty means ACC ON
            }
            logger.info("Initial sys.accanim.status: '$lastAccAnimStatus' (0 or empty = ACC ON)")
            
            // If we start with ACC already OFF (status != 0), enter sentry mode
            if (lastAccAnimStatus != "0") {
                logger.info("Started with ACC OFF (status=$lastAccAnimStatus) - entering sentry mode")
                onAccOff()
            } else {
                logger.info("Started with ACC ON - waiting for ACC OFF event...")
            }
            
            while (running) {
                try {
                    Thread.sleep(500) // Poll every 500ms for faster detection
                    pollCount++
                    
                    // Every 60 polls (30 seconds), log all sources for debugging
                    if (pollCount % 60 == 0) {
                        logAllPowerSources()
                    }
                    
                    // Check sys.accanim.status - THIS IS THE KEY INDICATOR
                    var accAnimStatus = execShell("getprop sys.accanim.status").trim()
                    if (accAnimStatus.isEmpty()) {
                        accAnimStatus = "0" // Empty means ACC ON
                    }
                    
                    if (accAnimStatus != lastAccAnimStatus) {
                        logger.info(">>> ACC ANIM STATUS CHANGED: '$accAnimStatus' (was: '$lastAccAnimStatus')")
                        
                        // sys.accanim.status != "0" means ACC OFF (shutdown animation started)
                        if (accAnimStatus != "0" && lastAccAnimStatus == "0") {
                            logger.info("!!! ACC OFF DETECTED (accanim.status=$accAnimStatus) !!!")
                            onAccOff()
                        }
                        // sys.accanim.status = "0" means ACC ON (normal operation restored)
                        else if (accAnimStatus == "0" && lastAccAnimStatus != "0") {
                            logger.info("!!! ACC ON DETECTED (accanim.status=0) !!!")
                            onAccOn()
                        }
                        
                        lastAccAnimStatus = accAnimStatus
                    }
                    
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    logger.error("Polling error: ${e.message}")
                    try { Thread.sleep(1000) } catch (ignored: Exception) {}
                }
            }
        }, "PowerLevelPoller")
        
        pollingThread?.start()
    }
    
    /**
     * Stop polling.
     */
    fun stopPolling() {
        running = false
        pollingThread?.interrupt()
        pollingThread = null
    }

    
    /**
     * Log power state from all available sources for debugging.
     */
    private fun logAllPowerSources() {
        logger.info("=== Power State Snapshot ===")
        
        // 1. Driving state from byd_car_service
        val drivingState = getDrivingState()
        logger.info("Driving State: $drivingState")
        
        // 2. ACC animation status
        val accAnimStatus = execShell("getprop sys.accanim.status")
        logger.info("sys.accanim.status: $accAnimStatus")
        
        // 3. ACC animation service
        val accAnimSvc = execShell("getprop init.svc.accanim")
        logger.info("init.svc.accanim: $accAnimSvc")
        
        // 4. accmodemanager dirty flag
        val accDump = execShell("dumpsys accmodemanager 2>/dev/null | head -5")
        logger.info("accmodemanager: ${accDump.replace("\n", " | ")}")
        
        // 5. Screen state
        val screenState = execShell("dumpsys power 2>/dev/null | grep -i 'Display Power' | head -1")
        logger.info("Display Power: $screenState")
        
        logger.info("=== End Snapshot ===")
    }
    
    /**
     * Get driving state from byd_car_service.
     */
    private fun getDrivingState(): Int {
        return try {
            val output = execShell("dumpsys byd_car_service 2>/dev/null | grep 'Current Driving State'")
            if (output.contains(":")) {
                val value = output.split(":")[1].trim()
                value.toInt()
            } else -1
        } catch (e: Exception) {
            -1
        }
    }
    
    /**
     * Convert power level to human-readable string.
     */
    fun powerLevelToString(level: Int): String {
        return when (level) {
            0 -> "OFF(0)"
            1 -> "ACC(1)"
            2 -> "ON(2)"
            3 -> "OK(3)"
            4 -> "FAKE_OK(4)"
            255 -> "INVALID(255)"
            else -> "UNKNOWN($level)"
        }
    }
    
    /**
     * Execute shell command.
     */
    private fun execShell(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            process.waitFor()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            output.toString().trim()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}
