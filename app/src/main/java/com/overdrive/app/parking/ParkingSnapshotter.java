package com.overdrive.app.parking;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Four-camera stills for a session ("arrived" ~90 s after switch-off,
 * "returned" at the first door-open / unlock / ACC-on), written as
 * {@code <sessionDir>/<prefix>_front.jpg …} plus a 2×2 composite
 * {@code <prefix>_mosaic.jpg} used as the notification image.
 *
 * <p>Capture goes through {@link ParkingEnvironment#captureQuadrantJpeg},
 * i.e. the same recording-safe shared-EGL sampler the camera-mapping dialog
 * uses. Each call may block ~150 ms (up to ~2.5 s worst case), so this only
 * ever runs on the parking worker thread, never on a camera or ACC thread.
 */
public final class ParkingSnapshotter {

    public static final String PREFIX_ARRIVED = "arrived";
    public static final String PREFIX_RETURNED = "returned";
    /** Composite tile size: each 1280×960 quadrant is downscaled 2× → 1280×960 mosaic. */
    static final int COMPOSITE_TILE_W = 640;
    static final int COMPOSITE_TILE_H = 480;
    static final int JPEG_QUALITY = 88;

    private final ParkingEnvironment env;

    public ParkingSnapshotter(ParkingEnvironment env) {
        this.env = env;
    }

    /** Result of one four-camera capture. */
    public static final class Result {
        public final int quadrantsOk;
        public final File compositeFile;   // may be null
        Result(int quadrantsOk, File compositeFile) {
            this.quadrantsOk = quadrantsOk;
            this.compositeFile = compositeFile;
        }
        public boolean ok() { return quadrantsOk > 0; }
    }

    /**
     * Capture all four quadrants into {@code sessionDir}. Never throws; a
     * quadrant that fails to sample is simply absent from the composite.
     */
    public Result capture(File sessionDir, String prefix) {
        if (sessionDir == null) return new Result(0, null);
        if (!ensureDir(sessionDir)) {
            env.log("Parking snapshot: cannot create " + sessionDir);
            return new Result(0, null);
        }
        byte[][] tiles = new byte[4][];
        int ok = 0;
        for (int q = 0; q < 4; q++) {
            byte[] jpeg = null;
            try {
                jpeg = env.captureQuadrantJpeg(q);
            } catch (Throwable t) {
                env.log("Parking snapshot q" + q + " failed: " + t.getMessage());
            }
            if (jpeg == null || jpeg.length == 0) continue;
            tiles[q] = jpeg;
            File f = new File(sessionDir, prefix + "_" + ParkingNeighbour.sideName(q) + ".jpg");
            if (writeAtomic(f, jpeg)) ok++;
        }
        File composite = null;
        if (ok > 0) {
            ParkingEnvironment.ImageOps ops = env.imageOps();
            if (ops != null) {
                try {
                    byte[] mosaic = ops.composeMosaic(tiles, COMPOSITE_TILE_W, COMPOSITE_TILE_H, JPEG_QUALITY);
                    if (mosaic != null && mosaic.length > 0) {
                        File m = new File(sessionDir, prefix + "_mosaic.jpg");
                        if (writeAtomic(m, mosaic)) composite = m;
                    }
                } catch (Throwable t) {
                    env.log("Parking snapshot composite failed: " + t.getMessage());
                }
            }
            if (composite == null) {
                // No composite: fall back to the front tile as the banner image.
                File front = new File(sessionDir, prefix + "_front.jpg");
                if (front.isFile()) composite = front;
            }
        }
        return new Result(ok, composite);
    }

    /**
     * Create a session asset directory, traversable by other UIDs (the Telegram
     * bot daemon reads the stills directly), like the base directory.
     */
    static boolean ensureDir(File dir) {
        if (dir == null) return false;
        if (dir.isDirectory()) return true;
        if (!dir.mkdirs() && !dir.isDirectory()) return false;
        try { dir.setReadable(true, false); dir.setExecutable(true, false); } catch (Throwable ignored) {}
        return true;
    }

    /** Atomic write (tmp + rename), world-readable so the Telegram daemon (other UID) can read it. */
    static boolean writeAtomic(File target, byte[] bytes) {
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(bytes);
            try { fos.getFD().sync(); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            tmp.delete();
            return false;
        }
        try { tmp.setReadable(true, false); } catch (Throwable ignored) {}
        // Replace in place: an existing good file is never deleted before the
        // new one is guaranteed to land.
        try {
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Throwable ignored) {
            // Filesystem without atomic replace (some FUSE mounts): fall back.
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            if (!tmp.renameTo(target)) { tmp.delete(); return false; }
        }
        return true;
    }
}
