package com.overdrive.app.camera;

import android.graphics.SurfaceTexture;
import android.os.HandlerThread;
import android.view.Surface;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-time probe that opens both the pano AVMCamera id and the OEM Dashcam id
 * concurrently and writes the result (sticky) to UCM at
 * {@code camera.concurrentAvmSupported}.
 *
 * <h3>Result semantics</h3>
 * <ul>
 *   <li>{@code -1} (default) — unprobed.</li>
 *   <li>{@code 0} — only one AVMCamera client at a time. Caller must run
 *       pipelines exclusively (yield protocol via {@link BydCameraCoordinator}).</li>
 *   <li>{@code 1} — both ids deliver frames simultaneously. Caller may run
 *       pano + OEM dashcam in parallel.</li>
 * </ul>
 *
 * <h3>When to invoke</h3>
 * Run once at daemon boot when:
 *  <ul>
 *    <li>{@code camera.concurrentAvmSupported} is -1 (never probed); AND</li>
 *    <li>{@code resolveOemDashcamId() >= 0} (an OEM dashcam id is configured);
 *        AND</li>
 *    <li>{@code probedCameraId >= 0} (pano is known).</li>
 *  </ul>
 * Re-run if the user changes either id (the dialog flow already invalidates
 * the result by calling {@link #invalidate}).
 *
 * <h3>Cost</h3>
 * The probe opens AVMCamera twice and waits up to 5 s for first frames on
 * each. SurfaceTexture-only path — no encoder, no GL render pass. Safe to
 * call from the daemon's setup thread; runs synchronously.
 */
public final class ConcurrentAvmProbe {
    private static final String TAG = "ConcurrentAvmProbe";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final long FIRST_FRAME_TIMEOUT_MS = 5_000;
    private static final long CAMERA_OPEN_HARD_TIMEOUT_MS = 10_000L;
    private static final AtomicBoolean terminalCameraOpenRestart =
        new AtomicBoolean(false);

    private ConcurrentAvmProbe() {}

    /**
     * Run the probe if needed and persist the result. Returns the final
     * sticky value: -1 if probe was skipped, 0/1 otherwise.
     */
    public static int runIfNeeded() {
        try {
            if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                    || terminalCameraOpenRestart.get()
                    || com.overdrive.app.daemon.CameraDaemon
                        .isProcessRestartPending()) {
                return -1;
            }
            JSONObject camera = UnifiedConfigManager.loadConfig().optJSONObject("camera");
            if (camera == null) return -1;

            int existing = camera.optInt("concurrentAvmSupported", -1);
            if (existing >= 0) {
                logger.info("Probe already done (sticky=" + existing + "); skipping");
                return existing;
            }

            // OPT-IN gate. The probe is a DESTRUCTIVE dual-camera open whose
            // only real consumer is a minor OEM bitrate-budget refinement; the
            // unprobed default (-1) already produces correct sole-encoder
            // behaviour. Camera-id resolution is entirely separate
            // (resolveOemDashcamId / probedCameraId — saved ids honored
            // verbatim, sane pano=1/OEM=0 defaults otherwise) and does NOT
            // depend on this probe. So we only run when the user explicitly
            // opts in via the camera-mapping dialog. Default false = never
            // auto-probe — this is what prevents the +15s boot probe from
            // truncating a live dvr_*.mp4 in the DashCam+Pano layout.
            if (!camera.optBoolean("concurrentAvmProbeEnabled", false)) {
                logger.info("Probe skipped: concurrentAvmProbeEnabled=false "
                    + "(opt-in only; -1 unprobed default is safe — OEM uses "
                    + "sole-encoder full bitrate budget)");
                return -1;
            }

            // CRITICAL: never open AVMCamera while a live pipeline holds the
            // handle. probe() opens BOTH the pano id AND the OEM id via raw
            // HAL open()+startPreview(). If the pano or OEM recording pipeline
            // is already running on either id, the probe's second open steals
            // / disrupts the handle and TRUNCATES the in-flight clip — the
            // observed "15-second dvr_*.mp4 then recording resumes later"
            // symptom in the DashCam+Pano layout (the only layout where this
            // probe is even scheduled, since it needs a distinct OEM id).
            //
            // Two reasons this guard is also semantically correct, not just a
            // workaround:
            //   1. If BOTH pipelines are already running concurrently, that IS
            //      the answer — the HAL demonstrably supports dual clients, so
            //      we record concurrentAvmSupported=1 observationally (below)
            //      instead of running a destructive probe to "find out."
            //   2. The sticky result is consumed lazily and the only real
            //      consumer (applyBitrateBudgetCap) treats any non-1 value —
            //      including the unprobed -1 — as sole-encoder full budget, so
            //      leaving it at -1 for one more ACC cycle is benign.
            //
            // Defer: a subsequent daemon boot / ACC cycle with no live
            // pipeline at the +15s mark runs the probe cleanly.
            try {
                com.overdrive.app.surveillance.GpuSurveillancePipeline pano =
                    com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
                com.overdrive.app.camera.OemDashcamPipeline oem =
                    com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
                boolean panoLive = pano != null && pano.isRunning();
                boolean oemLive = oem != null && oem.isRunning();
                if (panoLive && oemLive) {
                    // Both pipelines are running RIGHT NOW on their distinct
                    // AVMCamera ids — that is direct, non-destructive proof the
                    // HAL supports concurrent clients. Record it positively
                    // (sticky=1) instead of deferring forever; in the
                    // DashCam+Pano layout pipelines are essentially always live
                    // at the +15s mark, so a blind defer would leave the value
                    // unprobed (-1) permanently. No AVMCamera is opened here.
                    logger.info("Probe satisfied observationally: pano AND OEM "
                        + "both live on distinct ids → concurrentAvmSupported=1 "
                        + "(no destructive open performed)");
                    JSONObject patch = new JSONObject();
                    patch.put("concurrentAvmSupported", 1);
                    patch.put("concurrentAvmProbeEnabled", false);
                    UnifiedConfigManager.updateSection("camera", patch);
                    return 1;
                }
                if (panoLive || oemLive) {
                    // Exactly one pipeline is up. We can't conclude concurrency
                    // (the other id is untested) and we must NOT open the idle
                    // id's HAL while its sibling holds a handle — on a
                    // single-client HAL that open would still disrupt the live
                    // one. Defer to a quiescent boot. -1 is tolerated downstream
                    // (applyBitrateBudgetCap treats !=1 as sole-encoder budget).
                    logger.info("Probe deferred: one pipeline is live (pano="
                        + panoLive + ", oem=" + oemLive + ") — opening the idle "
                        + "AVMCamera id now could disrupt the live clip. Will "
                        + "retry on a quiescent boot.");
                    return -1;
                }
            } catch (Throwable t) {
                // If we can't even determine liveness, do NOT probe — the
                // safe default is to leave concurrentAvmSupported unprobed
                // rather than risk stealing a live handle.
                logger.warn("Probe liveness check failed (" + t.getMessage()
                    + ") — deferring probe to avoid handle contention");
                return -1;
            }

            int panoId = camera.optInt("probedCameraId", -1);
            if (panoId < 0 && camera.optBoolean("manualOverride", false)) {
                panoId = camera.optInt("manualCameraId", -1);
            }
            int oemId = UnifiedConfigManager.resolveOemDashcamId();

            if (panoId < 0 || oemId < 0 || panoId == oemId) {
                logger.info("Probe skipped: panoId=" + panoId + " oemId=" + oemId
                    + " (both must be set and distinct)");
                return -1;
            }

            // The UI promises this destructive one-shot runs only while
            // parked. AccMonitor's cold default is ACC-off, so require a clean
            // hardware read instead of trusting the cache.
            if (!com.overdrive.app.daemon.CameraDaemon
                    .isAccConfirmedOffForCameraProbe()) {
                logger.info("Probe deferred: ACC is not confirmed OFF by a "
                    + "trustworthy hardware read");
                return -1;
            }

            // Consume the one-shot BEFORE opening either camera. If the HAL
            // wedges and the urgent restart kills this process, the next daemon
            // boot must not repeat the same destructive probe forever.
            JSONObject consume = new JSONObject();
            consume.put("concurrentAvmProbeEnabled", false);
            try {
                UnifiedConfigManager.updateSection("camera", consume);
            } catch (Throwable writeFailure) {
                logger.warn("Probe deferred: could not consume one-shot opt-in: "
                    + writeFailure.getMessage());
                return -1;
            }

            int result = probe(panoId, oemId);
            if (result < 0) {
                logger.info("Probe retired before completion; leaving "
                    + "concurrentAvmSupported unprobed");
                return -1;
            }
            JSONObject patch = new JSONObject();
            patch.put("concurrentAvmSupported", result);
            UnifiedConfigManager.updateSection("camera", patch);
            logger.info("Probe complete: concurrentAvmSupported=" + result
                + " (panoId=" + panoId + ", oemId=" + oemId + ")");
            return result;
        } catch (Throwable t) {
            logger.warn("runIfNeeded failed: " + t.getMessage());
            return -1;
        }
    }

    /**
     * Reset the sticky result to -1 so the next {@link #runIfNeeded} call
     * re-probes. Used by the camera-mapping dialog when the user changes
     * either id.
     */
    public static void invalidate() {
        try {
            JSONObject patch = new JSONObject();
            patch.put("concurrentAvmSupported", -1);
            UnifiedConfigManager.updateSection("camera", patch);
            logger.info("Probe result invalidated (will re-run on next daemon boot)");
        } catch (Throwable t) {
            logger.warn("invalidate failed: " + t.getMessage());
        }
    }

    // Returns 1 if both ids delivered a frame; 0 otherwise. Always best-effort
    // close on the way out so a failed probe doesn't leak an open AVMCamera
    // handle.
    private static int probe(int panoId, int oemId) {
        Object panoCam = null;
        Object oemCam = null;
        SurfaceTexture panoSt = null;
        SurfaceTexture oemSt = null;
        Surface panoSurf = null;
        Surface oemSurf = null;
        HandlerThread panoThread = null;
        HandlerThread oemThread = null;
        try {
            if (!com.overdrive.app.daemon.CameraDaemon
                    .isAccConfirmedOffForCameraProbe()) {
                return -1;
            }
            Class<?> avm = Class.forName("android.hardware.AVMCamera");

            panoCam = openAvmCamera(avm, panoId);
            if (panoCam == null) {
                logger.warn("Probe: pano open failed (id=" + panoId + ")");
                return 0;
            }

            if (!com.overdrive.app.daemon.CameraDaemon
                    .isAccConfirmedOffForCameraProbe()) {
                logger.info("Probe retired after pano open: ACC no longer "
                    + "confirmed OFF");
                return -1;
            }
            oemCam = openAvmCamera(avm, oemId);
            if (oemCam == null) {
                // Single-client HAL: pano grabbed it, OEM open failed. That
                // alone is the answer — concurrentAvmSupported=0.
                logger.info("Probe: OEM open failed while pano was open — single-client HAL");
                return 0;
            }

            if (!com.overdrive.app.daemon.CameraDaemon
                    .isAccConfirmedOffForCameraProbe()) {
                logger.info("Probe retired after OEM open: ACC no longer "
                    + "confirmed OFF");
                return -1;
            }

            // Attach SurfaceTexture-only consumers and watch for first frame
            // on each. We don't allocate encoders or GL contexts here — just
            // raw HAL → SurfaceTexture availability callbacks.
            panoThread = new HandlerThread("ProbePano");
            panoThread.start();
            panoSt = new SurfaceTexture(/* texName= */ 0);
            panoSt.detachFromGLContext();   // we don't render; just want the callback
            panoSurf = new Surface(panoSt);
            CountDownLatch panoLatch = new CountDownLatch(1);
            panoSt.setOnFrameAvailableListener(st -> panoLatch.countDown(),
                new android.os.Handler(panoThread.getLooper()));

            oemThread = new HandlerThread("ProbeOem");
            oemThread.start();
            oemSt = new SurfaceTexture(/* texName= */ 0);
            oemSt.detachFromGLContext();
            oemSurf = new Surface(oemSt);
            CountDownLatch oemLatch = new CountDownLatch(1);
            oemSt.setOnFrameAvailableListener(st -> oemLatch.countDown(),
                new android.os.Handler(oemThread.getLooper()));

            // Attach + start preview on both. Try addPreviewSurface first
            // (covers Seal/Han single-channel). If a HAL only exposes
            // addTexture, we'd need a real GL texture name — skip that
            // path here; a probe failure to attach is treated as "single
            // client" since we can't validate concurrent operation.
            if (!attachAndStart(avm, panoCam, panoSurf, panoSt)) {
                logger.warn("Probe: pano attach failed");
                return 0;
            }
            if (!com.overdrive.app.daemon.CameraDaemon
                    .isAccConfirmedOffForCameraProbe()) {
                logger.info("Probe retired before OEM preview start: ACC no "
                    + "longer confirmed OFF");
                return -1;
            }
            if (!attachAndStart(avm, oemCam, oemSurf, oemSt)) {
                // OEM attach failed while pano is live — could be either HAL
                // refused second client OR HAL doesn't expose addPreviewSurface.
                // Either way, can't run both.
                logger.info("Probe: OEM attach failed with pano live — treating as single-client");
                return 0;
            }

            // Race both first-frame latches. We need BOTH to fire within
            // the timeout for a positive result.
            AtomicInteger okCount = new AtomicInteger(0);
            long deadline = android.os.SystemClock.elapsedRealtime()
                + FIRST_FRAME_TIMEOUT_MS;
            if (panoLatch.await(Math.max(0L,
                    deadline - android.os.SystemClock.elapsedRealtime()),
                TimeUnit.MILLISECONDS)) okCount.incrementAndGet();
            if (oemLatch.await(Math.max(0L,
                    deadline - android.os.SystemClock.elapsedRealtime()),
                TimeUnit.MILLISECONDS)) okCount.incrementAndGet();

            int result = okCount.get() == 2 ? 1 : 0;
            logger.info("Probe frames within " + FIRST_FRAME_TIMEOUT_MS + "ms: "
                + okCount.get() + "/2 → result=" + result);
            return result;
        } catch (Throwable t) {
            logger.warn("Probe threw: " + t.getMessage());
            return terminalCameraOpenRestart.get() ? -1 : 0;
        } finally {
            if (terminalCameraOpenRestart.get()) {
                // A raw vendor open is still in flight and may publish its
                // camera handle late. Java cleanup could overlap it; process
                // retirement is the only safe owner now.
                logger.warn("Probe cleanup skipped: terminal camera-open "
                    + "restart owns all HAL resources");
            } else {
                // Always close in reverse order. AVMCamera close paths must run
                // even on exception so we don't leak HAL handles.
                tryStopClose(oemCam);
                tryStopClose(panoCam);
                if (panoSurf != null) try { panoSurf.release(); } catch (Throwable ignored) {}
                if (oemSurf != null) try { oemSurf.release(); } catch (Throwable ignored) {}
                if (panoSt != null) try { panoSt.release(); } catch (Throwable ignored) {}
                if (oemSt != null) try { oemSt.release(); } catch (Throwable ignored) {}
                if (panoThread != null) panoThread.quitSafely();
                if (oemThread != null) oemThread.quitSafely();
            }
        }
    }

    private static Object openAvmCamera(Class<?> avm, int id)
            throws InterruptedException {
        final AtomicReference<Object> result = new AtomicReference<>();
        Thread worker = new Thread(
            () -> result.set(openAvmCameraBlocking(avm, id)),
            "ConcurrentAvmOpen-" + id);
        worker.setDaemon(true);
        worker.start();

        final long deadline = android.os.SystemClock.elapsedRealtime()
            + CAMERA_OPEN_HARD_TIMEOUT_MS;
        while (worker.isAlive()) {
            long remaining =
                deadline - android.os.SystemClock.elapsedRealtime();
            if (remaining <= 0L) break;
            try {
                worker.join(Math.min(remaining, 200L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (worker.isAlive()) {
                    armTerminalOpenRestart(
                        "Concurrent AVMCamera probe open interrupted for id="
                            + id);
                    throw interrupted;
                }
                Object completed = result.getAndSet(null);
                tryStopClose(completed);
                throw interrupted;
            }
        }
        if (worker.isAlive()) {
            armTerminalOpenRestart(
                "Concurrent AVMCamera probe open blocked for "
                    + CAMERA_OPEN_HARD_TIMEOUT_MS + "ms (id=" + id + ")");
            return null;
        }
        return result.get();
    }

    private static void armTerminalOpenRestart(String reason) {
        terminalCameraOpenRestart.set(true);
        com.overdrive.app.daemon.CameraDaemon
            .requestUrgentCameraReleaseRestart(reason);
        logger.error(reason + " — terminal restart armed; probe cleanup fenced");
    }

    private static Object openAvmCameraBlocking(Class<?> avm, int id) {
        try {
            Constructor<?> c = avm.getDeclaredConstructor(int.class);
            c.setAccessible(true);
            Object cam = c.newInstance(id);
            Method m = avm.getDeclaredMethod("open");
            m.setAccessible(true);
            Object r = m.invoke(cam);
            if (r instanceof Boolean && !((Boolean) r)) return null;
            return cam;
        } catch (NoSuchMethodException nsme) {
            try {
                Method m = avm.getDeclaredMethod("open", int.class);
                m.setAccessible(true);
                return m.invoke(null, id);
            } catch (Throwable t) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean attachAndStart(Class<?> avm, Object cam, Surface surf, SurfaceTexture st) {
        try {
            try {
                Method m = avm.getDeclaredMethod("addPreviewSurface", Surface.class, int.class);
                m.setAccessible(true);
                m.invoke(cam, surf, 0);
            } catch (NoSuchMethodException ignored) {
                // Try addTexture path. Note: we pass the SurfaceTexture; some
                // HAL builds want a valid GL texture name attached to it.
                // Probe is best-effort — if this path is required, the probe
                // still returns 0 but we log it so users get diagnostics.
                Method m = avm.getDeclaredMethod("addTexture", SurfaceTexture.class, int.class);
                m.setAccessible(true);
                m.invoke(cam, st, 0);
            }
            Method start = avm.getDeclaredMethod("startPreview");
            start.setAccessible(true);
            start.invoke(cam);
            return true;
        } catch (Throwable t) {
            logger.warn("attachAndStart failed: " + t.getMessage());
            return false;
        }
    }

    private static void tryStopClose(Object cam) {
        if (cam == null) return;
        try {
            Method m = cam.getClass().getDeclaredMethod("stopPreview");
            m.setAccessible(true);
            m.invoke(cam);
        } catch (Throwable ignored) {}
        try {
            Method m = cam.getClass().getDeclaredMethod("close");
            m.setAccessible(true);
            m.invoke(cam);
        } catch (Throwable ignored) {}
    }
}
