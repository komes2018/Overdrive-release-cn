package com.overdrive.app.remoteaccess;

import android.content.Context;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydDeviceHelper;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.monitor.AccMonitor;
import java.util.function.Supplier;

/** Reads current vehicle power without starting the full telemetry collector. */
public final class VehiclePowerReader {
    private static final String BODYWORK =
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice";

    private VehiclePowerReader() {}

    public static int readNow(Context context) {
        if (DiLink5Platform.isSelected()) {
            if (DiLink5DaemonPowerReader.isAppProcess()) {
                // The app UID cannot run the shell-owned car-service probe.
                // Its fresh ignition decision comes from the authenticated daemon.
                return DiLink5DaemonPowerReader.readNow();
            }
            return readLocalDiLink5Now(context);
        }
        return readFrom(
                () -> BydDataCollector.getInstance().readPowerLevelNow(),
                () -> {
                    if (context == null) return null;
                    Context application = context.getApplicationContext();
                    // OEM signature permissions can be revoked on APK replacement.
                    // Use the same SDK context as vehicle controls; HAL access and
                    // the fresh getter still determine whether power is available.
                    Context bydContext = BydDeviceHelper.withBydPermissionBypass(
                            application != null ? application : context);
                    Object device = BydDeviceHelper.getDevice(
                            BODYWORK, bydContext);
                    return device == null ? null
                            : BydDeviceHelper.callGetter(device, "getPowerLevel");
                },
                () -> DiLink5DaemonPowerReader.readNow());
    }

    /**
     * Shell-daemon read that never calls back into HTTP, even for an embedded server.
     * An awake IVI alone never proves vehicle power.
     */
    public static int readLocalDiLink5Now(Context context) {
        return readDiLink5From(() -> {
            AccMonitor.DiLink5AccObservation observation =
                    AccMonitor.probeDiLink5AccObservation(context);
            return observation != null && observation.strong ? observation.accOn : null;
        });
    }

    static int readDiLink5From(Supplier<Boolean> freshIgnition) {
        try {
            Boolean on = freshIgnition.get();
            return on == null ? BydVehicleData.UNAVAILABLE : on ? 1 : 0;
        } catch (Throwable unavailable) {
            return BydVehicleData.UNAVAILABLE;
        }
    }

    static int readFrom(Supplier<Object> existing, Supplier<Object> fallback) {
        return readFrom(existing, fallback, () -> null);
    }

    static int readFrom(Supplier<Object> existing, Supplier<Object> fallback,
                        Supplier<Object> daemon) {
        int current = read(existing);
        if (current != BydVehicleData.UNAVAILABLE) return current;
        current = read(fallback);
        // The daemon reader refuses unsupported modes/SDKs and non-app callers,
        // so a shell-owned read cannot recursively enter its own HTTP server.
        return current != BydVehicleData.UNAVAILABLE ? current : read(daemon);
    }

    private static int read(Supplier<Object> source) {
        try {
            Object raw = source.get();
            if (!(raw instanceof Number)) return BydVehicleData.UNAVAILABLE;
            double value = ((Number) raw).doubleValue();
            return Double.isFinite(value) && value == Math.rint(value) && value >= 0 && value <= 3
                    ? (int) value : BydVehicleData.UNAVAILABLE;
        } catch (Throwable unavailable) {
            return BydVehicleData.UNAVAILABLE;
        }
    }
}
