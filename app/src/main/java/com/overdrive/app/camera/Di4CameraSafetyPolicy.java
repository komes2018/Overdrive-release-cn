package com.overdrive.app.camera;

/**
 * Fail-closed secondary-camera rules for the DiLink 4 AVMCamera backend.
 *
 * <p>These rules are deliberately inactive outside DiLink 4 so legacy
 * ImageReader and DiLink 5 camera behavior remain unchanged.
 */
public final class Di4CameraSafetyPolicy {

    private Di4CameraSafetyPolicy() {
    }

    /**
     * A second AVMCamera client is safe on DiLink 4 only when concurrency was
     * positively verified and the secondary ID is distinct from the panorama.
     *
     * <p>Unknown ({@code -1}) and unsupported ({@code 0}) both fail closed.
     * Outside DiLink 4 this policy is inert to preserve existing behavior.
     */
    public static boolean canOpenSecondaryAvmCamera(
            boolean dilink4,
            int primaryCameraId,
            int secondaryCameraId,
            int concurrentAvmSupported) {
        if (secondaryCameraId < 0) {
            return false;
        }
        if (!dilink4) {
            return true;
        }
        return primaryCameraId >= 0
                && primaryCameraId != secondaryCameraId
                && concurrentAvmSupported == 1;
    }
}
