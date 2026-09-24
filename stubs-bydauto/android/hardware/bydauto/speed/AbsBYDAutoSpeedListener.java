package android.hardware.bydauto.speed;

import android.hardware.bydauto.BYDAutoEventValue;
import android.hardware.bydauto.IBYDAutoEvent;

/**
 * Compile-time stub for the platform speed listener. At runtime the real framework class
 * (same FQN) shadows this one (kept un-renamed via proguard), so an anonymous subclass extends
 * the framework type and receives its callbacks. Both int and double speed overloads are declared
 * so whichever the firmware actually uses is overridden (the other is a harmless extra method).
 */
public abstract class AbsBYDAutoSpeedListener {
    public void onSpeedChanged(double speed) {}
    public void onSpeedChanged(int speed) {}
    public void onSpeedValueChanged(double speed) {}
    public void onCurrentSpeedChanged(double speed) {}
    public void onCurrentSpeedChanged(int speed) {}
    public void onAccelerateDeepnessChanged(int value) {}
    public void onBrakeDeepnessChanged(int value) {}
    public void onDataChanged(IBYDAutoEvent event) {}
    public void onDataEventChanged(int eventId, BYDAutoEventValue value) {}
    public void onError(int code, String msg) {}
}
