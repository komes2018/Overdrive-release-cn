package com.overdrive.app.parking;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.geo.GeocodingResolver;
import com.overdrive.app.geo.PlaceResult;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.GpsMonitor;
import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 驻车耗电追踪器 (ParkingDrainTracker)
 * 
 * 核心机制：
 * 1. 车辆熄火 (ACC OFF) 瞬间：
 *    在后台线程将当前时间戳、动力电池 SOC、剩余容量 kWh、12V 小电瓶电压、GPS 坐标及位置名称写入持久化文件；
 * 2. 停放期间：
 *    零后台轮询、零唤醒锁、零额外耗电；
 * 3. 车辆点火 (ACC ON) 瞬间：
 *    读取持久化文件，结算停放总时长、SOC 耗电百分比、度数与 12V 电压变化，并发布 vehicle.parking.drain 事件至 NotificationBus (进而推送至 Webhook)。
 */
public final class ParkingDrainTracker {

    private static final DaemonLogger logger = DaemonLogger.getInstance("ParkingDrainTracker");
    private static final String STATE_FILE_PATH = "/data/local/tmp/parking_drain_state.json";
    public static final String CATEGORY_PARKING_DRAIN = "vehicle.parking.drain";

    // 过滤不足 2 分钟的瞬时熄火重开
    private static final long MIN_PARKED_DURATION_MS = 120_000L;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ParkingDrainWorker");
        t.setDaemon(true);
        return t;
    });

    private ParkingDrainTracker() {}

    /**
     * ACC 边沿触发入口 (由 CameraDaemon 权威 transition 调用)
     *
     * @param accIsOff true 表示熄火 (ACC OFF)，false 表示点火 (ACC ON)
     */
    public static void onAccEdge(boolean accIsOff) {
        executor.execute(() -> {
            try {
                if (accIsOff) {
                    handleAccOff();
                } else {
                    handleAccOn();
                }
            } catch (Throwable t) {
                logger.warn("onAccEdge failed: " + t.getMessage());
            }
        });
    }

    private static void handleAccOff() {
        long now = System.currentTimeMillis();
        BydVehicleData data = getVehicleData();

        double soc = data != null ? data.socPercent : Double.NaN;
        double kwh = data != null ? data.remainKwh : Double.NaN;
        double volt12v = data != null ? data.voltage12v : Double.NaN;

        double lat = 0.0;
        double lng = 0.0;
        String place = null;

        try {
            GpsMonitor gps = GpsMonitor.getInstance();
            if (gps != null && gps.hasLocation()) {
                lat = gps.getLatitude();
                lng = gps.getLongitude();
                PlaceResult pr = GeocodingResolver.getInstance().resolveCachedOnly(lat, lng);
                if (pr != null) {
                    place = pr.shortName != null && !pr.shortName.isEmpty() ? pr.shortName : pr.displayName;
                }
            }
        } catch (Throwable ignored) {}

        JSONObject json = new JSONObject();
        try {
            json.put("parkedAtMs", now);
            if (!Double.isNaN(soc)) json.put("startSoc", soc);
            if (!Double.isNaN(kwh)) json.put("startKwh", kwh);
            if (!Double.isNaN(volt12v)) json.put("start12v", volt12v);
            if (lat != 0.0 && lng != 0.0) {
                json.put("lat", lat);
                json.put("lng", lng);
            }
            if (place != null && !place.isEmpty()) {
                json.put("place", place);
            }

            File f = new File(STATE_FILE_PATH);
            File tmp = new File(STATE_FILE_PATH + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(json.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            tmp.renameTo(f);
            logger.info("Recorded parked state: time=" + now + ", soc=" + soc + ", 12v=" + volt12v);
        } catch (Throwable t) {
            logger.warn("Failed to record parked state: " + t.getMessage());
        }
    }

    private static void handleAccOn() {
        File f = new File(STATE_FILE_PATH);
        if (!f.exists() || !f.isFile()) {
            return;
        }

        JSONObject json = null;
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            fis.read(buf);
            json = new JSONObject(new String(buf, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            logger.warn("Failed to read parked state: " + t.getMessage());
            f.delete();
            return;
        }

        // 读完后清除状态文件，避免重复结算
        f.delete();

        if (json == null) return;

        long parkedAtMs = json.optLong("parkedAtMs", 0L);
        long now = System.currentTimeMillis();
        long durationMs = now - parkedAtMs;

        // 过滤不足 2 分钟的瞬时熄火
        if (parkedAtMs <= 0 || durationMs < MIN_PARKED_DURATION_MS) {
            logger.info("Parked duration too short (" + (durationMs / 1000) + "s), skip notification");
            return;
        }

        double startSoc = json.optDouble("startSoc", Double.NaN);
        double startKwh = json.optDouble("startKwh", Double.NaN);
        double start12v = json.optDouble("start12v", Double.NaN);
        String place = json.optString("place", "");
        double lat = json.optDouble("lat", 0.0);
        double lng = json.optDouble("lng", 0.0);

        BydVehicleData data = getVehicleData();
        double endSoc = data != null ? data.socPercent : Double.NaN;
        double endKwh = data != null ? data.remainKwh : Double.NaN;
        double end12v = data != null ? data.voltage12v : Double.NaN;

        if (place.isEmpty() && lat != 0.0 && lng != 0.0) {
            try {
                PlaceResult pr = GeocodingResolver.getInstance().resolveCachedOnly(lat, lng);
                if (pr != null) {
                    place = pr.shortName != null && !pr.shortName.isEmpty() ? pr.shortName : pr.displayName;
                }
            } catch (Throwable ignored) {}
        }

        publishParkingDrainReport(parkedAtMs, now, durationMs, startSoc, endSoc, startKwh, endKwh, start12v, end12v, place);
    }

    private static void publishParkingDrainReport(long startMs, long endMs, long durationMs,
                                                  double startSoc, double endSoc,
                                                  double startKwh, double endKwh,
                                                  double start12v, double end12v,
                                                  String place) {
        StringBuilder sb = new StringBuilder();

        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        String timeStr = sdf.format(new Date(startMs)) + " → " + sdf.format(new Date(endMs));
        sb.append("• 停放时间：").append(timeStr).append(" (共 ").append(formatDuration(durationMs)).append(")");

        // 动力电量变化
        if (!Double.isNaN(startSoc) && !Double.isNaN(endSoc) && startSoc > 0 && endSoc > 0) {
            double socDiff = startSoc - endSoc;
            if (socDiff > 0) {
                sb.append("\n• 动力电量：").append(String.format(Locale.CHINA, "%.0f%% → %.0f%% (消耗 %.1f%%)",
                        startSoc, endSoc, socDiff));
            } else if (socDiff < 0) {
                sb.append("\n• 动力电量：").append(String.format(Locale.CHINA, "%.0f%% → %.0f%% (充入 %.1f%%)",
                        startSoc, endSoc, -socDiff));
            } else {
                sb.append("\n• 动力电量：").append(String.format(Locale.CHINA, "%.0f%% (保持不变)", endSoc));
            }
        }

        // 动力电量度数 (kWh) 及平均时耗
        double hours = durationMs / 3600000.0;
        if (!Double.isNaN(startKwh) && !Double.isNaN(endKwh) && startKwh > 0 && endKwh > 0) {
            double kwhDiff = startKwh - endKwh;
            if (kwhDiff > 0) {
                sb.append("\n• 消耗电量：").append(String.format(Locale.CHINA, "%.2f kWh", kwhDiff));
                if (hours >= 1.0) {
                    sb.append(String.format(Locale.CHINA, " (时均 %.2f kWh/h)", kwhDiff / hours));
                }
            } else if (kwhDiff < 0) {
                sb.append("\n• 充入电量：").append(String.format(Locale.CHINA, "%.2f kWh", -kwhDiff));
            }
        } else if (!Double.isNaN(startSoc) && !Double.isNaN(endSoc) && startSoc > 0 && endSoc > 0) {
            double socDiff = startSoc - endSoc;
            if (socDiff > 0) {
                // 基于典型比亚迪 PHEV/EV 电池估算消耗 (默认参考 18.3 kWh 或标称)
                double estKwh = socDiff * 0.183;
                sb.append("\n• 估算耗电：").append(String.format(Locale.CHINA, "约 %.2f kWh", estKwh));
                if (hours >= 1.0) {
                    sb.append(String.format(Locale.CHINA, " (时均 %.2f kWh/h)", estKwh / hours));
                }
            }
        }

        // 12V 小电瓶电压
        if (!Double.isNaN(start12v) && !Double.isNaN(end12v) && start12v > 5.0 && end12v > 5.0) {
            sb.append("\n• 12V 电瓶：").append(String.format(Locale.CHINA, "%.1f V → %.1f V", start12v, end12v));
            if (end12v < 11.9) {
                sb.append(" (⚠️ 电压偏低)");
            } else {
                sb.append(" (正常)");
            }
        }

        // 停放地点
        if (place != null && !place.trim().isEmpty()) {
            sb.append("\n• 停放地点：").append(place.trim());
        }

        String body = sb.toString();
        logger.info("Publishing parking drain report:\n" + body);

        JSONObject data = new JSONObject();
        try {
            data.put("durationMs", durationMs);
            if (!Double.isNaN(startSoc)) data.put("startSoc", startSoc);
            if (!Double.isNaN(endSoc)) data.put("endSoc", endSoc);
            if (!Double.isNaN(start12v)) data.put("start12v", start12v);
            if (!Double.isNaN(end12v)) data.put("end12v", end12v);
        } catch (Throwable ignored) {}

        NotificationEvent event = new NotificationEvent(
                CATEGORY_PARKING_DRAIN,
                NotificationEvent.Severity.INFO,
                "车辆驻车耗电报告",
                body,
                "parking:drain:" + endMs,
                "/parking.html",
                data
        );

        NotificationBus.get().publish(event);
    }

    public static String formatDuration(long ms) {
        long seconds = ms / 1000L;
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long mins = (seconds % 3600L) / 60L;

        if (days > 0) {
            return days + "天" + hours + "小时" + mins + "分";
        } else if (hours > 0) {
            return hours + "小时" + mins + "分";
        } else {
            return mins + "分钟";
        }
    }

    private static BydVehicleData getVehicleData() {
        try {
            BydDataCollector collector = BydDataCollector.getInstance();
            if (collector != null && collector.isInitialized()) {
                return collector.getData();
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
