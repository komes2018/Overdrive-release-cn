package com.overdrive.app.remoteaccess;

/** A missing pairing can be established only from a complete read with a stable ON radio. */
final class BluetoothMonitorStartupPolicy {
    enum Decision { START, WAIT_FOR_RADIO, WAIT_FOR_PAIRED_DEVICES, REJECT_UNPAIRED }

    private BluetoothMonitorStartupPolicy() {}

    static Decision decide(boolean radioOn, boolean pairedDevicesAvailable, boolean trustedPaired) {
        if (!radioOn) return Decision.WAIT_FOR_RADIO;
        if (!pairedDevicesAvailable) return Decision.WAIT_FOR_PAIRED_DEVICES;
        return trustedPaired ? Decision.START : Decision.REJECT_UNPAIRED;
    }
}
