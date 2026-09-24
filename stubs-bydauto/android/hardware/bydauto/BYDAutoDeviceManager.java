package android.hardware.bydauto;

import android.content.Context;
import android.hardware.BYDAutoManager;
import android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice;

/**
 * COMPILE-TIME STUB for the platform's {@code BYDAutoDeviceManager}. Every method here returns a
 * failure sentinel; the real implementation lives in the on-device framework and is reached
 * reflectively (see {@code BydDeviceHelper}).
 *
 * <p>The stub includes the platform's manager-level acquisition, activation, read and write
 * signatures so production code can probe those capabilities without bundling an implementation.
 *
 * <p>That difference is a candidate root cause for a real device symptom: on a BEV capture
 * (log_F2ZQH7CC) {@code getChargePower}, {@code getExternalChargingPower},
 * {@code getChargingPower} and the cluster feature id were ALL NaN at the same time, which is far
 * more consistent with an un-activated / unresolved device handle than with four independently
 * broken getters. See {@code CHARGING-POWER-INVARIANTS.md}.
 *
 * {@code enableDevice}/{@code disableDevice} take an {@code IBYDAutoDevice}; a separate
 * {@code BYDAutoManager.enableDevice(int deviceType)} int-form also exists. Callers must probe
 * both shapes reflectively rather than assume either.
 */
public abstract class BYDAutoDeviceManager implements BYDAutoManager.OnBYDAutoListener {
    public static synchronized BYDAutoDeviceManager getInstance(Context context) {
        synchronized (BYDAutoDeviceManager.class) {
        }
        return null;
    }

    // ==================== DEVICE REGISTRY ====================
    /** Hand a device to the manager so it can be enabled / streamed. */
    public void addDevice(android.hardware.IBYDAutoDevice device) { }
    public void removeDevice(android.hardware.IBYDAutoDevice device) { }
    /** Activate a device so the HAL begins serving its feature ids. Returns a status code. */
    public int enableDevice(android.hardware.IBYDAutoDevice device) {
        return BYDAutoBodyworkDevice.BODYWORK_COMMAND_FAILED;
    }
    public int disableDevice(android.hardware.IBYDAutoDevice device) {
        return BYDAutoBodyworkDevice.BODYWORK_COMMAND_FAILED;
    }

    /**
     * Resolve a device handle by numeric device type.
     */
    public Object getDevice(int deviceType) { return null; }

    // ==================== MANAGER-LEVEL READS ====================
    // (deviceType, featureId) — a second HAL entry point, distinct from the per-device
    // get(int[], Class) form we probe today. A trim may implement one and not the other.
    public double getDouble(int deviceType, int featureId) { return Double.NaN; }
    public int getInt(int deviceType, int featureId) { return Integer.MIN_VALUE; }
    public byte[] getBuffer(int deviceType, int featureId) { return null; }
    public double[] getDoubleArray(int deviceType, int[] featureIds) { return null; }
    public int[] getIntArray(int deviceType, int[] featureIds) { return null; }

    // ==================== MANAGER-LEVEL WRITES ====================
    public int setInt(int i, int i2, int i3) {
        return BYDAutoBodyworkDevice.BODYWORK_COMMAND_FAILED;
    }
    public int setDouble(int deviceType, int featureId, double v) {
        return BYDAutoBodyworkDevice.BODYWORK_COMMAND_FAILED;
    }
    public int setBuffer(int deviceType, int featureId, byte[] v) {
        return BYDAutoBodyworkDevice.BODYWORK_COMMAND_FAILED;
    }
    public int setIntArray(int deviceType, int[] featureIds, int[] values) {
        return BYDAutoBodyworkDevice.BODYWORK_COMMAND_FAILED;
    }
    public int setDoubleArray(int deviceType, int[] featureIds, double[] values) {
        return BYDAutoBodyworkDevice.BODYWORK_COMMAND_FAILED;
    }
}
