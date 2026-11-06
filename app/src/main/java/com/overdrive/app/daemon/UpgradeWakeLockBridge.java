package com.overdrive.app.daemon;

import android.content.Context;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Standalone temporary PARTIAL_WAKE_LOCK bridge for remote parked OTA upgrades.
 *
 * On BYD DiLink head units, AccModeManagerService polls PowerManagerService wake
 * locks every 2.0 seconds while ACC is OFF and suspends the AP/MCU if zero wake
 * locks remain held. Running two instances of this bridge from a staged APK copy
 * in /data/local/tmp/ holds independent UID-2000 PARTIAL_WAKE_LOCKs across both
 * `pm install -r` (which kills com.overdrive.app) and daemon restarts.
 *
 * Usage:
 *   CLASSPATH=/data/local/tmp/wl_bridge.apk app_process /system/bin \
 *       --nice-name=od_wl_bridge com.overdrive.app.daemon.UpgradeWakeLockBridge 240 Overdrive:UpgradeBridgeA
 */
public final class UpgradeWakeLockBridge {

    private UpgradeWakeLockBridge() {}

    public static void main(String[] args) {
        int durationSec = 240;
        String tag = "Overdrive:UpgradeBridge";
        if (args != null && args.length >= 1) {
            try {
                durationSec = Math.max(15, Math.min(1800, Integer.parseInt(args[0].trim())));
            } catch (NumberFormatException ignored) {}
        }
        if (args != null && args.length >= 2 && args[1] != null && !args[1].trim().isEmpty()) {
            tag = args[1].trim();
        }

        try {
            if (Looper.myLooper() == null) {
                Looper.prepareMainLooper();
            }
        } catch (Throwable ignored) {}

        Context systemContext = resolveSystemContext();
        if (systemContext == null) {
            System.err.println("ERROR: Failed to obtain systemContext");
            System.exit(2);
            return;
        }

        final long leaseMs = (durationSec + 30L) * 1000L;
        PowerManager.WakeLock wakeLock = null;
        String usedPkg = null;

        String[] candidatePackages = new String[] { "com.android.shell", "com.overdrive.app" };
        for (String pkg : candidatePackages) {
            try {
                int flags = "com.android.shell".equals(pkg)
                        ? Context.CONTEXT_IGNORE_SECURITY
                        : (Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                Context pkgCtx = systemContext.createPackageContext(pkg, flags);
                Context permissive = new DaemonBootstrap.PermissionBypassContext(pkgCtx);
                PowerManager pm = (PowerManager) permissive.getSystemService(Context.POWER_SERVICE);
                if (pm == null) continue;
                PowerManager.WakeLock candidate = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
                candidate.setReferenceCounted(false);
                candidate.acquire(leaseMs);
                if (candidate.isHeld()) {
                    wakeLock = candidate;
                    usedPkg = pkg;
                    break;
                }
            } catch (Throwable t) {
                System.err.println("WARN: candidate pkg " + pkg + " failed: " + t.getMessage());
            }
        }

        if (wakeLock == null || !wakeLock.isHeld()) {
            System.err.println("ERROR: Could not acquire PARTIAL_WAKE_LOCK for tag=" + tag);
            System.exit(3);
            return;
        }

        final PowerManager.WakeLock heldLock = wakeLock;
        final String finalTag = tag;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (heldLock.isHeld()) {
                    heldLock.release();
                    System.out.println("RELEASED tag=" + finalTag + " pid=" + Process.myPid());
                    System.out.flush();
                }
            } catch (Throwable ignored) {}
        }, "WlBridgeCleanup"));

        System.out.println("ACQUIRED tag=" + tag
                + " pid=" + Process.myPid()
                + " uid=" + Process.myUid()
                + " pkg=" + usedPkg
                + " durationSec=" + durationSec);
        System.out.flush();

        long deadlineElapsed = SystemClock.elapsedRealtime() + durationSec * 1000L;
        while (SystemClock.elapsedRealtime() < deadlineElapsed) {
            try {
                Thread.sleep(5_000L);
                long remainingMs = deadlineElapsed - SystemClock.elapsedRealtime();
                if (remainingMs > 0) {
                    heldLock.acquire(remainingMs + 15_000L);
                }
            } catch (InterruptedException ie) {
                break;
            } catch (Throwable ignored) {}
        }

        try {
            if (heldLock.isHeld()) {
                heldLock.release();
            }
        } catch (Throwable ignored) {}
        System.out.println("EXITED tag=" + tag + " pid=" + Process.myPid());
        System.out.flush();
    }

    private static Context resolveSystemContext() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = null;
            try {
                Method cur = atClass.getMethod("currentActivityThread");
                at = cur.invoke(null);
            } catch (Throwable ignored) {}
            if (at == null) {
                final Object[] box = new Object[1];
                Thread initThread = new Thread(() -> {
                    try {
                        Method sysMain = atClass.getMethod("systemMain");
                        box[0] = sysMain.invoke(null);
                    } catch (Throwable ignored) {}
                }, "WlBridgeSysMain");
                initThread.setDaemon(true);
                initThread.start();
                initThread.join(5_000L);
                if (!initThread.isAlive()) {
                    at = box[0];
                } else {
                    initThread.interrupt();
                }
            }
            if (at == null) {
                Constructor<?> ctor = atClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                at = ctor.newInstance();
                try {
                    Field f = atClass.getDeclaredField("sCurrentActivityThread");
                    f.setAccessible(true);
                    f.set(null, at);
                } catch (Throwable ignored) {}
            }
            Method getSysCtx = atClass.getMethod("getSystemContext");
            return (Context) getSysCtx.invoke(at);
        } catch (Throwable t) {
            System.err.println("resolveSystemContext failed: " + t.getMessage());
            return null;
        }
    }
}
