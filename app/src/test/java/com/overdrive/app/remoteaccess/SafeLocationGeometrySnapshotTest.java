package com.overdrive.app.remoteaccess;

import com.overdrive.app.surveillance.SafeLocation;
import com.overdrive.app.surveillance.SafeLocationManager;
import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/** Exercises the real in-memory publication without persistence or camera callbacks. */
public class SafeLocationGeometrySnapshotTest {
    private static final class Fixture {
        final SafeLocationManager manager;
        final SafeLocation zone;
        final Object lock;

        Fixture() throws Exception {
            Constructor<SafeLocationManager> constructor = SafeLocationManager.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            manager = constructor.newInstance();
            zone = new SafeLocation(new JSONObject().put("id", "home").put("name", "Home")
                    .put("lat", 12.0).put("lng", 77.0).put("radiusM", 100).put("enabled", true));
            Field storage = SafeLocationManager.class.getDeclaredField("zones");
            storage.setAccessible(true);
            @SuppressWarnings("unchecked")
            CopyOnWriteArrayList<SafeLocation> zones =
                    (CopyOnWriteArrayList<SafeLocation>) storage.get(manager);
            zones.add(zone);
            Field mutex = SafeLocationManager.class.getDeclaredField("geometryLock");
            mutex.setAccessible(true);
            lock = mutex.get(manager);
        }
    }

    private static ProximitySafeZonePolicy.Result outside(SafeLocationManager.ZoneSnapshot zone) {
        return ProximitySafeZonePolicy.evaluate(true, true, true, Collections.singletonList(zone.id),
                Collections.singletonList(new ProximitySafeZonePolicy.Zone(zone.id, zone.name,
                        zone.latitude, zone.longitude, zone.radiusMeters, zone.geometryValid)),
                new ProximitySafeZonePolicy.Fix(12.01, 77, 5, 0));
    }

    @Test public void publishedGeometryRetainsItsOriginalValuesAfterAnEdit() throws Exception {
        Fixture fixture = new Fixture();
        List<SafeLocationManager.ZoneSnapshot> before = fixture.manager.getGeometrySnapshot();
        ProximitySafeZonePolicy.Lease pending = outside(before.get(0)).lease("unlock");
        synchronized (fixture.lock) {
            fixture.zone.setName("Edited");
            fixture.zone.setLatitude(12.0001);
            fixture.zone.setLongitude(77.0001);
            fixture.zone.setRadiusMeters(200);
        }
        SafeLocationManager.ZoneSnapshot after = fixture.manager.getGeometrySnapshot().get(0);
        assertEquals("Home", before.get(0).name);
        assertEquals(12, before.get(0).latitude, 0);
        assertEquals(77, before.get(0).longitude, 0);
        assertEquals(100, before.get(0).radiusMeters);
        assertEquals("Edited", after.name);
        assertEquals(12.0001, after.latitude, 0);
        assertEquals(77.0001, after.longitude, 0);
        assertEquals(200, after.radiusMeters);
        assertTrue(outside(after).allowed("unlock"));
        assertFalse(outside(after).permits(pending));
        try {
            before.clear();
            fail("Published zone list must be immutable");
        } catch (UnsupportedOperationException expected) {}
    }

    @Test public void surveillanceSwitchesDoNotAlterTheProximityGeometryOrLease() throws Exception {
        Fixture fixture = new Fixture();
        SafeLocationManager.ZoneSnapshot before = fixture.manager.getGeometrySnapshot().get(0);
        ProximitySafeZonePolicy.Lease pending = outside(before).lease("lock");
        synchronized (fixture.lock) { fixture.zone.setEnabled(false); }
        Field surveillanceMaster = SafeLocationManager.class.getDeclaredField("featureEnabled");
        surveillanceMaster.setAccessible(true);
        surveillanceMaster.set(fixture.manager, false);
        SafeLocationManager.ZoneSnapshot after = fixture.manager.getGeometrySnapshot().get(0);
        assertFalse(fixture.zone.isEnabled());
        assertEquals(before.latitude, after.latitude, 0);
        assertEquals(before.longitude, after.longitude, 0);
        assertEquals(before.radiusMeters, after.radiusMeters);
        assertTrue(outside(after).permits(pending));
    }

    @Test public void aSnapshotCannotPublishHalfOfAnInProgressGeometryEdit() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch firstCoordinateChanged = new CountDownLatch(1), finishEdit = new CountDownLatch(1);
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
            synchronized (fixture.lock) {
                fixture.zone.setLatitude(13);
                firstCoordinateChanged.countDown();
                try {
                    if (!finishEdit.await(2, TimeUnit.SECONDS)) throw new AssertionError("Edit release timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                fixture.zone.setLongitude(78);
            }
        });
        assertTrue(firstCoordinateChanged.await(1, TimeUnit.SECONDS));
        CompletableFuture<List<SafeLocationManager.ZoneSnapshot>> reader =
                CompletableFuture.supplyAsync(fixture.manager::getGeometrySnapshot);
        try {
            reader.get(100, TimeUnit.MILLISECONDS);
            fail("Geometry must not escape halfway through an edit");
        } catch (TimeoutException expected) {
            // The real snapshot blocks until both coordinates are committed.
        } finally {
            finishEdit.countDown();
        }
        writer.get(1, TimeUnit.SECONDS);
        SafeLocationManager.ZoneSnapshot snapshot = reader.get(1, TimeUnit.SECONDS).get(0);
        assertEquals(13, snapshot.latitude, 0);
        assertEquals(78, snapshot.longitude, 0);
    }
}
