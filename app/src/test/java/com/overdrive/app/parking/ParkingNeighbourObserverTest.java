package com.overdrive.app.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.surveillance.Actor;
import com.overdrive.app.surveillance.DetectionBaseline;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Neighbour timeline derived from baseline + finalized-event signals. Uses a
 * real in-memory ParkingStore so the SQL paths run too.
 */
public class ParkingNeighbourObserverTest {

    private static final long T0 = 1_700_000_000_000L;
    private ParkingStore store;
    private ParkingNeighbourObserver observer;
    private ParkingSession session;

    @Before
    public void setUp() {
        store = new ParkingStore("jdbc:h2:mem:obs_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        assertTrue(store.open());
        session = new ParkingSession();
        session.sessionId = "park_test";
        session.startedMs = T0;
        session.createdMs = T0;
        assertTrue(store.insertSession(session));
        observer = new ParkingNeighbourObserver(store);
        observer.startSession(session);
    }

    @After
    public void tearDown() { store.close(); }

    static DetectionBaseline.EntrySnapshot entry(long id, int quadrant, String group,
                                                 float cx, float cy, float w, float h,
                                                 boolean confirmed, boolean live) {
        return new DetectionBaseline.EntrySnapshot(id, quadrant, "VEHICLE".equals(group) ? 2 : 0, group,
                cx, cy, w, h, T0, T0, confirmed ? 3 : 1, confirmed, live);
    }

    /** Non-static VEHICLE actor whose peak bbox sits at (cx,cy,w,h) of a 320×240 quadrant. */
    static Actor mover(long id, int camera, float cx, float cy, float w, float h, Actor.Proximity prox) {
        int qw = 320, qh = 240;
        int bw = Math.round(w * qw), bh = Math.round(h * qh);
        int bx = Math.round(cx * qw - bw / 2f), by = Math.round(cy * qh - bh / 2f);
        return new Actor(id, Actor.ClassGroup.VEHICLE, T0 + 60_000, T0 + 70_000, 0, 10_000,
                1 << camera, prox, prox, Actor.Trend.STABLE,
                false, false, true, true, true,
                Actor.Severity.ALERT, T0 + 65_000, 5_000, 0.9f,
                bx, by, bw, bh, qw, qh, camera,
                bx, by, bw, bh, camera);
    }

    static Actor staticCar(long id, int camera) {
        return new Actor(id, Actor.ClassGroup.VEHICLE, T0, T0 + 1000, 0, 1000, 1 << camera,
                Actor.Proximity.MID, Actor.Proximity.MID, Actor.Trend.STABLE,
                true, true, false, true, true,
                Actor.Severity.NOTICE, T0, 0, 0.8f, 10, 10, 50, 40, 320, 240, camera, 10, 10, 50, 40, camera);
    }

    @Test
    public void seededEntriesShortlyAfterStartArePresentOnArrival() {
        observer.onEntrySeeded(1, Arrays.asList(
                entry(1, 1, "VEHICLE", 0.3f, 0.6f, 0.3f, 0.3f, true, false),
                entry(2, 1, "PERSON", 0.7f, 0.6f, 0.1f, 0.3f, true, false)), T0 + 60_000);
        List<ParkingNeighbour> rows = store.listNeighbours("park_test");
        assertEquals("persons are not neighbours", 1, rows.size());
        ParkingNeighbour n = rows.get(0);
        assertEquals("q1:e1", n.neighbourKey);
        assertEquals(ParkingNeighbour.STATUS_PRESENT_ON_ARRIVAL, n.status);
        assertEquals(T0, n.firstSeenMs);
        assertTrue(n.confirmed);

        // Re-seed later with the same entry: idempotent.
        observer.onEntrySeeded(1, Collections.singletonList(
                entry(1, 1, "VEHICLE", 0.3f, 0.6f, 0.3f, 0.3f, true, false)), T0 + 3_600_000);
        assertEquals(1, store.listNeighbours("park_test").size());
    }

    @Test
    public void lateSeedIsAnArrivalNotPresentOnArrival() {
        observer.onEntrySeeded(2, Collections.singletonList(
                entry(9, 2, "VEHICLE", 0.5f, 0.5f, 0.3f, 0.3f, true, false)), T0 + 20 * 60_000);
        ParkingNeighbour n = store.findNeighbourByKey("park_test", "q2:e9");
        assertEquals(ParkingNeighbour.STATUS_ARRIVED, n.status);
        assertEquals(T0 + 20 * 60_000, n.arrivedMs);
    }

    @Test
    public void liveArrivalGetsEventNameAndActorLinksOnFinalize() {
        DetectionBaseline.EntrySnapshot e = entry(5, 3, "VEHICLE", 0.4f, 0.7f, 0.35f, 0.3f, false, true);
        observer.onEntryAdded(e, "event_end", T0 + 300_000);
        ParkingNeighbour n = store.findNeighbourByKey("park_test", "q3:e5");
        assertNotNull(n);
        assertEquals(ParkingNeighbour.STATUS_ARRIVED, n.status);
        assertFalse(n.confirmed);
        assertNull(n.arrivalEvent);

        Actor arriving = mover(77, 3, 0.4f, 0.7f, 0.35f, 0.3f, Actor.Proximity.CLOSE);
        List<ParkingNeighbourObserver.Attachment> att = observer.onEventFinalized(
                new File("/x/sentry_20260917_1.mp4"), Collections.singletonList(arriving),
                T0 + 290_000, true, T0 + 305_000);
        ParkingNeighbour after = store.findNeighbourByKey("park_test", "q3:e5");
        assertEquals("sentry_20260917_1.mp4", after.arrivalEvent);
        assertEquals("77", after.actorIds);
        assertEquals(ParkingNeighbour.STATUS_ARRIVED, after.status);   // an arrival is not also a departure
        assertEquals(1, att.size());
        assertEquals("q3:e5", att.get(0).neighbourKey);

        observer.onEntryConfirmed(e, T0 + 400_000);
        assertTrue(store.findNeighbourByKey("park_test", "q3:e5").confirmed);
    }

    @Test
    public void lightingAdditionIsTreatedAsAlreadyPresent() {
        observer.onEntryAdded(entry(6, 0, "VEHICLE", 0.5f, 0.5f, 0.3f, 0.3f, true, false), "lighting", T0 + 5 * 3_600_000L);
        ParkingNeighbour n = store.findNeighbourByKey("park_test", "q0:e6");
        assertEquals(ParkingNeighbour.STATUS_PRESENT_ON_ARRIVAL, n.status);
        assertEquals(T0, n.firstSeenMs);
    }

    @Test
    public void moverOnAKnownSpotIsThatNeighbourDeparting() {
        observer.onEntrySeeded(1, Collections.singletonList(
                entry(1, 1, "VEHICLE", 0.3f, 0.6f, 0.3f, 0.3f, true, false)), T0 + 60_000);
        Actor leaving = mover(42, 1, 0.32f, 0.62f, 0.3f, 0.3f, Actor.Proximity.CLOSE);
        List<ParkingNeighbourObserver.Attachment> att = observer.onEventFinalized(
                new File("/x/sentry_dep.mp4"), Collections.singletonList(leaving), T0 + 2_000_000, true, T0 + 2_010_000);
        ParkingNeighbour n = store.findNeighbourByKey("park_test", "q1:e1");
        assertEquals(ParkingNeighbour.STATUS_DEPARTED, n.status);
        assertEquals("sentry_dep.mp4", n.departureEvent);
        assertEquals(leaving.firstSeenWallMs, n.departedMs);
        assertEquals("42", n.actorIds);
        assertEquals(1, att.size());
        assertEquals("no close-pass row when the mover was matched", 1, store.listNeighbours("park_test").size());
    }

    @Test
    public void unmatchedCloseMoverBecomesAClosePass() {
        Actor passer = mover(99, 0, 0.5f, 0.5f, 0.2f, 0.2f, Actor.Proximity.VERY_CLOSE);
        Actor far = mover(100, 2, 0.5f, 0.5f, 0.05f, 0.05f, Actor.Proximity.FAR);
        observer.onEventFinalized(new File("/x/sentry_pass.mp4"), Arrays.asList(passer, far, staticCar(5, 0)),
                T0 + 100_000, true, T0 + 110_000);
        List<ParkingNeighbour> rows = store.listNeighbours("park_test");
        assertEquals("only the close mover; FAR and static actors are ignored", 1, rows.size());
        ParkingNeighbour p = rows.get(0);
        assertEquals(ParkingNeighbour.KIND_CLOSE_PASS, p.kind);
        assertEquals("a99", p.neighbourKey);
        assertEquals("VERY_CLOSE", p.proximity);
        assertEquals("sentry_pass.mp4", p.arrivalEvent);
    }

    @Test
    public void expiryRetiresAsLeftUnknownAndEndSessionMarksStillThere() {
        DetectionBaseline.EntrySnapshot a = entry(1, 1, "VEHICLE", 0.3f, 0.6f, 0.3f, 0.3f, true, false);
        DetectionBaseline.EntrySnapshot b = entry(2, 2, "VEHICLE", 0.6f, 0.6f, 0.3f, 0.3f, true, false);
        observer.onEntrySeeded(1, Collections.singletonList(a), T0 + 60_000);
        observer.onEntrySeeded(2, Collections.singletonList(b), T0 + 60_000);

        observer.onEntryRetired(a, "expired", T0 + 900_000);
        assertEquals(ParkingNeighbour.STATUS_LEFT_UNKNOWN, store.findNeighbourByKey("park_test", "q1:e1").status);
        observer.onEntryRetired(b, "reseed", T0 + 900_000);   // lifecycle, not a departure
        assertEquals(ParkingNeighbour.STATUS_PRESENT_ON_ARRIVAL, store.findNeighbourByKey("park_test", "q2:e2").status);

        observer.endSession(session, T0 + 1_000_000);
        assertEquals(ParkingNeighbour.STATUS_STILL_THERE, store.findNeighbourByKey("park_test", "q2:e2").status);
        assertEquals(ParkingNeighbour.STATUS_LEFT_UNKNOWN, store.findNeighbourByKey("park_test", "q1:e1").status);
        assertNull(observer.currentSession());
    }

    @Test
    public void matchingGeometry() {
        ParkingNeighbour n = new ParkingNeighbour();
        n.kind = ParkingNeighbour.KIND_NEIGHBOUR; n.side = 1; n.status = ParkingNeighbour.STATUS_ARRIVED;
        n.cx = 0.5f; n.cy = 0.5f; n.w = 0.2f; n.h = 0.2f;
        assertNotNull(ParkingNeighbourObserver.matchDeparture(Collections.singletonList(n),
                mover(1, 1, 0.55f, 0.5f, 0.2f, 0.2f, Actor.Proximity.MID), 1));
        assertNull("other side never matches", ParkingNeighbourObserver.matchDeparture(Collections.singletonList(n),
                mover(1, 2, 0.5f, 0.5f, 0.2f, 0.2f, Actor.Proximity.MID), 2));
        assertNull("far away never matches", ParkingNeighbourObserver.matchDeparture(Collections.singletonList(n),
                mover(1, 1, 0.1f, 0.1f, 0.1f, 0.1f, Actor.Proximity.MID), 1));
        assertEquals("1,2,3", ParkingNeighbourObserver.mergeIds("1,2", Arrays.asList(2L, 3L)));
    }
}
