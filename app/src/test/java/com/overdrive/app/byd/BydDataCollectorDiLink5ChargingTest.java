package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.monitor.ChargingStateData;

import org.junit.Test;

public class BydDataCollectorDiLink5ChargingTest {
    @Test
    public void parsesEachCarPropertyLineWithoutMixingValues() {
        CarSvcTelemetry.ChargingObservation charging =
                CarSvcTelemetry.INSTANCE.parseChargingObservation(
                        "lastEvent:Property:0x21403407,status: 0,int32Values: [1]\n"
                                + "lastEvent:Property:0x2140461c,status: 0,int32Values: [2]\n"
                                + "lastEvent:Property:0x21406407,status: 0,int32Values: [1]");
        assertEquals(2, charging.gunState);
        assertEquals(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING,
                charging.bmsState);
        assertTrue(charging.charging);

        CarSvcTelemetry.ChargingObservation disconnected =
                CarSvcTelemetry.INSTANCE.parseChargingObservation(
                        "lastEvent:Property:0x21403c00,status: 0,int32Values: [1]\n"
                                + "lastEvent:Property:0x21403407,status: 0,int32Values: [0]\n"
                                + "lastEvent:Property:0x2140461c,status: 0,int32Values: [2]");
        assertEquals(1, disconnected.gunState);
        assertEquals(ChargingStateData.CHARGING_BATTERY_STATE_IDLE,
                disconnected.bmsState);
        assertFalse(disconnected.charging);
    }

    @Test
    public void failedCarPropertyStatusNeverPublishesAChargingEdge() {
        CarSvcTelemetry.ChargingObservation failed =
                CarSvcTelemetry.INSTANCE.parseChargingObservation(
                        "lastEvent:Property:0x21403407,status: 1,int32Values: [1]\n"
                                + "lastEvent:Property:0x2140461c,status: 2,int32Values: [2]");
        assertEquals(BydVehicleData.UNAVAILABLE, failed.gunState);
        assertEquals(BydVehicleData.UNAVAILABLE, failed.bmsState);
        assertFalse(failed.charging);

        CarSvcTelemetry.ChargingObservation malformed =
                CarSvcTelemetry.INSTANCE.parseChargingObservation(
                        "lastEvent:Property:0x21403407,status: bad,int32Values: [1]\n"
                                + "lastEvent:Property:0x2140461c,status: ?,int32Values: [2]");
        assertEquals(BydVehicleData.UNAVAILABLE, malformed.gunState);
        assertEquals(BydVehicleData.UNAVAILABLE, malformed.bmsState);
        assertFalse(malformed.charging);

        CarSvcTelemetry.ChargingObservation noStatus =
                CarSvcTelemetry.INSTANCE.parseChargingObservation(
                        "lastEvent:Property:0x21403407,int32Values: [1]\n"
                                + "lastEvent:Property:0x2140461c,int32Values: [4]");
        assertEquals(2, noStatus.gunState);
        assertEquals(ChargingStateData.CHARGING_BATTERY_STATE_IDLE,
                noStatus.bmsState);
    }

    @Test
    public void chargingTypeRejectsSdkFailureSentinels() {
        assertFalse(BydDataCollector.isValidChargingType(-1, true));
        assertFalse(BydDataCollector.isValidChargingType(
                (int) BydFeatureIds.SDK_NOT_AVAILABLE, true));
        assertFalse(BydDataCollector.isValidChargingType(65534, true));
        assertFalse(BydDataCollector.isValidChargingType(65535, true));
        assertTrue(BydDataCollector.isValidChargingType(65535, false));
        assertTrue(BydDataCollector.isValidChargingType(0, true));
        assertTrue(BydDataCollector.isValidChargingType(3, true));
    }
}
