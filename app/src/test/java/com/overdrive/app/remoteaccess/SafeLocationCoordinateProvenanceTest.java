package com.overdrive.app.remoteaccess;

import com.overdrive.app.surveillance.SafeLocation;
import com.overdrive.app.surveillance.SafeLocationManager;
import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/** Parser defaults and unrelated saves cannot turn unknown zone coordinates into authorization. */
public class SafeLocationCoordinateProvenanceTest {
    private static JSONObject zoneJson() throws Exception {
        return new JSONObject().put("id", "home").put("name", "Home").put("lat", 12)
                .put("lng", 77).put("radiusM", 100).put("enabled", true).put("createdAt", 10L);
    }

    private static void rawUpdate(SafeLocation zone, JSONObject update) throws Exception {
        Method method = SafeLocation.class.getDeclaredMethod("applyCoordinateUpdates", JSONObject.class);
        method.setAccessible(true);
        method.invoke(zone, update);
    }

    private static ProximitySafeZonePolicy.Result gate(SafeLocation zone) {
        return ProximitySafeZonePolicy.evaluate(true, true, true, Collections.singletonList("home"),
                Collections.singletonList(new ProximitySafeZonePolicy.Zone("home", zone.getName(),
                        zone.getLatitude(), zone.getLongitude(), zone.getRadiusMeters(),
                        zone.hasValidProximityGeometry())),
                new ProximitySafeZonePolicy.Fix(14, 78, 5, 0));
    }

    @Test public void eitherMissingSavedCoordinateRetainsZeroGetterButBlocksProximity() throws Exception {
        for (String axis : new String[] {"lat", "lng"}) {
            JSONObject json = zoneJson();
            json.remove(axis);
            SafeLocation zone = new SafeLocation(json);
            assertEquals(0, "lat".equals(axis) ? zone.getLatitude() : zone.getLongitude(), 0);
            assertFalse(zone.hasValidProximityGeometry());
            assertEquals("safe_zone_invalid", gate(zone).reason);
            assertFalse(gate(zone).allowed("unlock"));
        }
    }

    @Test public void eitherNullSavedCoordinateRetainsZeroGetterButBlocksProximity() throws Exception {
        for (String axis : new String[] {"lat", "lng"}) {
            SafeLocation zone = new SafeLocation(zoneJson().put(axis, JSONObject.NULL));
            assertEquals(0, "lat".equals(axis) ? zone.getLatitude() : zone.getLongitude(), 0);
            assertFalse(zone.hasValidProximityGeometry());
            assertFalse(gate(zone).allowed("lock"));
        }
    }

    @Test public void legitimatelyExplicitSingleZeroCoordinatesRemainValidAcrossSave() throws Exception {
        for (String axis : new String[] {"lat", "lng"}) {
            SafeLocation original = new SafeLocation(zoneJson().put(axis, 0));
            assertTrue(original.hasValidProximityGeometry());
            JSONObject saved = original.toJson();
            assertFalse(saved.has("proximityLatitudeValid"));
            assertFalse(saved.has("proximityLongitudeValid"));
            SafeLocation restored = new SafeLocation(new JSONObject(saved.toString()));
            assertTrue(restored.hasValidProximityGeometry());
            assertEquals(original.getLatitude(), restored.getLatitude(), 0);
            assertEquals(original.getLongitude(), restored.getLongitude(), 0);
            assertTrue(gate(restored).allowed("unlock"));
        }
    }

    @Test public void unrelatedNameRadiusAndSurveillanceEditsCannotRepairMissingCoordinates() throws Exception {
        for (String axis : new String[] {"lat", "lng"}) {
            JSONObject initial = zoneJson();
            initial.remove(axis);
            SafeLocation zone = new SafeLocation(initial);
            for (int edit = 0; edit < 3; edit++) {
                rawUpdate(zone, new JSONObject().put("name", "Renamed").put("radiusM", 200).put("enabled", false));
                zone.setName("Renamed");
                zone.setRadiusMeters(200);
                zone.setEnabled(false);
                JSONObject saved = zone.toJson();
                assertFalse(saved.getBoolean("lat".equals(axis)
                        ? "proximityLatitudeValid" : "proximityLongitudeValid"));
                zone = new SafeLocation(new JSONObject(saved.toString()));
                assertFalse(zone.hasValidProximityGeometry());
                assertFalse(gate(zone).allowed("lock"));
            }
        }
    }

    @Test public void unrelatedSavesAlsoRetainNullCoordinateProvenance() throws Exception {
        for (String axis : new String[] {"lat", "lng"}) {
            SafeLocation zone = new SafeLocation(zoneJson().put(axis, JSONObject.NULL));
            zone.setName("Edited");
            zone.setEnabled(false);
            zone = new SafeLocation(new JSONObject(zone.toJson().toString()));
            assertFalse(zone.hasValidProximityGeometry());
            assertEquals("safe_zone_invalid", gate(zone).reason);
        }
    }

