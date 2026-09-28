package com.overdrive.app.logging

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized logging manager for app-context code (Kotlin).
 * 
 * App logs are written to the app's cache directory.
 * Daemon logs (via DaemonLogger.java) go to /data/local/tmp.
 */
class LogManager private constructor(@Volatile private var config: LogConfig) {
    
    interface LogListener {
        fun onLog(tag: String, message: String, level: LogLevel)
    }
    
    companion object {
        @Volatile
        private var instance: LogManager? = null
        
        @Volatile
        private var logListener: LogListener? = null
        
        private const val TAG = "LogManager"
        
        fun getInstance(config: LogConfig = LogConfig.default()): LogManager {
            return instance ?: synchronized(this) {
                instance ?: LogManager(config).also { instance = it }
            }
        }
        
        fun setLogListener(listener: LogListener?) {
            logListener = listener
        }
        
        fun getLogListener(): LogListener? = logListener
    }
    
    private val writers = ConcurrentHashMap<String, PrintWriter>()
    private val fileSizes = ConcurrentHashMap<String, Long>()
    private val writeLock = Any()
    private var lastFlushTime = 0L
    private var unflushedCount = 0
    private val flushIntervalMs = 2500L
    private val maxUnflushedLines = 25
    // ThreadLocal: SimpleDateFormat is NOT thread-safe, and format() below runs
    // OUTSIDE writeLock, concurrently from the daemon-health-check looper, the
    // adb-shell executor and the tunnel-poll looper. A shared instance can throw
    // ArrayIndexOutOfBoundsException/NumberFormatException under that race.
    // DaemonLogger.java already uses exactly this fix; LogManager never got it.
    private val timestampFormat: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }

    fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        // Snapshot the volatile config ONCE so the console gate, file gate, and
        // logDir all read a single consistent instance even if updateConfig()
        // swaps it on another thread mid-call.
        val cfg = config
        // Level gate for the EXPENSIVE sinks only (logcat + the synchronously
        // FLUSHED file append, plus the timestamp format + string build they need).
        // Background paths call debug() on every adb shell command, so an ungated
        // DEBUG did a flushed disk write under a process-global lock thousands of
        // times a day. Mirrors DaemonLogger's minLevel gate.
        //
        // The listener is deliberately OUTSIDE this gate: it feeds the in-app Logs
        // screen (MainActivity.setupLogListener → LogsViewModel.addLog), which has
        // its own DEBUG level and does its own filtering. Early-returning before it
        // would silently empty the DEBUG view — a user-visible regression, not an
        // optimisation. Notifying a listener is a cheap in-memory call.
        try {
            logListener?.onLog(tag, message, level)
        } catch (e: Exception) {
            // Ignore listener errors
        }

        if (level.ordinal < cfg.minLevel.ordinal) return
        val timestamp = timestampFormat.get()!!.format(Date())
        val logLine = "[$timestamp] [${level.name}] [$tag] $message"

        if (cfg.enableConsoleLog) {
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, message)
                LogLevel.INFO -> Log.i(tag, message)
                LogLevel.WARN -> Log.w(tag, message)
                LogLevel.ERROR -> Log.e(tag, message)
            }
        }

        if (cfg.enableFileLog && cfg.logDir.isNotEmpty()) {
            val immediate = (level == LogLevel.ERROR || level == LogLevel.WARN)
            writeToFile(tag, logLine, immediate)
        }
    }
    
    private fun writeToFile(tag: String, logLine: String, immediateFlush: Boolean) {
        synchronized(writeLock) {
            try {
                if (writers.containsKey(tag) &&
                    !File(config.logDir, "${tag.lowercase()}.log").exists()) {
                    writers.remove(tag)?.close()
                    fileSizes.remove(tag)
                }
                val writer = getOrCreateWriter(tag)
                writer.println(logLine)
                unflushedCount++
                val now = System.currentTimeMillis()
                if (immediateFlush || unflushedCount >= maxUnflushedLines || (now - lastFlushTime) >= flushIntervalMs) {
                    writer.flush()
                    lastFlushTime = now
                    unflushedCount = 0
                }
                
                val currentSize = fileSizes.getOrDefault(tag, 0L) + logLine.length + 1
                fileSizes[tag] = currentSize

                // Long arithmetic: maxFileSizeMB is Int, so `maxFileSizeMB *
                // 1024 * 1024` would be computed in 32-bit and silently wrap
                // negative for maxFileSizeMB >= 2048 (→ rotate on every write).
                // .toLong() forces 64-bit. Matches DaemonLogger.java:343.
                if (currentSize >= config.maxFileSizeMB.toLong() * 1024L * 1024L) {
                    rotateLogFile(tag)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log: ${e.message}")
            }
        }
    }
    
    private fun getOrCreateWriter(tag: String): PrintWriter {
        return writers.getOrPut(tag) {
            val logFile = File(config.logDir, "${tag.lowercase()}.log")
            logFile.parentFile?.mkdirs()

            // If the pre-existing file is already over the cap (e.g. a prior
            // process wrote a huge log), rotate it now so the appended writer
            // doesn't start beyond the threshold. Must run BEFORE seeding the
            // size and opening the writer.
            if (logFile.exists() &&
                logFile.length() >= config.maxFileSizeMB.toLong() * 1024L * 1024L) {
                rotateExisting(tag)
            }

            fileSizes[tag] = if (logFile.exists()) logFile.length() else 0L
            PrintWriter(FileOutputStream(logFile, true).bufferedWriter(Charsets.UTF_8), false)
        }
    }

    /**
     * Rotate a log file that is found already-oversized at writer-open time,
     * without going through the writers map (the writer for [tag] does not
     * exist yet). Shifts .1→.2→.3 (oldest deleted) then moves the live file
     * to .1, matching [rotateLogFile].
     */
    private fun rotateExisting(tag: String) {
        try {
            val logFile = File(config.logDir, "${tag.lowercase()}.log")
            for (i in config.rotationCount downTo 1) {
                val oldFile = File(config.logDir, "${tag.lowercase()}.log.$i")
                if (i == config.rotationCount) {
                    oldFile.delete()
                } else {
                    val newFile = File(config.logDir, "${tag.lowercase()}.log.${i + 1}")
                    if (oldFile.exists()) oldFile.renameTo(newFile)
                }
            }
            File(config.logDir, "${tag.lowercase()}.log.1").let { logFile.renameTo(it) }
            fileSizes[tag] = 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate pre-existing log file: ${e.message}")
        }
    }
    
    private fun rotateLogFile(tag: String) {
        try {
            writers[tag]?.close()
            writers.remove(tag)
            
            val logFile = File(config.logDir, "${tag.lowercase()}.log")
            
            for (i in config.rotationCount downTo 1) {
                val oldFile = File(config.logDir, "${tag.lowercase()}.log.$i")
                if (i == config.rotationCount) {
                    oldFile.delete()
                } else {
                    val newFile = File(config.logDir, "${tag.lowercase()}.log.${i + 1}")
                    oldFile.renameTo(newFile)
                }
            }
            
            val rotatedFile = File(config.logDir, "${tag.lowercase()}.log.1")
            logFile.renameTo(rotatedFile)
            
            fileSizes[tag] = 0L
            Log.i(TAG, "Rotated log file: ${tag.lowercase()}.log")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log file: ${e.message}")
        }
    }
    
    fun debug(tag: String, message: String) = log(tag, message, LogLevel.DEBUG)
    fun info(tag: String, message: String) = log(tag, message, LogLevel.INFO)
    fun warn(tag: String, message: String) = log(tag, message, LogLevel.WARN)
    
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message: ${throwable.message}"
        } else {
            message
        }
        log(tag, fullMessage, LogLevel.ERROR)
    }
    
    fun updateConfig(newConfig: LogConfig) {
        synchronized(writeLock) {
            if (newConfig.isValid()) {
                this.config = newConfig
            }
        }
    }
    
    fun getConfig(): LogConfig = config
    
    // Cleanup stats
    @Volatile
    private var cleanupStats = CleanupStats(0, 0, 0)
    
    /**
     * Get cleanup statistics.
     */
    fun getCleanupStats(): CleanupStats = cleanupStats
    
    /**
     * Update cleanup statistics (called by LogCleaner).
     */
    internal fun updateCleanupStats(stats: CleanupStats) {
        this.cleanupStats = stats
    }
    
    fun stop() {
        synchronized(writeLock) {
            writers.values.forEach { it.close() }
            writers.clear()
            fileSizes.clear()
        }
    }
}

data class CleanupStats(
    val lastCleanupTime: Long,
    val filesDeleted: Int,
    val spaceFreeKB: Long
)
