package com.overdrive.app.wecom;

import android.util.Log;

import com.overdrive.app.notifications.sinks.WeComSink;
import com.overdrive.app.telegram.TelegramNotifier;

import java.io.File;
import java.io.FileInputStream;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 企业微信群机器人推送器。
 *
 * <p>对标 {@link TelegramNotifier}，覆盖「车→手机」全部场景：
 * <ul>
 *   <li>哨兵检测到有人/车辆/动物</li>
 *   <li>事件录像生成（发文字通知 + 文件名，视频文件通过 Web 页面查看）</li>
 *   <li>Hero 截图（base64 图片直接推送）</li>
 *   <li>隧道 URL 更新</li>
 *   <li>紧急告警（低电量、存储满、进程崩溃、系统重启）</li>
 * </ul>
 *
 * <p><b>与 TelegramNotifier 的区别：</b>
 * <ul>
 *   <li>直接 HTTP POST，无需守护进程、无需代理（企微国内直连）</li>
 *   <li>视频文件无法直接发送（企微 Webhook 不支持），改为文字+文件名告知</li>
 *   <li>图片通过 Base64 内嵌发送（≤2MB，Hero JPEG 通常 100-300KB，满足需求）</li>
 * </ul>
 *
 * <h3>配置</h3>
 * <pre>
 *   adb shell "echo 'webhook_url=https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=你的KEY' \
 *       > /data/local/tmp/wecom_config.properties"
 * </pre>
 */
public class WeComNotifier {

    private static final String TAG = "WeComNotifier";

