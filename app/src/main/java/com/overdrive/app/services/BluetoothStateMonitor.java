package com.overdrive.app.services;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.util.Log;

import com.overdrive.app.remoteaccess.BluetoothRadioRecoveryEvents;
import com.overdrive.app.remoteaccess.BluetoothRssiReader;
import com.overdrive.app.remoteaccess.ProximityEvaluator;
import com.overdrive.app.remoteaccess.RemoteAccessSettings;
import com.overdrive.app.remoteaccess.SharedBluetoothKeepAliveController;
import com.overdrive.app.util.DaemonHttpClient;

import org.json.JSONObject;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * App-process Bluetooth-connection watcher. The daemon (UID 2000, synthetic Context)
 * cannot reliably read Bluetooth — the framework {@link BluetoothAdapter} proxies and a
 * {@code dumpsys bluetooth_manager} parse are both flaky from a shell UID; Bluetooth is
 * only reliably readable from a normal app process. So this runs in the real app process
 * (started from {@link KeepAliveAccessibilityService}, which is always alive) and RELAYS
 * each connection transition to the daemon via {@link DaemonHttpClient} →
 * {@code POST /api/automations/event}, exactly like {@link CallStateMonitor}. The daemon
 * publishes the whitelisted {@code btState} / {@code btDeviceName} automation events (see
 * {@code Automations.publishExternalEvent}), enabling "when my phone connects" / "only
 * while <name> is connected" rules.
 *
 * <p><b>Detection.</b> Ground truth is resolved by reflecting the hidden
 * {@code BluetoothAdapter.getConnectDevices()} (note the spelling — no "e") and treating a
 * non-empty result as connected, reading the device's {@code getName()} (falling back to
 * {@code getAddress()}). When that hidden method is absent we fall back to iterating
 * {@code getBondedDevices()} + the hidden {@code BluetoothDevice.isConnected()}. The
 * connect/disconnect broadcasts ({@code ACL_CONNECTED} / {@code ACL_DISCONNECTED} /
 * adapter {@code STATE_CHANGED}) are only used as a cheap wake signal — the authoritative
 * state is always re-resolved by the reflection read.
 *
 * <p>Singleton + idempotent {@link #start}: the a11y service can re-bind and call
 * start() repeatedly; a second call is a no-op while already registered. Every read is
 * best-effort and never throws, so a Bluetooth-stack hiccup can't disrupt the process.
 */
public final class BluetoothStateMonitor {

    private static final String TAG = "BluetoothStateMonitor";
    private static BluetoothStateMonitor instance;

    private final Context appContext;
    private BroadcastReceiver receiver;
    // Last values relayed to the daemon, to dedupe identical consecutive relays (the
    // daemon's Automations.update is edge-gated anyway, but this saves the HTTP round-trip).
    private volatile String lastState;      // "on" / "off"
    private volatile String lastName;       // device name or ""
    private volatile String lastRssi;       // dBm integer string or "" (expired)
    private volatile String lastProximity;  // "near" / "transition" / "away" / "unknown"
    private volatile long lastRssiSentWallMs = 0L;
    // Cached hidden Method handles (resolve once). null after a failed probe.
    private volatile Method getConnectDevices;
    private volatile boolean getConnectDevicesProbed;
    private volatile Method deviceIsConnected;
    private volatile boolean deviceIsConnectedProbed;

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bt-state-relay");
        t.setDaemon(true);
        return t;
    });

    // Low-cadence re-assert: the daemon holds BT state only in an in-memory map that a
    // daemon-only restart wipes; the broadcast receiver won't re-fire without a physical BT
    // edge, and relay() dedups on 2xx — so without this a post-restart daemon could sit with
    // no BT state until the next connect/disconnect. This periodically re-publishes current
    // truth with the dedup BYPASSED. It's cheap and safe: the daemon's update() is edge-gated,
    // so a same-value re-assert fires no trigger — it only reseeds the map.
    private static final long REASSERT_SECONDS = 60;
    // Delays for the post-connect name re-resolves (see scheduleNameRecheck), in ms from the
    // ACL_CONNECTED edge. The first is short enough to feel immediate; the later ones cover head
    // units / phones whose profile+SDP handshake publishes the friendly name seconds after the
    // link comes up, so the name lands well before the 60s re-assert would have caught it.
    // Each attempt self-cancels once the name is known (see scheduleNameRecheck), so on the
    // common path only the 1.2s tick does any work.
    private static final long[] NAME_RECHECK_MS = {1200L, 3000L, 5000L, 10_000L};
    private final ScheduledExecutorService reassert = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bt-state-reassert");
        t.setDaemon(true);
        return t;
    });
    private final BluetoothRadioRecoveryEvents radioRecovery;

    // ── BT-automation gate ────────────────────────────────────────────────────
    // Whether any ENABLED automation references a bluetooth trigger. Answered by
    // the daemon over the local HTTP API (GET /api/automations/list) because the
    // automations live in /data/local/tmp/.automations, which is daemon-owned and
    // not reliably readable from the app UID. Cached with a TTL so the gate costs
    // at most one small loopback GET per TTL, not one per tick.
    private static final long REASSERT_GATE_TTL_MS = 5L * 60_000L;
    private static final long REASSERT_GATE_IDLE_TTL_MS = 30L * 60_000L;
    private static final long RSSI_POLL_MS = 2_000L;
    private static final long RSSI_REFRESH_MS = 8_000L;
    private static final long PROBE_ACTIVE_TTL_MS = 10_000L;
    private static final long SETTINGS_REFRESH_MS = 10_000L;

    private volatile long gateCheckedAtMs = 0L;
    // FAIL OPEN for basic state reassert until definitively checked; RSSI stays off until requested.
    private volatile boolean gateBtInUse = true;
    private volatile boolean gateRssiInUse = false;

    private final ScheduledExecutorService rssiPoller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bt-rssi-poller");
        t.setDaemon(true);
        return t;
    });
    // Confined to rssiPoller thread:
    private BluetoothRssiReader rssiReader;
    private String rssiReaderAddress;
    private ProximityEvaluator proximityEvaluator;
    private String evaluatorSignature;
    private String lastTrackedConnectedAddress;
    private long disconnectedAtElapsedMs = -1L;
    private int disconnectedReads = 0;
    private JSONObject cachedEffectiveSettings;
    private long lastSettingsReadWallMs = 0L;

    /** Called via bridge when automations change or editor opens, to start/stop RSSI polling promptly. */
    public static void refreshProbe() {
        BluetoothStateMonitor m = instance;
        if (m == null) return;
        m.gateCheckedAtMs = 0L;
        m.rssiPoller.execute(() -> m.sampleRssiAndProximityIfActive(true));
    }

    private void refreshProbeSnapshot(boolean force) {
        long now = System.currentTimeMillis();
        long ttlMs = gateRssiInUse ? PROBE_ACTIVE_TTL_MS
                : ((gateCheckedAtMs > 0L && !gateBtInUse) ? REASSERT_GATE_IDLE_TTL_MS : REASSERT_GATE_TTL_MS);
        if (!force && gateCheckedAtMs > 0L && now - gateCheckedAtMs < ttlMs) return;
        gateCheckedAtMs = now;
        HttpURLConnection conn = null;
        try {
            conn = DaemonHttpClient.open("/api/automations/bt-probe", "GET", 1500, 2500);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(),
                                java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONObject json = new JSONObject(sb.toString());
                if (json.optBoolean("success", false)) {
                    boolean bt = json.optBoolean("btActive", gateBtInUse);
                    boolean rssi = json.optBoolean("rssiActive", false);
                    if (gateBtInUse != bt || gateRssiInUse != rssi) {
                        Log.i(TAG, "BT probe gate: btActive=" + bt + " rssiActive=" + rssi);
                    }
                    gateBtInUse = bt;
                    gateRssiInUse = rssi;
                }
            }
        } catch (Throwable ignored) {
            // Keep prior cached values on transient IPC hiccup.
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * True when the periodic re-assert is worth doing.
     */
    private boolean btAutomationInUse() {
        refreshProbeSnapshot(false);
        return gateBtInUse;
    }

    private boolean rssiAutomationInUse(boolean forceProbe) {
        refreshProbeSnapshot(forceProbe);
        return gateRssiInUse;
    }

    private BluetoothStateMonitor(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.radioRecovery = new BluetoothRadioRecoveryEvents((task, delayMs) -> {
            java.util.concurrent.ScheduledFuture<?> scheduled =
                    reassert.schedule(task, delayMs, TimeUnit.MILLISECONDS);
            return () -> scheduled.cancel(false);
        }, () -> SharedBluetoothKeepAliveController.resume(appContext));
    }

    /** Start the monitor (idempotent). Safe to call from onServiceConnected each bind. */
    public static synchronized void start(Context ctx) {
        if (instance != null) return;
        if (ctx == null) return;
        BluetoothStateMonitor m = new BluetoothStateMonitor(ctx);
        if (m.begin()) {
            instance = m;
        }
    }

    private boolean begin() {
        try {
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    if (intent == null) return;
                    if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                        try {
                            radioRecovery.onAdapterChanged();
                        } catch (RuntimeException unavailable) {
                            Log.w(TAG, "Radio recovery scheduling failed: "
                                    + unavailable.getClass().getSimpleName());
                        }
                    }
                    // Any BT edge — re-resolve ground truth and relay if it changed.
                    //
                    // Pass the device the broadcast CARRIES as a name hint. On a fresh connect
                    // the hidden getConnectDevices() can still be empty and getName() can still
                    // be null for seconds while the profile/SDP handshake finishes, so resolving
                    // purely from the adapter reported "" (or the old name) and the real name only
                    // landed on the 60s re-assert — the reported 5-10s+ device-name lag. The
                    // EXTRA_DEVICE on an ACL_CONNECTED already identifies the device that just
                    // connected, and its cached name is usually populated from bonding.
                    BluetoothDevice hint = null;
                    try {
                        hint = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    } catch (Throwable ignored) { /* malformed extra — fall back to the adapter */ }
                    String action = intent.getAction();
                    boolean connectEdge = BluetoothDevice.ACTION_ACL_CONNECTED.equals(action);
                    publish(false, connectEdge ? hint : null);
                    // A connect whose name is still unresolved gets one short retry, so the name
                    // lands in ~1s instead of waiting for the next periodic re-assert.
                    if (connectEdge) scheduleNameRecheck();
                    if (gateRssiInUse) {
                        rssiPoller.execute(() -> sampleRssiAndProximityIfActive(false));
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            // Profile-level connection edges. ACL is the raw link; a head unit's phone connect
            // completes A2DP/HFP slightly later, and it is that moment the friendly name is
            // reliably readable. Registering both means whichever arrives first publishes, and
            // the later one corrects the name if it changed.
            filter.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
            filter.addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED");
            // The name itself can arrive after the connect (fetched over SDP post-bond).
            filter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
            appContext.registerReceiver(receiver, filter);
        } catch (Throwable t) {
            Log.w(TAG, "registerReceiver failed: " + t.getMessage());
            return false;
        }
        // Seed the daemon with the current state now (the receiver only fires on future
        // edges, so without this a phone already connected at start would never publish).
        // force=true so the initial seed always reaches the daemon even if a prior instance
        // had latched the same values.
        publish(true);
        // Periodic re-assert (force-resend) as the daemon-restart safety net —
        // but ONLY while an enabled automation actually uses a Bluetooth trigger.
        //
        // BT state feeds nothing except automation conditions (see
        // Automations.publishExternalEvent / BydEvent.BT_STATE), so on a head unit
        // with no BT rule this timer was pure waste: reflection into
        // BluetoothAdapter + the hidden getConnectDevices(), then TWO HTTP POSTs
        // with dedup deliberately bypassed, every 60s forever — 1,440 wakeups and
        // 2,880 POSTs a day for a value nothing reads.
        //
        // The gate is re-evaluated on every tick (cheap: a cached answer refreshed
        // at most once per REASSERT_GATE_TTL_MS), so enabling a BT automation later
        // starts the re-assert within one tick — no app restart, no lost self-heal.
        // The BroadcastReceiver above stays registered unconditionally, so a real
        // BT connect/disconnect still relays immediately even while the timer is
        // gated off; the timer only covers the daemon-restart-wiped-its-map case.
        try {
            reassert.scheduleWithFixedDelay(() -> {
                if (btAutomationInUse()) publish(true);
            }, REASSERT_SECONDS, REASSERT_SECONDS, TimeUnit.SECONDS);
        } catch (Throwable t) {
            Log.w(TAG, "reassert schedule failed: " + t.getMessage());
        }
        try {
            rssiPoller.scheduleWithFixedDelay(
                    () -> sampleRssiAndProximityIfActive(false),
                    RSSI_POLL_MS, RSSI_POLL_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            Log.w(TAG, "rssiPoller schedule failed: " + t.getMessage());
        }
        Log.i(TAG, "bluetooth-state monitor started");
        return true;
    }

    /**
     * Re-resolve connection state + name and relay to the daemon.
     * @param force when true, bypass the local dedup and re-send even if the values are
     *     unchanged (used by the initial seed + the periodic re-assert so a wiped daemon
     *     state map is re-seeded); a broadcast-driven publish passes false to skip no-ops.
     */
    private void publish(boolean force) {
        publish(force, null);
    }

    /**
     * @param hint the device carried by an ACL_CONNECTED broadcast, or null. Used only to answer
     *     "connected, and what is it called" while the adapter's own view is still catching up:
     *     a fresh connect can leave {@code getConnectDevices()} empty and {@code getName()} null
     *     for several seconds. The hint is authoritative for the CONNECTED case only — a
     *     disconnect must always be resolved from the adapter.
     */
    private void publish(boolean force, BluetoothDevice hint) {
        boolean connected;
        String name;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                connected = false;
                name = "";
            } else {
                BluetoothDevice dev = firstConnectedDevice(adapter);
                if (dev == null && hint != null) dev = hint;   // adapter lagging the edge
                connected = dev != null;
                name = connected ? deviceName(dev) : "";
                // Adapter resolved the device but not yet its name, and the broadcast named it.
                if (connected && name.isEmpty() && hint != null) name = deviceName(hint);
            }
        } catch (Throwable t) {
            // Never let a stack hiccup crash the always-alive a11y process; skip this edge.
            Log.w(TAG, "publish read failed: " + t.getMessage());
            return;
        }
        // ORDER MATTERS on connect: relay the device NAME before the connection STATE.
        // btState is the edge that triggers rule evaluation on the daemon; if it arrived
        // first, a zero-delay "connects AND name == X" rule could evaluate the name against
        // the stale/empty value in the gap before btDeviceName lands. Pushing the name first
        // means it's already in the daemon state map when the state edge fires. (The two
        // relays run on the single-threaded io executor, so this ordering is preserved.)
        relay("btDeviceName", name, force);
        relay("btState", connected ? "on" : "off", force);
    }

    /**
     * A short ladder of re-resolves after a connect edge, for the case where the device was known
     * but its friendly name was not yet readable (name fetched over SDP after the link comes up).
     * Without it, a connect that published an empty/stale name had to wait for the 60s re-assert —
     * the reported device-name lag.
     *
     * <p>Only scheduled on an ACL_CONNECTED edge (never on disconnect, adapter state or name
     * changes), so this is a one-shot ladder per connect, not a background poll. Cheap and
     * idempotent twice over: each tick returns immediately once a real friendly name has been
     * accepted by the daemon, and relay() dedups anyway, so a slow phone costs at most four
     * adapter reads and the common path costs one.
     */
    private void scheduleNameRecheck() {
        for (long delayMs : NAME_RECHECK_MS) {
            try {
                reassert.schedule(() -> {
                    // Already resolved and accepted by the daemon — nothing left to correct.
                    // A disconnect in the meantime is handled by its own broadcast, and a
                    // stale tick that does run only re-resolves ground truth (hint is null),
                    // so it can never republish a device that has since gone away.
                    if (nameResolved()) return;
                    publish(false);
                }, delayMs, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
                // Executor shutting down — the periodic re-assert still covers it.
            }
        }
    }

    // A name that is really just the MAC address, i.e. deviceName()'s getAddress() fallback.
    private static final java.util.regex.Pattern MAC_SHAPED =
            java.util.regex.Pattern.compile("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}");

    /**
     * True once a connected state with a REAL friendly name has been accepted by the daemon.
     *
     * <p>The MAC test is not cosmetic: {@link #deviceName} falls back to {@code getAddress()}
     * when {@code getName()} is still null, so an unresolved name reaches the daemon as
     * "AA:BB:CC:DD:EE:FF" — non-empty, and therefore indistinguishable from success by a plain
     * isEmpty() check. Treating that as resolved would cancel the whole ladder in exactly the
     * case it exists for, leaving the friendly name to the 60s re-assert.
     */
    private boolean nameResolved() {
        String n = lastName;
        return "on".equals(lastState)
                && n != null
                && !n.isEmpty()
                && !MAC_SHAPED.matcher(n).matches();
    }

    /**
     * The first actually-connected device, via the hidden {@code getConnectDevices()}
     * (the primary path), falling back to a {@code getBondedDevices()}
     * scan gated on the hidden {@code BluetoothDevice.isConnected()}. Returns null when
     * nothing is connected or nothing can be resolved.
     */
    private BluetoothDevice firstConnectedDevice(BluetoothAdapter adapter) {
        // Primary: hidden BluetoothAdapter.getConnectDevices() → Set<BluetoothDevice>.
        Method m = resolveGetConnectDevices(adapter);
        if (m != null) {
            try {
                Object r = m.invoke(adapter);
                if (r instanceof Set) {
                    for (Object o : (Set<?>) r) {
                        if (o instanceof BluetoothDevice) return (BluetoothDevice) o;
                    }
                    return null; // resolvable and empty → definitively nothing connected
                }
            } catch (Throwable ignored) {
                // fall through to the bonded-scan fallback
            }
        }
        // Fallback: bonded devices + hidden BluetoothDevice.isConnected().
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                Method isConn = resolveDeviceIsConnected();
                if (isConn != null) {
                    for (BluetoothDevice d : bonded) {
                        try {
                            if (Boolean.TRUE.equals(isConn.invoke(d))) return d;
                        } catch (Throwable ignored) { /* try next */ }
                    }
                }
            }
        } catch (Throwable ignored) {
            // getBondedDevices may throw — treat as "unknown → nothing connected".
        }
        return null;
    }

    /** Device friendly name, or its MAC address when the name is unavailable (never null). */
    private static String deviceName(BluetoothDevice dev) {
        try {
            String n = dev.getName();
            if (n != null && !n.isEmpty()) return n;
            String a = dev.getAddress();
            return a != null ? a : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private Method resolveGetConnectDevices(BluetoothAdapter adapter) {
        if (getConnectDevicesProbed) return getConnectDevices;
        try {
            // Hidden AOSP/BYD adapter method — spelling verified against the reference
            // apps: getConnectDevices (no trailing "e" on "Connect").
            getConnectDevices = adapter.getClass().getMethod("getConnectDevices");
        } catch (Throwable t) {
            getConnectDevices = null;
        }
        getConnectDevicesProbed = true;
        return getConnectDevices;
    }

    private Method resolveDeviceIsConnected() {
        if (deviceIsConnectedProbed) return deviceIsConnected;
        try {
            deviceIsConnected = BluetoothDevice.class.getMethod("isConnected");
        } catch (Throwable t) {
            deviceIsConnected = null;
        }
        deviceIsConnectedProbed = true;
        return deviceIsConnected;
    }

    /**
     * Whether the daemon's JSON reply says it actually published the event. Absent/unparseable
     * body is treated as ACCEPTED: older daemons that answered 2xx with no body were accepting,
     * and failing closed here would re-POST the same value every edge forever.
     */
    private static boolean readSuccess(HttpURLConnection conn) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            String body = sb.toString().trim();
            if (body.isEmpty()) return true;
            return new JSONObject(body).optBoolean("success", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Relay one whitelisted event to the daemon, deduping identical consecutive values.
     * The value is JSON-encoded (a device name may contain quotes / backslashes / unicode),
     * so — unlike {@link CallStateMonitor}'s fixed-enum body — this must not concatenate.
     *
     * <p>The dedup check AND the "last relayed" memory update both run on the single-threaded
     * {@link #io} executor, and the memory is committed ONLY after a confirmed 2xx. This
     * matters at startup: this monitor is started by the same a11y service that also kicks
     * daemon startup, so the first {@code publish()} can race ahead of the daemon listening on
     * the HTTP port. Committing the dedup memory before the POST (as a naive relay would) would
     * suppress every future retry of a seed the daemon never actually received; committing only
     * on success means a failed relay is naturally re-attempted on the next edge until it lands.
     *
     * @param force when true, skip the dedup and re-send even if the value is unchanged (the
     *     initial seed + periodic re-assert, to re-seed a daemon whose in-memory state was
     *     wiped by a restart). The daemon's update() is edge-gated, so a same-value re-assert
     *     reseeds the map without firing any trigger.
     */
    private void relay(String event, String value, boolean force) {
        final String v = (value == null) ? "" : value;
        io.submit(() -> {
            // Dedup on the io thread (single writer → no race with the success-commit below).
            // Skipped when force=true so a re-assert always reaches a possibly-wiped daemon.
            if (!force) {
                if ("btState".equals(event)) { if (v.equals(lastState)) return; }
                else if ("btDeviceName".equals(event)) { if (v.equals(lastName)) return; }
                else if ("btRssi".equals(event)) {
                    if (v.equals(lastRssi) && (v.isEmpty()
                            || System.currentTimeMillis() - lastRssiSentWallMs < RSSI_REFRESH_MS)) return;
                }
                else if ("btProximity".equals(event)) { if (v.equals(lastProximity)) return; }
            }

            final String body;
            try {
                body = new JSONObject().put("event", event).put("value", v).toString();
            } catch (Throwable t) {
                Log.w(TAG, "relay body build failed: " + t.getMessage());
                return;
            }
            HttpURLConnection conn = null;
            try {
                conn = DaemonHttpClient.open("/api/automations/event", "POST", 2000, 3000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                // Commit the dedup memory only once the daemon has actually accepted it, so a
                // startup-race failure retries on the next edge instead of being latched.
                //
                // A 2xx is NOT acceptance on its own: /api/automations/event answers
                // 200 {"success":false} when publishExternalEvent rejects the value (unknown
                // event key, or a btState that is not exactly "on"/"off"). Latching the dedup on
                // that reply marked a value as delivered that the daemon never stored, and since
                // the receiver only re-relays on a CHANGE, the correct value could then never be
                // resent — the device name stayed stale until the next 60s force re-assert.
                boolean accepted = false;
                if (code >= 200 && code < 300) {
                    accepted = readSuccess(conn);
                    if (accepted) {
                        if ("btState".equals(event)) lastState = v;
                        else if ("btDeviceName".equals(event)) lastName = v;
                        else if ("btRssi".equals(event)) {
                            lastRssi = v;
                            lastRssiSentWallMs = System.currentTimeMillis();
                        }
                        else if ("btProximity".equals(event)) lastProximity = v;
                    }
                }
                if (!"btRssi".equals(event)) {
                    Log.i(TAG, "relayed " + event + "=" + v + " -> HTTP " + code
                            + (accepted ? "" : " (not accepted)"));
                }
            } catch (Throwable t) {
                Log.w(TAG, "relay failed (" + event + "): " + t.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /** Samples connected BT RSSI and evaluates stabilized btProximity when automations listen. */
    private void sampleRssiAndProximityIfActive(boolean forceProbe) {
        if (!rssiAutomationInUse(forceProbe)) {
            releaseRssiReader();
            proximityEvaluator = null;
            evaluatorSignature = null;
            disconnectedAtElapsedMs = -1L;
            disconnectedReads = 0;
            return;
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                releaseRssiReader();
                proximityEvaluator = null;
                evaluatorSignature = null;
                disconnectedAtElapsedMs = -1L;
                disconnectedReads = 0;
                clearRssiRelayIfNeeded();
                relay("btProximity", "unknown", false);
                return;
            }
            BluetoothDevice dev = firstConnectedDevice(adapter);
            if (dev == null) {
                releaseRssiReader();
                clearRssiRelayIfNeeded();
                if (lastTrackedConnectedAddress != null) {
                    long nowElapsed = SystemClock.elapsedRealtime();
                    if (disconnectedAtElapsedMs < 0L) {
                        disconnectedAtElapsedMs = nowElapsed;
                        disconnectedReads = 1;
                    } else {
                        disconnectedReads++;
                    }
                    if (disconnectedReads >= 2 && nowElapsed - disconnectedAtElapsedMs >= 2_000L) {
                        if (proximityEvaluator != null) proximityEvaluator.reset();
                        relay("btProximity", "away", false);
                    }
                } else {
                    relay("btProximity", "unknown", false);
                }
                return;
            }
            String rawAddr = dev.getAddress();
            String address = rawAddr != null ? rawAddr.trim().toUpperCase(Locale.US) : "";
            if (address.isEmpty()) return;
            disconnectedAtElapsedMs = -1L;
            disconnectedReads = 0;
            lastTrackedConnectedAddress = address;

            Integer rssi = readRssiFor(address);
            if (rssi != null) {
                relay("btRssi", String.valueOf(rssi), false);
            } else if (lastRssi != null && !lastRssi.isEmpty()
                    && System.currentTimeMillis() - lastRssiSentWallMs > 10_000L) {
                relay("btRssi", "", true);
            }

            ProximityEvaluator evaluator = ensureProximityEvaluator(address);
            ProximityEvaluator.Snapshot snap = evaluator.observe(
                    address, rssi, SystemClock.elapsedRealtime(), true, true);
            relay("btProximity", snap.state.name().toLowerCase(Locale.US), false);
        } catch (Throwable t) {
            Log.w(TAG, "RSSI automation poll failed: " + t.getMessage());
        }
    }

    private void clearRssiRelayIfNeeded() {
        if (lastRssi != null && !lastRssi.isEmpty()) {
            relay("btRssi", "", true);
        }
    }

    private Integer readRssiFor(String address) {
        try {
            if (rssiReader == null || !address.equalsIgnoreCase(rssiReaderAddress)) {
                releaseRssiReader();
                rssiReader = new BluetoothRssiReader(appContext, address);
                rssiReaderAddress = address;
            }
            Integer val = rssiReader.read();
            if (val == null) releaseRssiReader();
            return val;
        } catch (Throwable unavailable) {
            releaseRssiReader();
            return null;
        }
    }

    private void releaseRssiReader() {
        BluetoothRssiReader reader = rssiReader;
        rssiReader = null;
        rssiReaderAddress = null;
        if (reader != null) {
            try { reader.close(); } catch (Throwable ignored) {}
        }
    }

    private ProximityEvaluator ensureProximityEvaluator(String address) {
        long nowWall = System.currentTimeMillis();
        if (cachedEffectiveSettings == null || nowWall - lastSettingsReadWallMs >= SETTINGS_REFRESH_MS) {
            try {
                cachedEffectiveSettings = RemoteAccessSettings.effective(RemoteAccessSettings.read());
            } catch (Throwable ignored) {
                if (cachedEffectiveSettings == null) cachedEffectiveSettings = new JSONObject();
            }
            lastSettingsReadWallMs = nowWall;
        }
        JSONObject phone = RemoteAccessSettings.phone(cachedEffectiveSettings, address);
        ProximityEvaluator.Config cfg = phone != null
                ? RemoteAccessSettings.evaluatorConfig(cachedEffectiveSettings, phone)
                : RemoteAccessSettings.evaluatorConfig(cachedEffectiveSettings);
        String sig = address + ":" + cfg.nearThreshold + ":" + cfg.awayThreshold + ":"
                + cfg.windowMs + ":" + cfg.stableMs + ":" + cfg.staleMs + ":"
                + cfg.nearWindowMs + ":" + cfg.nearStableMs + ":" + cfg.unlockTrigger.setting;
        if (proximityEvaluator == null || !sig.equals(evaluatorSignature)) {
            proximityEvaluator = new ProximityEvaluator(cfg);
            evaluatorSignature = sig;
        }
        return proximityEvaluator;
    }
}
