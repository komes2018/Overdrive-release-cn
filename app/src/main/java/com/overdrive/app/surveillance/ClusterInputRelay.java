package com.overdrive.app.surveillance;

import android.content.Context;
import android.graphics.Point;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.MotionEvent;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * Daemon-side touch relay: forwards a tap / swipe captured on the head-unit mirror pane
 * into the app that is projected on the NON-TOUCH driver-cluster ("fission") display,
 * restoring interactivity to a cast app.
 *
 * <h3>Why the daemon does the injection</h3>
 * {@code INJECT_EVENTS} is a signature|privileged permission granted to the shell uid out
 * of the box; the daemon runs as uid 2000 (shell) and already injects system input
 * ({@code input keyevent} for home/back/recents, wake/sleep). The app process (uid 1000,
 * not platform-signed) cannot inject, so the app POSTs normalized coordinates to the
 * daemon and this class targets the cluster display.
 *
 * <h3>Coordinates are NORMALIZED (0..1)</h3>
 * The app sends the tap position as fractions of the mirror pane (0..1 in each axis) so it
 * never has to know the cluster panel resolution (which is resolved live and varies by
 * trim). We multiply by the live fission panel size here. This keeps the transform correct
 * even if the pane is dragged/resized on the head unit.
 *
 * <h3>Safety — never clobber the head unit</h3>
 * The live fission {@code displayId} is never hardcoded — SurfaceFlinger assigns it per
 * projection-open and it is NOT reliably 1. DI5 uses the validated display snapshot retained by
 * the active mirror session (avoiding a multi-second dumpsys on every tap); legacy mode keeps its
 * existing live resolver. We HARD-REFUSE to inject unless the id is {@code > 0} (0 == head unit):
 * a stale/absent id must never fall through to display 0 and actuate the infotainment screen.
 *
 * <h3>Two injection strategies</h3>
 * Primary is the fork-free reflected {@code InputManager.injectInputEvent} +
 * {@code MotionEvent.setDisplayId(fissionId)} (no per-tap process spawn). If reflection is
 * unavailable on this build we fall back to the shell {@code input -d <id> tap|swipe}
 * (the {@code -d} display flag exists on API 29; same exec form the keymap replay uses).
 * A tap that lands on a window the projected app marks non-touchable simply no-ops — that
 * is the app's choice, identical to a head-unit tap, not a failure of this relay.
 */
public final class ClusterInputRelay {

    private static final String TAG = "ClusterInputRelay";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // Injected-event mode ASYNC = 0 (INJECT_INPUT_EVENT_MODE_ASYNC). Kept as a literal
    // so we don't reflect a constant; the numeric contract is stable on API 29.
    private static final int INJECT_MODE_ASYNC = 0;
    private static final int DEFAULT_SWIPE_MS = 200;
    private static final int MAX_SWIPE_MS = 3000;

    // Cached reflected injectInputEvent handle (resolved once; null = use shell fallback).
    private static volatile Method injectMethod;      // IInputManager#injectInputEvent(InputEvent,int)
    private static volatile Object inputManagerService;
    private static volatile boolean injectResolved = false;
    private static volatile Method setDisplayIdMethod; // MotionEvent#setDisplayId(int)

    private ClusterInputRelay() {}

    /** Relay a single tap at a mirror-SURFACE px coordinate. Inverts through the live
     *  projection mapping to a cluster px and injects it. Returns true if dispatched. */
    public static boolean tap(double sx, double sy) {
        ProjectionMapping mapping = currentProjectionMapping();
        if (mapping == null || !Double.isFinite(sx) || !Double.isFinite(sy)) return false;
        int[] panel = resolvePanel(mapping);
        if (panel == null) return false;
        int id = panel[0], w = panel[1], h = panel[2];
        int x = clampToPanel(mapping.mapX(sx), w);
        int y = clampToPanel(mapping.mapY(sy), h);
        if (injectTap(id, x, y)) return true;
        return shellTap(id, x, y);
    }