    // 独立线程池：MOTION/VIDEO 事件不阻塞 CRITICAL 告警
    private static final ExecutorService motionExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WeComNotifier-Motion");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService criticalExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WeComNotifier-Crit");
        t.setDaemon(true);
        return t;
    });

    // ==================== 检测到移动/人/车 ====================

    /**
     * 哨兵检测到目标，推送文字告警。
     *
     * @param aiDetection 检测类型："person" / "vehicle" / "bike" / "animal" / "motion"
     * @param confidence  置信度 0-1
     * @param camera      摄像头方向："front"/"rear"/"left"/"right"，可为 null
     * @param severity    严重级别："NOTICE"/"ALERT"/"CRITICAL"，可为 null
     */
    public static void notifyMotion(String aiDetection, float confidence,
                                    String camera, String severity) {
        motionExecutor.execute(() -> {
            String label = localizeDetection(aiDetection);
            String camStr = camera != null ? "（" + localizeCamera(camera) + "摄像头）" : "";
            String sevStr = "CRITICAL".equals(severity) ? "🚨【紧急提醒】" :
                    "ALERT".equals(severity) ? "⚠️【安全警报】" : "🔍【哨兵动检】";
            String confText = formatConfidence(confidence);

            String msg = sevStr + " 发现" + label + camStr + "\n"
                    + "• 可信度：" + confText + "\n"
                    + "• 时间：" + nowStr();
            WeComSink.sendText(msg);
        });
    }

    /**
     * 哨兵事件录像已生成，推送文字通知（含文件名，可通过 Web 页面下载）。
     *
     * @param videoFilename 录像文件名（不含路径）
     * @param aiDetection   检测类型
     * @param durationSec   录像时长（秒）
     */
    public static void notifyVideoRecorded(String videoFilename, String aiDetection, int durationSec) {
        motionExecutor.execute(() -> {
            String label = localizeDetection(aiDetection);
            String msg = "📹【哨兵录像已保存】\n"
                    + "• 触发原因：" + label + "\n"
                    + "• 录像时长：" + durationSec + " 秒\n"
                    + "• 录像文件：" + videoFilename + "\n"
                    + "• 提示：可在网页端「事件录像」中查看回放\n"
                    + "• 时间：" + nowStr();
            WeComSink.sendText(msg);
        });
    }

    /**
     * 事件录像已完成，同时推送 Hero 截图（base64 图片）。
     *
     * @param heroPhotoPath Hero 截图绝对路径，null 则只发文字
     * @param videoFilename 录像文件名
     * @param aiDetection   检测类型
     * @param camera        摄像头方向
     */
    public static void notifyMotionFinalized(String heroPhotoPath, String videoFilename,
                                             String aiDetection, String camera) {
        motionExecutor.execute(() -> {
            String label = localizeDetection(aiDetection);
            String camStr = camera != null ? "（" + localizeCamera(camera) + "摄像头）" : "";

            // 先发文字
            String msg = "🎯【哨兵事件录像完成】\n"
                    + "• 目标：" + label + camStr + "\n"
                    + "• 文件：" + (videoFilename != null ? videoFilename : "–") + "\n"
                    + "• 时间：" + nowStr();
            WeComSink.sendText(msg);

            // 再发截图（如有）
            if (heroPhotoPath != null && !heroPhotoPath.isEmpty()) {
                sendHeroPhoto(heroPhotoPath);
            }
        });
    }

    // ==================== 隧道 URL ====================

    /**
     * Cloudflare/Zrok 隧道地址更新，推送新 URL。
     */
    public static void notifyTunnelUrl(String url, boolean isNew) {
        criticalExecutor.execute(() -> {
            String title = isNew ? "🌐【隧道已建立】" : "🔄【隧道地址已更新】";
            String msg = title + "\n"
                    + "• 访问地址：" + url + "\n"
                    + "• 时间：" + nowStr();
            WeComSink.sendText(msg);
        });
    }

    // ==================== 紧急告警 ====================

    /**
     * 系统紧急告警。
     *
     * @param type    告警类型（LOW_BATTERY / STORAGE_FULL / DAEMON_CRASH / SYSTEM_ERROR / SYSTEM_REBOOT）
     * @param details 附加详情
     */
    public static void notifyCritical(String type, String details) {
        criticalExecutor.execute(() -> {
            String title = criticalTitle(type);
            String msg = title + "\n"
                    + (details != null && !details.isEmpty() ? "• 详情：" + details + "\n" : "")
                    + "• 时间：" + nowStr();
            WeComSink.sendText(msg);
        });
    }

    /**
     * 发送自定义文字消息（用于自动化动作、手动测试等）。
     */
    public static void sendMessage(String text) {
        criticalExecutor.execute(() -> WeComSink.sendText(text));
    }

    // ==================== 内部工具 ====================

    /** 读取 Hero JPEG 并以 Base64 图片消息发送（企微限制 ≤2MB）。 */
    private static void sendHeroPhoto(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || f.length() == 0) return;
            if (f.length() > 2 * 1024 * 1024) {
                Log.w(TAG, "Hero photo too large (" + f.length() + " bytes), skipping image push");
                return;
            }
            byte[] bytes = new byte[(int) f.length()];
            try (FileInputStream fis = new FileInputStream(f)) {
                int read = fis.read(bytes);
                if (read != bytes.length) return;
            }
            String b64 = Base64.getEncoder().encodeToString(bytes);
            // MD5 for enterprise WeCom validation
            String md5 = md5Hex(bytes);
            WeComSink.sendImage(b64, md5);
        } catch (Exception e) {
            Log.e(TAG, "sendHeroPhoto failed: " + e.getMessage());
        }
    }

    private static String md5Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String formatConfidence(float confidence) {
        String pct = String.format(Locale.US, "%.0f%%", confidence * 100);
        if (confidence >= 0.85f) {
            return "极高 (" + pct + ")";
        } else if (confidence >= 0.70f) {
            return "较高 (" + pct + ")";
        } else if (confidence >= 0.50f) {
            return "中等 (" + pct + ")";
        } else {
            return "疑似 (" + pct + ")";
        }
    }

    private static String localizeDetection(String det) {
        if (det == null) return "移动物体";
        switch (det.toLowerCase(Locale.ROOT)) {
            case "person":  return "人员";
            case "vehicle": case "car": return "车辆";
            case "bike": case "bicycle": return "自行车/摩托";
            case "animal":  return "动物";
            case "motion":  return "移动物体";
            default:        return det;
        }
    }

    private static String localizeCamera(String cam) {
        if (cam == null) return "";
        switch (cam.toLowerCase(Locale.ROOT)) {
            case "front":  return "前置";
            case "rear":   return "后置";
            case "left":   return "左侧";
            case "right":  return "右侧";
            default:       return cam;
        }
    }

    private static String criticalTitle(String type) {
        if (type == null) return "🚨【系统告警】";
        switch (type) {
            case "LOW_BATTERY":   return "🔋【12V 小电瓶低电量告警】";
            case "STORAGE_FULL":  return "💾【存储空间不足】";
            case "DAEMON_CRASH":  return "💥【守护进程崩溃】";
            case "SYSTEM_ERROR":  return "⚠️【系统错误】";
            case "SYSTEM_REBOOT": return "🔄【系统已重启上线】";
            default:              return "🚨【" + type + "】";
        }
    }

    private static String nowStr() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                "MM-dd HH:mm:ss", java.util.Locale.CHINA);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return sdf.format(new java.util.Date());
    }
}
