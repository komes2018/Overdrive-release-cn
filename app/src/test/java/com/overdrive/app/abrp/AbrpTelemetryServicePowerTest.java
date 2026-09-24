package com.overdrive.app.abrp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.monitor.GearMonitor;

import org.junit.Test;

public class AbrpTelemetryServicePowerTest {

    @Test
    public void terminalConnectedSnapshotCannotEmitFrozenNegativePower() {
        long now = 1_000_000L;
        BydVehicleData terminal = new BydVehicleData.Builder()
                .chargingState(2)
                .chargingGunState(2)
                .enginePowerKw(-3.0)
                .enginePowerAtMs(now)
                .build();
        ChargingStateData finished =
                new ChargingStateData(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH);

        boolean charging = AbrpTelemetryService.isChargingForTelemetry(finished, terminal);
        double power = AbrpTelemetryService.selectTelemetryPower(
                terminal, finished, now, false, charging);

        assertFalse(charging);
        assertEquals(0.0, power, 0.0);
    }

    @Test
    public void enginePowerRequiresFreshCoherentDirectionState() {
        long now = 2_000_000L;
        ChargingStateData chargingState =
                new ChargingStateData(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        chargingState.chargingPowerKW = 2.7;
        BydVehicleData active = new BydVehicleData.Builder()
                .chargingState(1)
                .chargingGunState(2)
                .enginePowerKw(-3.0)
                .enginePowerAtMs(now)
                .build();

        assertTrue(AbrpTelemetryService.canPublishEnginePower(
                active, now, false, true));
        assertEquals(-2.7, AbrpTelemetryService.selectTelemetryPower(
                active, chargingState, now, false, true), 0.0);

        chargingState.isEstimated = true;
        assertEquals(-3.0, AbrpTelemetryService.selectTelemetryPower(
                active, chargingState, now, false, true), 0.0);
        chargingState.isEstimated = false;

        BydVehicleData stale = active.toBuilder()
                .enginePowerAtMs(now - 15_001L)
                .build();
        assertFalse(AbrpTelemetryService.canPublishEnginePower(
                stale, now, false, true));
        assertEquals(-2.7, AbrpTelemetryService.selectTelemetryPower(
                stale, chargingState, now, false, true), 0.0);
    }

    @Test
    public void positiveEnginePowerRequiresAccOnDrivingWithoutConnectedGun() {
        long now = 3_000_000L;
        ChargingStateData idle =
                new ChargingStateData(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
        BydVehicleData driving = new BydVehicleData.Builder()
                .chargingState(15)
                .chargingGunState(1)
                .enginePowerKw(18.0)
                .enginePowerAtMs(now)
                .build();

        assertEquals(18.0, AbrpTelemetryService.selectTelemetryPower(
                driving, idle, now, true, false), 0.0);
        assertEquals(0.0, AbrpTelemetryService.selectTelemetryPower(
                driving, idle, now, false, false), 0.0);

        BydVehicleData finishedConnected = driving.toBuilder()
                .chargingState(2)
                .chargingGunState(2)
                .build();
        assertEquals(0.0, AbrpTelemetryService.selectTelemetryPower(
                finishedConnected, idle, now, true, false), 0.0);
    }

    @Test
    public void idlePhevRateCanAssertChargingButV2lCannot() {
        ChargingStateData idle =
                new ChargingStateData(ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
        idle.chargingPowerKW = 3.1;
        BydVehicleData connected = new BydVehicleData.Builder()
                .chargingState(15)
                .chargingGunState(2)
                .build();
        assertTrue(AbrpTelemetryService.isChargingForTelemetry(idle, connected));
        idle.isEstimated = true;
        assertFalse(AbrpTelemetryService.isChargingForTelemetry(idle, connected));
        idle.isEstimated = false;

        BydVehicleData v2l = connected.toBuilder()
                .chargingGunState(5)
                .vtolCharging(true)
                .build();
        assertFalse(AbrpTelemetryService.isChargingForTelemetry(idle, v2l));

        ChargingStateData staleCharging =
                new ChargingStateData(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        BydVehicleData disconnected = connected.toBuilder()
                .chargingGunState(1)
                .build();
        assertFalse(AbrpTelemetryService.isChargingForTelemetry(
                staleCharging, disconnected));
    }

    @Test
    public void nonChargingTelemetryNeverSelectsNegativePower() {
        long now = 4_000_000L;
        ChargingStateData[] terminalStates = {
                new ChargingStateData(ChargingStateData.CHARGING_BATTERY_STATE_READY),
                new ChargingStateData(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH),
                new ChargingStateData(ChargingStateData.CHARGING_BATTERY_STATE_CHARG_TERMINATE),
                new ChargingStateData(ChargingStateData.CHARGING_BATTERY_STATE_IDLE)
        };
        for (ChargingStateData state : terminalStates) {
            state.chargingPowerKW = 3.0;
            for (boolean accOn : new boolean[] {false, true}) {
                for (int gun : new int[] {1, 2, 3, 4, 5, BydVehicleData.UNAVAILABLE}) {
                    BydVehicleData vd = new BydVehicleData.Builder()
                            .chargingState(state.stateCode)
                            .chargingGunState(gun)
                            .vtolCharging(gun == 5)
                            .enginePowerKw(-3.0)
                            .enginePowerAtMs(now)
                            .build();
                    boolean charging =
                            AbrpTelemetryService.isChargingForTelemetry(state, vd);
                    if (!charging) {
                        assertTrue(AbrpTelemetryService.selectTelemetryPower(
                                vd, state, now, accOn, false) >= 0.0);
                    }
                }
            }
        }
    }

    @Test
    public void freshParkOverridesStaleCollectorDrive() {
        assertEquals(Boolean.TRUE, AbrpTelemetryService.resolveParkedState(
                GearMonitor.GEAR_P, GearMonitor.GEAR_D, false));
    }

    @Test
    public void freshDriveOverridesStaleCollectorPark() {
        assertEquals(Boolean.FALSE, AbrpTelemetryService.resolveParkedState(
                GearMonitor.GEAR_D, GearMonitor.GEAR_P, false));
    }

    @Test
    public void confirmedAccOffOverridesStaleCollectorDrive() {
        assertEquals(Boolean.TRUE, AbrpTelemetryService.resolveParkedState(
                BydVehicleData.UNAVAILABLE, GearMonitor.GEAR_D, true));
    }

    @Test
    public void collectorIsOnlyUsedWhenLiveStateIsUnavailable() {
        assertEquals(Boolean.TRUE, AbrpTelemetryService.resolveParkedState(
                BydVehicleData.UNAVAILABLE, GearMonitor.GEAR_P, false));
        assertEquals(Boolean.FALSE, AbrpTelemetryService.resolveParkedState(
                BydVehicleData.UNAVAILABLE, GearMonitor.GEAR_D, false));
    }

    @Test
    public void unknownGearIsNotManufacturedIntoDrive() {
        assertNull(AbrpTelemetryService.resolveParkedState(
                BydVehicleData.UNAVAILABLE,
                BydVehicleData.UNAVAILABLE,
                false));
    }
}
