package com.overdrive.app.byd.cloud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class CloudCapabilitiesTest {

    @Test
    public void flattensNestedFunctionNumbersAndHonorsWindowLearnInfo() throws Exception {
        JSONArray nestedFunctions = new JSONArray()
                .put(new JSONObject().put("functionNo", "1012"));
        JSONArray functions = new JSONArray()
                .put(new JSONObject()
                        .put("functionNo", "1026")
                        .put("cfFixedSecondLevelList", nestedFunctions))
                .put(new JSONObject().put("functionNo", "1020"))
                .put(new JSONObject().put("functionNo", "1021"));
        JSONObject latest = new JSONObject().put("cfFixedList", functions);
        JSONObject vehicle = new JSONObject()
                .put("vehicleFunLearnInfo", new JSONObject().put("openWindow499LearnInfo", 1));

        CloudCapabilities capabilities = CloudCapabilities.fromResponses(
                "VIN", latest, vehicle, 1L);

        assertTrue(capabilities.supports(CloudCapabilities.Feature.WINDOWS_CLOSE));
        assertTrue(capabilities.supports(CloudCapabilities.Feature.WINDOWS_OPEN_VENT));
        assertTrue(capabilities.supports(CloudCapabilities.Feature.SMART_CHARGING));
        assertTrue(capabilities.supports(CloudCapabilities.Feature.TRUNK_OPEN));
        assertTrue(capabilities.supports(CloudCapabilities.Feature.TRUNK_CLOSE));
    }

    @Test
    public void windowOpenHonorsExplicitNonPositiveLearnInfo() throws Exception {
        JSONObject latest = new JSONObject().put("cfFixedList", new JSONArray()
                .put(new JSONObject().put("functionNo", "1026")));
        JSONObject vehicle = new JSONObject().put("vehicleFunLearnInfo",
                new JSONObject()
                        .put("openWindowLearnInfo", 0)
                        .put("openWindow499LearnInfo", -1));

        CloudCapabilities capabilities = CloudCapabilities.fromResponses(
                "VIN", latest, vehicle, 1L);

        assertTrue(capabilities.supports(CloudCapabilities.Feature.WINDOWS_CLOSE));
        assertFalse(capabilities.supports(CloudCapabilities.Feature.WINDOWS_OPEN_VENT));
    }

    @Test
    public void windowOpenFallsBackToFunctionWhenVehicleLearnInfoCouldNotBeFetched()
            throws Exception {
        JSONObject latest = new JSONObject().put("cfFixedList", new JSONArray()
                .put(new JSONObject().put("functionNo", "1026")));

        CloudCapabilities capabilities = CloudCapabilities.fromResponses(
                "VIN", latest, null, 1L);

        assertTrue(capabilities.supports(CloudCapabilities.Feature.WINDOWS_OPEN_VENT));
    }

    @Test
    public void windowOpenTreatsEmptyVehicleLearnInfoAsUnavailable() throws Exception {
        JSONObject latest = new JSONObject().put("cfFixedList", new JSONArray()
                .put(new JSONObject().put("functionNo", "1026")));
        JSONObject vehicle = new JSONObject()
                .put("vehicleFunLearnInfo", new JSONObject());

        CloudCapabilities capabilities = CloudCapabilities.fromResponses(
                "VIN", latest, vehicle, 1L);

        assertTrue(capabilities.supports(CloudCapabilities.Feature.WINDOWS_OPEN_VENT));
    }

    @Test
    public void windowOpenStillRequiresFunction1026() throws Exception {
        JSONObject vehicle = new JSONObject().put("vehicleFunLearnInfo",
                new JSONObject().put("openWindowLearnInfo", 1));

        CloudCapabilities capabilities = CloudCapabilities.fromResponses(
                "VIN", new JSONObject(), vehicle, 1L);

        assertFalse(capabilities.supports(CloudCapabilities.Feature.WINDOWS_OPEN_VENT));
    }

    @Test
    public void gatesAllCloudOnlyControlsWithTheirPublishedFunctionNumbers() throws Exception {
        JSONObject latest = new JSONObject().put("cfFixedList", new JSONArray()
                .put(new JSONObject().put("functionNo", "1005"))
                .put(new JSONObject().put("functionNo", "1006"))
                .put(new JSONObject().put("functionNo", "1007"))
                .put(new JSONObject().put("functionNo", "1008"))
                .put(new JSONObject().put("functionNo", "10300002")));

        CloudCapabilities capabilities = CloudCapabilities.fromResponses(
                "VIN", latest, null, 1L);

        assertTrue(capabilities.supports(CloudCapabilities.Feature.LOCK));
        assertTrue(capabilities.supports(CloudCapabilities.Feature.UNLOCK));
        assertTrue(capabilities.supports(CloudCapabilities.Feature.FIND_CAR));
        assertTrue(capabilities.supports(CloudCapabilities.Feature.FLASH_LIGHTS));
        assertTrue(capabilities.supports(CloudCapabilities.Feature.BATTERY_HEAT));
    }

    @Test
    public void smartChargingUsesOnlyThePublishedReservationChargingFunction() throws Exception {
        JSONObject unrelated = new JSONObject().put("cfFixedList", new JSONArray()
                .put(new JSONObject().put("functionNo", "1013"))
                .put(new JSONObject().put("functionNo", "1030")));
        CloudCapabilities unsupported = CloudCapabilities.fromResponses("VIN", unrelated, null, 1L);
        assertFalse(unsupported.supports(CloudCapabilities.Feature.SMART_CHARGING));

        JSONObject reservationCharging = new JSONObject().put("cfFixedList", new JSONArray()
                .put(new JSONObject().put("functionNo", "1012")));
        CloudCapabilities supported = CloudCapabilities.fromResponses("VIN", reservationCharging, null, 1L);
        assertTrue(supported.supports(CloudCapabilities.Feature.SMART_CHARGING));
    }

    @Test
    public void seatFallbackCapabilitiesFollowPyBydChairTypeGates() throws Exception {
        JSONObject driverSpecific = new JSONObject().put("cfFixedList", new JSONArray()
                .put(new JSONObject().put("functionNo", "10030002")));
        CloudCapabilities driver = CloudCapabilities.fromResponses("VIN", driverSpecific, null, 1L);
        assertTrue(driver.supports(CloudCapabilities.Feature.SEAT_DRIVER));
        assertFalse(driver.supports(CloudCapabilities.Feature.SEAT_PASSENGER));

        JSONObject passengerSpecific = new JSONObject().put("cfFixedList", new JSONArray()
                .put(new JSONObject().put("functionNo", "10030004")));
        CloudCapabilities passenger = CloudCapabilities.fromResponses("VIN", passengerSpecific, null, 1L);
        assertFalse(passenger.supports(CloudCapabilities.Feature.SEAT_DRIVER));
        assertTrue(passenger.supports(CloudCapabilities.Feature.SEAT_PASSENGER));

        JSONObject shared = new JSONObject().put("cfFixedList", new JSONArray()
                .put(new JSONObject().put("functionNo", "10300003")));
        CloudCapabilities both = CloudCapabilities.fromResponses("VIN", shared, null, 1L);
        assertTrue(both.supports(CloudCapabilities.Feature.SEAT_DRIVER));
        assertTrue(both.supports(CloudCapabilities.Feature.SEAT_PASSENGER));
    }

    @Test
    public void steeringWheelHeatUsesItsOwnPyBydCapabilityGate() throws Exception {
        JSONObject specific = new JSONObject().put("cfFixedList", new JSONArray()
                .put(new JSONObject().put("functionNo", "10030010")));
        CloudCapabilities wheel = CloudCapabilities.fromResponses("VIN", specific, null, 1L);
        assertTrue(wheel.supports(CloudCapabilities.Feature.SEAT_STEERING_WHEEL));

        JSONObject sharedFrontSeat = new JSONObject().put("cfFixedList", new JSONArray()
                .put(new JSONObject().put("functionNo", "10300003")));
        CloudCapabilities noWheel = CloudCapabilities.fromResponses("VIN", sharedFrontSeat, null, 1L);
        assertFalse(noWheel.supports(CloudCapabilities.Feature.SEAT_STEERING_WHEEL));

        JSONObject alternate = new JSONObject().put("cfFixedList", new JSONArray()
                .put(new JSONObject().put("functionNo", "10300004")));
        assertTrue(CloudCapabilities.fromResponses("VIN", alternate, null, 1L)
                .supports(CloudCapabilities.Feature.SEAT_STEERING_WHEEL));
    }
}
