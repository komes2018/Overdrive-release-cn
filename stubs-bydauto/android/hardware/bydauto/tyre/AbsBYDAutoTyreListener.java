package android.hardware.bydauto.tyre;

import android.hardware.bydauto.BYDAutoEventValue;
import android.hardware.bydauto.IBYDAutoEvent;

public abstract class AbsBYDAutoTyreListener {
    public void onDataChanged(IBYDAutoEvent event) {}
    public void onDataEventChanged(int eventId, BYDAutoEventValue value) {}
    public void onError(int code, String msg) {}
    public void onFeatureChanged(String feature, int value) {}
    public void onIndirectTyreSystemStateChanged(int state) {}
    public void onTyreAirLeakStateChanged(int wheel, int state) {}
    public void onTyreBatteryStateChanged(int state) {}
    public void onTyreBatteryValueChanged(int wheel, float value) {}
    public void onTyreBatteryValueChanged(int wheel, double value) {}
    public void onTyrePressureStateChanged(int wheel, int state) {}
    public void onTyrePressureValueChanged(int wheel, int value) {}
    public void onTyreSignalStateChanged(int wheel, int state) {}
    public void onTyreSystemStateChanged(int state) {}
    public void onTyreTemperatureStateChanged(int state) {}
    // DiLink-5 per-wheel temperature uses 0=LF, 1=RF, 2=LR, 3=RR.
    public void onTyreTemperatureValueChanged(int wheel, int value) {}
    // Pressure retains the device-area convention: 1=LF, 2=RF, 3=LR, 4=RR.
    public void onTyrePressureValueByTypeChanged(int wheel, float value) {}
}
