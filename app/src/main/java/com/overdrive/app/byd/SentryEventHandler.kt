package com.overdrive.app.byd

import com.overdrive.app.byd.radar.RadarConstants
import com.overdrive.app.client.CameraDaemonClient
import com.overdrive.app.logging.LogManager
import org.json.JSONObject

/**
 * Handles BYD events for Sentry Mode functionality.
 * 
 * Actions:
 * - Radar YELLOW/RED → Start recording
 * - Alarm triggered → Start recording
 * - Door/Window opened (when parked) → Start recording
 * 
 * Usage:
 *   SentryEventHandler.daemonClient = myDaemonClient
 *   BydEventClient.addListener(SentryEventHandler)
 *   BydEventClient.connect()
 */
object SentryEventHandler : BydEventClient.EventListener {
    
    private const val TAG = "SentryEventHandler"
    
    private val logger = LogManager.getInstance()
    
    // DaemonClient for camera control
    var daemonClient: CameraDaemonClient? = null
    
    // Which cameras to record in sentry mode (default: all 4)
    var sentryCameras: Set<Int> = setOf(1, 2, 3, 4)
    
    // State
    private var sentryModeEnabled = false
    private var currentPowerLevel = -1
    private var isRecording = false
    
    // Track all 8 radar sensor states
    private val radarStates = IntArray(8) { RadarConstants.STATE_SAFE }
    
    // Callbacks for UI notifications
    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingStopped: (() -> Unit)? = null
    var onSentryAlert: ((reason: String) -> Unit)? = null
    var onSentryModeChanged: ((enabled: Boolean) -> Unit)? = null
    
    private fun log(msg: String) {
        logger.info(TAG, msg)
    }
    
    // ==================== PUBLIC API ====================
    
    fun enableSentryMode() {
        sentryModeEnabled = true
        log("Sentry mode ENABLED")
        onSentryModeChanged?.invoke(true)
    }
    
    fun disableSentryMode() {
        sentryModeEnabled = false
        log("Sentry mode DISABLED")
        // Stop recording if active
        if (isRecording) {
            stopRecording()
        }
        onSentryModeChanged?.invoke(false)
    }
    
    fun toggleSentryMode(): Boolean {
        if (sentryModeEnabled) {
            disableSentryMode()
        } else {
            enableSentryMode()
        }
        return sentryModeEnabled
    }
    
    fun isSentryModeEnabled() = sentryModeEnabled
    
    /** Check if vehicle is parked (ACC OFF) */
    fun isParked() = currentPowerLevel == 0
    
    // ==================== EVENT HANDLERS ====================
    
    override fun onConnected() {
        log("Connected to BydEventDaemon")
    }
    
    override fun onDisconnected() {
        log("Disconnected from BydEventDaemon")
    }
    
    override fun onPowerLevelChanged(level: Int, levelName: String) {
        log("Power level: $levelName (was: $currentPowerLevel)")
        
        val wasParked = currentPowerLevel == 0
        currentPowerLevel = level
        
        when (level) {
            0 -> { // OFF - Vehicle parked
                if (!wasParked) {
                    log("Vehicle parked - sentry mode active")
                }
            }
            1, 2 -> { // ACC or ON - Vehicle active
                if (wasParked && isRecording) {
                    log("Vehicle started - stopping sentry recording")
                    stopRecording()
                }
            }
        }
    }
    
    override fun onRadarEvent(area: Int, areaName: String, state: Int, stateName: String) {
        // Update tracked state
        if (area in 0..7) {
            radarStates[area] = state
        }
        
        log("Radar event: $areaName = $stateName (sentryEnabled=$sentryModeEnabled, parked=${isParked()})")
        
        if (!sentryModeEnabled) return
        if (!isParked()) return
        
        when (state) {
            RadarConstants.STATE_YELLOW -> {
                log("Radar YELLOW at $areaName - starting recording")
                triggerAlert("Radar: $areaName proximity warning")
                startRecording()
            }
            RadarConstants.STATE_RED -> {
                log("Radar RED at $areaName - immediate threat!")
                triggerAlert("Radar: $areaName close proximity!")
                startRecording()
            }
            RadarConstants.STATE_SAFE, RadarConstants.STATE_GREEN -> {
                // Stop recording only if ALL sensors are safe/green
                if (isRecording && allSensorsSafe()) {
                    log("All sensors safe - stopping recording")
                    stopRecording()
                }
            }
        }
    }
    
    /** Check if all radar sensors are in safe or green state */
    private fun allSensorsSafe(): Boolean {
        // ABNORMAL (1) means sensor is inactive/not detecting - treat as safe
        // SAFE (0) and GREEN (2) are explicitly safe states
        return radarStates.all { 
            it == RadarConstants.STATE_SAFE || 
            it == RadarConstants.STATE_GREEN ||
            it == RadarConstants.STATE_ABNORMAL
        }
    }
    
    override fun onDoorEvent(area: Int, open: Boolean) {
        log("Door event: area=$area open=$open")
        if (!sentryModeEnabled) return
        if (!isParked()) return
        
        if (open) {
            log("Door $area OPENED while parked!")
            triggerAlert("Door opened")
            startRecording()
        }
    }
    
    override fun onWindowEvent(area: Int, open: Boolean) {
        log("Window event: area=$area open=$open")
        if (!sentryModeEnabled) return
        if (!isParked()) return
        
        if (open) {
            log("Window $area OPENED while parked!")
            triggerAlert("Window opened")
            startRecording()
        }
    }
    
    override fun onAlarmEvent(active: Boolean) {
        log("Alarm event: active=$active")
        if (!sentryModeEnabled) return
        
        if (active) {
            log("ALARM TRIGGERED!")
            triggerAlert("Vehicle alarm!")
            startRecording()
        }
    }
    
    override fun onRawEvent(event: JSONObject) {
        // Can be used for logging/debugging
    }
    
    // ==================== ACTIONS ====================
    
    private fun triggerAlert(reason: String) {
        log("SENTRY ALERT: $reason")
        onSentryAlert?.invoke(reason)
    }
    
    private fun startRecording() {
        if (isRecording) {
            log("Already recording, skipping start")
            return
        }
        if (daemonClient == null) {
            log("ERROR: Cannot start recording - daemonClient not set")
            return
        }
        
        log("Starting sentry recording on cameras: $sentryCameras")
        isRecording = true
        
        daemonClient?.startRecording(sentryCameras, false, object : CameraDaemonClient.ResponseCallback {
            override fun onResponse(response: JSONObject) {
                val status = response.optString("status")
                if (status == "ok") {
                    log("Recording started successfully")
                    onRecordingStarted?.invoke()
                } else {
                    log("Recording failed: $response")
                    isRecording = false
                }
            }
            
            override fun onError(error: String) {
                log("Recording error: $error")
                isRecording = false
            }
        })
    }
    
    private fun stopRecording() {
        if (!isRecording) {
            log("Not recording, skipping stop")
            return
        }
        if (daemonClient == null) {
            log("ERROR: Cannot stop recording - daemonClient not set")
            return
        }
        
        log("Stopping sentry recording...")
        
        daemonClient?.stopRecording(sentryCameras, object : CameraDaemonClient.ResponseCallback {
            override fun onResponse(response: JSONObject) {
                log("Recording stopped: $response")
                isRecording = false
                onRecordingStopped?.invoke()
            }
            
            override fun onError(error: String) {
                log("Stop recording error: $error")
            }
        })
    }
}
