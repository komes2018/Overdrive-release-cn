package com.overdrive.app.surveillance;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;

/**
 * Daemon-side ({@code uid 2000}) mirror that composites the driver-cluster ("fission")
 * display into a Surface OWNED BY THE APP — a {@code TextureView} living in the Projection
 * screen. This is the resize-correct path: the app view is sized/positioned by the normal
 * Android layout system, so growing/shrinking the pane simply changes the view's Surface
 * dimensions, and we re-letterbox the projection into those new dimensions. Nothing is
 * pinned to a fixed buffer, so there is no size to "fight" (which is why the earlier
 * daemon-owned buffer-layer approach could never resize on this firmware).
 *
 * <h3>How the pixels get there — codec-free</h3>
 * SurfaceFlinger is asked to composite the cluster's compositing layer-stack a SECOND time
 * onto an output surface we hand it:
 * <ol>
 *   <li>{@code SurfaceControl.createDisplay(name, false)} — a mirror display token.</li>
 *   <li>{@code Transaction.setDisplayLayerStack(token, clusterLayerStack)} — mirror the
 *       live fission stack (resolved per open, never hardcoded).</li>
 *   <li>{@code Transaction.setDisplaySurface(token, appSurface)} — output into the app's
 *       {@code TextureView} Surface (crossed the process boundary as a Binder parcelable).</li>
 *   <li>{@code Transaction.setDisplayProjection(token, 0, srcFullCluster, destLetterbox)} —
 *       aspect-preserving fit into the view's {@code viewW×viewH}. No MediaCodec, no
 *       WebSocket, no per-frame CPU: SurfaceFlinger drives frames at vsync.</li>
 * </ol>
 *
 * <h3>Wire protocol</h3>
 * The app finds this via {@code ServiceManager.getService(SERVICE_NAME)} and transacts:
 * <ul>
 *   <li>{@link #TXN_ATTACH} — hand over the Surface + view size; (re)creates the mirror.</li>
 *   <li>{@link #TXN_REPROJECT} — new view size only; re-letterboxes the LIVE token
 *       (no teardown) — the resize fast path.</li>
 *   <li>{@link #TXN_DETACH} — tear the mirror down.</li>
 *   <li>{@link #TXN_STATE} — read back mode + last projection offset/scale (for touch).</li>
 * </ul>
 * All mutating transacts reply with a status int so the caller knows the mirror actually
 * came up (and can fall back to the low-rate still-preview otherwise). New clients append
 * an owner Binder + monotonically increasing session id to each mutating transaction. The
 * trailer is additive: old daemons ignore it and this service still accepts old clients.
 *
 * <h3>Safety</h3>
 * NEVER targets a layer-stack of 0 (head unit) or an unresolved stack — the projection is
 * refused rather than risk painting over the infotainment. The display token is unbound
 * ({@code setDisplaySurface(token,null)}) BEFORE it is destroyed, so a teardown that races
 * the OEM projection close / panel power-gate at ACC-off can't leave SurfaceFlinger
 * compositing a dangling display (the native-fault-cascade this ordering prevents).
 */
public final class ClusterViewMirrorService extends Binder {

    private static final String TAG = "ClusterViewMirror";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** ServiceManager name the app resolves. OverDrive-native; distinct per feature. */
    public static final String SERVICE_NAME = "overdrive_cluster_view";
    /** Binder interface token. Must stay stable across app updates (a daemon spawned by an
     *  older build keeps enforcing this exact token). */
    public static final String DESCRIPTOR = "com.overdrive.app.cluster.IClusterViewMirror";

    public static final int TXN_ATTACH    = IBinder.FIRST_CALL_TRANSACTION;      // + view size + Surface
    public static final int TXN_REPROJECT = IBinder.FIRST_CALL_TRANSACTION + 1;  // + view size
    public static final int TXN_DETACH    = IBinder.FIRST_CALL_TRANSACTION + 2;
    public static final int TXN_STATE     = IBinder.FIRST_CALL_TRANSACTION + 3;

    // Mirror state reported back to the UI (matches the UI-side constants).
    public static final int STATE_STOPPED       = 0;
    public static final int STATE_ACTIVE        = 1;   // SurfaceFlinger composite into the view
    public static final int STATE_NO_PROJECTION = 2;   // nothing cast — open a cast first
    public static final int STATE_UNSUPPORTED   = 3;   // compose into the app Surface denied

    // Scaling modes — MUST match the UI constants. FIT letterboxes (default); FILL stretches
    // to the whole view; ZOOM crops the source to cover the view.
    public static final int SCALE_FIT  = 0;
    public static final int SCALE_FILL = 1;
    public static final int SCALE_ZOOM = 2;