    /** Relay a swipe/scroll between two mirror-SURFACE px coordinates over {@code durationMs}. */
    public static boolean swipe(double sx1, double sy1, double sx2, double sy2, int durationMs) {
        ProjectionMapping mapping = currentProjectionMapping();
        if (mapping == null || !Double.isFinite(sx1) || !Double.isFinite(sy1)
                || !Double.isFinite(sx2) || !Double.isFinite(sy2)) {
            return false;
        }
        int[] panel = resolvePanel(mapping);
        if (panel == null) return false;
        int id = panel[0], w = panel[1], h = panel[2];
        int x1 = clampToPanel(mapping.mapX(sx1), w);
        int y1 = clampToPanel(mapping.mapY(sy1), h);
        int x2 = clampToPanel(mapping.mapX(sx2), w);
        int y2 = clampToPanel(mapping.mapY(sy2), h);
        int dur = durationMs <= 0 ? DEFAULT_SWIPE_MS : Math.min(durationMs, MAX_SWIPE_MS);
        if (injectSwipe(id, x1, y1, x2, y2, dur)) return true;
        return shellSwipe(id, x1, y1, x2, y2, dur);
    }

    /**
     * Take one validated live projection snapshot for a complete tap/swipe. Re-reading the
     * mapping independently for each axis or endpoint lets a concurrent resize combine old
     * and new rectangles into a diagonal jump. A missing mapping is a hard refusal: falling
     * back to raw surface coordinates can inject into the wrong cluster location after
     * teardown.
     */
    private static ProjectionMapping currentProjectionMapping() {
        try {
            ClusterViewMirrorService m = ClusterViewMirrorService.getInstance();
            int[] snapshot = m.inputSnapshot();
            if (snapshot == null || snapshot.length < 13
                    || snapshot[0] != ClusterViewMirrorService.STATE_ACTIVE) {
                return null;
            }
            int panelW = snapshot[1];
            int panelH = snapshot[2];
            int mode = snapshot[3];
            int inputDisplayId = snapshot[4];
            int[] src = new int[] {
                    snapshot[5], snapshot[6], snapshot[7], snapshot[8]
            };
            int[] dst = new int[] {
                    snapshot[9], snapshot[10], snapshot[11], snapshot[12]
            };
            if (src[0] < 0 || src[1] < 0 || src[2] <= 0 || src[3] <= 0
                    || panelW <= 0 || panelH <= 0
                    || dst[2] <= 0 || dst[3] <= 0
                    || (long) src[0] + src[2] > panelW
                    || (long) src[1] + src[3] > panelH) {
                return null;
            }
            if (mode != ProjectionGeometry.SCALE_FILL
                    && !aspectsCompatible(src[2], src[3], dst[2], dst[3])) {
                logger.debug("relay refused inconsistent projection src=" + src[2] + "x"
                        + src[3] + " dst=" + dst[2] + "x" + dst[3] + " mode=" + mode);
                return null;
            }
            return new ProjectionMapping(
                    src, dst, panelW, panelH, inputDisplayId);
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean aspectsCompatible(int aw, int ah, int bw, int bh) {
        long delta = Math.abs((long) aw * bh - (long) ah * bw);
        long tolerance = Math.max(Math.max(aw, ah), Math.max(bw, bh));
        return delta <= tolerance;
    }

    private static final class ProjectionMapping {
        final int srcX;
        final int srcY;
        final int srcW;
        final int srcH;
        final int dstX;
        final int dstY;
        final int dstW;
        final int dstH;
        final int panelW;
        final int panelH;
        final int inputDisplayId;

        ProjectionMapping(
                int[] src, int[] dst, int panelW, int panelH,
                int inputDisplayId) {
            this.srcX = src[0];
            this.srcY = src[1];
            this.srcW = src[2];
            this.srcH = src[3];
            this.dstX = dst[0];
            this.dstY = dst[1];
            this.dstW = dst[2];
            this.dstH = dst[3];
            this.panelW = panelW;
            this.panelH = panelH;
            this.inputDisplayId = inputDisplayId;
        }

        double mapX(double surfaceX) {
            return ProjectionGeometry.destinationToSource(
                    surfaceX, srcX, srcW, dstX, dstW);
        }

        double mapY(double surfaceY) {
            return ProjectionGeometry.destinationToSource(
                    surfaceY, srcY, srcH, dstY, dstH);
        }
    }

    // ── Panel/display resolution + safety gate ──────────────────────────────────────

    /** {@code [fissionDisplayId, panelW, panelH]}, or null if no safe cluster target.
     *  HARD-REFUSES id <= 0 (0 = head unit) so injection can never hit the infotainment. */
    private static int[] resolvePanel(ProjectionMapping mapping) {
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            int targetId = com.overdrive.app.launcher.DiLink5ClusterCast
                    .currentTargetDisplayId();
            int w = com.overdrive.app.launcher.DiLink5ClusterCast
                    .currentTargetDisplayWidth();
            int h = com.overdrive.app.launcher.DiLink5ClusterCast
                    .currentTargetDisplayHeight();
            int id = mapping.inputDisplayId;
            if (targetId <= 0 || id <= 0 || w <= 0 || h <= 0) {
                logger.warn("relay refused — no active DI5 OEM target (id="
                        + targetId + " composed=" + id + " size="
                        + w + "x" + h + ")");
                return null;
            }
            if (w != mapping.panelW || h != mapping.panelH) {
                logger.warn("relay refused — DI5 target size " + w + "x" + h
                        + " differs from projection " + mapping.panelW + "x"
                        + mapping.panelH);
                return null;
            }
            // DI5 uses two different endpoints by design: the app task lives on
            // shared_fission..._0/_1 (targetId), while input and panel composition
            // are owned by the exact plain fission display (id). Injecting into the
            // shadow target is accepted but never reaches the visible app.
            return new int[] { id, w, h };
        }
        BsNativeLayer.FissionDisplay fd = BsNativeLayer.resolveFissionDisplay();
        if (fd.displayId <= 0) {
            logger.warn("relay refused — no positive fission displayId (id=" + fd.displayId + ")");
            return null;
        }
        Context ctx = resolveContext();
        Point panel = BsNativeLayer.clusterDisplaySize(ctx, fd);
        int w = Math.max(1, panel.x), h = Math.max(1, panel.y);
        if (w != mapping.panelW || h != mapping.panelH) {
            logger.warn("relay refused — live display size " + w + "x" + h
                    + " differs from projection " + mapping.panelW + "x" + mapping.panelH);
            return null;
        }
        return new int[] { fd.displayId, w, h };
    }

    /** Clamp an ABSOLUTE cluster-px coordinate into {@code [0, size-1]}. */
    private static int clampToPanel(double clusterPx, int size) {
        if (Double.isNaN(clusterPx)) clusterPx = 0;
        int v = (int) Math.round(clusterPx);
        if (v < 0) v = 0;
        if (v > size - 1) v = size - 1;
        return v;
    }

    // ── Primary: fork-free reflected injectInputEvent with setDisplayId ─────────────

    private static boolean injectTap(int displayId, int x, int y) {
        long now = SystemClock.uptimeMillis();
        boolean down = injectMotion(MotionEvent.ACTION_DOWN, now, now, x, y, displayId);
        if (!down) return false;
        // Small dwell so the target registers a press, then up at the same point.
        boolean up = injectMotion(MotionEvent.ACTION_UP, now, now + 60, x, y, displayId);
        return up;
    }

    private static boolean injectSwipe(int displayId, int x1, int y1, int x2, int y2, int durMs) {
        long down = SystemClock.uptimeMillis();
        if (!injectMotion(MotionEvent.ACTION_DOWN, down, down, x1, y1, displayId)) return false;
        int steps = Math.max(1, Math.min(durMs / 16, 64));   // ~60fps of moves, capped
        for (int i = 1; i <= steps; i++) {
            // Keep MOVE timestamps before the final UP. Sending a MOVE and UP at the same
            // endpoint/time can collapse the end of short swipes on InputDispatcher.
            float t = (float) i / (steps + 1);
            int mx = Math.round(x1 + (x2 - x1) * t);
            int my = Math.round(y1 + (y2 - y1) * t);
            long ev = down + (long) (durMs * t);
            if (!injectMotion(MotionEvent.ACTION_MOVE, down, ev, mx, my, displayId)) {
                // A move failed mid-swipe — still emit the UP so we don't strand a
                // pressed pointer on the target.
                injectMotion(MotionEvent.ACTION_UP, down, down + durMs, mx, my, displayId);
                return false;
            }
        }
        return injectMotion(MotionEvent.ACTION_UP, down, down + durMs, x2, y2, displayId);
    }

    /** Build a single-pointer MotionEvent tagged with {@code displayId} and inject it.
     *  Returns false (and disables the reflected path) if reflection is unavailable. */
    private static boolean injectMotion(int action, long downTime, long eventTime,
                                        int x, int y, int displayId) {
        if (!ensureInjectResolved()) return false;
        MotionEvent ev = null;
        try {
            ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
            ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            // Route to the cluster display. If setDisplayId is missing the event would go
            // to the default display → refuse rather than tap the head unit.
            if (setDisplayIdMethod == null) return false;
            setDisplayIdMethod.invoke(ev, displayId);
            Object ret = injectMethod.invoke(inputManagerService, ev, INJECT_MODE_ASYNC);
            return !(ret instanceof Boolean) || (Boolean) ret;
        } catch (Throwable t) {
            logger.debug("injectMotion failed: " + t.getMessage());
            // Disable the reflected path for subsequent calls; shell fallback takes over.
            injectMethod = null;
            return false;
        } finally {
            if (ev != null) try { ev.recycle(); } catch (Throwable ignored) {}
        }
    }

    /** Resolve IInputManager + injectInputEvent + MotionEvent.setDisplayId ONCE. */
    private static boolean ensureInjectResolved() {
        if (injectResolved) return injectMethod != null;
        synchronized (ClusterInputRelay.class) {
            if (injectResolved) return injectMethod != null;
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                Object binder = sm.getMethod("getService", String.class).invoke(null, Context.INPUT_SERVICE);
                Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
                Object iim = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
                Method m = iim.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
                Method sd = MotionEvent.class.getMethod("setDisplayId", int.class);
                inputManagerService = iim;
                injectMethod = m;
                setDisplayIdMethod = sd;
                logger.info("input relay: reflected injectInputEvent resolved");
            } catch (Throwable t) {
                injectMethod = null;
                logger.info("input relay: reflected inject unavailable (" + t.getMessage()
                        + ") — using `input -d` shell fallback");
            }
            injectResolved = true;
        }
        return injectMethod != null;
    }

    // ── Fallback: shell `input -d <id> tap|swipe` ────────────────────────────────────

    private static boolean shellTap(int displayId, int x, int y) {
        return runShell("input -d " + displayId + " tap " + x + " " + y);
    }

    private static boolean shellSwipe(int displayId, int x1, int y1, int x2, int y2, int durMs) {
        return runShell("input -d " + displayId + " swipe " + x1 + " " + y1 + " "
                + x2 + " " + y2 + " " + durMs);
    }

    private static boolean runShell(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            logger.warn("input relay shell failed: " + t.getMessage());
            return false;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    private static Context resolveContext() {
        try {
            Class<?> cd = Class.forName("com.overdrive.app.daemon.CameraDaemon");
            Object ctx = cd.getMethod("getAppContext").invoke(null);
            if (ctx instanceof Context) return (Context) ctx;
        } catch (Throwable ignored) {}
        return null;
    }
}
