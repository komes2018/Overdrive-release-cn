package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class Di4PreviewBootstrapContractTest {

    @Test
    public void callbackFirstStartupIsOldDilink4OnlyAndPrecedesTextureAttach()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        String gate = method(source,
                "private boolean shouldUseDi4PreviewBootstrap() {",
                "\n    /**\n     * Start the old-Di4 producer");
        assertTrue(gate.contains("USE_DILINK4_AVM_PATH"));
        assertTrue(gate.contains("android.os.Build.VERSION.SDK_INT < 32"));

        String attach = method(source,
                "private void attachSurfaceTextureToCamera(int cameraId) throws Exception {",
                "\n    /** True when BmmCameraInfo answered");
        assertOrdered(attach,
                "shouldUseDi4PreviewBootstrap()",
                "&& !di4CallbackReady",
                "bootstrapDi4PreviewBeforeTexture(",
                "firstFrameDimsLogged = false;",
                "return;",
                "\"addTexture\"",
                "\"setTexture\"",
                "USE_DILINK4_AVM_PATH && alreadyPreviewing");
        String bootstrapGate = attach.substring(
                attach.indexOf("if (shouldUseDi4PreviewBootstrap()"),
                attach.indexOf("bootstrapDi4PreviewBeforeTexture("));
        assertFalse(bootstrapGate.contains("halDeclaredDimsKnown"));

        String dims = method(source,
                "private void logHalDeclaredDims(int cameraId) {",
                "\n    /** Live byte-callback proxy");
        assertOrdered(dims,
                "halDeclaredDimsKnown = false;",
                "if (shouldUseDi4PreviewBootstrap())",
                "DIPlus beta18 always uses callback-first startup",
                "return;");

        String bootstrap = method(source,
                "private boolean bootstrapDi4PreviewBeforeTexture(",
                "\n    /** True when BmmCameraInfo answered");
        assertOrdered(bootstrap,
                "armPreviewCallbackKick(avmClass, previewIndex, true, () -> {",
                "boolean posted = handler.post(() -> {",
                "attachSurfaceTextureToCamera(",
                "cameraId, true, expectedCamera)",
                "disableDi4PreviewCallbackAfterFirstFrame(");
        assertFalse(bootstrap.contains("await("));
        assertFalse(bootstrap.contains("POST_ATTACH_PREVIEW_KICK_MAX_MS"));

        String firstFrameCleanup = method(source,
                "private void disableDi4PreviewCallbackAfterFirstFrame(",
                "\n    private void disarmPreviewCallbackKick(");
        assertTrue(firstFrameCleanup.contains(
                "getDeclaredMethod(\n                \"disablePreviewCallback\""));
        assertFalse(firstFrameCleanup.contains("setPreviewCallback"));

        String callback = method(source,
                "private boolean armPreviewCallbackKick(",
                "\n    /** Tear the producer kick down.");
        assertOrdered(callback,
                "int w = -1, h = -1, size = -1;",
                "args.length >= 6",
                "args[5] instanceof Integer",
                "previewKickByteSeen = true;",
                "boolean validFrame = w > 0 && h > 0 && size > 0;",
                "if (validFrame)",
                "firstValidFrameAction.run()",
                "if (firstValidFrameAction != null) {",
                "return true;",
                "previewKickWatcherRunning.compareAndSet(false, true)");

        String restart = method(source,
                "private void restartCameraAfterError() {",
                "\n    /**\n     * FORTIFY FIX");
        assertTrue(restart.contains("long openSoftTimeout = 2_000L;"));
        assertTrue(restart.contains(
                "boolean legacyOpenAttempt =\n"
                        + "                    !USE_DILINK4_AVM_PATH "
                        + "&& !USE_DILINK5_QCARCAM_PATH;"));
        assertTrue(restart.contains(
                "long openHardTimeout = legacyOpenAttempt\n"
                        + "                    ? Math.max(openSoftTimeout, "
                        + "GL_THREAD_WARMUP_TIMEOUT_MS)\n"
                        + "                    : openSoftTimeout;"));
        assertFalse(restart.contains("POST_ATTACH_PREVIEW_KICK_MAX_MS"));
    }

    @Test
    public void oldDilink4UsesStaticOpenFirstWithoutReverseYield()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        String open = method(source,
                "private void startCameraViaAvmReflection(int cameraId) throws Exception {",
                "\n        // Set FPS BEFORE attaching any consumer.");

        int di4Start = open.indexOf("if (shouldUseDi4PreviewBootstrap()) {");
        int otherPaths = open.indexOf("\n        } else {", di4Start);
        assertTrue(di4Start >= 0);
        assertTrue(otherPaths > di4Start);

        String di4 = open.substring(di4Start, otherPaths);
        assertOrdered(di4,
                "getDeclaredMethod(\"open\", int.class)",
                "mStaticOpen.invoke(null, cameraId)",
                "if (cameraObj == null)",
                "getDeclaredConstructor(int.class)");
        assertFalse(di4.contains("for (int tryId = 0; tryId <= 5; tryId++)"));

        String unchangedPaths = open.substring(otherPaths);
        assertOrdered(unchangedPaths,
                "getDeclaredConstructor(int.class)",
                "getDeclaredMethod(\"open\", int.class)");

        assertFalse(source.contains("onDiLink4GearChanged"));
        assertFalse(source.contains("DiLink 4 shifted to reverse — yielding"));
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
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
