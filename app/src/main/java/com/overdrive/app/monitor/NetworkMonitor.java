package com.overdrive.app.monitor;

import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import com.overdrive.app.daemon.CameraDaemon;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Calendar;
import java.util.Locale;

/**
 * Network Monitor - provides WiFi SSID, IP address, or Mobile Data status
 * for the HTTP status API sidebar display.
 *
 * Strategy:
 * 1. Try standard Android APIs (ConnectivityManager/WifiManager) first
 * 2. Fall back to shell commands (ip addr, dumpsys wifi) if Android APIs fail
 *
 * The shell fallback uses Runtime.exec() directly (same pattern as AccSentryDaemon,
 * SentryDaemon, etc.) — NOT AdbShellExecutor which requires ADB over TCP.
 */
public class NetworkMonitor {

    private static volatile String networkType = "none";
    private static volatile String wifiSsid = "";
    private static volatile String ipAddress = "";
    private static volatile int signalPercent = -1;
    private static volatile long lastUpdate = 0;
    private static volatile Context appContext;
    private static volatile boolean shellFallbackLogged = false;

    // Monthly mobile data traffic cache & thresholds
    private static volatile long cachedMonthlyMobileBytes = -1;
    private static volatile long lastTrafficQueryTime = 0;
    private static final long TRAFFIC_QUERY_INTERVAL_MS = 30000L; // 30s cache
    public static final long DEFAULT_MOBILE_LIMIT_BYTES = 5L * 1024 * 1024 * 1024; // 5 GB
    public static final double WARNING_RATIO = 0.8; // 80% warning threshold

    public static void init(Context context) {
        appContext = context;
        CameraDaemon.log("NetworkMonitor: init with context=" +
                (context != null ? context.getClass().getSimpleName() : "null"));
        refresh();
    }

    /**
     * Refresh network state. Tries Android APIs first, falls back to shell.
     */
    public static void refresh() {
        // Try Android APIs first
        if (appContext != null && tryAndroidApis()) {
            return;
        }
        // Fallback: shell commands
        tryShellFallback();
    }

    // ==================== ANDROID API APPROACH ====================

