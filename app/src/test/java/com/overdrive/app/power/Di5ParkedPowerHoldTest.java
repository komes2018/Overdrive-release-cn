package com.overdrive.app.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * State-machine tests for the DiLink 5 parked keep-alive lease with a fake
 * hardware seam. No Android classes are touched.
 */
public class Di5ParkedPowerHoldTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Records every hardware interaction. */
    static final class FakeHardware implements Di5ParkedPowerHold.Hardware {
        int mcuStatus = 1;
        /** Status the MCU reports after wakeUpMcu() has been called this many polls later. */
        int wakeAfterPolls = -1;
        boolean wakeAccepted = true;
        Double volts = 12.6;
        boolean charging = false;
        int sentryRc = 0;
        int powerHoldRc = 0;
        int panoramaRc = 0;
        int shellExit = 0;
        String shellOutput = "BEFORE=\nAFTER=ovdrv";
        volatile long now = 1_000_000L;
        /** Real wall-clock cap per fake sleep; the virtual clock still advances by the full request. */
        long sleepCapMs = 3L;
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final List<String> logs = Collections.synchronizedList(new ArrayList<>());
        private int pollsSinceWake = -1;

        /** The MCU will report awake {@code polls} status reads after the NEXT wakeUpMcu(). */
        void armWakeAfter(int polls) {
            wakeAfterPolls = polls;
            pollsSinceWake = -1;
        }

        @Override
        public int getMcuStatus(long generation) {
            calls.add("getMcuStatus:" + generation);
            if (pollsSinceWake >= 0 && wakeAfterPolls >= 0) {
                pollsSinceWake++;
                if (pollsSinceWake >= wakeAfterPolls) mcuStatus = 1;
            }
            return mcuStatus;
        }

        @Override
        public boolean wakeUpMcu(long generation) {
            calls.add("wakeUpMcu:" + generation);
            pollsSinceWake = 0;
            return wakeAccepted;
        }

        @Override
        public int[] writeSentryFlags(long generation, boolean assertFlags) {
            calls.add((assertFlags ? "sentryAssert:" : "sentryRelease:") + generation);
            return new int[]{sentryRc, sentryRc};
        }

        @Override
        public int writeMcuPowerHold(long generation, boolean hold) {
            calls.add((hold ? "powerHold:" : "powerRelease:") + generation);
            return powerHoldRc;
        }

        @Override
        public int writePanoramaWorkMode(int value) {
            calls.add("panorama:" + value);
            return panoramaRc;
        }

        @Override
        public Integer readPanoramaWorkMode() {
            return 1;
        }

        @Override
        public Double readBatteryVoltage() {
            calls.add("volts");
            return volts;
        }

        @Override
        public Di5ParkedPowerHold.ShellOutcome shell(String command) {
            calls.add("shell:" + (command.contains("OFFLINE") ? "release"
                    : command.contains("setprop") ? "apply" : "check"));
            return new Di5ParkedPowerHold.ShellOutcome(shellExit, shellOutput);
        }

        @Override
        public boolean isChargingConfirmed() {
            return charging;
        }

        @Override
        public String powerModeLine() {
            return "test";
        }

        @Override
        public long nowMs() {
            return now;
        }

        @Override
        public int pid() {
            return 4321;
        }

        @Override
        public void sleepMs(long ms) throws InterruptedException {
            // Keep the wake-poll and heartbeat loops fast but real.
            Thread.sleep(Math.min(ms, sleepCapMs));
            now += ms;
        }

        @Override
        public void log(String message) {
            logs.add(message);
        }

        int count(String prefix) {
            int n = 0;
            synchronized (calls) {
                for (String c : calls) if (c.startsWith(prefix)) n++;
            }
            return n;
        }
    }

    private FakeHardware hw;
    private AtomicReference<JSONObject> surveillance;
    private AtomicBoolean killSwitch;
    private AtomicReference<Long> currentGeneration;
    private Di5KeepAliveOwnershipMarker marker;
    private File statusFile;
    private Di5ParkedPowerHold hold;

    @Before
    public void setUp() throws Exception {
        hw = new FakeHardware();
        surveillance = new AtomicReference<>(new JSONObject());
        killSwitch = new AtomicBoolean(false);
        currentGeneration = new AtomicReference<>(5L);
        marker = new Di5KeepAliveOwnershipMarker(new File(tmp.getRoot(), "owner.json"));
        statusFile = new File(tmp.getRoot(), "status.json");
        hold = new Di5ParkedPowerHold(
                hw,
                gen -> currentGeneration.get() != null && currentGeneration.get() == gen,
                () -> Di5ParkedKeepAliveSettings.fromSurveillance(
                        surveillance.get(), killSwitch.get()),
                marker,
                statusFile);
    }

    private void enable(String... extraKeys) throws Exception {
        JSONObject s = new JSONObject().put(Di5ParkedKeepAliveSettings.KEY_ENABLED, true);
        for (String k : extraKeys) s.put(k, true);
        surveillance.set(s);
    }

    // ── Disabled path ──────────────────────────────────────────────

    @Test
    public void disabledIsCompletelyInert() throws Exception {
        hold.requestForTransition(5L, true);
        hold.tick(5L);
        hold.tick(5L);
        assertEquals(0, hw.count("sentry"));
        assertEquals(0, hw.count("panorama"));
        assertEquals(0, hw.count("shell"));
        assertEquals(0, hw.count("wakeUpMcu"));
        assertEquals(0, hw.count("volts"));      // not even a voltage read
        assertFalse(marker.exists());
        assertFalse(hold.isActive());
        assertTrue(statusFile.isFile());          // diagnostics still published
        assertFalse(hold.status().getBoolean("active"));
    }

    // ── MCU hold ───────────────────────────────────────────────────

    @Test
    public void mcuHoldAssertsOnceMcuIsAwakeAndRecordsMarkerFirst() throws Exception {
        enable();
        hold.requestForTransition(5L, true);
        assertEquals(1, hw.count("sentryAssert:5"));
        // OEM-parity MCU power hold rides the same assert (default lever ON).
        assertEquals(1, hw.count("powerHold:5"));
        assertEquals(0, hw.count("wakeUpMcu"));
        assertTrue(hold.isActive());
        Di5KeepAliveOwnershipMarker.Record rec = marker.read();
        assertNotNull(rec);
        assertEquals(5L, rec.generation);
        assertEquals(Arrays.asList("mcu", "mcupower"), rec.levers);
        JSONObject st = hold.status();
        assertTrue(st.getJSONObject("mcu").getBoolean("held"));
        assertTrue(st.getJSONObject("mcu").getBoolean("powerHoldAsserted"));
        assertEquals(0, st.getJSONObject("mcu").getInt("powerHoldLastRc"));
        assertEquals("held", st.getJSONObject("mcu").getString("status"));
        assertEquals("ALLOW", st.getJSONObject("voltage").getString("decision"));
    }

    @Test
    public void mcuHoldReassertsOnCadenceAndWhenMcuDrifts() throws Exception {
        enable();
        hold.requestForTransition(5L, true);
        assertEquals(1, hw.count("sentryAssert"));

        // Within the cadence: liveness check only, no write.
        hw.now += 10_000L;
        hold.tick(5L);
        assertEquals(1, hw.count("sentryAssert"));

        // MCU drifted to sleep between cadences → immediate wake + re-assert.
        hw.mcuStatus = 0;
        hw.armWakeAfter(1);
        hw.now += 10_000L;
        hold.tick(5L);
        assertEquals(1, hw.count("wakeUpMcu"));
        assertEquals(2, hw.count("sentryAssert"));

        // Past the 30 s cadence → scheduled re-assert.
        hw.now += 31_000L;
        hold.tick(5L);
        assertEquals(3, hw.count("sentryAssert"));
        assertTrue(hold.status().getJSONObject("mcu").getInt("reasserts") >= 1);
    }

    @Test
    public void sleepingMcuNeverReceivesFlagsUntilAwake() throws Exception {
        enable();
        hw.mcuStatus = 0;       // asleep
        hw.wakeAfterPolls = -1; // never wakes
        hold.requestForTransition(5L, true);
        assertEquals(1, hw.count("wakeUpMcu"));
        assertEquals(0, hw.count("sentryAssert"));
        // The MCU power hold is deliberately UNCONDITIONAL (OEM V1 parity):
        // the 1 IS the wake request, so it lands even while the MCU sleeps —
        // and the lease is therefore active (holding that lever) already.
        assertEquals(1, hw.count("powerHold:5"));
        assertTrue(hold.isActive());
        JSONObject mcu = hold.status().getJSONObject("mcu");
        assertFalse(mcu.getBoolean("held"));
        assertTrue(mcu.getBoolean("powerHoldAsserted"));
        assertEquals(1, mcu.getInt("wakeAttempts"));
        assertTrue(mcu.getString("status").startsWith("mcu asleep"));

        // Now it wakes on the second poll of the next attempt.
        hw.armWakeAfter(2);
        hw.now += 10_000L;
        hold.tick(5L);
        assertEquals(2, hw.count("wakeUpMcu"));
        assertEquals(1, hw.count("sentryAssert"));
        assertTrue(hold.isActive());
    }

    @Test
    public void rejectedWriteIsLoggedNoOpAndRetriedOnCadence() throws Exception {
        enable();
        hw.sentryRc = 7;
        hw.powerHoldRc = 7;
        hold.requestForTransition(5L, true);
        assertEquals(1, hw.count("sentryAssert"));
        assertFalse(hold.isActive());
        assertFalse(hold.status().getJSONObject("mcu").getBoolean("powerHoldAsserted"));
        assertTrue(hold.status().getJSONObject("mcu").getString("status").startsWith("rejected"));
        hw.sentryRc = 0;
        hw.powerHoldRc = 0;
        // Next 10 s tick: still inside the 30 s cadence → no hammering.
        hw.now += 10_000L;
        hold.tick(5L);
        assertEquals(1, hw.count("sentryAssert"));
        // Cadence elapsed → one retry, which now lands.
        hw.now += 21_000L;
        hold.tick(5L);
        assertEquals(2, hw.count("sentryAssert"));
        assertTrue(hold.isActive());
        assertTrue(hold.status().getJSONObject("mcu").getBoolean("powerHoldAsserted"));
    }

    @Test
    public void powerHoldEscapeHatchOffNeverTouchesTheEvent() throws Exception {
        JSONObject s = new JSONObject()
                .put(Di5ParkedKeepAliveSettings.KEY_ENABLED, true)
                .put(Di5ParkedKeepAliveSettings.KEY_MCU_POWER_HOLD, false);
        surveillance.set(s);
        hold.requestForTransition(5L, true);
        assertEquals(1, hw.count("sentryAssert"));
        assertEquals(0, hw.count("powerHold"));
        assertEquals(Arrays.asList("mcu"), marker.read().levers);
        // Release must not write the 0 either — this install never asserted the 1.
        currentGeneration.set(6L);
        hold.requestForTransition(6L, false);
        assertEquals(1, hw.count("sentryRelease"));
        assertEquals(0, hw.count("powerRelease"));
    }

    @Test
    public void releaseInterruptsABlockedWakePoll() throws Exception {
        enable();
        // Asleep and never waking → without the abort flag the poll would run the
        // full WAKE_POLL_MAX_MS while holding the lock. Make each fake poll sleep
        // long enough (real time) that release() can arrive mid-poll.
        FakeHardware slow = new FakeHardware();
        slow.mcuStatus = 0;
        slow.wakeAfterPolls = -1;
        slow.sleepCapMs = 40L;
        Di5ParkedPowerHold h = new Di5ParkedPowerHold(
                slow,
                gen -> true,
                () -> Di5ParkedKeepAliveSettings.fromSurveillance(surveillance.get(), false),
                new Di5KeepAliveOwnershipMarker(new File(tmp.getRoot(), "owner2.json")),
                new File(tmp.getRoot(), "status3.json"));
        Thread t = new Thread(() -> h.requestForTransition(5L, true), "wake-poller");
        t.start();
        // Wait until the poll has started (wakeUpMcu recorded), then release.
        long deadline = System.currentTimeMillis() + 2_000L;
        while (slow.count("wakeUpMcu") == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
        long before = System.currentTimeMillis();
        h.release("test");
        long took = System.currentTimeMillis() - before;
        t.join(2_000L);
        assertFalse(t.isAlive());
        // Full poll would be 10 × 40 ms = 400 ms; release must return well before that.
        assertTrue("release blocked for " + took + " ms", took < 300L);
        assertEquals(0, slow.count("sentryAssert"));
        assertFalse(h.isActive());
    }

    // ── Release paths ──────────────────────────────────────────────

    @Test
    public void accOnReleasesFlagsAndClearsMarker() throws Exception {
        enable();
        hold.requestForTransition(5L, true);
        assertTrue(marker.exists());
        currentGeneration.set(6L);
        hold.requestForTransition(6L, false);
        assertEquals(1, hw.count("sentryRelease"));
        // The power hold was asserted, so release writes -1442840502=0 exactly once.
        assertEquals(1, hw.count("powerRelease"));
        assertFalse(marker.exists());
        assertFalse(hold.isActive());
        assertEquals("transition:6", hold.status().getJSONObject("lastStop").getString("reason"));
        // Later ticks for the old generation are ignored.
        hold.tick(5L);
        assertEquals(1, hw.count("sentryAssert"));
    }

    @Test
    public void toggleOffMidParkReleasesWithinOneTick() throws Exception {
        enable();
        hold.requestForTransition(5L, true);
        assertTrue(hold.isActive());
        surveillance.set(new JSONObject());
        hw.now += 10_000L;
        hold.tick(5L);
        assertFalse(hold.isActive());
        assertEquals(1, hw.count("sentryRelease"));
        assertFalse(marker.exists());
        assertEquals("disabled in config", hold.status().getJSONObject("lastStop").getString("reason"));
    }

    @Test
    public void killSwitchMidParkReleases() throws Exception {
        enable();
        hold.requestForTransition(5L, true);
        killSwitch.set(true);
        hw.now += 10_000L;
        hold.tick(5L);
        assertFalse(hold.isActive());
        assertEquals("kill switch", hold.status().getJSONObject("lastStop").getString("reason"));
    }

    @Test
    public void staleGenerationIsIgnoredAndReleasesIfHeld() throws Exception {
        enable();
        hold.requestForTransition(5L, true);
        assertTrue(hold.isActive());
        // The daemon moved on to another generation without telling us (should
        // not happen, but the fence must still win).
        currentGeneration.set(9L);
        hw.now += 10_000L;
        hold.tick(5L);
        assertFalse(hold.isActive());
        assertEquals(1, hw.count("sentryRelease"));
        // Older generations never assert.
        hold.requestForTransition(4L, true);
        assertEquals(1, hw.count("sentryAssert"));
    }

    @Test
    public void releaseForProcessExitIsIdempotent() throws Exception {
        enable();
        hold.requestForTransition(5L, true);
        Di5ParkedPowerHold.install(hold);
        Di5ParkedPowerHold.releaseForProcessExit("test exit");
        Di5ParkedPowerHold.releaseForProcessExit("test exit again");
        Di5ParkedPowerHold.install(null);
        assertEquals(1, hw.count("sentryRelease"));
        assertFalse(hold.isActive());
        // A subsequent tick must not re-assert: release cleared `desired`.
        hold.tick(5L);
        assertEquals(1, hw.count("sentryAssert"));
    }

    // ── Voltage guard ──────────────────────────────────────────────

    @Test
    public void noVoltageSampleBlocksActivation() throws Exception {
        enable();
        hw.volts = null;
        hold.requestForTransition(5L, true);
        assertEquals(0, hw.count("sentryAssert"));
        assertFalse(marker.exists());
        assertEquals("NO_SAMPLE", hold.status().getJSONObject("voltage").getString("decision"));
        hw.volts = 12.5;
        hold.tick(5L);
        assertEquals(1, hw.count("sentryAssert"));
    }

    @Test
    public void cutoffReleasesAndLatchesUntilAccOn() throws Exception {
        enable();
        hold.requestForTransition(5L, true);
        assertTrue(hold.isActive());
        hw.volts = 11.5;
        hw.now += 10_000L; hold.tick(5L);
        assertTrue(hold.isActive());           // 1 low sample
        hw.now += 10_000L; hold.tick(5L);
        assertTrue(hold.isActive());           // 2 low samples
        hw.now += 10_000L; hold.tick(5L);      // 3rd → cutoff
        assertFalse(hold.isActive());
        assertEquals(1, hw.count("sentryRelease"));
        assertEquals("voltage cutoff", hold.status().getJSONObject("lastStop").getString("reason"));
        assertTrue(hold.status().getJSONObject("voltage").getBoolean("latched"));

        // Rebound must not resume.
        hw.volts = 12.9;
        hw.now += 10_000L; hold.tick(5L);
        hw.now += 10_000L; hold.tick(5L);
        assertFalse(hold.isActive());
        assertEquals(1, hw.count("sentryAssert"));

        // ACC ON clears the latch; the next park asserts again.
        currentGeneration.set(6L);
        hold.requestForTransition(6L, false);
        currentGeneration.set(7L);
        hold.requestForTransition(7L, true);
        assertEquals(2, hw.count("sentryAssert"));
        assertTrue(hold.isActive());
    }

    @Test
    public void confirmedChargingClearsTheLatch() throws Exception {
        enable();
        surveillance.get().put(Di5ParkedKeepAliveSettings.KEY_CUTOFF_SAMPLES, 1);
        hw.volts = 11.0;
        hold.requestForTransition(5L, true);   // first sample already trips
        assertFalse(hold.isActive());
        assertTrue(hold.status().getJSONObject("voltage").getBoolean("latched"));
        hw.volts = 12.9;
        hw.charging = true;
        hw.now += 10_000L;
        hold.tick(5L);
        assertFalse(hold.status().getJSONObject("voltage").getBoolean("latched"));
        assertTrue(hold.isActive());
    }

    @Test
    public void staleSampleKeepsHoldButBlocksReassert() throws Exception {
        enable();
        hold.requestForTransition(5L, true);
        assertEquals(1, hw.count("sentryAssert"));
        hw.volts = null;                        // reads start failing
        hw.now += 200_000L;                     // > 120 s max age, > 30 s cadence
        hold.tick(5L);
        assertTrue(hold.isActive());            // nothing torn down…
        assertEquals(1, hw.count("sentryAssert"));   // …but no re-assert either
        assertEquals("STALE", hold.status().getJSONObject("voltage").getString("decision"));
    }

    // ── Camera heartbeat ───────────────────────────────────────────

    @Test
    public void cameraHeartbeatWritesOneThenZeroOnRelease() throws Exception {
        enable(Di5ParkedKeepAliveSettings.KEY_CAMERA_HEARTBEAT);
        hold.requestForTransition(5L, true);
        assertEquals(1, hw.count("panorama:1"));
        assertTrue(hold.status().getJSONObject("camera").getBoolean("heartbeatRunning"));
        // Let the heartbeat thread tick at least once (fake sleep is ~3 ms).
        long deadline = System.currentTimeMillis() + 2_000L;
        while (hw.count("panorama:1") < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue(hw.count("panorama:1") >= 2);
        assertTrue(PanoramaWorkModeOwner.isParkedHoldActive());

        hold.release("test");
        assertEquals(1, hw.count("panorama:0"));
        assertFalse(PanoramaWorkModeOwner.isParkedHoldActive());
        deadline = System.currentTimeMillis() + 2_000L;
        while (hold.heartbeatThreadAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
        assertFalse(hold.heartbeatThreadAlive());
        assertFalse(hold.status().getJSONObject("camera").getBoolean("heartbeatRunning"));
        int writesAfterRelease = hw.count("panorama:1");
        Thread.sleep(30L);
        assertEquals("heartbeat must stop writing after release",
                writesAfterRelease, hw.count("panorama:1"));
        Di5KeepAliveOwnershipMarker.Record rec = marker.read();
        assertNull(rec);
    }

    // ── AP hold (experimental) ─────────────────────────────────────

    @Test
    public void apHoldRequiresPreflightAndReleasesOwnTokenOnly() throws Exception {
        enable(Di5ParkedKeepAliveSettings.KEY_AP_HOLD);
        hold.requestForTransition(5L, true);
        assertEquals(0, hw.count("shell"));     // no pre-flight → no setprop
        surveillance.get().put(Di5ParkedKeepAliveSettings.KEY_AP_HOLD_PREFLIGHT_PASSED, true);
        hw.now += 10_000L;
        hold.tick(5L);
        assertEquals(1, hw.count("shell:apply"));
        assertEquals("held", hold.status().getJSONObject("ap").getString("status"));
        assertTrue(marker.read().has(Di5KeepAliveOwnershipMarker.LEVER_AP));
        // Re-check on the next tick is a plain check, not a re-apply.
        hw.now += 10_000L;
        hold.tick(5L);
        assertEquals(1, hw.count("shell:check"));
        assertEquals(1, hw.count("shell:apply"));
        hold.release("test");
        assertEquals(1, hw.count("shell:release"));
        assertTrue(Di5ParkedPowerHold.apHoldReleaseScript().contains("OFFLINE"));
        assertFalse(Di5ParkedPowerHold.apHoldApplyScript().contains("vandp"));
    }

    @Test
    public void apHoldDeniedIsLoggedNoOp() throws Exception {
        enable(Di5ParkedKeepAliveSettings.KEY_AP_HOLD,
                Di5ParkedKeepAliveSettings.KEY_AP_HOLD_PREFLIGHT_PASSED);
        hw.shellExit = 2;
        hw.shellOutput = "BEFORE=\nsetprop: failed to set property";
        hold.requestForTransition(5L, true);
        assertEquals("denied", hold.status().getJSONObject("ap").getString("status"));
        assertFalse(hold.status().getJSONObject("ap").getBoolean("held"));
        // The MCU hold is unaffected by the AP denial.
        assertTrue(hold.status().getJSONObject("mcu").getBoolean("held"));
        // Denied → re-probe only every 10 ticks.
        for (int i = 0; i < 9; i++) { hw.now += 10_000L; hold.tick(5L); }
        assertEquals(1, hw.count("shell:apply"));
        hw.now += 10_000L; hold.tick(5L);
        assertEquals(2, hw.count("shell:apply"));
    }

    // ── Start-up hygiene ───────────────────────────────────────────

    @Test
    public void hygieneReleasesExactlyRecordedLeversWhenAccReadsOn() throws Exception {
        marker.record(3L, Arrays.asList("mcu", "camera"), 1L, 999);
        assertTrue(hold.onDaemonStart(true));
        assertEquals(1, hw.count("sentryRelease"));
        assertEquals(1, hw.count("panorama:0"));
        assertEquals(0, hw.count("shell"));     // AP was not recorded → untouched
        // mcupower was NOT recorded → the 0 ("request MCU sleep") is never issued.
        assertEquals(0, hw.count("powerRelease"));
        assertFalse(marker.exists());
        assertEquals("hygiene", hold.status().getJSONObject("lastStop").getString("reason"));
    }

    @Test
    public void hygieneReleasesPowerHoldOnlyWhenRecorded() throws Exception {
        marker.record(3L, Arrays.asList("mcu", "mcupower"), 1L, 999);
        assertTrue(hold.onDaemonStart(true));
        assertEquals(1, hw.count("sentryRelease"));
        assertEquals(1, hw.count("powerRelease"));
        assertFalse(marker.exists());
    }

    @Test
    public void hygieneKeepsMarkerWhileAccReadsOff() throws Exception {
        marker.record(3L, Arrays.asList("mcu"), 1L, 999);
        assertFalse(hold.onDaemonStart(false));
        assertEquals(0, hw.count("sentryRelease"));
        assertTrue(marker.exists());
    }

    @Test
    public void hygieneWithoutMarkerTouchesNothing() {
        assertFalse(hold.onDaemonStart(true));
        assertEquals(0, hw.calls.size());
    }

    @Test
    public void hygieneWithoutMarkerNeverRunsTheAccProbe() {
        AtomicBoolean probed = new AtomicBoolean(false);
        assertFalse(hold.onDaemonStart(() -> {
            probed.set(true);
            return true;
        }));
        assertFalse("ACC probe must be lazy when no marker exists", probed.get());
        assertEquals(0, hw.calls.size());
    }

    @Test
    public void hygieneTreatsAFailingAccProbeAsAccOn() throws Exception {
        marker.record(3L, Arrays.asList("mcu"), 1L, 999);
        assertTrue(hold.onDaemonStart(() -> {
            throw new IllegalStateException("BYD API unavailable");
        }));
        assertEquals(1, hw.count("sentryRelease"));
        assertFalse(marker.exists());
    }

    @Test
    public void markerWriteFailureBlocksHalWrites() throws Exception {
        // Marker path inside a regular file → record() fails → no HAL writes.
        File blocker = new File(tmp.getRoot(), "blocker");
        assertTrue(blocker.createNewFile());
        Di5KeepAliveOwnershipMarker broken =
                new Di5KeepAliveOwnershipMarker(new File(blocker, "owner.json"));
        Di5ParkedPowerHold h = new Di5ParkedPowerHold(
                hw,
                gen -> true,
                () -> Di5ParkedKeepAliveSettings.fromSurveillance(surveillance.get(), false),
                broken,
                new File(tmp.getRoot(), "status2.json"));
        enable();
        h.requestForTransition(5L, true);
        assertEquals(0, hw.count("sentryAssert"));
        assertFalse(h.isActive());
        boolean logged = false;
        for (String l : hw.logs) if (l.contains("fail closed")) logged = true;
        assertTrue(logged);
    }
}
