package android.hardware.bydauto.statistic;
import android.hardware.bydauto.BYDAutoEventValue;
// DiLink-5 compile-only stub (float signatures). The real class is loaded from the installed OEM
// APK at runtime; this stub is compileOnly and is never packaged into the universal app.
public abstract class AbsBYDAutoStatisticListener {
    public AbsBYDAutoStatisticListener() {}
    public void onElecPercentageChanged(double v) {}
    public void onSOCBatteryPercentageChanged(int v) {}
    public void onTotalMileageValueChanged(float v) {}
    public void onEVMileageValueChanged(int v) {}
    public void onElecDrivingRangeChanged(int v) {}
    public void onFuelDrivingRangeChanged(int v) {}
    public void onDrivingRangeValueChanged(int v) {}
    public void onFuelPercentageChanged(int v) {}
    public void onEVRemainingBatteryPowerChanged(float v) {}
    public void onRemainingBatteryPowerChanged(float v) {}
    public void onTotalElecConChanged(double v) {}
    public void onDataEventChanged(int featureId, BYDAutoEventValue value) {}
    public void onError(int code, String msg) {}
}
