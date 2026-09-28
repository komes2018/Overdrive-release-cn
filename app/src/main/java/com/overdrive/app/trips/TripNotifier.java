package com.overdrive.app.trips;

import com.overdrive.app.geo.GeocodingResolver;
import com.overdrive.app.geo.PlaceResult;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 负责在行程结束且车辆熄火后，生成行程综合报告并推送到 NotificationBus 及 Webhook。
 */
public final class TripNotifier {

    private static final DaemonLogger logger = DaemonLogger.getInstance("TripNotifier");
    public static final String CATEGORY_COMPLETED = "vehicle.trip.completed";

    private TripNotifier() {}

    /**
     * 推送本次行程报告。
     *
     * @param trip 已结算并存盘的行程记录
     */
    public static void notifyTripCompleted(TripRecord trip) {
        if (trip == null) return;
        // 过滤不足 100 米且不足 30 秒的极轻微挪车或晃动
        if (trip.distanceKm < 0.1 && trip.durationSeconds < 30) {
            logger.info("Trip too short (" + String.format(Locale.US, "%.2f km, %ds", trip.distanceKm, trip.durationSeconds)
                    + "), skipping webhook notification");
            return;
        }

        try {
            String title = "行程结束报告";
            String body = buildTripSummaryBody(trip);
            JSONObject data = buildTripData(trip);

            logger.info("Publishing trip completed notification for trip id=" + trip.id
                    + " (" + String.format(Locale.US, "%.1f km, %ds", trip.distanceKm, trip.durationSeconds) + ")");

            NotificationEvent event = new NotificationEvent(
                    CATEGORY_COMPLETED,
                    NotificationEvent.Severity.INFO,
                    title,
                    body,
                    "trip:" + trip.id,
                    "/trips.html#trip-" + trip.id,
                    data
            );

            NotificationBus.get().publish(event);

        } catch (Throwable t) {
            logger.error("Failed to notify trip completed: " + t.getMessage());
        }
    }

