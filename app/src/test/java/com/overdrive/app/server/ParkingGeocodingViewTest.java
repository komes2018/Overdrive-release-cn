package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The Parking Intelligence geocoding flow inherits the sentry / dashcam
 * choice until the user sets it explicitly — the view the API returns must
 * say which of the two it is doing, and the merge must never drop an
 * explicit choice when another page saves.
 */
public class ParkingGeocodingViewTest {

    private static JSONObject flow(boolean enabled, boolean online) throws Exception {
        return new JSONObject().put("enabled", enabled).put("allowOnline", online);
    }

    @Test
    public void inheritsSentryFirstThenDashcamWhenUnset() throws Exception {
        JSONObject geo = new JSONObject()
                .put("recording", flow(true, false))
                .put("surveillance", flow(true, true));
        JSONObject v = QualitySettingsApiHandler.parkingGeocodingView(geo);
        assertTrue(v.getBoolean("inherited"));
        assertTrue(v.getBoolean("enabled"));
        assertTrue("sentry wins when both are on", v.getBoolean("allowOnline"));

        geo.put("surveillance", flow(false, true));
        v = QualitySettingsApiHandler.parkingGeocodingView(geo);
        assertTrue(v.getBoolean("enabled"));
        assertFalse("dashcam is the source now: its allowOnline is off", v.getBoolean("allowOnline"));

        geo.put("recording", flow(false, true));
        v = QualitySettingsApiHandler.parkingGeocodingView(geo);
        assertTrue(v.getBoolean("inherited"));
        assertFalse(v.getBoolean("enabled"));
        assertFalse(v.getBoolean("allowOnline"));
    }

    @Test
    public void explicitParkingChoiceOverridesInheritance() throws Exception {
        JSONObject geo = new JSONObject()
                .put("surveillance", flow(true, true))
                .put("parking", flow(false, false));
        JSONObject v = QualitySettingsApiHandler.parkingGeocodingView(geo);
        assertFalse(v.getBoolean("inherited"));
        assertFalse("user said no for parking even though sentry tags places", v.getBoolean("enabled"));

        geo.put("parking", flow(true, false));
        v = QualitySettingsApiHandler.parkingGeocodingView(geo);
        assertTrue(v.getBoolean("enabled"));
        assertFalse(v.getBoolean("allowOnline"));
    }

    @Test
    public void emptyOrMissingConfigIsInheritedAndOff() throws Exception {
        JSONObject v = QualitySettingsApiHandler.parkingGeocodingView(new JSONObject());
        assertTrue(v.getBoolean("inherited"));
        assertFalse(v.getBoolean("enabled"));
        v = QualitySettingsApiHandler.parkingGeocodingView(null);
        assertTrue(v.getBoolean("inherited"));
        assertEquals(false, v.getBoolean("enabled"));
    }
}