    private static boolean tryAndroidApis() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                CameraDaemon.log("NetworkMonitor: ConnectivityManager is null");
                return false;
            }

            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork == null) {
                // Silent fallback — common when running as UID 2000 (shell daemon)
                return false;
            }

            NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
            if (caps == null) {
                return false;
            }

            // Get IP from LinkProperties
            String ip = "";
            try {
                LinkProperties lp = cm.getLinkProperties(activeNetwork);
                if (lp != null) {
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress addr = la.getAddress();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            ip = addr.getHostAddress();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // LinkProperties may fail under UID 2000 — not critical, IP from shell
            }

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                networkType = "wifi";
                ipAddress = ip;
                readWifiDetailsAndroid();
                lastUpdate = System.currentTimeMillis();
                return true;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                networkType = "cellular";
                ipAddress = ip;
                wifiSsid = "";
                signalPercent = -1;
                lastUpdate = System.currentTimeMillis();
                return true;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                networkType = "ethernet";
                ipAddress = ip;
                wifiSsid = "";
                signalPercent = -1;
                lastUpdate = System.currentTimeMillis();
                return true;
            }

            return false;
        } catch (SecurityException e) {
            // UID 2000 (shell) cannot access package manager APIs on some devices.
            // "Package android does not belong to 2000" — expected on DiLink 5.
            // Silent fallback to shell commands which work fine under UID 2000.
            if (!shellFallbackLogged) {
                CameraDaemon.log("NetworkMonitor: Android APIs unavailable (UID 2000), using shell fallback");
                shellFallbackLogged = true;
            }
            return false;
        } catch (Exception e) {
            // Other unexpected errors — log once then go silent
            if (!shellFallbackLogged) {
                CameraDaemon.log("NetworkMonitor: Android API error: " + e.getMessage() + " — using shell fallback");
                shellFallbackLogged = true;
            }
            return false;
        }
    }

    private static void readWifiDetailsAndroid() {
        try {
            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                CameraDaemon.log("NetworkMonitor: WifiManager is null");
                wifiSsid = "WiFi";
                return;
            }
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) {
                wifiSsid = "WiFi";
                return;
            }
            String ssid = info.getSSID();
            if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            if (ssid == null || ssid.contains("unknown") || ssid.equals("<none>")) {
                // SSID hidden by Android — try shell fallback for SSID only
                String shellSsid = shellGetWifiSsid();
                wifiSsid = (shellSsid != null && !shellSsid.isEmpty()) ? shellSsid : "WiFi";
            } else {
                wifiSsid = ssid;
            }
            int rssi = info.getRssi();
            signalPercent = Math.max(0, Math.min(100, (rssi + 90) * 100 / 60));
        } catch (Exception e) {
            CameraDaemon.log("NetworkMonitor: WifiInfo error: " + e.getMessage());
            wifiSsid = "WiFi";
        }
    }

    // ==================== SHELL FALLBACK ====================

    private static final java.util.regex.Pattern IPV4_PATTERN =
            java.util.regex.Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");

    /**
     * 【上游优化 PR #626 / 1ba9fa81】：合并网络接口探测为单次 Shell 探测
     * 一次性执行 `ip addr show` 并在 Java 内存中解析各个网卡的 IPv4 地址，
     * 避免原来多达 6 次独立 fork 子进程与管道 grep/awk/cut 导致的系统抖动与 CPU 飙升。
     */
    private static java.util.Map<String, String> shellInterfaceIpv4Snapshot() {
        String output = execShell("ip addr show 2>/dev/null");
        java.util.Map<String, String> addresses = new java.util.HashMap<>();
        String currentIface = null;
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                // Header format: `2: wlan0: <BROADCAST,...>`
                int firstColon = line.indexOf(':');
                int secondColon = firstColon < 0 ? -1 : line.indexOf(':', firstColon + 1);
                currentIface = null;
                if (secondColon > firstColon) {
                    String iface = line.substring(firstColon + 1, secondColon).trim();
                    int peer = iface.indexOf('@');
                    currentIface = peer >= 0 ? iface.substring(0, peer) : iface;
                }
            } else if (currentIface != null && trimmed.startsWith("inet ")
                    && !addresses.containsKey(currentIface)) {
                String addressWithMask = trimmed.substring(5).split("\\s+", 2)[0];
                int slash = addressWithMask.indexOf('/');
                String address = slash >= 0
                        ? addressWithMask.substring(0, slash) : addressWithMask;
                if (IPV4_PATTERN.matcher(address).matches()) {
                    addresses.put(currentIface, address);
                }
            }
        }
        return addresses;
    }

    private static void tryShellFallback() {
        try {
            java.util.Map<String, String> addresses = shellInterfaceIpv4Snapshot();

            // Check wlan0 for WiFi
            String wlanIp = addresses.get("wlan0");
            if (wlanIp != null && !wlanIp.isEmpty()) {
                networkType = "wifi";
                ipAddress = wlanIp;
                String ssid = shellGetWifiSsid();
                wifiSsid = (ssid != null && !ssid.isEmpty()) ? ssid : "WiFi";
                shellGetWifiSignal();
                lastUpdate = System.currentTimeMillis();
                return;
            }

            // Check rmnet for mobile data
            String[] rmnetIfaces = {"rmnet_data0", "rmnet_data1", "rmnet_data2", "rmnet_data3"};
            for (String iface : rmnetIfaces) {
                String rmnetIp = addresses.get(iface);
                if (rmnetIp != null && !rmnetIp.isEmpty()) {
                    networkType = "cellular";
                    ipAddress = rmnetIp;
                    wifiSsid = "";
                    signalPercent = -1;
                    lastUpdate = System.currentTimeMillis();
                    return;
                }
            }

            // Check eth0
            String ethIp = addresses.get("eth0");
            if (ethIp != null && !ethIp.isEmpty()) {
                networkType = "ethernet";
                ipAddress = ethIp;
                wifiSsid = "";
                signalPercent = -1;
                lastUpdate = System.currentTimeMillis();
                return;
            }

            // No network
            networkType = "none";
            ipAddress = "";
            wifiSsid = "";
            signalPercent = -1;
            lastUpdate = System.currentTimeMillis();
        } catch (Exception e) {
            CameraDaemon.log("NetworkMonitor: shell fallback error: " + e.getMessage());
        }
    }

    /**
     * Get WiFi SSID via shell (dumpsys wifi or wpa_cli).
     */
    private static String shellGetWifiSsid() {
        // Try dumpsys wifi — look for SSID in mWifiInfo line
        String dump = execShell("dumpsys wifi 2>/dev/null | grep 'mWifiInfo' | head -1");
        if (!dump.isEmpty()) {
            int idx = dump.indexOf("SSID: ");
            if (idx >= 0) {
                idx += 6;
                int end = dump.indexOf(",", idx);
                if (end < 0) end = dump.length();
                String ssid = dump.substring(idx, end).trim();
                if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length() - 1);
                }
                if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("<none>")) {
                    return ssid;
                }
            }
        }
        // Fallback: wpa_cli
        String wpa = execShell("wpa_cli -i wlan0 status 2>/dev/null | grep '^ssid='");
        if (wpa.startsWith("ssid=")) {
            return wpa.substring(5).trim();
        }
        return null;
    }

    private static void shellGetWifiSignal() {
        String wpa = execShell("wpa_cli -i wlan0 signal_poll 2>/dev/null | grep '^RSSI='");
        if (wpa.startsWith("RSSI=")) {
            try {
                int rssi = Integer.parseInt(wpa.substring(5).trim());
                signalPercent = Math.max(0, Math.min(100, (rssi + 90) * 100 / 60));
                return;
            } catch (NumberFormatException ignored) {}
        }
        signalPercent = -1;
    }

    // ==================== STATUS API ====================

    /**
     * Get network info as JSON for the /status endpoint.
     * Auto-refreshes if data is older than 10 seconds.
     */
    public static JSONObject getNetworkInfo() {
        if (System.currentTimeMillis() - lastUpdate > 10000) {
            refresh();
        }
        JSONObject net = new JSONObject();
        long monthlyBytes = getMonthlyMobileDataBytes();
        boolean isWarning = isWarningUsage();
        boolean isExceeded = monthlyBytes >= DEFAULT_MOBILE_LIMIT_BYTES;

        try {
            net.put("type", networkType);
            net.put("ssid", wifiSsid);
            net.put("ip", ipAddress);
            net.put("signal", signalPercent);
            net.put("monthlyMobileBytes", monthlyBytes);
            net.put("monthlyMobileFormatted", formatDataSize(monthlyBytes));
            net.put("limitBytes", DEFAULT_MOBILE_LIMIT_BYTES);
            net.put("isWarning", isWarning);
            net.put("isExceeded", isExceeded);
        } catch (Exception ignored) {}
        return net;
    }

    /**
     * Get total mobile data consumed in current calendar month in bytes.
     */
    public static long getMonthlyMobileDataBytes() {
        long now = System.currentTimeMillis();
        if (cachedMonthlyMobileBytes >= 0 && (now - lastTrafficQueryTime < TRAFFIC_QUERY_INTERVAL_MS)) {
            return cachedMonthlyMobileBytes;
        }

        long bytes = queryCellularBytesFromSystem();
        if (bytes < 0) {
            bytes = queryCellularBytesFromProcOrFallback();
        }
        cachedMonthlyMobileBytes = Math.max(0, bytes);
        lastTrafficQueryTime = now;
        return cachedMonthlyMobileBytes;
    }

    /**
     * Checks if current month's cellular usage has reached the warning threshold (>= 80%).
     */
    public static boolean isWarningUsage() {
        long bytes = cachedMonthlyMobileBytes >= 0 ? cachedMonthlyMobileBytes : getMonthlyMobileDataBytes();
        return bytes >= (long) (DEFAULT_MOBILE_LIMIT_BYTES * WARNING_RATIO);
    }

    private static long queryCellularBytesFromSystem() {
        if (appContext == null) return -1;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                NetworkStatsManager nsm = (NetworkStatsManager)
                        appContext.getSystemService(Context.NETWORK_STATS_SERVICE);
                if (nsm != null) {
                    Calendar cal = Calendar.getInstance();
                    cal.set(Calendar.DAY_OF_MONTH, 1);
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    cal.set(Calendar.MILLISECOND, 0);
                    long start = cal.getTimeInMillis();
                    long end = System.currentTimeMillis();

                    NetworkStats.Bucket bucket = nsm.querySummaryForDevice(
                            NetworkCapabilities.TRANSPORT_CELLULAR,
                            null,
                            start,
                            end
                    );
                    if (bucket != null) {
                        return bucket.getRxBytes() + bucket.getTxBytes();
                    }
                }
            }
        } catch (Throwable ignored) {
            // UID 2000 or missing permission - fallback
        }
        return -1;
    }

    private static long queryCellularBytesFromProcOrFallback() {
        long bytes = 0;
        try {
            File f = new File("/proc/net/dev");
            if (f.exists() && f.canRead()) {
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("rmnet_data") || line.startsWith("rmnet") || line.startsWith("ccmni")) {
                            int colon = line.indexOf(':');
                            if (colon > 0) {
                                String data = line.substring(colon + 1).trim();
                                String[] tokens = data.split("\\s+");
                                if (tokens.length >= 9) {
                                    long rx = Long.parseLong(tokens[0]);
                                    long tx = Long.parseLong(tokens[8]);
                                    bytes += (rx + tx);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (bytes > 0) return bytes;

        // Fallback: Check DataUsageMonitor if available
        try {
            DataUsageMonitor dum = DataUsageMonitor.getInstance();
            if (dum != null) {
                Calendar cal = Calendar.getInstance();
                int dayOfMonth = cal.get(Calendar.DAY_OF_MONTH);
                JSONObject usage = dum.getUsage(dayOfMonth);
                if (usage != null) {
                    long totalMobile = usage.optLong("totalMobile", -1);
                    if (totalMobile >= 0) {
                        return totalMobile;
                    }
                }
            }
        } catch (Throwable ignored) {}

        return 0;
    }

    public static String formatDataSize(long bytes) {
        if (bytes <= 0) return "0 MB";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * Current network type ("wifi", "cellular", "ethernet", "none"). Reads the last
     * cached value without forcing a refresh — callers that need freshness (e.g. the
     * automation WiFi poll) call {@link #refresh()} themselves on their own cadence.
     */
    public static String getNetworkType() { return networkType; }

    /** True when the last known network type is WiFi. */
    public static boolean isWifiConnected() { return "wifi".equals(networkType); }

    /** Current WiFi SSID, or "" when not on WiFi / unknown. */
    public static String getWifiSsid() { return wifiSsid; }

    // ==================== SHELL UTIL ====================

    private static String execShell(String cmd) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
            process.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }
}
