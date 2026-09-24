package android.hardware.bydauto.energy;
import android.hardware.IBYDAutoEvent;
import android.hardware.IBYDAutoListener;

// DiLink-5 compile-only stub. Real class (abstract) from the OEM SDK at runtime.
// energy.getEnergyFeedback() (CarBodyManager path) confirmed DEAD on-car — constant 0
// through a parked High/Standard toggle test. The real regen
// mode getter is setting.getEnergyFeedback().
// Probed here anyway (compat-report only, not wired into app telemetry) in case another
// vehicle's firmware wires this device instead.
public abstract class AbsBYDAutoEnergyListener implements IBYDAutoListener {
    public AbsBYDAutoEnergyListener() {}
    public void onDataChanged(IBYDAutoEvent event) {}
    public void onEnergyModeChanged(int mode) {}
    public void onOperationModeChanged(int mode) {}
    public void onRoadSurfaceChanged(int mode) {}
    public void oniTACModeChanged(int mode) {}
    public void onEnergyFeedbackLevelChanged(int level) {}
    public void onError(int code, String msg) {}
}
