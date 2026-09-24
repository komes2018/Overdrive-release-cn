package com.overdrive.app.power;

import android.content.Context;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;

/**
 * Single writer for the panorama HAL work-mode while the DiLink 5 parked
 * keep-alive lease holds it.
 *
 * <p>Feature ids per the DiCarServer feature catalog
 * ({@code dev/byd-property-bus/dicarserver_feature_catalog.txt}):
 * <ul>
 *   <li>{@code 0x4DE01014 = 1306529812} PANORAMA_WORK_MODE_SET (the setter)</li>
 *   <li>{@code 0x4F401014 = 1329598484} PANORAMA_WORK_MODE (the getter)</li>
 * </ul>
 * The reference implementation issues the setter through the platform
 * {@code BYDAutoManager} as {@code setInt(1031 /*panorama device*&#47;, id, value)} on
 * a 2 s cadence while parked and writes {@code 0} on release; this class
 * exposes exactly that pair of operations and nothing else.
 *
 * <p>Resolution is reflective and process-local: the caller passes its own
 * (PermissionBypass-wrapped) context because {@code acc_sentry_daemon} and
 * {@code byd_cam_daemon} are separate processes with separate statics.
 *
 * <p>{@link #isParkedHoldActive()} lets other panorama writers detect that a
 * parked lease owns the work mode. Nothing on the DiLink 4 path consults it
 * today (the viewpoint helper never runs on DiLink 5), so existing behaviour is
 * untouched; it exists so a future writer can defer instead of overwriting the
 * parked value with 0.
 */
public final class PanoramaWorkModeOwner {

    private static final DaemonLogger logger =
            DaemonLogger.getInstance("PanoramaWorkModeOwner");

    /** Panorama device type used by every panorama HAL call in this code base. */
    public static final int DEVICE_PANORAMA = 1031;
    /** PANORAMA_WORK_MODE_SET — 0x4DE01014. */
    public static final int PANORAMA_WORK_MODE_SET = 1306529812;
    /** PANORAMA_WORK_MODE (getter) — 0x4F401014. */
    public static final int PANORAMA_WORK_MODE = 1329598484;

    /** Raw rc when the manager or method is unavailable. */
    public static final int RC_UNAVAILABLE = Integer.MIN_VALUE;

    private static volatile boolean parkedHoldActive = false;

    private final Context context;
    private volatile Object manager;
    private volatile Method setInt;
    private volatile Method getInt;

    public PanoramaWorkModeOwner(Context context) {
        this.context = context;
    }

    /** True while a parked lease owns PANORAMA_WORK_MODE_SET. */
    public static boolean isParkedHoldActive() {
        return parkedHoldActive;
    }

    static void setParkedHoldActive(boolean active) {
        parkedHoldActive = active;
    }

    /**
     * {@code BYDAutoManager.setInt(1031, PANORAMA_WORK_MODE_SET, value)}.
     *
     * @return the HAL's raw return code (0 = accepted), or {@link #RC_UNAVAILABLE}
     *         when the manager, method or call is unavailable.
     */
    public int writeWorkMode(int value) {
        Object mgr = ensureManager();
        if (mgr == null) return RC_UNAVAILABLE;
        try {
            Method m = setInt;
            if (m == null) {
                m = mgr.getClass().getMethod("setInt", int.class, int.class, int.class);
                setInt = m;
            }
            Object rc = m.invoke(mgr, DEVICE_PANORAMA, PANORAMA_WORK_MODE_SET, value);
            if (rc instanceof Integer) return (Integer) rc;
            if (rc instanceof Boolean) return ((Boolean) rc) ? 0 : -1;
            return 0;
        } catch (NoSuchMethodException e) {
            logger.warn("setInt(int,int,int) not present on this BYDAutoManager");
            return RC_UNAVAILABLE;
        } catch (Throwable t) {
            logger.warn("PANORAMA_WORK_MODE_SET write failed: " + t.getMessage());
            if (isDeadBinder(t)) manager = null;
            return RC_UNAVAILABLE;
        }
    }

    /** {@code BYDAutoManager.getInt(1031, PANORAMA_WORK_MODE)}; null when unavailable. */
    public Integer readWorkMode() {
        Object mgr = ensureManager();
        if (mgr == null) return null;
        try {
            Method m = getInt;
            if (m == null) {
                m = mgr.getClass().getMethod("getInt", int.class, int.class);
                getInt = m;
            }
            Object v = m.invoke(mgr, DEVICE_PANORAMA, PANORAMA_WORK_MODE);
            return v instanceof Integer ? (Integer) v : null;
        } catch (Throwable t) {
            if (isDeadBinder(t)) manager = null;
            return null;
        }
    }

    private Object ensureManager() {
        Object mgr = manager;
        if (mgr != null) return mgr;
        Context ctx = context;
        if (ctx == null) return null;
        try {
            Object svc = ctx.getSystemService("auto");
            if (svc == null) {
                logger.warn("BYDAutoManager (\"auto\" service) unavailable — work-mode writes no-op");
                return null;
            }
            manager = svc;
            setInt = null;
            getInt = null;
            logger.info("BYDAutoManager acquired for parked work-mode hold: "
                    + svc.getClass().getName());
            return svc;
        } catch (Throwable t) {
            logger.warn("BYDAutoManager lookup failed: " + t.getMessage());
            return null;
        }
    }

    private static boolean isDeadBinder(Throwable t) {
        Throwable c = t;
        for (int i = 0; c != null && i < 4; i++) {
            String name = c.getClass().getName();
            if (name.endsWith("DeadObjectException")) return true;
            c = c.getCause();
        }
        return false;
    }
}
