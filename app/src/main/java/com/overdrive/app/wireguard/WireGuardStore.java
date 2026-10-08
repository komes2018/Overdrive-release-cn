package com.overdrive.app.wireguard;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Reads and writes the WireGuard config on behalf of the web API. Runs inside
 * the camera daemon (uid 2000), which owns /data/local/tmp.
 *
 * <p>The config holds the private key, so it is written with mode 0600 and
 * swapped in atomically. wgproxy notices the new file by itself and restarts
 * with it, so saving needs no explicit restart.
 */
public final class WireGuardStore {

    private WireGuardStore() {}

    /** Validate and store a config. Throws IllegalArgumentException with the reason when invalid. */
    public static synchronized WireGuardConfig.Summary save(String text) throws IOException {
        WireGuardConfig.Summary summary = WireGuardConfig.parse(text);
        if (text.startsWith("﻿")) text = text.substring(1);

        File home = new File(WireGuardPaths.HOME);
        if (!home.isDirectory() && !home.mkdirs() && !home.isDirectory()) {
            throw new IOException("cannot create " + home);
        }
        chmod(home, 0711);

        File tmp = new File(WireGuardPaths.CONFIG + ".tmp");
        File dest = new File(WireGuardPaths.CONFIG);
        try {
            // Tighten the mode before the key is written into the file.
            if (tmp.exists() && !tmp.delete()) throw new IOException("cannot replace " + tmp);
            if (!tmp.createNewFile()) throw new IOException("cannot create " + tmp);
            chmod(tmp, 0600);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            chmod(tmp, 0600);
            checkWithBinary(tmp);
            if (!tmp.renameTo(dest)) throw new IOException("cannot move config into place");
        } finally {
            if (tmp.exists()) tmp.delete();
        }
        chmod(dest, 0600);
        return summary;
    }

    /**
     * Let wgproxy itself judge the file, so what is accepted here is exactly
     * what the daemon will load. Skipped while the binary is not installed;
     * the Java validation has already run by then. Never logs the config.
     */
    private static void checkWithBinary(File config) throws IOException {
        File binary = new File(WireGuardPaths.BINARY);
        if (!binary.canExecute()) return;
        Process p = new ProcessBuilder(binary.getPath(), "-check", "-config", config.getPath())
                .redirectErrorStream(true)
                .start();
        try {
            p.getOutputStream().close();
            // Bounded read: stdout is a one-line JSON verdict.
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (java.io.InputStream in = p.getInputStream()) {
                    byte[] chunk = new byte[1024];
                    int n;
                    while ((n = in.read(chunk)) > 0 && buf.size() < 8192) buf.write(chunk, 0, n);
                } catch (IOException ignored) {
                }
            }, "WireGuardCheck");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IOException("config check timed out");
            }
            reader.join(1000);
            JSONObject verdict = new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
            if (!verdict.optBoolean("ok", false)) {
                String error = verdict.optString("error", "");
                throw new IllegalArgumentException(error.isEmpty() ? "invalid WireGuard config" : error);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("config check interrupted");
        } catch (org.json.JSONException e) {
            throw new IOException("config check gave no verdict");
        } finally {
            p.destroy();
        }
    }

    /** Remove the stored config. Returns true when no config remains. */
    public static synchronized boolean delete() {
        File f = new File(WireGuardPaths.CONFIG);
        return !f.exists() || f.delete();
    }

    public static boolean hasConfig() {
        return new File(WireGuardPaths.CONFIG).isFile();
    }

    /** True when the user opted in to serving the dashboard on the tunnel address. Off when unset. */
    public static boolean isDashboardExposed() {
        File f = new File(WireGuardPaths.EXPOSE_FLAG);
        if (!f.isFile() || f.length() > 16) return false;
        try {
            return "true".equalsIgnoreCase(new String(readAll(f), StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return false;
        }
    }

    /** Persist the dashboard opt-in. A running wgproxy applies it after a restart. */
    public static synchronized void setDashboardExposed(boolean enabled) throws IOException {
        File home = new File(WireGuardPaths.HOME);
        if (!home.isDirectory() && !home.mkdirs() && !home.isDirectory()) {
            throw new IOException("cannot create " + home);
        }
        chmod(home, 0711);
        File tmp = new File(WireGuardPaths.EXPOSE_FLAG + ".tmp");
        File dest = new File(WireGuardPaths.EXPOSE_FLAG);
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write((enabled ? "true\n" : "false\n").getBytes(StandardCharsets.UTF_8));
            }
            chmod(tmp, 0644);
            if (!tmp.renameTo(dest)) throw new IOException("cannot move flag into place");
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    /** Summary of the stored config, or null when there is none or it no longer validates. */
    public static WireGuardConfig.Summary readSummary() {
        File f = new File(WireGuardPaths.CONFIG);
        if (!f.isFile() || f.length() > WireGuardConfig.MAX_INPUT_BYTES) return null;
        try {
            return WireGuardConfig.parse(new String(readAll(f), StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Status JSON last written by wgproxy, or null. */
    public static JSONObject readStatus() {
        File f = new File(WireGuardPaths.STATUS);
        if (!f.isFile() || f.length() > 64 * 1024) return null;
        try {
            return new JSONObject(new String(readAll(f), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the pid in the status file is alive and is wgproxy (the file outlives the process). */
    public static boolean isRunning() {
        JSONObject status = readStatus();
        if (status == null) return false;
        int pid = status.optInt("pid", 0);
        if (pid <= 0) return false;
        File cmdline = new File("/proc/" + pid + "/cmdline");
        try {
            return new String(readAll(cmdline), StandardCharsets.UTF_8)
                    .contains(WireGuardPaths.PROCESS_NAME);
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toByteArray();
        }
    }

    private static void chmod(File f, int mode) throws IOException {
        try {
            android.system.Os.chmod(f.getPath(), mode);
        } catch (android.system.ErrnoException e) {
            throw new IOException("chmod " + f + ": " + e.getMessage(), e);
        }
    }
}
