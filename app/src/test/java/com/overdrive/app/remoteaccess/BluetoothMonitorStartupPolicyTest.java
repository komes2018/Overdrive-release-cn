package com.overdrive.app.remoteaccess;

import static com.overdrive.app.remoteaccess.BluetoothMonitorStartupPolicy.Decision.*;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Cold-start sequences do not confuse a sleeping adapter or unavailable service with lost pairing. */
public class BluetoothMonitorStartupPolicyTest {
    @Test public void offTurningOrMissingRadioCanStartTheSavedMonitorWaiting() {
        assertEquals(WAIT_FOR_RADIO, BluetoothMonitorStartupPolicy.decide(false, false, false));
        assertEquals(WAIT_FOR_RADIO, BluetoothMonitorStartupPolicy.decide(false, true, false));
        assertEquals(WAIT_FOR_RADIO, BluetoothMonitorStartupPolicy.decide(false, true, true));
    }

    @Test public void anOnRadioWithAnUnavailablePairedReadWaitsInsteadOfRejectingSavedConsent() {
        assertEquals(WAIT_FOR_PAIRED_DEVICES,
                BluetoothMonitorStartupPolicy.decide(true, false, false));
    }

    @Test public void coldRestartCanWaitThroughRadioStartupAndThenUseTheExistingBond() {
        assertEquals(WAIT_FOR_RADIO, BluetoothMonitorStartupPolicy.decide(false, false, false));
        assertEquals(WAIT_FOR_PAIRED_DEVICES,
                BluetoothMonitorStartupPolicy.decide(true, false, false));
        assertEquals(START, BluetoothMonitorStartupPolicy.decide(true, true, true));
    }

    @Test public void onlyACompleteStableOnReadCanRejectAnActuallyRemovedTrustedPhone() {
        assertEquals(WAIT_FOR_RADIO, BluetoothMonitorStartupPolicy.decide(false, true, false));
        assertEquals(REJECT_UNPAIRED, BluetoothMonitorStartupPolicy.decide(true, true, false));
    }
}
