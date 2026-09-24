package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.NetworkMonitor;

/**
 * Publishes WiFi connection state / SSID (and a one-shot system-boot event) into the
 * automation state so wifi and boot triggers can be evaluated.
 *
 * <p>Design notes:
 * <ul>
 *   <li><b>Only while referenced.</b> The Wi-Fi task exists only while an enabled rule reads
 *       Wi-Fi state or SSID. Disabling the last reference cancels it completely.</li>
 *   <li><b>WiFi state is an edge.</b> {@link Automations#update} only fires evaluation
 *       on a real value transition, so republishing the same SSID every poll is free.</li>
 *   <li><b>Boot fires once per daemon start.</b> {@code boot=on} is consumed exactly
 *       once after saved configuration loads, so it can't be manufactured by adding a rule
 *       later. It does NOT gate on device uptime:
 *       the daemon is often launched minutes after the device booted (and again on an OTA
 *       / watchdog restart), so an uptime window silently dropped the event on the common
 *       path. The one-per-process guard is the dedup — it can't re-fire within a run.</li>
 * </ul>
 */
public class NetworkEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final long POLL_MS = 2000L;
    private static final ConditionalPoller wifiPoller = new ConditionalPoller(
            "Wi-Fi",
            POLL_MS,
            NetworkEvent::wifiReferenced,
            NetworkEvent::publishWifi,
            false);

    /** True when an enabled automation reads either WiFi signal, in any of the reference syntaxes. */
    private static boolean wifiReferenced() {
        return Automations.isEventReferenced(BydEvent.WIFI_STATE)
                || Automations.isEventReferenced(BydEvent.WIFI_SSID);
    }

    private NetworkEvent() {}

    private static volatile boolean bootConsumed = false;

    /** Consume the one-shot daemon-start event after config has loaded. */
    public static synchronized void start() {
        if (bootConsumed) return;
        try {
            if (Automations.isEventReferenced(BydEvent.BOOT)) publishBoot();
            bootConsumed = true;
        } catch (Throwable t) {
            logger.warn("Failed to publish boot event: " + t.getMessage());
        }
    }

    public static void refresh() {
        wifiPoller.refresh();
    }

    public static void seedForEditor() {
        try {
            publishWifi();
        } catch (Throwable t) {
            logger.warn("Failed to seed Wi-Fi signals: " + t.getMessage());
        }
    }

    /**
     * Publish {@code boot=on} exactly once for this daemon start.
     *
     * <p>{@link Automations#stateChanged} deliberately does NOT fire on a
     * {@code null -> X} seed transition (so events don't all fire at daemon startup).
     * A one-shot boot event would therefore be swallowed if it only ever published
     * {@code on}. To make it a genuine transition we seed {@code off} first: the
     * {@code null -> off} seed is (correctly) swallowed, then {@code off -> on} is a
     * real transition that fires any boot-triggered automation exactly once.
     */
    private static void publishBoot() {
        try {
            // Fire once per daemon process. We do NOT gate on device uptime
            // (SystemClock.elapsedRealtime): the daemon is launched by the app, often well
            // after the head unit powered on (and again after an OTA / watchdog restart), so
            // a 5-min uptime window silently dropped the boot event on exactly the common
            // path — the reported "boot trigger not working". The one-per-process guard
            // above is the correct dedup: each fresh daemon start (≈ the system coming up
            // for the user) fires boot exactly once; it can't re-fire within a process.
            // Seed "off" first (null -> off is swallowed by stateChanged), then transition
            // to "on" so a boot-triggered automation actually fires.
            Automations.update(BydEvent.BOOT, "off");
            Automations.update(BydEvent.BOOT, "on");
        } catch (Throwable ignored) {
            // Boot is best-effort; never disrupt the poll loop.
        }
    }

    private static void publishWifi() {
        // Refresh from the OS, then publish the (possibly changed) state. refresh()
        // is internally cache-bounded and falls back to shell only when the Android
        // APIs are unavailable.
        NetworkMonitor.refresh();
        boolean connected = NetworkMonitor.isWifiConnected();
        // ORDER MATTERS on connect: publish the SSID before the connection STATE, for the
        // same reason BluetoothStateMonitor relays btDeviceName before btState. wifiState is
        // the edge that triggers rule evaluation; if it went first, a "WiFi connects AND
        // ssid == home" rule would evaluate the SSID against the previous poll's value (empty
        // on the first connect) in the gap before wifiSsid lands, and silently not fire.
        // Publish the SSID (empty string when not on WiFi) so an SSID-match condition
        // sees a stable value. Never publish null — a null would be treated as
        // "unseen" and could NPE downstream comparisons.
        String ssid = NetworkMonitor.getWifiSsid();
        // forceStore so the value lands even while no automation is enabled (the seed pass
        // above). Sampled semantics are unchanged: update() still treats a first value as a
        // silent seed and only a later change fires, so seeding can never misfire a rule.
        Automations.update(BydEvent.WIFI_SSID, ssid == null ? "" : ssid, true);
        Automations.update(BydEvent.WIFI_STATE, connected ? "on" : "off", true);
    }
}
