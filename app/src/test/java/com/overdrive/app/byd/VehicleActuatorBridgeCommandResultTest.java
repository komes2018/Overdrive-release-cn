package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.routing.VehicleCommandRouter;

import org.junit.Test;

public class VehicleActuatorBridgeCommandResultTest {

    @Test
    public void legacyCallsHaveNoDiLink5RequestDeadline() {
        assertFalse(VehicleActuatorBridge.isDiLink5RequestExpired());
        assertFalse(VehicleActuatorBridge.hasDiLink5RequestScope());
        assertEquals(0L, VehicleActuatorBridge.currentDiLink5RequestDeadline());
        assertNull(VehicleActuatorBridge.currentDiLink5RequestAccOn());
    }

    @Test(expected = IllegalStateException.class)
    public void requestWithoutModeGenerationFailsClosed() throws Exception {
        VehicleActuatorBridge.runDiLink5Request(
                Long.MAX_VALUE, null, false, true, () -> true);
    }

    @Test
    public void relativeTemperatureSetpointReturnsToTheCallingProcess() {
        VehicleCommandRouter.ClimateStepTempCommand command =
                new VehicleCommandRouter.ClimateStepTempCommand(0, 0, 1);

        VehicleActuatorBridge.applyDiLink5CommandResult(command, 23);

        assertEquals(23, command.resultSetpoint);
    }

    @Test
    public void authoritativeAccCannotOutliveItsSafetyLease() {
        assertEquals(15_000L,
                VehicleActuatorBridge.capDeadlineToAccFreshness(
                        20_000L, true, 15_000L));
        assertEquals(20_000L,
                VehicleActuatorBridge.capDeadlineToAccFreshness(
                        20_000L, false, 15_000L));
    }
}
