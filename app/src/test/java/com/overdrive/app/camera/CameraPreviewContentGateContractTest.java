package com.overdrive.app.camera;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Structural contract for the direct camera-preview content gate.
 *
 * A camera whose AVM/ISP power rail is down (post ACC-OFF) opens fine and
 * delivers frames, but every byte is zero — and YUV(0,0,0) encodes to a
 * confident SOLID GREEN JPEG that the camera-mapping dialog's pairing card
 * then shows as if it were a live feed. The gate rejects all-zero frames
 * inside the existing capture timeout so the dialog falls back to its
 * placeholder + bounded retry instead, while HAL warmup (zero-filled first
 * frames) self-heals by simply waiting for the first real frame.
 */
public class CameraPreviewContentGateContractTest {

    private static final String HELPER =
            "app/src/main/java/com/overdrive/app/camera/CameraPreviewHelper.java";

    @Test
    public void allZeroFramesAreRejectedNotLatched() throws IOException {
        String helper = readRepositoryFile(HELPER);

        // Listener: content check runs BEFORE the one-shot latch, rejected
        // frames are closed by the finally and the listener keeps waiting.
        assertOrdered(helper,
                "acquireLatestImage();",
                "!hasNonZeroLuma(image)",
                "zeroFramesRejected.incrementAndGet()",
                "return; // finally closes it; keep listening",
                "imageRef.compareAndSet(null, image)",
                "latch.countDown();");

        // Timeout diagnosis names the rail-down suspect instead of a bare
        // "no frame" when zero frames were seen and rejected.
        assertTrue(helper.contains("all-zero frames rejected"));
    }

    @Test
    public void lumaGateIsStrictAndFailOpen() throws IOException {
        String helper = readRepositoryFile(HELPER);

        // Near-zero floor (>2), NOT a dark-scene threshold — a night frame
        // with scattered sensor noise must pass; only true all-zero fails.
        String gate = method(helper,
                "private static boolean hasNonZeroLuma(Image image) {",
                "\n    private static byte[] yuv420888ToJpeg(");
        assertTrue(gate.contains("& 0xFF) > 2"));

        // FAIL-OPEN everywhere: missing planes, null buffer, empty buffer,
        // or any throw reports "has content" — the gate may never be the
        // reason previews break.
        assertOrdered(gate,
                "if (planes == null || planes.length == 0 || planes[0] == null) {",
                "return true;",
                "catch (Throwable t) {",
                "return true;");
    }

    @Test
    public void legacyDirectPreviewIsOwnershipCheckedAndHardBounded()
            throws IOException {
        String helper = readRepositoryFile(HELPER);

        String ownership = method(
                helper,
                "public static boolean isCameraHeldByPipeline(int cameraId)",
                "\n    public static byte[] captureDirectPreviewJpeg(");
        assertOrdered(
                ownership,
                "GpuSurveillancePipeline p =",
                "heldCamId == cameraId",
                "OemDashcamPipeline oem =",
                "oem.isRunning()",
                "oem.getCameraId() == cameraId",
                "catch (Throwable e)");

        String admission = method(
                helper,
                "private static byte[] captureWithSize(int cameraId, "
                        + "int width, int height, int fps, int timeoutMs)",
                "\n    private static byte[] captureLegacyPreviewWithHardTimeout(");
        assertOrdered(
                admission,
                "directPreviewTerminalRestart.get()",
                "directPreviewBusy.compareAndSet(false, true)",
                "isDiLink4Selected()",
                "captureLegacyPreviewWithHardTimeout(",
                "if (!directPreviewTerminalRestart.get())",
                "directPreviewBusy.set(false)");

        String bounded = method(
                helper,
                "private static byte[] captureLegacyPreviewWithHardTimeout(",
                "\n    private static byte[] captureWithSizeLocked(");
        assertOrdered(
                bounded,
                "Thread worker = new Thread",
                "captureWithSizeLocked(",
                "worker.setDaemon(true)",
                "DIRECT_PREVIEW_HARD_TIMEOUT_MS",
                "android.os.SystemClock.elapsedRealtime()",
                "directPreviewTerminalRestart.set(true)",
                "requestUrgentCameraReleaseRestart(reason)");
        assertTrue(helper.contains(
                "DIRECT_PREVIEW_HARD_TIMEOUT_MS = 12_000L"));
    }

    private static String method(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        assertTrue("method start not found: " + start, from >= 0);
        assertTrue("method end not found: " + end, to > from);
        return source.substring(from, to);
    }

    private static void assertOrdered(String source, String... needles) {
        int cursor = -1;
        for (String needle : needles) {
            int next = source.indexOf(needle, cursor + 1);
            assertTrue("missing or out of order: " + needle, next > cursor);
            cursor = next;
        }
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
