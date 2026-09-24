package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PanoramicCameraGpuDiLink5ReacquireTest {

    @Test
    public void noHandleCannotBecomeAFrameStallDuringDiLink5Reacquire() {
        assertTrue(PanoramicCameraGpu.shouldSuppressDiLink5FrameStall(
                true, true, false, 0L, 1_000L, 20_000L,
                9_000L, 45_000L));
        assertTrue(PanoramicCameraGpu.shouldSuppressDiLink5FrameStall(
                true, true, false, 5_000L, 1_000L, 20_000L,
                9_000L, 45_000L));
    }

    @Test
    public void openedHandleGetsOnlyTheBoundedFirstFrameGrace() {
        assertTrue(PanoramicCameraGpu.shouldSuppressDiLink5FrameStall(
                true, true, true, 10_000L, 1_000L, 18_999L,
                9_000L, 45_000L));
        assertFalse(PanoramicCameraGpu.shouldSuppressDiLink5FrameStall(
                true, true, true, 10_000L, 1_000L, 19_000L,
                9_000L, 45_000L));
    }

    @Test
    public void guardNeverChangesOtherModesOrNormalActiveStreams() {
        assertFalse(PanoramicCameraGpu.shouldSuppressDiLink5FrameStall(
                false, true, false, 0L, 1_000L, 20_000L,
                9_000L, 45_000L));
        assertFalse(PanoramicCameraGpu.shouldSuppressDiLink5FrameStall(
                true, false, false, 0L, 1_000L, 20_000L,
                9_000L, 45_000L));
    }

    @Test
    public void noHandleSuppressionEndsAtTheOverallReacquireDeadline() {
        assertFalse(PanoramicCameraGpu.shouldSuppressDiLink5FrameStall(
                true, true, false, 0L, 1_000L, 46_000L,
                9_000L, 45_000L));
        assertTrue(PanoramicCameraGpu.isDiLink5ReacquireDeadlineExpired(
                1_000L, 46_000L, 45_000L));
        assertFalse(PanoramicCameraGpu.isDiLink5ReacquireDeadlineExpired(
                1_000L, 45_999L, 45_000L));
    }

    @Test
    public void reverseSourceResumeDoesNotRestartRecorderLifecycle() {
        assertFalse(PanoramicCameraGpu.shouldRunPostReacquireLifecycle(
                true, true));
        assertTrue(PanoramicCameraGpu.shouldRunPostReacquireLifecycle(
                true, false));
        assertTrue(PanoramicCameraGpu.shouldRunPostReacquireLifecycle(
                false, true));
    }
}
