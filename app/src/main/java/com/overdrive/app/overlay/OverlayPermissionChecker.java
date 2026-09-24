package com.overdrive.app.overlay;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Authoritative app-process check for Display-over-other-apps permission.
 */
public final class OverlayPermissionChecker {

    private static final String TAG = "OverlayPermission";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final AtomicBoolean mismatchLogged = new AtomicBoolean(false);

    private OverlayPermissionChecker() {}

    public static boolean isGranted(Context context) {
        Context appContext = context.getApplicationContext();
        Context probeContext = appContext != null ? appContext : context;
        boolean frameworkAllowed = frameworkAllows(probeContext);
        Boolean appOpsAllowed = appOpsAllows(probeContext);
        boolean granted =
                OverlayPermissionDecision.resolve(appOpsAllowed, frameworkAllowed);

        if (appOpsAllowed != null
                && appOpsAllowed.booleanValue() != frameworkAllowed
                && mismatchLogged.compareAndSet(false, true)) {
            logger.warn("Permission APIs disagree: appOps=" + appOpsAllowed
                    + ", framework=" + frameworkAllowed
                    + "; using explicit AppOps result");
        }
        return granted;
    }

    private static boolean frameworkAllows(Context context) {
        try {
            return Settings.canDrawOverlays(context);
        } catch (Throwable error) {
            logger.warn("Settings.canDrawOverlays failed: " + error.getMessage());
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static Boolean appOpsAllows(Context context) {
        try {
            AppOpsManager appOps =
                    (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return null;

            String opPackageName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? context.getOpPackageName()
                    : context.getPackageName();
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = appOps.unsafeCheckOpRawNoThrow(
                        AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                        context.getApplicationInfo().uid,
                        opPackageName);
            } else {
                mode = appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                        context.getApplicationInfo().uid,
                        opPackageName);
            }
            Boolean decision = appOpsDecision(
                    mode,
                    com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected(),
                    context.getApplicationInfo().targetSdkVersion);
            if (decision != null || mode == AppOpsManager.MODE_DEFAULT
                    || mode == AppOpsManager.MODE_FOREGROUND) {
                return decision;
            }
            logger.warn("Unknown SYSTEM_ALERT_WINDOW AppOps mode: " + mode);
        } catch (Throwable error) {
            logger.warn("SYSTEM_ALERT_WINDOW AppOps check failed: "
                    + error.getMessage());
        }
        return null;
    }

    static Boolean appOpsDecision(
            int mode, boolean diLink5, int targetSdkVersion) {
        if (mode == AppOpsManager.MODE_ALLOWED) return Boolean.TRUE;
        if (mode == AppOpsManager.MODE_IGNORED) return Boolean.FALSE;
        if (mode == AppOpsManager.MODE_ERRORED) return Boolean.FALSE;
        if (!diLink5) {
            if (mode == AppOpsManager.MODE_DEFAULT) return Boolean.FALSE;
            if (mode == AppOpsManager.MODE_FOREGROUND) return Boolean.TRUE;
        }
        // On DiLink 5, DEFAULT and FOREGROUND do not settle this special
        // permission; the framework check evaluates the current grant.
        return null;
    }
}
