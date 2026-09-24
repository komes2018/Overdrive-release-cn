package com.overdrive.app.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/** ParkingStore against in-memory H2 (same engine the daemon uses on disk). */
public class ParkingStoreTest {

    private ParkingStore store;

    @Before
    public void open() {
        store = new ParkingStore("jdbc:h2:mem:parking_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        assertTrue(store.open());
    }

    @After
    public void close() {
        store.close();
    }

    static ParkingSession session(String id, long started) {
        ParkingSession s = new ParkingSession();
        s.sessionId = id;
        s.startedMs = started;
        s.createdMs = started;
        s.transitionGeneration = 7;
        s.lat = 3.1234; s.lng = 101.5678; s.accuracyM = 12f; s.fixAgeMs = 4000; s.gpsQuality = ParkingSession.GPS_FRESH;
        s.placeShort = "Mid Valley";
        s.sentryState = ParkingSession.SENTRY_ARMED;
        return s;
    }

    @Test
    public void insertGetUpdateRoundTrip() throws Exception {
        ParkingSession s = session("park_20260917_101500", 1_000L);
        s.startSocPercent = 78.0;                       // open bookends captured at park
        s.startRemainKwh = 61.42;
        assertTrue(store.insertSession(s));
        assertFalse("duplicate id must be rejected", store.insertSession(s));

        ParkingSession got = store.getSession(s.sessionId);
        assertNotNull(got);
        assertEquals(1_000L, got.startedMs);
        assertEquals(7L, got.transitionGeneration);
        assertEquals(3.1234, got.lat, 1e-9);
        assertEquals("Mid Valley", got.placeShort);
        assertEquals(ParkingSession.GPS_FRESH, got.gpsQuality);
        assertTrue(got.isOpen());
        assertEquals(ParkingSession.SIGNAGE_PENDING, got.signageState);
        assertEquals(78.0, got.startSocPercent, 1e-9);
        assertEquals(61.42, got.startRemainKwh, 1e-9);
        assertTrue("no end bookend yet", Double.isNaN(got.endSocPercent));
        assertTrue(Double.isNaN(got.endRemainKwh));
        assertFalse(got.chargedWhileParked);
        assertTrue(Double.isNaN(got.energyEstKwh));
        assertFalse("half-open bookends must not report a delta", got.hasEnergyBookends());
        assertTrue(Double.isNaN(got.energyDeltaKwh()));

        got.endedMs = 5_000L;
        got.endTrigger = ParkingSession.END_UNLOCK;
        got.eventCount = 3;
        got.notifiedEnded = true;
        got.signageJson = "{\"found\":true,\"level\":\"B2\"}";
        got.signageState = ParkingSession.SIGNAGE_DONE;
        got.endSocPercent = 74.0;                       // close bookends on genuine return
        got.endRemainKwh = 58.17;
        got.energyEstKwh = 3.3;
        assertTrue(store.updateSession(got));

        ParkingSession again = store.getSession(s.sessionId);
        assertFalse(again.isOpen());
        assertEquals(ParkingSession.END_UNLOCK, again.endTrigger);
        assertEquals(3, again.eventCount);
        assertTrue(again.notifiedEnded);
        assertEquals(ParkingSession.SIGNAGE_DONE, again.signageState);
        assertEquals("B2", again.toJson().getJSONObject("signage").getString("level"));
        assertEquals(74.0, again.endSocPercent, 1e-9);
        assertEquals(58.17, again.endRemainKwh, 1e-9);
        assertEquals(-4.0, again.socDeltaPercent(), 1e-9);
        assertEquals(3.3, again.energyEstKwh, 1e-9);
        assertFalse(again.chargedWhileParked);
        // The BMS pair is the figure of record; the SoC estimate is kept beside it.
        assertEquals(ParkingSession.ENERGY_SRC_BMS, again.energySource());
        assertEquals(-3.25, again.energyDeltaKwh(), 1e-9);
        assertTrue(again.hasMeasurableEnergyChange());

        org.json.JSONObject energy = again.toJson().getJSONObject("energy");
        assertEquals(78.0, energy.getDouble("startSoc"), 1e-9);
        assertEquals(74.0, energy.getDouble("endSoc"), 1e-9);
        assertEquals(61.42, energy.getDouble("startKwh"), 1e-9);
        assertEquals(58.17, energy.getDouble("endKwh"), 1e-9);
        assertEquals(-4.0, energy.getDouble("socDelta"), 1e-9);
        assertEquals(-3.25, energy.getDouble("kwh"), 1e-9);
        assertEquals("bms", energy.getString("source"));
        assertTrue(energy.getBoolean("measurable"));
        assertFalse(energy.getBoolean("charged"));
        assertEquals(3.3, energy.getDouble("estKwh"), 1e-9);
    }

    @Test
    public void socOnlyEnergyFallsBackToEstimateAndHidesQuantizationNoise() throws Exception {
        // Trim without a remaining-energy channel: SoC pair + capacity estimate.
        ParkingSession s = session("park_soc_only", 1_000L);
        s.startSocPercent = 80; s.endSocPercent = 78; s.energyEstKwh = 1.6;
        assertEquals(ParkingSession.ENERGY_SRC_SOC, s.energySource());
        assertEquals(-1.6, s.energyDeltaKwh(), 1e-9);
        assertTrue(s.hasMeasurableEnergyChange());

        // A flat integer gauge is NOT a measured zero: nothing to show.
        ParkingSession flat = session("park_flat", 1_000L);
        flat.startSocPercent = 80; flat.endSocPercent = 80;
        assertFalse(flat.hasMeasurableEnergyChange());
        assertTrue(Double.isNaN(flat.energyDeltaKwh()));
        assertNull(flat.energySource());
        assertFalse(flat.toJson().getJSONObject("energy").getBoolean("measurable"));

        // BMS jitter under the trips' 0.05 kWh floor is hidden the same way.
        ParkingSession jitter = session("park_jitter", 1_000L);
        jitter.startRemainKwh = 60.00; jitter.endRemainKwh = 59.98;
        assertFalse(jitter.hasMeasurableEnergyChange());

        // Charged while parked: BMS pair rose.
        ParkingSession charged = session("park_charged", 1_000L);
        charged.startRemainKwh = 30.0; charged.endRemainKwh = 45.5; charged.chargedWhileParked = true;
        assertEquals(15.5, charged.energyDeltaKwh(), 1e-9);
        assertTrue(charged.hasMeasurableEnergyChange());
    }

    @Test
    public void openAndLatestSessionQueries() {
        assertNull(store.getOpenSession());
        assertNull(store.getLatestSession());
        ParkingSession a = session("park_a", 1_000L); a.endedMs = 2_000L;
        ParkingSession b = session("park_b", 3_000L);
        store.insertSession(a);
        store.insertSession(b);
        assertEquals("park_b", store.getOpenSession().sessionId);
        assertEquals("park_b", store.getLatestSession().sessionId);
        assertEquals(2, store.countSessions());

        List<ParkingSession> page = store.listSessions(0, 0, 10, 0);
        assertEquals(2, page.size());
        assertEquals("park_b", page.get(0).sessionId);   // newest first
        assertEquals(1, store.listSessions(0, 0, 1, 1).size());
    }

    @Test
    public void retentionAndSignageQueues() {
        ParkingSession old = session("park_old", 1_000L); old.endedMs = 2_000L;
        ParkingSession openOld = session("park_open_old", 1_500L);           // open: never pruned
        ParkingSession fresh = session("park_fresh", 900_000L); fresh.endedMs = 950_000L;
        store.insertSession(old); store.insertSession(openOld); store.insertSession(fresh);

        List<String> prune = store.listSessionIdsStartedBefore(500_000L, 50);
        assertEquals(1, prune.size());
        assertEquals("park_old", prune.get(0));

        List<ParkingSession> pending = store.listSessionsWithSignageState(ParkingSession.SIGNAGE_PENDING, 10);
        assertEquals("only CLOSED sessions are queued", 2, pending.size());
        assertEquals("park_fresh", pending.get(0).sessionId);

        assertTrue(store.deleteSession("park_old"));
        assertNull(store.getSession("park_old"));
        assertFalse(store.deleteSession("park_old"));
    }

    @Test
    public void neighbourUpsertIsKeyedPerSession() throws Exception {
        store.insertSession(session("park_n", 1_000L));
        ParkingNeighbour n = new ParkingNeighbour();
        n.sessionId = "park_n";
        n.neighbourKey = "q1:e42";
        n.side = 1;
        n.classGroup = "VEHICLE";
        n.status = ParkingNeighbour.STATUS_PRESENT_ON_ARRIVAL;
        n.confirmed = true;
        n.cx = 0.5f; n.cy = 0.6f; n.w = 0.3f; n.h = 0.25f;
        n.firstSeenMs = 1_000L;
        long id = store.upsertNeighbour(n);
        assertTrue(id > 0);

        n.status = ParkingNeighbour.STATUS_DEPARTED;
        n.departedMs = 4_000L;
        n.departureEvent = "sentry_x.mp4";
        n.actorIds = "17,18";
        n.framesJson = "[{\"name\":\"frame_q1_e42_0.jpg\",\"score\":12.5,\"ms\":3000}]";
        assertEquals(id, store.upsertNeighbour(n));

        ParkingNeighbour got = store.findNeighbourByKey("park_n", "q1:e42");
        assertNotNull(got);
        assertEquals(id, got.id);
        assertEquals(ParkingNeighbour.STATUS_DEPARTED, got.status);
        assertEquals("sentry_x.mp4", got.departureEvent);
        assertEquals("17,18", got.actorIds);
        assertEquals(1, got.toJson().getJSONArray("frames").length());
        assertEquals("right", got.toJson().getString("sideName"));

        assertEquals(1, store.listNeighbours("park_n").size());
        assertEquals(1, store.countNeighbours("park_n", true));
        assertNull(store.findNeighbourByKey("park_other", "q1:e42"));

        // Deleting the session removes its neighbours too.
        assertTrue(store.deleteSession("park_n"));
        assertEquals(0, store.listNeighbours("park_n").size());
    }

    @Test
    public void sessionJsonShape() throws Exception {
        ParkingSession s = session("park_json", 10_000L);
        s.safeZone = "Home";
        org.json.JSONObject j = s.toJson();
        assertEquals("park_json", j.getString("sessionId"));
        assertTrue(j.getBoolean("open"));
        assertEquals("FRESH", j.getJSONObject("gps").getString("quality"));
        assertEquals("Home", j.getString("safeZone"));
        assertEquals("armed", j.getString("sentryState"));
        assertFalse(j.getJSONObject("snapshots").getBoolean("arrivedOk"));
        assertFalse("NaN bookends must not leak an energy object", j.has("energy"));

        ParkingSession noFix = new ParkingSession();
        noFix.sessionId = "x"; noFix.startedMs = 1;
        assertEquals("UNKNOWN", noFix.toJson().getJSONObject("gps").getString("quality"));
    }
}
