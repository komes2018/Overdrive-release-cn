package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Di4CameraSafetyPolicyTest {

    @Test
    public void dilink4SecondaryCameraRequiresOneDistinctVerifiedHandle() {
        assertTrue(Di4CameraSafetyPolicy.canOpenSecondaryAvmCamera(
                true, 0, 1, 1));
        assertFalse(Di4CameraSafetyPolicy.canOpenSecondaryAvmCamera(
                true, 0, 0, 1));
        assertFalse(Di4CameraSafetyPolicy.canOpenSecondaryAvmCamera(
                true, 1, 1, 1));
        assertFalse(Di4CameraSafetyPolicy.canOpenSecondaryAvmCamera(
                true, 0, 1, 0));
        assertFalse(Di4CameraSafetyPolicy.canOpenSecondaryAvmCamera(
                true, 0, 1, -1));
        assertFalse(Di4CameraSafetyPolicy.canOpenSecondaryAvmCamera(
                true, 0, -1, 1));
    }

    @Test
    public void secondaryCameraPolicyIsInertOutsideDilink4() {
        // Preserve the pre-existing behavior of non-DiLink-4 backends.
        assertTrue(Di4CameraSafetyPolicy.canOpenSecondaryAvmCamera(
                false, 0, 0, 0));
        assertTrue(Di4CameraSafetyPolicy.canOpenSecondaryAvmCamera(
                false, 1, 0, -1));
    }
}
