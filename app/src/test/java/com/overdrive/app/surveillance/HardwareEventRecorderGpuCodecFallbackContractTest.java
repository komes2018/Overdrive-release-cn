package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * R25: DI5-only, session-local HEVC→AVC encoder fallback.
 *
 * Field evidence (DiLink 5 head unit, Android 11): the platform H.265
 * encoder repeatedly fails synchronous init while H.264 succeeds with the
 * identical geometry. The fallback must be (a) gated on DiLink 5 selection
 * so every other platform keeps single-attempt behavior byte-identically,
 * (b) session-local — the persisted codec preference must never be
 * rewritten by the retry path, and (c) preceded by cleanup of the
 * partially-built HEVC codec so the AVC attempt starts clean.
 */
public class HardwareEventRecorderGpuCodecFallbackContractTest {

    private static final String RECORDER =
            "app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java";

    @Test
    public void di5SessionLocalAvcRetryIsGatedCleanedAndUnpersisted()
            throws Exception {
        String source = readRepositoryFile(RECORDER);

        // Wrapper shape: init() keeps the terminal-latch guard first (pinned
        // by CamViewOwnershipContractTest), then attempts initOnce() and only
        // enters the fallback on a synchronous failure.
        int init = source.indexOf("public void init() throws Exception {");
        assertTrue(init >= 0);
        int firstAttempt = source.indexOf("initOnce();", init);
        int catchBlock = source.indexOf("catch (Exception hevcFailure)", init);
        int gate = source.indexOf("if (!shouldRetryInitWithAvc()) throw hevcFailure;", init);
        assertTrue(firstAttempt > init);
        assertTrue(catchBlock > firstAttempt);
        assertTrue(gate > catchBlock);

        // Cleanup of the partial HEVC codec happens BEFORE the mime switch,
        // and the switch happens BEFORE the retry.
        int surfaceCleanup = source.indexOf("inputSurface = null;", gate);
        int encoderCleanup = source.indexOf("encoder = null;", gate);
        int mimeSwitch = source.indexOf(
                "codecMimeType = MediaFormat.MIMETYPE_VIDEO_AVC;", gate);
        int retry = source.indexOf("initOnce();", gate);
        int initEnd = source.indexOf("private boolean shouldRetryInitWithAvc()", init);
        assertTrue(surfaceCleanup > gate && surfaceCleanup < mimeSwitch);
        assertTrue(encoderCleanup > gate && encoderCleanup < mimeSwitch);
        assertTrue(mimeSwitch > gate);
        assertTrue(retry > mimeSwitch && retry < initEnd);

        // Session-local: the fallback region persists NOTHING. No config
        // write, no codec-preference rewrite, no HTTP-layer setter.
        String fallbackRegion = source.substring(init, initEnd);
        assertFalse(fallbackRegion.contains("setVideoCodec"));
        assertFalse(fallbackRegion.contains("UnifiedConfigManager"));
        assertFalse(fallbackRegion.contains("setRecordingCodec"));

        // Authorization: wedged instances never retry, only an HEVC intent
        // retries, and the platform gate is DiLink 5 selection resolved
        // through the active-mode fence (isSelected), failing safe.
        int auth = initEnd;
        int authEnd = source.indexOf("private void initOnce() throws Exception {", auth);
        assertTrue(auth >= 0 && authEnd > auth);
        String authRegion = source.substring(auth, authEnd);
        assertTrue(authRegion.contains("if (teardownWedged) return false;"));
        assertTrue(authRegion.contains(
                "if (!MediaFormat.MIMETYPE_VIDEO_HEVC.equals(codecMimeType)) return false;"));
        assertTrue(authRegion.contains(
                "com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()"));
        assertTrue(authRegion.contains("catch (Throwable t)"));
        int wedgeCheck = authRegion.indexOf("if (teardownWedged) return false;");
        int hevcCheck = authRegion.indexOf("MIMETYPE_VIDEO_HEVC.equals(codecMimeType)");
        int di5Check = authRegion.indexOf("DiLink5Platform.isSelected()");
        assertTrue(wedgeCheck >= 0 && hevcCheck > wedgeCheck && di5Check > hevcCheck);

        // The original init body still exists exactly once as initOnce(), and
        // the drainer-latch reset moved with it (re-run resets it again on the
        // AVC retry, matching a fresh attempt).
        int body = source.indexOf("private void initOnce() throws Exception {");
        assertTrue(body > 0);
        assertTrue(source.indexOf("private void initOnce() throws Exception {",
                body + 1) < 0);
        int latchReset = source.indexOf("drainerRestartSuppressed = false;", body);
        int createCodec = source.indexOf("MediaCodec.createEncoderByType", body);
        assertTrue(latchReset > body && createCodec > latchReset);
    }

    private static String readRepositoryFile(String relativePath)
            throws Exception {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate),
                        StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule),
                        StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError(
                "Could not locate repository file: " + relativePath);
    }
}