    private static volatile ClusterViewMirrorService instance;
    private static volatile boolean registered;

    // ── All mirror state guarded by `lock` (transacts arrive on Binder pool threads) ──
    private final Object lock = new Object();
    private Object mirrorToken;          // android.os.IBinder from SurfaceControl.createDisplay
    private Surface outputSurface;        // the app's TextureView Surface (we hold a ref while live)
    private IBinder ownerToken;            // app-process death token; null for legacy clients
    private OwnerDeath ownerDeathRecipient;
    private long activeSessionId;          // 0 for clients predating the session trailer
    private IBinder sessionOwnerToken;     // retained across detach as an ordering watermark
    private long newestSessionId;
    private boolean holdsProjection;      // true only while WE hold the "viewmirror" sustained token
    private long ownerCastGeneration;     // cast mirrored by ownerToken; 0 when not a cast
    private int state = STATE_STOPPED;
    private int clusterW = 1, clusterH = 1;
    private int layerStack = -1;
    private int fissionDisplayId = -1;
    private int scaleMode = SCALE_FIT;
    // Last projection mapping, published for the touch relay. A tap in SURFACE px inverts to
    // a cluster px via: clusterX = srcX + (sx - dstX) * srcW/dstW (same for Y). This is
    // general across FIT (dst letterboxed), FILL (dst == box, src full) and ZOOM (src
    // cropped, dst == box). {left,top,w,h} each; all 0 when not projected.
    private volatile int[] projSrc = { 0, 0, 0, 0 };   // cluster px
    private volatile int[] projDst = { 0, 0, 0, 0 };   // surface px

    /** One recipient per successful attach. Identity binding is essential: an unlink racing
     *  an already queued death callback must not let that stale callback detach a replacement. */
    private final class OwnerDeath implements IBinder.DeathRecipient {
        final IBinder owner;
        final long sessionId;

        OwnerDeath(IBinder owner, long sessionId) {
            this.owner = owner;
            this.sessionId = sessionId;
        }

        @Override public void binderDied() {
            handleOwnerDeath(this);
        }
    }

    private ClusterViewMirrorService() { attachInterface(null, DESCRIPTOR); }

    // ── Registration (called once from the daemon after its Looper is up) ─────────────

    /** Register the service with ServiceManager so the app can resolve it. Idempotent;
     *  guarded + logged. No-op if already registered or the platform call is unavailable. */
    public static synchronized void register() {
        if (registered) return;
        try {
            ClusterViewMirrorService svc = getInstance();
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method add;
            try {
                // Android 10 form: addService(String, IBinder, boolean, int).
                add = sm.getDeclaredMethod("addService", String.class, IBinder.class,
                        boolean.class, int.class);
                add.setAccessible(true);
                add.invoke(null, SERVICE_NAME, svc, false, 0);
            } catch (NoSuchMethodException e) {
                add = sm.getDeclaredMethod("addService", String.class, IBinder.class);
                add.setAccessible(true);
                add.invoke(null, SERVICE_NAME, svc);
            }
            registered = true;
            logger.info("registered service " + SERVICE_NAME);
        } catch (Throwable t) {
            logger.warn("register failed: " + t.getMessage());
        }
    }

    public static ClusterViewMirrorService getInstance() {
        if (instance == null) {
            synchronized (ClusterViewMirrorService.class) {
                if (instance == null) instance = new ClusterViewMirrorService();
            }
        }
        return instance;
    }

    /** ACC-off / daemon-exit / cast-app-uninstall reconcile. No-op if never attached. */
    public static void forceDetachIfActive(String reason) {
        ClusterViewMirrorService i = instance;
        if (i != null) {
            logger.info("forceDetach(" + reason + ")");
            i.detach();
        }
    }

    /**
     * Projection-controller close hook. The mirror display reads from the projection's
     * fission layer stack, so it must be unbound and destroyed before that source closes.
     *
     * <p>This path deliberately does not release the {@code viewmirror} sustained token:
     * the caller is already performing an authoritative close that clears every token.
     * Releasing here could recursively enter {@code forceClose("sustained-release")}.
     */
    public static boolean detachBeforeProjectionClose(String reason) {
        ClusterViewMirrorService i = instance;
        if (i == null) return true;
        synchronized (i.lock) {
            if (i.state == STATE_STOPPED && i.mirrorToken == null
                    && i.outputSurface == null && i.ownerToken == null
                    && !i.holdsProjection) {
                return true;
            }
            logger.info("detachBeforeProjectionClose(" + reason + ")");
            i.holdsProjection = false;
            i.detachCurrentLocked();
            return i.state == STATE_STOPPED
                    && i.mirrorToken == null
                    && i.outputSurface == null
                    && i.ownerToken == null;
        }
    }

