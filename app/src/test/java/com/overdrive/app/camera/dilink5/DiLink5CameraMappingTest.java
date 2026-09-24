package com.overdrive.app.camera.dilink5;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class DiLink5CameraMappingTest {

    @Test
    public void resolvesSharkAndValidatesManualMappings() {
        assertArrayEquals(
                new int[]{8, 9, 5, 4},
                DiLink5CameraMapping.forPlatform("shark", "", ""));
        assertArrayEquals(
                new int[]{8, 9, 5, 4},
                DiLink5CameraMapping.forPlatform("", "", "BYD_DMO"));
        assertArrayEquals(
                new int[]{0, 1, 2, 3},
                DiLink5CameraMapping.forPlatform(
                        "sealion7", "BYD AUTO", ""));

        int[] withDashcam = DiLink5CameraMapping.parse("8, 9, 5, 4, 6");
        assertArrayEquals(new int[]{8, 9, 5, 4, 6}, withDashcam);
        assertEquals("8,9,5,4,6",
                DiLink5CameraMapping.toCsv(withDashcam));

        assertNull(DiLink5CameraMapping.parse("8,9,5,8"));
        assertNull(DiLink5CameraMapping.parse("8,9,5,4;reboot"));
        assertNull(DiLink5CameraMapping.parse("8,9,5,256"));
    }

    @Test
    public void captureFpsOverrideIsExplicitAndDefaultsSafely() {
        assertEquals(30, DiLink5QCarCamBackend.parseCaptureFps(null));
        assertEquals(30, DiLink5QCarCamBackend.parseCaptureFps(""));
        assertEquals(30, DiLink5QCarCamBackend.parseCaptureFps("0"));
        assertEquals(30, DiLink5QCarCamBackend.parseCaptureFps("31"));
        assertEquals(30, DiLink5QCarCamBackend.parseCaptureFps("bad"));
        assertEquals(1, DiLink5QCarCamBackend.parseCaptureFps("1"));
        assertEquals(15, DiLink5QCarCamBackend.parseCaptureFps(" 15 "));
        assertEquals(30, DiLink5QCarCamBackend.parseCaptureFps("30"));
    }

    @Test
    public void appMappingOverrideCanonicalizesAndRejectsUnsafeValues() {
        assertEquals("",
                DiLink5QCarCamBackend.normalizeCameraMapping(""));
        assertEquals("2,3,0,1",
                DiLink5QCarCamBackend.normalizeCameraMapping(" 2, 3, 0, 1 "));
        assertEquals("8,9,5,4,6",
                DiLink5QCarCamBackend.normalizeCameraMapping("8,9,5,4,6"));
        assertNull(DiLink5QCarCamBackend.normalizeCameraMapping("2,3,0,2"));
        assertNull(DiLink5QCarCamBackend.normalizeCameraMapping("2,3,0,1;reboot"));
    }
}
