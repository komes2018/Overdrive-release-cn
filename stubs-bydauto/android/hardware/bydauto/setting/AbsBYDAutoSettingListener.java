package android.hardware.bydauto.setting;
import android.hardware.IBYDAutoEvent;
import android.hardware.bydauto.BYDAutoEventValue;
// DiLink-5 compile-only stub. Real class (abstract) from the OEM SDK at runtime. Only the member
// this client needs is declared (the real class has ~100+ callbacks).
public abstract class AbsBYDAutoSettingListener {
    public AbsBYDAutoSettingListener() {}
    public void onEnergyFeedbackStrengthChanged(int strength) {}
    public void onRecoverOrSaveParamsChanged(
            int position, int location, int state, int code) {}
    public void onCpdImsSwitchStateChanged(int state) {}
    public void onDataChanged(IBYDAutoEvent event) {}
    public void onDataEventChanged(int featureId, BYDAutoEventValue value) {}
    public void onError(int code, String msg) {}
}