    /** Snapshot for the status endpoint. */
    public int currentState() { synchronized (lock) { return state; } }
    public int currentClusterW() { synchronized (lock) { return clusterW; } }
    public int currentClusterH() { synchronized (lock) { return clusterH; } }
    public int currentScaleMode() { synchronized (lock) { return scaleMode; } }
    /**
     * Atomic touch-relay snapshot:
     * state,w,h,scaleMode,inputDisplayId,src(x,y,w,h),dst(x,y,w,h).
     */
    public int[] inputSnapshot() {
        synchronized (lock) {
            return new int[] {
                    state,
                    clusterW,
                    clusterH,
                    scaleMode,
                    fissionDisplayId,
                    projSrc[0], projSrc[1], projSrc[2], projSrc[3],
                    projDst[0], projDst[1], projDst[2], projDst[3]
            };
        }
    }
    /** Last projection source rect (cluster px) — copy. {@code w==0} when not projected. */
    public int[] projectionSrc() { int[] s = projSrc; return new int[] { s[0], s[1], s[2], s[3] }; }
    /** Last projection destination rect (surface px) — copy. {@code w==0} when not projected. */
    public int[] projectionDst() { int[] d = projDst; return new int[] { d[0], d[1], d[2], d[3] }; }

    // ── Binder dispatch ────────────────────────────────────────────────────────────

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        // CRASH CONTAINMENT: this runs on a Binder POOL thread inside the camera daemon. An
        // uncaught throw here (a reflection failure, an OEM SurfaceControl quirk, a malformed
        // parcel) must NEVER propagate and risk taking the daemon down — the projection mirror is
        // a non-critical feature. Any unexpected error is logged and reported to the caller as a
        // benign STOPPED reply, so the daemon keeps serving recording/surveillance/etc.
        try {
            return dispatchTransact(code, data, reply, flags);
        } catch (Throwable t) {
            // EVERYTHING in this catch must itself be throw-proof — it is the last line of defense
            // on the Binder thread. Even the logging is guarded: DaemonLogger formats a timestamp
            // with a shared SimpleDateFormat, which can throw under concurrent daemon threads, so a
            // bare log here could defeat the very containment it lives in and kill the daemon.
            try { logger.warn("onTransact(" + code + ") failed: " + t.getMessage()); }
            catch (Throwable ignored) { /* logging must never crash the daemon */ }
            try {
                if (reply != null && code >= TXN_ATTACH && code <= TXN_STATE) {
                    reply.writeNoException();
                    reply.writeInt(STATE_STOPPED);
                }
            } catch (Throwable ignored) { /* reply may be one-way / already written */ }
            return true;   // handled (degraded) — never rethrow onto the Binder thread
        }
    }

    private boolean dispatchTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        switch (code) {
            case TXN_ATTACH: {
                data.enforceInterface(DESCRIPTOR);
                int mode = data.readInt();
                int bx = data.readInt(), by = data.readInt();
                int bw = data.readInt(), bh = data.readInt();
                Surface surface = (data.readInt() != 0)
                        ? Surface.CREATOR.createFromParcel(data) : null;
                try {
                    // Optional additive trailer for compatibility in both directions: an old
                    // client sends neither value; an old daemon ignores both trailing values.
                    IBinder owner = data.dataAvail() > 0 ? data.readStrongBinder() : null;
                    boolean scoped = owner != null && data.dataAvail() >= 8;
                    long sessionId = scoped ? data.readLong() : 0L;
                    // Once called, attach() owns the unparceled wrapper on every outcome.
                    int st = attach(surface, new Rect(bx, by, bx + bw, by + bh), mode,
                            owner, sessionId, scoped);
                    surface = null;
                    if (reply != null) { reply.writeNoException(); reply.writeInt(st); }
                    return true;
                } finally {
                    // A malformed optional trailer can fail before attach() takes ownership.
                    releaseSurface(surface);
                }
            }
            case TXN_REPROJECT: {
                data.enforceInterface(DESCRIPTOR);
                int mode = data.readInt();
                int bx = data.readInt(), by = data.readInt();
                int bw = data.readInt(), bh = data.readInt();
                IBinder owner = data.dataAvail() > 0 ? data.readStrongBinder() : null;
                boolean scoped = owner != null && data.dataAvail() >= 8;
                long sessionId = scoped ? data.readLong() : 0L;
                int st = reproject(new Rect(bx, by, bx + bw, by + bh), mode,
                        owner, sessionId, scoped);
                if (reply != null) { reply.writeNoException(); reply.writeInt(st); }
                return true;
            }
            case TXN_DETACH: {
                data.enforceInterface(DESCRIPTOR);
                IBinder owner = data.dataAvail() > 0 ? data.readStrongBinder() : null;
                boolean scoped = owner != null && data.dataAvail() >= 8;
                long sessionId = scoped ? data.readLong() : 0L;
                int st = detach(owner, sessionId, scoped);
                if (reply != null) { reply.writeNoException(); reply.writeInt(st); }
                return true;
            }
            case TXN_STATE: {
                data.enforceInterface(DESCRIPTOR);
                if (reply != null) {
                    reply.writeNoException();
                    synchronized (lock) {
                        reply.writeInt(state);
                        reply.writeInt(clusterW);
                        reply.writeInt(clusterH);
                        reply.writeInt(scaleMode);
                    }
                }
                return true;
            }
            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    // ── Mirror lifecycle ─────────────────────────────────────────────────────────────

    /**
     * (Re)create the mirror onto {@code surface}, projecting the cluster into {@code box}
     * (a sub-rect of the surface, in surface px) per the scaling mode. Fully replaces any
     * prior session. Returns a STATE_* code.
     */
    private int attach(Surface surface, Rect box, int mode, IBinder owner,
                       long sessionId, boolean scoped) {
        // Read the cast identity BEFORE taking `lock`. ClusterCast.currentSessionGeneration() is
        // synchronized on the ClusterCast monitor, and ClusterCast.start() (also synchronized) can
        // reach `lock` via ClusterMapProjector.stop -> releaseSustained -> forceClose ->
        // detachBeforeProjectionClose. Acquiring the two in opposite orders would deadlock, so the
        // rule is absolute: never touch ClusterCast while holding `lock`. Sampling it a few
        // microseconds early is harmless — a cast that starts in between simply gets a newer
        // generation, and the owner-death path's generation guard then declines to stop it (which is
        // the safe direction: never tear down a cast this mirror session does not own).
        final long castGenerationAtAttach =
                com.overdrive.app.launcher.ClusterCast.currentSessionGeneration();
        synchronized (lock) {
            if (surface == null || !surface.isValid() || box == null
                    || box.width() <= 0 || box.height() <= 0) {
                logger.warn("attach: invalid surface/box " + box);
                releaseSurface(surface);
                // Do not destroy a valid current mirror because a replacement Surface raced
                // TextureView destruction while its Binder transaction was in flight.
                return STATE_STOPPED;
            }
            if (!acceptAttachIdentityLocked(owner, sessionId, scoped)) {
                logger.info("attach: stale/foreign session ignored id=" + sessionId);
                releaseSurface(surface);
                return STATE_STOPPED;
            }

            boolean surfaceAdopted = false;
            try {
                ClusterProjectionController projection =
                        ClusterProjectionController.getInstance();
                boolean diLink5 =
                        com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected();
                // A hard close marks the source non-open before detaching us. Refuse an attach
                // that arrives in the detach→close window so it cannot bind a fresh mirror to a
                // fission display that is about to be destroyed.
                if (!projectionSourceOpen(projection, diLink5)) {
                    logger.info("attach: projection is not open");
                    releaseSurface(surface);
                    return STATE_NO_PROJECTION;
                }
                int diLink5TargetId = diLink5
                        ? com.overdrive.app.launcher.DiLink5ClusterCast
                                .currentTargetDisplayId()
                        : -1;
                BsNativeLayer.FissionDisplay fd = diLink5
                        ? BsNativeLayer.resolveDiLink5MirrorDisplay(diLink5TargetId)
                        : BsNativeLayer.resolveFissionDisplay();
                Context ctx = resolveContext();
                int diLink5Width = diLink5
                        ? com.overdrive.app.launcher.DiLink5ClusterCast
                                .currentTargetDisplayWidth()
                        : 0;
                int diLink5Height = diLink5
                        ? com.overdrive.app.launcher.DiLink5ClusterCast
                                .currentTargetDisplayHeight()
                        : 0;
                Point panel = diLink5Width > 1 && diLink5Height > 1
                        ? new Point(diLink5Width, diLink5Height)
                        : ((ctx != null)
                            ? BsNativeLayer.clusterDisplaySize(ctx, fd)
                            : new Point(1920, 720));
                teardownLocked();
                if (!projectionSourceOpen(projection, diLink5)) {
                    logger.info("attach: projection closed while replacing mirror");
                    releaseSurface(surface);
                    state = STATE_NO_PROJECTION;
                    return state;
                }
                this.scaleMode = normalizeMode(mode);
                this.clusterW = Math.max(1, panel.x);
                this.clusterH = Math.max(1, panel.y);
                this.layerStack = fd.layerStack;
                this.fissionDisplayId = fd.displayId;

                // Nothing is projected on the cluster → nothing to mirror. Never proceed on an
                // unresolved or head-unit (0) stack.
                if (this.layerStack == BsNativeLayer.STACK_UNRESOLVED || this.layerStack <= 0) {
                    logger.info("attach: no live cluster projection (stack=" + this.layerStack + ")");
                    releaseSurface(surface);
                    state = STATE_NO_PROJECTION;
                    return state;
                }
                if (!linkOwnerLocked(owner, scoped ? sessionId : 0L)) {
                    logger.warn("attach: owner process already dead");
                    releaseSurface(surface);
                    state = STATE_STOPPED;
                    return state;
                }

                if (!diLink5) {
                    try {
                        if (!projection.acquireSustained("viewmirror")) {
                            logger.info("attach: projection hold was not admitted");
                            releaseSurface(surface);
                            teardownLocked();
                            state = STATE_NO_PROJECTION;
                            return state;
                        }
                        holdsProjection = true;
                    } catch (Throwable t) {
                        logger.warn("attach: acquireSustained failed: " + t.getMessage());
                        releaseSurface(surface);
                        teardownLocked();
                        state = STATE_UNSUPPORTED;
                        return state;
                    }
                }
                if (!projectionSourceOpen(projection, diLink5)) {
                    logger.info("attach: projection began closing during attach");
                    releaseSurface(surface);
                    teardownLocked();
                    state = STATE_NO_PROJECTION;
                    return state;
                }

                outputSurface = surface;
                surfaceAdopted = true;
                mirrorToken = createDisplay(MIRROR_NAME);
                if (mirrorToken == null) {
                    logger.warn("attach: createDisplay unavailable");
                    teardownLocked();
                    state = STATE_UNSUPPORTED;
                    return state;
                }
                if (!applyProjection(box)) {
                    logger.warn("attach: projection transaction failed");
                    teardownLocked();
                    state = STATE_UNSUPPORTED;
                    return state;
                }
                state = STATE_ACTIVE;
                ownerCastGeneration = castGenerationAtAttach;   // sampled before `lock`; see above
                // The mirror composites the WHOLE cluster layer stack; suppress the daemon speed
                // badge (a layer on that stack) for the mirror's duration so it isn't re-captured
                // into the head-unit preview. Re-shown on teardown. Best-effort — never fail the
                // attach over the badge.
                try { ClusterSpeedOverlay.setMirrorActive(true); } catch (Throwable ignored) {}
                logger.info("mirror ACTIVE stack=" + layerStack + " cluster="
                        + clusterW + "x" + clusterH + " box=" + box.toShortString()
                        + " scaleMode=" + scaleMode);
                return state;
            } catch (Throwable t) {
                logger.warn("attach failed: " + t.getMessage());
                teardownLocked();
                if (!surfaceAdopted) releaseSurface(surface);
                state = STATE_UNSUPPORTED;
                return state;
            }
        }
    }

    private static boolean projectionSourceOpen(
            ClusterProjectionController projection, boolean diLink5) {
        return diLink5
                ? com.overdrive.app.launcher.DiLink5ClusterCast
                        .isProjectionSourceActive()
                : projection.isOpen();
    }

    /** Re-letterbox the live token into a new box rect (resize fast path — no teardown).
     *  No-op with the current state if the token was lost. */
    private int reproject(Rect box, int mode, IBinder owner, long sessionId, boolean scoped) {
        synchronized (lock) {
            if (!matchesActiveSessionLocked(owner, sessionId, scoped)) {
                logger.debug("reproject: stale/foreign session ignored id=" + sessionId);
                return STATE_STOPPED;
            }
            this.scaleMode = normalizeMode(mode);
            if (mirrorToken == null || outputSurface == null || !outputSurface.isValid()) {
                logger.info("reproject: no live token (state=" + state + ")");
                teardownLocked();
                state = STATE_STOPPED;
                return state;
            }
            if (box == null || box.width() <= 0 || box.height() <= 0) return state;
            // FIRMWARE QUIRK (confirmed on-car via SurfaceFlinger dump): re-applying
            // setDisplayProjection to an ALREADY-LIVE virtual display is silently ignored — the
            // composited `frame` stays pinned at the value set when the token was first created.
            // The ONLY reliable way to change the projection is to DESTROY the display token and
            // CREATE a fresh one, then project onto it (a FIRST projection on a new token IS
            // honored). This handler recreates the token but keeps the SAME app-owned output
            // Surface — a token swap, not a full re-attach. NOTE: on this firmware even a fresh
            // token bound to the SAME still-live Surface does not visibly move; the UI therefore
            // drives resize through a full detach + re-attach onto a FRESH Surface (see
            // ProjectionFragment.restartMirrorForResize), and this transaction is retained only for
            // wire compatibility with clients that still call it.
            Object oldToken = mirrorToken;
            mirrorToken = null;   // clear first so any failure below can't leave a stale token live
            detachSurfaceSafe(oldToken);
            destroyDisplaySafe(oldToken);
            Object freshToken = createDisplay(MIRROR_NAME);
            if (freshToken == null) {
                logger.warn("reproject: createDisplay unavailable on recreate");
                teardownLocked();
                state = STATE_STOPPED;
                return state;
            }
            mirrorToken = freshToken;
            if (!applyProjection(box)) {
                logger.warn("reproject: projection transaction failed on fresh token");
                teardownLocked();
                state = STATE_UNSUPPORTED;
                return state;
            }
            state = STATE_ACTIVE;
            logger.info("reproject via token recreate → " + box.toShortString());
            return state;
        }
    }

    /** Unscoped safety teardown used by ACC-off, daemon shutdown and package removal. */
    private void detach() {
        synchronized (lock) {
            detachCurrentLocked();
        }
    }

    /** Session-scoped UI teardown. A delayed old Fragment must not stop a replacement. */
    private int detach(IBinder owner, long sessionId, boolean scoped) {
        synchronized (lock) {
            if (!matchesActiveSessionLocked(owner, sessionId, scoped)) {
                logger.debug("detach: stale/foreign session ignored id=" + sessionId);
                return STATE_STOPPED;
            }
            detachCurrentLocked();
            return STATE_STOPPED;
        }
    }

    private void detachCurrentLocked() {
        if (state == STATE_STOPPED && mirrorToken == null && outputSurface == null
                && ownerToken == null && !holdsProjection) {
            return;
        }
        teardownLocked();
        state = STATE_STOPPED;
        logger.info("mirror detached");
    }

    /**
     * Compute the source crop + destination rect (in SURFACE px) for the active scaling mode
     * and apply the display transaction. The destination is inside {@code box} (the pane
     * rect the user positioned within the mirror surface). Publishes the surface-px→cluster-px
     * touch mapping (offset + scale, plus the source crop origin for ZOOM). Caller holds
     * {@code lock}.
     */
    private boolean applyProjection(Rect box) {
        ProjectionGeometry.Mapping mapping = ProjectionGeometry.calculate(
                clusterW, clusterH, box.left, box.top, box.width(), box.height(), scaleMode);
        Rect src = new Rect(
                mapping.srcX, mapping.srcY,
                mapping.srcX + mapping.srcW, mapping.srcY + mapping.srcH);
        Rect dst = new Rect(
                mapping.dstX, mapping.dstY,
                mapping.dstX + mapping.dstW, mapping.dstY + mapping.dstH);
        boolean ok = projectDisplay(mirrorToken, outputSurface, layerStack, src, dst);
        if (ok) {
            projSrc = new int[] { src.left, src.top, src.width(), src.height() };
            projDst = new int[] { dst.left, dst.top, dst.width(), dst.height() };
        }
        return ok;
    }

    private void teardownLocked() {
        if (mirrorToken != null) {
            // Unbind the output surface BEFORE destroying the token so SurfaceFlinger stops
            // compositing the fission stack into the app Surface first — closes the ACC-off
            // teardown-race window (dangling display while the source closes / panel gates).
            detachSurfaceSafe(mirrorToken);
            destroyDisplaySafe(mirrorToken);
            mirrorToken = null;
        }
        if (outputSurface != null) {
            // Release the daemon's LOCAL Surface wrapper (its native handle) — the app owns
            // the underlying buffer producer, but this wrapper was created from the parcel in
            // TXN_ATTACH and must be freed so it doesn't leak across re-attaches / teardown.
            try { outputSurface.release(); } catch (Throwable ignored) {}
            outputSurface = null;
        }
        unlinkOwnerLocked();
        activeSessionId = 0L;
        ownerCastGeneration = 0L;
        fissionDisplayId = -1;
        projSrc = new int[] { 0, 0, 0, 0 };
        projDst = new int[] { 0, 0, 0, 0 };
        // Release ONLY if we actually acquired — releaseSustained on an unheld token whose
        // set is already empty would fire an unwanted forceClose (closing a projection a
        // transient blind-spot signal might want).
        if (holdsProjection) {
            holdsProjection = false;
            try { ClusterProjectionController.getInstance().releaseSustained("viewmirror"); }
            catch (Throwable ignored) {}
        }
        // Mirror is gone — let the speed badge return to the cluster. This is the single choke
        // point for EVERY teardown (UI detach, ACC-off, owner death, projection close), so the
        // badge re-arms whenever the preview stops capturing. Idempotent + best-effort; start()'s
        // own gates keep it down if no projection is open.
        try { ClusterSpeedOverlay.setMirrorActive(false); } catch (Throwable ignored) {}
    }

    /**
     * Advances the per-owner session watermark before replacing anything. Invalid/stale
     * attaches therefore release only their newly unparceled Surface and leave the current
     * token untouched. Session id 0 is reserved for legacy clients.
     */
    private boolean acceptAttachIdentityLocked(IBinder owner, long sessionId, boolean scoped) {
        if (!scoped) {
            // A legacy process can manage a legacy session, but cannot supersede a mirror
            // already protected by the new protocol.
            return activeSessionId == 0L;
        }
        if (owner == null || sessionId <= 0L) return false;
        if (!binderAlive(owner)) return false;
        if (ownerToken != null && !sameBinder(ownerToken, owner)) {
            if (binderAlive(ownerToken)) return false;

            // The old process is already dead but its death callback has not acquired `lock`
            // yet. Retire it here so a replacement process does not get a one-shot STOPPED
            // response and remain black until another surface event happens.
            IBinder deadOwner = ownerToken;
            logger.info("attach: retiring dead prior owner before replacement");
            detachCurrentLocked();
            if (sameBinder(sessionOwnerToken, deadOwner)) {
                sessionOwnerToken = null;
                newestSessionId = 0L;
            }
        }

        if (sameBinder(sessionOwnerToken, owner)) {
            if (sessionId < newestSessionId) return false;
            newestSessionId = Math.max(newestSessionId, sessionId);
            return true;
        }

        // Do not let a queued transaction from another still-live app process steal the
        // mirror. A dead prior process is safe to replace even if its detached watermark
        // remains because no death recipient is linked after an explicit detach.
        if (sessionOwnerToken != null && binderAlive(sessionOwnerToken)) return false;
        sessionOwnerToken = owner;
        newestSessionId = sessionId;
        return true;
    }

    private boolean matchesActiveSessionLocked(
            IBinder owner, long sessionId, boolean scoped) {
        if (!scoped) return activeSessionId == 0L;
        return sessionId > 0L
                && activeSessionId == sessionId
                && sameBinder(ownerToken, owner);
    }

    private static boolean sameBinder(IBinder a, IBinder b) {
        return a == b || (a != null && a.equals(b));
    }

    private static boolean binderAlive(IBinder binder) {
        if (binder == null) return false;
        try { return binder.isBinderAlive(); } catch (Throwable ignored) { return false; }
    }

    private static int normalizeMode(int mode) {
        return (mode == SCALE_FILL || mode == SCALE_ZOOM) ? mode : SCALE_FIT;
    }

    private boolean linkOwnerLocked(IBinder owner, long sessionId) {
        activeSessionId = sessionId;
        if (owner == null) return true;
        OwnerDeath death = new OwnerDeath(owner, sessionId);
        try {
            owner.linkToDeath(death, 0);
            ownerToken = owner;
            ownerDeathRecipient = death;
            return true;
        } catch (RemoteException e) {
            ownerToken = null;
            ownerDeathRecipient = null;
            activeSessionId = 0L;
            return false;
        }
    }

    private void unlinkOwnerLocked() {
        IBinder owner = ownerToken;
        OwnerDeath death = ownerDeathRecipient;
        ownerToken = null;
        ownerDeathRecipient = null;
        activeSessionId = 0L;
        if (owner == null || death == null) return;
        try { owner.unlinkToDeath(death, 0); } catch (Throwable ignored) {}
    }

    private void handleOwnerDeath(OwnerDeath death) {
        long castGeneration;
        synchronized (lock) {
            if (ownerDeathRecipient != death
                    || !sameBinder(ownerToken, death.owner)
                    || activeSessionId != death.sessionId) {
                logger.debug("stale mirror owner death ignored session=" + death.sessionId);
                return;
            }
            logger.warn("mirror owner process died; detaching session=" + death.sessionId);
            castGeneration = ownerCastGeneration;
            detachCurrentLocked();
            if (sameBinder(sessionOwnerToken, death.owner)) {
                sessionOwnerToken = null;
                newestSessionId = 0L;
            }
        }
        if (castGeneration <= 0L) return;

        // Keep slow shell/AMS work off the Binder death callback.
        //
        // LOCK ORDER — the ClusterCast monitor must NEVER be acquired while holding `lock`.
        // ClusterCast.start() runs synchronized and can reach back into this class:
        // start -> ClusterMapProjector.stop -> releaseSustained -> ClusterProjectionController
        // .forceClose -> detachBeforeProjectionClose -> synchronized (lock). That is the
        // ClusterCast -> lock edge. Taking them in the opposite order here would close the cycle
        // and deadlock, wedging isActive()/getCastPackage()/stop() forever — including the ACC-off
        // teardown that calls ClusterCast.isActive(), whose ordering is load-bearing for the
        // SurfaceFlinger tear-down. (The refusal is reachable: acquireSustained() returns WITHOUT
        // adding its token while projState == ST_CLOSING, so start() really can end up releasing
        // the map's last holder and driving a forceClose.)
        //
        // So we take the ownership decision under `lock`, then call ClusterCast with NO lock held.
        // The generation guard in stopIfSession() preserves the safety this previously relied on
        // the lock for: it stops the cast ONLY if `castGeneration` is still the live one, so a
        // replacement UI that claimed/restarted the cast in the meantime is never torn down.
        new Thread(() -> {
            synchronized (lock) {
                // A replacement UI may have claimed this same still-running cast before the
                // death worker was scheduled. Its live owner/watermark wins; never stop a cast
                // that is now controllable from that replacement process.
                if (ownerToken != null
                        || (sessionOwnerToken != null
                        && !sameBinder(sessionOwnerToken, death.owner)
                        && binderAlive(sessionOwnerToken))) {
                    return;
                }
            }
            try { com.overdrive.app.launcher.ClusterCast.stopIfSession(castGeneration); }
            catch (Throwable t) {
                logger.warn("owner-death cast stop failed: " + t.getMessage());
            }
        }, "ProjectionOwnerDeath").start();
    }

    // ── SurfaceControl reflection (createDisplay / Transaction on the fission stack) ──

    private static final String MIRROR_NAME = "OverDriveClusterView";

    private static Object createDisplay(String name) {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            return sc.getMethod("createDisplay", String.class, boolean.class)
                    .invoke(null, name, false);
        } catch (Throwable t) {
            logger.warn("createDisplay failed: " + t.getMessage());
            return null;
        }
    }

    private static void destroyDisplaySafe(Object token) {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            sc.getMethod("destroyDisplay", IBinder.class).invoke(null, token);
        } catch (Throwable t) {
            logger.debug("destroyDisplay failed: " + t.getMessage());
        }
    }

    /** Unbind the display's output surface (setDisplaySurface(token,null)) so SF drops its
     *  producer ref before the token is destroyed. Best-effort + guarded. */
    private static void detachSurfaceSafe(Object token) {
        Class<?> txCls = null;
        Object tx = null;
        try {
            txCls = Class.forName("android.view.SurfaceControl$Transaction");
            tx = txCls.getDeclaredConstructor().newInstance();
            try { txCls.getMethod("setDisplaySurface", IBinder.class, Surface.class)
                    .invoke(tx, token, (Surface) null); } catch (Throwable ignored) {}
            txCls.getMethod("apply").invoke(tx);
        } catch (Throwable t) {
            logger.debug("detachSurface failed: " + t.getMessage());
        } finally {
            closeTransactionSafe(txCls, tx);
        }
    }

    /** setDisplayLayerStack + setDisplaySurface + setDisplayProjection + apply, in ONE
     *  transaction. Returns false on any missing method (→ caller reports unsupported). */
    private static boolean projectDisplay(Object token, Surface out, int stack, Rect src, Rect dst) {
        Class<?> txCls = null;
        Object tx = null;
        try {
            txCls = Class.forName("android.view.SurfaceControl$Transaction");
            tx = txCls.getDeclaredConstructor().newInstance();
            txCls.getMethod("setDisplayLayerStack", IBinder.class, int.class).invoke(tx, token, stack);
            txCls.getMethod("setDisplaySurface", IBinder.class, Surface.class).invoke(tx, token, out);
            txCls.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                    .invoke(tx, token, 0, src, dst);
            txCls.getMethod("apply").invoke(tx);
            return true;
        } catch (Throwable t) {
            logger.warn("projectDisplay failed: " + t.getMessage());
            return false;
        } finally {
            closeTransactionSafe(txCls, tx);
        }
    }

    private static void closeTransactionSafe(Class<?> txCls, Object tx) {
        if (txCls == null || tx == null) return;
        try { txCls.getMethod("close").invoke(tx); } catch (Throwable ignored) {}
    }

    private static void releaseSurface(Surface surface) {
        if (surface == null) return;
        try { surface.release(); } catch (Throwable ignored) {}
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
