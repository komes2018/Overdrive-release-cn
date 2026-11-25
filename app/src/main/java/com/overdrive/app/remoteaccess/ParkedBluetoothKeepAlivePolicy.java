package com.overdrive.app.remoteaccess;

/** Decisions for the shared radio lease, independent of Android or sampling resources. */
final class ParkedBluetoothKeepAlivePolicy {
    enum RadioState { OFF, TURNING_ON, ON, TURNING_OFF, UNKNOWN }
    enum Action { NONE, ENABLE, DISABLE, RELEASE_OWNERSHIP }

    private ParkedBluetoothKeepAlivePolicy() {}

    static Action decide(boolean configured, boolean onOnly, boolean vehicleOff,
                         boolean vehicleOn, boolean owned, boolean forceDisable,
                         RadioState state) {
        if (vehicleOn && !vehicleOff) {
            // Normal driving Bluetooth takes over without turning the adapter off.
            return owned ? Action.RELEASE_OWNERSHIP : Action.NONE;
        }
        if (state == RadioState.OFF && owned && (!configured || onOnly)) {
            return Action.RELEASE_OWNERSHIP;
        }
        if (!vehicleOff || vehicleOn) return Action.NONE;
        if (configured && !onOnly) {
            return state == RadioState.OFF ? Action.ENABLE : Action.NONE;
        }
        if (!owned && !forceDisable) return Action.NONE;
        if (state == RadioState.OFF) {
            return owned ? Action.RELEASE_OWNERSHIP : Action.NONE;
        }
        return state == RadioState.ON || state == RadioState.TURNING_ON
                ? Action.DISABLE : Action.NONE;
    }
}