    @Test public void originalApiAndIpcAddInputsDoNotAcquirePresentZeroDefaults() throws Exception {
        for (String axis : new String[] {"lat", "lng"}) {
            JSONObject input = zoneJson();
            input.remove(axis);
            SafeLocation absent = SafeLocation.fromNewZoneInput(input);
            assertFalse(absent.hasValidProximityGeometry());
            assertFalse(gate(absent).allowed("unlock"));
            SafeLocation nil = SafeLocation.fromNewZoneInput(zoneJson().put(axis, JSONObject.NULL));
            assertFalse(nil.hasValidProximityGeometry());
            assertFalse(new SafeLocation(new JSONObject(nil.toJson().toString())).hasValidProximityGeometry());
        }
        SafeLocation valid = SafeLocation.fromNewZoneInput(zoneJson().put("lat", 0).put("enabled", false));
        assertTrue(valid.hasValidProximityGeometry());
        assertTrue(valid.isEnabled()); // Existing new-zone surveillance behavior.
        assertNotEquals("home", valid.getId()); // Incoming IDs still cannot select a new zone's identity.
    }

    @Test public void nullAndNonnumericPartialCoordinateUpdatesRevokeProximityValidity() throws Exception {
        for (String axis : new String[] {"lat", "lng"}) {
            for (Object bad : new Object[] {JSONObject.NULL, "0", false}) {
                SafeLocation zone = new SafeLocation(zoneJson());
                rawUpdate(zone, new JSONObject().put(axis, bad));
                assertFalse(zone.hasValidProximityGeometry());
                assertFalse(gate(zone).allowed("lock"));
                SafeLocation restored = new SafeLocation(new JSONObject(zone.toJson().toString()));
                assertFalse(restored.hasValidProximityGeometry());
            }
        }
    }

    @Test public void explicitCorrectionRepairsOnlyTheSuppliedCoordinate() throws Exception {
        JSONObject incomplete = zoneJson();
        incomplete.remove("lat");
        incomplete.remove("lng");
        SafeLocation zone = new SafeLocation(incomplete);
        rawUpdate(zone, new JSONObject().put("lat", 0));
        assertFalse(zone.hasValidProximityGeometry());
        rawUpdate(zone, new JSONObject().put("lng", 77));
        assertTrue(zone.hasValidProximityGeometry());
        assertEquals(0, zone.getLatitude(), 0);
        assertEquals(77, zone.getLongitude(), 0);
        JSONObject fixed = zone.toJson();
        assertFalse(fixed.has("proximityLatitudeValid"));
        assertFalse(fixed.has("proximityLongitudeValid"));
        assertTrue(new SafeLocation(new JSONObject(fixed.toString())).hasValidProximityGeometry());
    }

    @Test public void invalidOrMalformedMarkersCannotOverrideBadCoordinates() throws Exception {
        for (String axis : new String[] {"lat", "lng"}) {
            String marker = "lat".equals(axis) ? "proximityLatitudeValid" : "proximityLongitudeValid";
            JSONObject omitted = zoneJson().put(marker, true);
            omitted.remove(axis);
            assertFalse(new SafeLocation(omitted).hasValidProximityGeometry());
            assertFalse(new SafeLocation(zoneJson().put(axis, JSONObject.NULL).put(marker, true))
                    .hasValidProximityGeometry());
            for (Object invalidMarker : new Object[] {false, "true", 1, JSONObject.NULL}) {
                SafeLocation zone = new SafeLocation(zoneJson().put(marker, invalidMarker));
                assertFalse(zone.hasValidProximityGeometry());
                assertFalse(new SafeLocation(new JSONObject(zone.toJson().toString())).hasValidProximityGeometry());
            }
        }
    }

    @Test public void malformedNumericInputsStayInvalidWhileLegacyGetterParsingIsPreserved() throws Exception {
        for (String axis : new String[] {"lat", "lng"}) {
            SafeLocation text = new SafeLocation(zoneJson().put(axis, "0"));
            assertEquals(0, "lat".equals(axis) ? text.getLatitude() : text.getLongitude(), 0);
            assertFalse(text.hasValidProximityGeometry());
            SafeLocation outOfRange = new SafeLocation(zoneJson().put(axis, 200));
            assertEquals(200, "lat".equals(axis) ? outOfRange.getLatitude() : outOfRange.getLongitude(), 0);
            assertFalse(outOfRange.hasValidProximityGeometry());
        }
    }

    @Test public void immutableSnapshotPreservesInvalidProvenanceUntilExplicitCorrection() throws Exception {
        Constructor<SafeLocationManager> constructor = SafeLocationManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        SafeLocationManager manager = constructor.newInstance();
        JSONObject input = zoneJson();
        input.remove("lat");
        SafeLocation zone = new SafeLocation(input);
        Field field = SafeLocationManager.class.getDeclaredField("zones");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        CopyOnWriteArrayList<SafeLocation> zones = (CopyOnWriteArrayList<SafeLocation>) field.get(manager);
        zones.add(zone);
        SafeLocationManager.ZoneSnapshot before = manager.getGeometrySnapshot().get(0);
        assertFalse(before.geometryValid);
        rawUpdate(zone, new JSONObject().put("lat", 0));
        SafeLocationManager.ZoneSnapshot after = manager.getGeometrySnapshot().get(0);
        assertTrue(after.geometryValid);
        assertFalse(before.geometryValid);
        assertEquals(before.latitude, after.latitude, 0); // The provenance changed; numeric defaults did not.
        assertFalse(ProximitySafeZonePolicy.evaluate(true, true, true, Collections.singletonList("home"),
                Collections.singletonList(new ProximitySafeZonePolicy.Zone(before.id, before.name,
                        before.latitude, before.longitude, before.radiusMeters, before.geometryValid)),
                new ProximitySafeZonePolicy.Fix(14, 78, 5, 0)).allowed("unlock"));
    }
}
