package com.overdrive.app.surveillance.impact;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.AccMonitor;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public abstract class ParkedImpactController {
    public static final DaemonLogger logger = DaemonLogger.getInstance("ParkedImpact");
    public static final Object lock = new Object();
    public static ScheduledExecutorService executor;
    public static boolean isRunning = false;
    public static String currentSensitivity = null;
    public static long lastStartTime = 0;
    public static long g = 0;
    private static String sensorState = "waiting";
    private static String sensorName = "";
    private static long lastSensorReportMs = 0L;
    private static long lastSampleCount = 0L;

    public static void startMonitoring() {
        synchronized (lock) {
            if (executor != null) {
                return;
            }
            executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "ParkedImpactMonitor");
                    t.setDaemon(true);
                    return t;
                }
            });
            executor.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    check();
                }
            }, 30000, 30000, TimeUnit.MILLISECONDS);
        }
    }

    public static void check() {
        boolean impactDetection = false;
        try {
            JSONObject config = UnifiedConfigManager.getSurveillance();
            if (config != null) {
                impactDetection = config.optBoolean("impactDetection", false);
            }
        } catch (Throwable t) {
            impactDetection = false;
        }

        boolean shouldRun = false;
        if (impactDetection) {
            boolean inSentry = AccMonitor.isInSentryMode();
            boolean accOn = AccMonitor.isAccOn();
            boolean surveillanceActive = CameraDaemon.isSurveillanceActive() || CameraDaemon.isSurveillanceEnabled();
            if ((inSentry || !accOn) && surveillanceActive) {
                shouldRun = true;
            }
        }

        String sensitivity = "medium";
        try {
            JSONObject config = UnifiedConfigManager.getSurveillance();
            if (config != null) {
                String s = config.optString("impactSensitivity", "medium");
                if ("low".equalsIgnoreCase(s) || "high".equalsIgnoreCase(s)) {
                    sensitivity = s.toLowerCase();
                }
            }
        } catch (Throwable ignored) {}

        long now = System.currentTimeMillis();
        synchronized (lock) {
            boolean sensitivityChanged = isRunning && !sensitivity.equals(currentSensitivity);
            if (shouldRun) {
                if (!isRunning || sensitivityChanged || (now - lastStartTime >= 300000)) {
                    ParkedImpactSensorService.Companion.start(sensitivity);
                    if (!isRunning) {
                        logger.info("Started parked impact sensor service with sensitivity=" + sensitivity);
                    }
                    isRunning = true;
                    currentSensitivity = sensitivity;
                    lastStartTime = now;
                }
            } else {
                if (isRunning) {
                    ParkedImpactSensorService.Companion.stop();
                    isRunning = false;
                    currentSensitivity = null;
                    logger.info("Stopped parked impact sensor service");
                }
                sensorState = "inactive";
                sensorName = "";
                lastSensorReportMs = 0L;
                lastSampleCount = 0L;
            }
        }
    }

    /** Record the app-process sensor heartbeat. */
    public static void onSensorStatus(String state, long samples, String name) {
        if (!"registered".equals(state) && !"sampling".equals(state)
                && !"unavailable".equals(state)) return;
        synchronized (lock) {
            sensorState = state;
            sensorName = name == null ? "" : name;
            lastSampleCount = Math.max(0L, samples);
            lastSensorReportMs = System.currentTimeMillis();
        }
    }

    /** Snapshot for the WebUI; stale heartbeats are never presented as healthy. */
    public static JSONObject getSensorStatus() {
        boolean enabled = false;
        try {
            JSONObject config = UnifiedConfigManager.getSurveillance();
            if (config != null) {
                enabled = config.optBoolean("impactDetection", false);
            }
        } catch (Throwable ignored) {}
        boolean watchWindow = false;
        try {
            boolean inSentry = AccMonitor.isInSentryMode();
            boolean accOn = AccMonitor.isAccOn();
            boolean surveillanceActive = CameraDaemon.isSurveillanceActive() || CameraDaemon.isSurveillanceEnabled();
            watchWindow = (inSentry || !accOn) && surveillanceActive;
        } catch (Throwable ignored) {}
        synchronized (lock) {
            long age = lastSensorReportMs == 0 ? Long.MAX_VALUE
                    : Math.max(0L, System.currentTimeMillis() - lastSensorReportMs);
            String state = age > 45_000L ? (enabled && watchWindow ? "stale" : "inactive")
                    : sensorState;
            JSONObject result = new JSONObject();
            try {
                result.put("enabled", enabled);
                result.put("watchWindow", watchWindow);
                result.put("state", state);
                result.put("sensor", sensorName);
                result.put("lastReportAgeMs", age == Long.MAX_VALUE ? JSONObject.NULL : age);
                result.put("samples", lastSampleCount);
            } catch (Exception ignored) {}
            return result;
        }
    }

    public static void triggerCheck() {
        ScheduledExecutorService exec;
        synchronized (lock) {
            exec = executor;
        }
        if (exec != null) {
            exec.execute(new Runnable() {
                @Override
                public void run() {
                    check();
                }
            });
        }
    }
}
