package com.overdrive.app.byd.cloud;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** Regression coverage for the full cloud seat payload used by remote fallback. */
public class VehicleCloudSnapshotSeatClimateTest {

    @Test
    public void parsesCompleteSeatAndWheelStateFromNestedCloudStatus() throws Exception {
        JSONObject vehicleInfo = new JSONObject().put("statusNow", new JSONObject()
                .put("mainSeatHeatState", 3)
                .put("mainSeatVentilationState", 2)
                .put("copilotSeatHeatState", 1)
                .put("copilotSeatVentilationState", 3)
                .put("stearingWheelHeatState", -1));

        VehicleCloudSnapshot snapshot = VehicleCloudSnapshot.fromVehicleInfo(vehicleInfo).build();

        assertTrue(snapshot.hasCompleteFrontSeatClimateState());
        assertArrayEquals(new int[] { 2, 1, 0, 2 }, snapshot.frontSeatClimateUiState());
        assertTrue(snapshot.hasSteeringWheelHeatState());
        assertEquals(1, snapshot.steeringWheelHeatWireState());
        assertTrue(snapshot.isSeatClimateFresh());
    }

    @Test
    public void refusesPartialCompositeAndUnknownWheelState() throws Exception {
        JSONObject vehicleInfo = new JSONObject()
                .put("mainSeatHeatState", 1)
                .put("mainSeatVentilationState", 1)
                .put("copilotSeatHeatState", 1)
                .put("steeringWheelHeatState", 0);

        VehicleCloudSnapshot snapshot = VehicleCloudSnapshot.fromVehicleInfo(vehicleInfo).build();

        assertFalse(snapshot.hasCompleteFrontSeatClimateState());
        assertFalse(snapshot.hasSteeringWheelHeatState());
        assertEquals(-1, snapshot.steeringWheelHeatWireState());
    }

    /**
     * The wheel field's real domain contains -1 (=on), so an ABSENT key must not
     * decode to -1. Trims without a wheel heater, and partial MQTT pushes, would
     * otherwise report the heater on — and a routed seat command would then
     * switch it on as a side effect.
     */
    @Test
    public void absentWheelKeyIsNotReportedAsHeaterOn() throws Exception {
        JSONObject vehicleInfo = new JSONObject()
                .put("mainSeatHeatState", 1)
                .put("mainSeatVentilationState", 1)
                .put("copilotSeatHeatState", 1)
                .put("copilotSeatVentilationState", 1);

        VehicleCloudSnapshot snapshot = VehicleCloudSnapshot.fromVehicleInfo(vehicleInfo).build();

        assertTrue(snapshot.hasCompleteFrontSeatClimateState());
        assertFalse("absent wheel key must read as unknown, not on",
                snapshot.hasSteeringWheelHeatState());
        assertEquals(-1, snapshot.steeringWheelHeatWireState());
        assertEquals(VehicleCloudSnapshot.WHEEL_HEAT_UNKNOWN, snapshot.steeringWheelHeatState);
    }

    /** A default-built snapshot (no payload at all) must also read as unknown. */
    @Test
    public void defaultSnapshotWheelStateIsUnknown() {
        VehicleCloudSnapshot snapshot = new VehicleCloudSnapshot.Builder().build();

        assertFalse(snapshot.hasSteeringWheelHeatState());
        assertEquals(-1, snapshot.steeringWheelHeatWireState());
    }

    /** An explicit -1 is still a genuine "heater on" reading. */
    @Test
    public void explicitMinusOneIsStillHeaterOn() throws Exception {
        JSONObject vehicleInfo = new JSONObject().put("steeringWheelHeatState", -1);

        VehicleCloudSnapshot snapshot = VehicleCloudSnapshot.fromVehicleInfo(vehicleInfo).build();

        assertTrue(snapshot.hasSteeringWheelHeatState());
        assertEquals(1, snapshot.steeringWheelHeatWireState());
    }

    @Test
    public void windowFallbackRequiresFreshOnlineSourceState() throws Exception {
        long now = System.currentTimeMillis();
        VehicleCloudSnapshot fresh = VehicleCloudSnapshot.fromVehicleInfo(new JSONObject()
                .put("onlineState", VehicleCloudSnapshot.ONLINE)
                .put("time", now)
                .put("leftFrontWindow", 1)
                .put("rightFrontWindow", 2))
                .build();
        assertTrue(fresh.isWindowStateFresh());
        assertEquals(1, fresh.leftFrontWindow);
        assertEquals(2, fresh.rightFrontWindow);

        VehicleCloudSnapshot stale = VehicleCloudSnapshot.fromVehicleInfo(new JSONObject()
                .put("onlineState", VehicleCloudSnapshot.ONLINE)
                .put("time", now - VehicleCloudSnapshot.CONNECTION_HEALTH_MAX_AGE_MS - 1))
                .build();
        assertFalse(stale.isWindowStateFresh());

        VehicleCloudSnapshot offline = VehicleCloudSnapshot.fromVehicleInfo(new JSONObject()
                .put("onlineState", VehicleCloudSnapshot.OFFLINE)
                .put("time", now))
                .build();
        assertFalse(offline.isWindowStateFresh());
    }
}