    private static String buildTripSummaryBody(TripRecord trip) {
        StringBuilder sb = new StringBuilder();

        // 1. 时间段与耗时
        String timeStr = formatTimeRange(trip.startTime, trip.endTime, trip.durationSeconds);
        sb.append("• 行程时间：").append(timeStr);

        // 2. 行驶里程
        sb.append("\n• 行驶里程：").append(String.format(Locale.CHINA, "%.1f km", trip.distanceKm));

        // 3. 动力电量变化
        if (trip.socStart > 0 && trip.socEnd > 0) {
            double diff = trip.socStart - trip.socEnd;
            if (diff > 0) {
                sb.append("\n• 动力电量：").append(String.format(Locale.CHINA, "%.0f%% → %.0f%% (消耗 %.0f%%)",
                        trip.socStart, trip.socEnd, diff));
            } else if (diff < 0) {
                sb.append("\n• 动力电量：").append(String.format(Locale.CHINA, "%.0f%% → %.0f%% (回充/增加 %.0f%%)",
                        trip.socStart, trip.socEnd, -diff));
            } else {
                sb.append("\n• 动力电量：").append(String.format(Locale.CHINA, "%.0f%% (保持不变)", trip.socEnd));
            }
        }

        // 4. 能耗统计 (电量 & 百公里电耗)
        double energyUsed = trip.getEnergyUsedKwh();
        if (energyUsed > 0 && trip.distanceKm > 0) {
            double kwhPer100 = (energyUsed / trip.distanceKm) * 100.0;
            sb.append("\n• 行程电耗：").append(String.format(Locale.CHINA, "%.2f kWh (%.1f kWh/100km)",
                    energyUsed, kwhPer100));
        } else if (energyUsed > 0) {
            sb.append("\n• 行程电耗：").append(String.format(Locale.CHINA, "%.2f kWh", energyUsed));
        }

        // 5. 燃油消耗 (PHEV 车型且有油耗)
        if (trip.isPhev && trip.litresUsed > 0) {
            if (trip.distanceKm > 0) {
                double lPer100 = (trip.litresUsed / trip.distanceKm) * 100.0;
                sb.append("\n• 燃油消耗：").append(String.format(Locale.CHINA, "%.1f L (%.1f L/100km)",
                        trip.litresUsed, lPer100));
            } else {
                sb.append("\n• 燃油消耗：").append(String.format(Locale.CHINA, "%.1f L", trip.litresUsed));
            }
        }

        // 6. 车速信息
        if (trip.avgSpeedKmh > 0 || trip.maxSpeedKmh > 0) {
            sb.append("\n• 车速统计：均速 ").append(String.format(Locale.CHINA, "%.0f km/h", trip.avgSpeedKmh))
              .append(" · 最高 ").append(trip.maxSpeedKmh).append(" km/h");
        }

        // 7. 驾驶评分 (DNA)
        int overall = trip.getOverallScore();
        if (overall > 0) {
            sb.append("\n• 驾驶评分：").append(overall).append(" 分");
            if (trip.smoothnessScore > 0 && trip.anticipationScore > 0) {
                sb.append(String.format(Locale.CHINA, " (平顺 %d / 预判 %d / 能效 %d)",
                        trip.smoothnessScore, trip.anticipationScore, trip.efficiencyScore));
            }
        }

        // 8. 预估费用
        if (trip.tripCost > 0) {
            String curr = (trip.currency != null && !trip.currency.isEmpty()) ? trip.currency : "¥";
            sb.append("\n• 本次费用：约 ").append(curr).append(String.format(Locale.CHINA, "%.2f", trip.tripCost));
        }

        // 9. 仪表总里程
        if (trip.odometerEndKm > 0) {
            sb.append("\n• 仪表总程：").append(String.format(Locale.CHINA, "%,.0f km", trip.odometerEndKm));
        }

        // 10. 到达地点 (尝试使用缓存逆地理编码，无网络开销)
        if (trip.endLat != 0 && trip.endLon != 0) {
            try {
                PlaceResult place = GeocodingResolver.getInstance().resolveCachedOnly(trip.endLat, trip.endLon);
                if (place != null) {
                    String placeName = place.shortName != null && !place.shortName.isEmpty()
                            ? place.shortName : place.displayName;
                    if (placeName != null && !placeName.isEmpty()) {
                        sb.append("\n• 到达位置：").append(placeName);
                    }
                }
            } catch (Throwable ignored) {}
        }

        return sb.toString();
    }

    private static String formatTimeRange(long startMs, long endMs, int durationSec) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.CHINA);
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));

        String range;
        if (startMs > 0 && endMs > 0) {
            range = sdf.format(new Date(startMs)) + " - " + sdf.format(new Date(endMs));
        } else if (endMs > 0) {
            range = sdf.format(new Date(endMs));
        } else {
            range = sdf.format(new Date());
        }

        String durStr = formatDuration(durationSec);
        return range + " (耗时 " + durStr + ")";
    }

    private static String formatDuration(int seconds) {
        if (seconds <= 0) return "0分钟";
        int mins = seconds / 60;
        int hours = mins / 60;
        int remMins = mins % 60;
        if (hours > 0) {
            return hours + "小时" + (remMins > 0 ? remMins + "分钟" : "");
        }
        return Math.max(1, mins) + "分钟";
    }

    private static JSONObject buildTripData(TripRecord trip) {
        JSONObject data = new JSONObject();
        try {
            data.put("tripId", trip.id);
            data.put("startTime", trip.startTime);
            data.put("endTime", trip.endTime);
            data.put("durationSeconds", trip.durationSeconds);
            data.put("distanceKm", trip.distanceKm);
            data.put("socStart", trip.socStart);
            data.put("socEnd", trip.socEnd);
            data.put("energyUsedKwh", trip.getEnergyUsedKwh());
            data.put("energyPerKm", trip.energyPerKm);
            data.put("avgSpeedKmh", trip.avgSpeedKmh);
            data.put("maxSpeedKmh", trip.maxSpeedKmh);
            data.put("overallScore", trip.getOverallScore());
            data.put("tripCost", trip.tripCost);
            data.put("odometerEndKm", trip.odometerEndKm);
            if (trip.endLat != 0 && trip.endLon != 0) {
                data.put("lat", trip.endLat);
                data.put("lng", trip.endLon);
            }
        } catch (Exception ignored) {}
        return data;
    }
}
