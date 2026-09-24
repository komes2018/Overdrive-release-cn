package com.overdrive.app.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.notifications.NotificationEvent;
import com.overdrive.app.parking.signage.TextOcrBackend;
import com.overdrive.app.surveillance.Actor;
import com.overdrive.app.surveillance.DetectionBaseline;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Zero-cost-off contract + session lifecycle of ParkingController against a
 * fake environment and an in-memory store. No Android, no camera, no threads
 * other than the controller's own worker.
 */
public class ParkingControllerLifecycleTest {

    /** Scriptable config source. */
    static final class FakeSource implements ParkingConfig.Source {
        volatile JSONObject section = new JSONObject();
        final List<Consumer<JSONObject>> listeners = new CopyOnWriteArrayList<>();
        @Override public ParkingConfig read() { return ParkingConfig.fromSection(section); }
        @Override public void addListener(Consumer<JSONObject> l) { listeners.add(l); }
        @Override public void removeListener(Consumer<JSONObject> l) { listeners.remove(l); }
        void set(String key, Object v) {
            try { section.put(key, v); } catch (Exception ignored) {}
            for (Consumer<JSONObject> l : listeners) l.accept(section);
        }
    }

    /** Fake daemon. */
    static final class FakeEnv implements ParkingEnvironment {
        long now = 1_700_000_000_000L;
        long elapsed = 500_000L;
        GpsFix fix = new GpsFix(3.10, 101.60, 9f, 490_000L, 0L, false);
        String safeZone;
        String sentry = ParkingSession.SENTRY_ARMED;
        boolean pipelineRunning = true;
        boolean accOn = false;
        boolean accAuthoritative = true;
        boolean charging = false;
        boolean recording = false;
        List<Actor> actors = new ArrayList<>();
        int sentryEvents = 2;
        int criticalEvents = 0;
        final List<NotificationEvent> published = new CopyOnWriteArrayList<>();
        final List<String> logs = new CopyOnWriteArrayList<>();
        volatile DoorListener doorListener;
        DetectionBaseline.Listener baselineListener;
        int captures;
        final File base;

        FakeEnv(File base) { this.base = base; }

        @Override public long nowMs() { return now; }
        @Override public long nowElapsedMs() { return elapsed; }
        @Override public GpsFix readGpsFix() { return fix; }
        /** False = no internet (garage): the resolver never calls back, like the real one. */
        volatile boolean geocodeAvailable = true;
        @Override public void resolvePlaceAsync(double lat, double lng, Consumer<Place> cb) {
            if (!geocodeAvailable) return;
            cb.accept(new Place("Mid Valley", "Mid Valley Megamall, KL", "CACHE"));
        }
        @Override public String currentSafeZoneName() { return safeZone; }
        @Override public String sentryState() { return sentry; }
        @Override public boolean isPipelineRunning() { return pipelineRunning; }
        @Override public boolean isSentryArmed() { return ParkingSession.SENTRY_ARMED.equals(sentry); }
        @Override public boolean isEventRecording() { return recording; }
        @Override public boolean isAccOn() { return accOn; }
        @Override public boolean isAccStateAuthoritative() { return accAuthoritative; }
        @Override public boolean isCharging() { return charging; }
        /** null = no fresh gear reading (poller down / stale), like the daemon impl. */
        Boolean gearInPark;
        @Override public Boolean gearInPark() { return gearInPark; }
        /** Energy channels; NaN = no reading, like the daemon impl. */
        double soc = Double.NaN, remainKwh = Double.NaN, nominalKwh = Double.NaN;
        @Override public double readSocPercent() { return soc; }
        @Override public double readRemainKwh() { return remainKwh; }
        @Override public double estimateEnergyKwh(double socPercent) {
            return Double.isNaN(nominalKwh) || socPercent <= 0 ? Double.NaN : socPercent / 100.0 * nominalKwh;
        }
        @Override public List<Actor> lastActors() { return actors; }
        @Override public byte[] captureQuadrantJpeg(int quadrant) {
            captures++;
            return ("jpeg-q" + quadrant).getBytes();
        }
        @Override public int rectifyStrength() { return 40; }
        @Override public File parkingBaseDir() { return base; }
        @Override public void setDoorListener(DoorListener l) { doorListener = l; }
        @Override public void setBaselineListener(DetectionBaseline.Listener l) {
            baselineListener = l;
            DetectionBaseline.setGlobalListener(l);
        }
        @Override public void publish(NotificationEvent event) { published.add(event); }
        @Override public int countSentryEvents(long fromMs, long toMs, boolean criticalOnly) {
            return criticalOnly ? criticalEvents : sentryEvents;
        }
        @Override public String signAssetToken(String subject, long ttlSec) { return "tok-" + subject.hashCode(); }
        @Override public ImageOps imageOps() { return null; }
        @Override public void log(String message) { logs.add(message); }
        /** Text the scripted OCR "sees" in every frame; null = models not installed. */
        String ocrText;
        int ocrOpens;
        int ocrFrames;
        @Override public TextOcrBackend openSignageOcr() {
            if (ocrText == null) return null;
            ocrOpens++;
            return new TextOcrBackend() {
                @Override public boolean isReady() { return true; }
                @Override public List<TextOcrBackend.TextLine> read(byte[] jpeg) {
                    ocrFrames++;
                    return Collections.singletonList(new TextOcrBackend.TextLine(ocrText, 0.92f, 0.5f, 0.3f, 0.4f, 0.12f));
                }
                @Override public void close() {}
            };
        }
        @Override public File recentDriveClip(long sessionStartMs) { return null; }
        @Override public List<byte[]> extractTailFrames(File mp4, int count, long tailMs) { return Collections.emptyList(); }
    }

    private File tmp;
    private FakeSource source;
    private FakeEnv env;
    private ParkingController controller;

    @Before
    public void setUp() throws Exception {
        tmp = Files.createTempDirectory("parking-test").toFile();
        source = new FakeSource();
        env = new FakeEnv(tmp);
        final String url = "jdbc:h2:mem:ctl_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        controller = new ParkingController(source, env, () -> new ParkingStore(url));
    }

    @After
    public void tearDown() {
        controller.detach();
        ParkingHooks.setListener(null);
        DetectionBaseline.setGlobalListener(null);
        ParkingController.deleteAssets(tmp);
    }

    private static boolean hasThread(String name) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && name.equals(t.getName())) return true;
        }
        return false;
    }

    private static void await(java.util.function.BooleanSupplier cond, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timed out waiting");
            Thread.sleep(10);
        }
    }

    // ==================== ZERO COST WHEN OFF ====================

    @Test
    public void disabledFeatureInstallsNothing() {
        controller.attach();
        assertFalse(controller.isStarted());
        assertFalse(ParkingHooks.isActive());
        assertNull(env.baselineListener);
        assertNull(env.doorListener);
        assertNull(controller.store());
        assertFalse(hasThread("parking-worker"));
        assertEquals("only the config listener is registered", 1, source.listeners.size());
        // Hook sites are safe no-ops.
        ParkingHooks.onAccOff(1);
        ParkingHooks.onAccOn(2);
        ParkingHooks.onUnlockWhileParked();
        assertNull(ParkingHooks.currentSessionId());
    }

    @Test
    public void toggleStartsAndStopsLive() throws Exception {
        controller.attach();
        source.set("enabled", true);
        await(controller::isStarted, 3000);
        assertTrue(ParkingHooks.isActive());
        assertNotNull(env.baselineListener);
        assertNotNull(env.doorListener);
        assertNotNull(controller.store());
        assertTrue(hasThread("parking-worker"));

        source.set("enabled", false);
        await(() -> !controller.isStarted(), 3000);
        assertFalse(ParkingHooks.isActive());
        assertNull(env.baselineListener);
        assertNull(env.doorListener);
        assertNull(controller.store());
        await(() -> !hasThread("parking-worker"), 3000);

        controller.detach();
        assertEquals(0, source.listeners.size());
    }

    // ==================== SESSION LIFECYCLE ====================

    private void startEnabled() throws Exception {
        source.set("enabled", true);
        controller.attach();
        await(controller::isStarted, 3000);
    }

    @Test
    public void accOffOpensOneSessionPerGeneration() throws Exception {
        startEnabled();
        ParkingHooks.onAccOff(11);
        await(() -> controller.currentSession() != null, 3000);
        ParkingHooks.onAccOff(11);              // deferred-replay of the same edge
        Thread.sleep(150);
        assertEquals(1, controller.store().countSessions());
        ParkingSession s = controller.currentSession();
        assertEquals(s.sessionId, ParkingHooks.currentSessionId());
        assertTrue(s.sessionId.startsWith("park_"));
        assertEquals(11L, s.transitionGeneration);
        assertTrue(s.hasFix());
        assertEquals(ParkingSession.GPS_FRESH, s.gpsQuality);      // 10 s old on the monotonic clock
        assertEquals(ParkingSession.SENTRY_ARMED, s.sentryState);
        assertEquals(40, s.rectifyStrength);
        await(() -> "Mid Valley".equals(controller.store().getSession(s.sessionId).placeShort), 3000);
        assertTrue("no notification before the arrived stills", env.published.isEmpty());
    }

    @Test
    public void arrivedStillsThenParkedNotificationThenReturn() throws Exception {
        startEnabled();
        ParkingHooks.onAccOff(1);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();
        await(() -> controller.store().getSession(s.sessionId).placeShort != null, 3000);

        env.now += 90_000;
        controller.arrivedAttempt(s.sessionId, 1);      // what the 90 s timer runs
        assertEquals(4, env.captures);
        assertTrue(s.arrivedSnapshotOk);
        File dir = controller.sessionDir(s.sessionId);
        assertTrue(new File(dir, "arrived_front.jpg").isFile());
        assertTrue(new File(dir, "arrived_left.jpg").isFile());
        assertEquals(1, env.published.size());
        NotificationEvent started = env.published.get(0);
        assertEquals(ParkingNotifier.CATEGORY_STARTED, started.category);
        assertEquals(NotificationEvent.Severity.INFO, started.severity);
        assertEquals("parking:" + s.sessionId, started.tag);
        assertEquals("/parking.html#/session/" + s.sessionId, started.clickUrl);
        assertTrue(started.body.contains("Mid Valley"));
        assertTrue(started.body.contains("GPS fresh"));
        assertFalse("armed sentry adds no warning", started.body.contains("Sentry not armed"));
        // Push banner + Telegram photo + maps button ride the data blob.
        assertTrue(started.data.getString("snapshot").startsWith("/parking/asset/" + s.sessionId + "/arrived_front.jpg?t="));
        assertTrue(new File(started.data.getString("telegramPhotoPath")).isFile());
        assertEquals("Walk me back", started.data.getJSONArray("telegramButtons").getJSONObject(0).getString("text"));
        assertTrue(started.data.getJSONArray("telegramButtons").getJSONObject(0).getString("url").startsWith("https://maps.google.com/?q=3.1"));

        // Re-running the timer never re-notifies.
        controller.arrivedAttempt(s.sessionId, 2);
        assertEquals(1, env.published.size());

        // Owner returns 3 h later: unlock → returned stills, session closed, one "Back at car".
        env.now += 3 * 3_600_000L + 7 * 60_000L;
        env.sentryEvents = 5;
        controller.handleReturnSignal(ParkingSession.END_UNLOCK);
        assertNull(controller.currentSession());
        assertNull(ParkingHooks.currentSessionId());
        ParkingSession closed = controller.store().getSession(s.sessionId);
        assertFalse(closed.isOpen());
        assertEquals(ParkingSession.END_UNLOCK, closed.endTrigger);
        assertTrue(closed.returnedSnapshotOk);
        assertEquals(5, closed.eventCount);
        assertTrue(closed.notifiedEnded);
        assertEquals(2, env.published.size());
        NotificationEvent ended = env.published.get(1);
        assertEquals(ParkingNotifier.CATEGORY_ENDED, ended.category);
        assertTrue(ended.body, ended.body.contains("3 h 08 m"));   // 90 s + 3 h 07 m
        assertTrue(ended.body.contains("5 events"));
        assertTrue(ended.body.contains("Nothing critical"));

        // A second return signal (door bounce) is a no-op.
        controller.handleReturnSignal(ParkingSession.END_DOOR_OPEN);
        assertEquals(2, env.published.size());
    }

    @Test
    public void sentrySuppressedSessionNotifiesWithoutStillsAndFlagsIt() throws Exception {
        env.sentry = ParkingSession.SENTRY_SUPPRESSED_SAFE_ZONE;
        env.safeZone = "Home";
        env.pipelineRunning = false;
        startEnabled();
        ParkingHooks.onAccOff(3);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();
        controller.arrivedAttempt(s.sessionId, 1);
        assertEquals("no camera pipeline: nothing captured", 0, env.captures);
        assertFalse(s.arrivedSnapshotOk);
        assertEquals("suppressed sentry will not come up — notify now", 1, env.published.size());
        String body = env.published.get(0).body;
        assertTrue(body, body.startsWith("Home"));
        assertTrue(body, body.contains("Sentry not armed (safe zone)"));
        assertFalse(env.published.get(0).data.has("snapshot"));
    }

    @Test
    public void lockWaitRetriesBeforeNotifying() throws Exception {
        env.sentry = ParkingSession.SENTRY_LOCK_WAIT;
        env.pipelineRunning = false;
        startEnabled();
        ParkingHooks.onAccOff(4);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();
        controller.arrivedAttempt(s.sessionId, 1);
        assertEquals("pipeline may still come up: hold the notification", 0, env.published.size());
        env.pipelineRunning = true;
        env.sentry = ParkingSession.SENTRY_ARMED;
        controller.arrivedAttempt(s.sessionId, 2);
        assertEquals(1, env.published.size());
        assertEquals(ParkingSession.SENTRY_ARMED, s.sentryState);
        assertTrue(s.arrivedSnapshotOk);
    }

    @Test
    public void accOnClosesTheSessionAndAShortStopNeverNotifiesLate() throws Exception {
        startEnabled();
        ParkingHooks.onAccOff(5);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();
        env.now += 40_000;
        ParkingHooks.onAccOn(6);                        // drove off before the 90 s stills
        await(() -> controller.currentSession() == null, 3000);
        ParkingSession closed = controller.store().getSession(s.sessionId);
        assertEquals(ParkingSession.END_ACC_ON, closed.endTrigger);
        assertTrue("the late 'Parked' is suppressed", closed.notifiedStarted);
        assertTrue(closed.notifiedEnded);
        assertEquals("only 'Back at car'", 1, env.published.size());
        assertEquals(ParkingNotifier.CATEGORY_ENDED, env.published.get(0).category);
        // The stale timer firing afterwards is harmless.
        controller.arrivedAttempt(s.sessionId, 1);
        assertEquals(1, env.published.size());
    }

    /** Simulates a daemon restart: detach() leaves the row open, attach() recovers it. */
    private void restartDaemon() throws Exception {
        controller.detach();
        assertFalse(controller.isStarted());
        assertNull(ParkingHooks.currentSessionId());
        controller.attach();
        await(controller::isStarted, 3000);
    }

    @Test
    public void restartAdoptsAnOpenSessionWhileStillParked() throws Exception {
        startEnabled();
        ParkingHooks.onAccOff(7);
        await(() -> controller.currentSession() != null, 3000);
        String sid = controller.currentSession().sessionId;

        restartDaemon();
        await(() -> sid.equals(ParkingHooks.currentSessionId()), 3000);
        assertEquals(1, controller.store().countSessions());

        // The daemon replays its boot ACC-off edge for the very same park (new
        // process ⇒ new generation numbering): it must confirm, not supersede.
        ParkingHooks.onAccOff(1);
        Thread.sleep(200);
        assertEquals(sid, ParkingHooks.currentSessionId());
        assertEquals(1, controller.store().countSessions());
        assertEquals(1L, controller.store().getSession(sid).transitionGeneration);

        // A genuinely new park later is still a new session.
        env.now += 3_600_000L;
        ParkingHooks.onAccOn(2);
        await(() -> controller.currentSession() == null, 3000);
        ParkingHooks.onAccOff(3);
        await(() -> controller.currentSession() != null, 3000);
        assertEquals(2, controller.store().countSessions());
    }

    @Test
    public void restartWhileDrivingClosesTheOrphanAsRecovered() throws Exception {
        startEnabled();
        ParkingHooks.onAccOff(8);
        await(() -> controller.currentSession() != null, 3000);
        String sid = controller.currentSession().sessionId;

        env.accOn = true;
        restartDaemon();
        await(() -> !controller.store().getSession(sid).isOpen(), 3000);
        assertEquals(ParkingSession.END_RECOVERED, controller.store().getSession(sid).endTrigger);
        assertNull(controller.currentSession());
        assertTrue("the user was never told about this park: silent", env.published.isEmpty());
    }

    @Test
    public void restartWhileDrivingTellsTheUserAboutAParkTheyWereToldAbout() throws Exception {
        startEnabled();
        ParkingHooks.onAccOff(8);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();
        env.now += 90_000;
        controller.arrivedAttempt(s.sessionId, 1);
        assertEquals(1, env.published.size());

        env.now += 2 * 3_600_000L;
        env.accOn = true;
        env.sentryEvents = 3;
        restartDaemon();
        await(() -> !controller.store().getSession(s.sessionId).isOpen(), 3000);
        await(() -> env.published.size() == 2, 3000);
        NotificationEvent ended = env.published.get(1);
        assertEquals(ParkingNotifier.CATEGORY_ENDED, ended.category);
        assertTrue(ended.body, ended.body.contains("3 events"));
        assertTrue(controller.store().getSession(s.sessionId).notifiedEnded);
    }

    @Test
    public void recoveryWaitsForAnAuthoritativeAccReading() throws Exception {
        startEnabled();
        ParkingHooks.onAccOff(8);
        await(() -> controller.currentSession() != null, 3000);
        String sid = controller.currentSession().sessionId;

        env.accAuthoritative = false;
        env.accOn = false;                 // AccMonitor's boot default — must not be trusted
        restartDaemon();
        Thread.sleep(300);
        assertNull("nothing adopted on the boot default", controller.currentSession());
        assertTrue(controller.store().getSession(sid).isOpen());

        // The real reading arrives: the car is being driven.
        env.accOn = true;
        env.accAuthoritative = true;
        await(() -> !controller.store().getSession(sid).isOpen(), 8000);
        assertEquals(ParkingSession.END_RECOVERED, controller.store().getSession(sid).endTrigger);
    }

    @Test
    public void bootAccOffEdgeWhileRecoveryIsPendingAdoptsTheOpenRow() throws Exception {
        startEnabled();
        ParkingHooks.onAccOff(8);
        await(() -> controller.currentSession() != null, 3000);
        String sid = controller.currentSession().sessionId;

        env.accAuthoritative = false;
        restartDaemon();
        Thread.sleep(200);
        assertNull(controller.currentSession());
        ParkingHooks.onAccOff(1);          // the daemon's boot edge: ACC is OFF, same park
        await(() -> sid.equals(ParkingHooks.currentSessionId()), 3000);
        assertEquals(1, controller.store().countSessions());
        assertEquals(1L, controller.store().getSession(sid).transitionGeneration);
    }

    @Test
    public void disablingTheFeatureClosesTheOpenSessionQuietly() throws Exception {
        startEnabled();
        ParkingHooks.onAccOff(8);
        await(() -> controller.currentSession() != null, 3000);
        String sid = controller.currentSession().sessionId;
        env.now += 600_000L;

        source.set("enabled", false);
        await(() -> !controller.isStarted(), 3000);
        source.set("enabled", true);
        await(controller::isStarted, 3000);
        ParkingSession s = controller.store().getSession(sid);
        assertFalse("closed when the user switched the feature off", s.isOpen());
        assertEquals(ParkingSession.END_DISABLED, s.endTrigger);
        assertEquals(600_000L, s.durationMs(env.now));
        assertNull(controller.currentSession());
        assertTrue(env.published.isEmpty());
    }

    // ==================== ADDRESS LOOKUP ====================

    @Test
    public void addressDeniedByTheGarageIsFilledInWhenTheOwnerReturns() throws Exception {
        env.geocodeAvailable = false;                     // underground: no internet
        startEnabled();
        ParkingHooks.onAccOff(41);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();
        env.now += 90_000;
        controller.arrivedAttempt(s.sessionId, 1);
        assertNull(controller.store().getSession(s.sessionId).placeShort);
        // The notification never claims the location is unknown when a fix exists.
        String body = env.published.get(0).body;
        assertFalse(body, body.contains("Location unavailable"));
        assertTrue(body, body.contains("3.10000, 101.60000"));

        env.now += 3_600_000L;
        env.geocodeAvailable = true;                      // back at the surface
        controller.handleReturnSignal(ParkingSession.END_UNLOCK);
        await(() -> "Mid Valley".equals(controller.store().getSession(s.sessionId).placeShort), 3000);
    }

    // ==================== SURVEILLANCE OFF ====================

    @Test
    public void parkWithSurveillanceOffStillRecordsWhereAndHowLongButSaysSentryWasOff() throws Exception {
        env.sentry = ParkingSession.SENTRY_SURVEILLANCE_OFF;
        env.pipelineRunning = false;                     // daemon stops the camera at ACC-off
        env.sentryEvents = 0;
        startEnabled();
        ParkingHooks.onAccOff(31);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();
        assertTrue(s.hasFix());
        env.now += 90_000;
        controller.arrivedAttempt(s.sessionId, 1);
        assertEquals("no camera: no stills, but Parked goes out at once", 1, env.published.size());
        assertTrue(env.published.get(0).body, env.published.get(0).body.contains("Sentry not armed (surveillance off)"));

        env.now += 2 * 3_600_000L;
        ParkingHooks.onAccOn(32);
        await(() -> controller.currentSession() == null, 3000);
        String ended = env.published.get(1).body;
        assertTrue(ended, ended.contains("Away 2 h 01 m"));
        assertTrue(ended, ended.contains("Sentry not armed (surveillance off)"));
        assertFalse("nothing was watched, so no 'saw nothing' claim", ended.contains("events"));
        assertFalse(ended.contains("Nothing critical"));
    }

    @Test
    public void arrivalTimerFiringAfterASleepNeverSendsALateParked() throws Exception {
        env.sentry = ParkingSession.SENTRY_SURVEILLANCE_OFF;
        env.pipelineRunning = false;
        startEnabled();
        ParkingHooks.onAccOff(33);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();
        // Head unit slept through the 90 s timer; it fires 3 h later at wake-up.
        env.now += 3 * 3_600_000L;
        controller.arrivedAttempt(s.sessionId, 1);
        assertTrue(env.published.isEmpty());
        assertTrue(controller.store().getSession(s.sessionId).notifiedStarted);
        assertEquals(0, env.captures);
    }

    @Test
    public void sentryEvidenceUpgradesALockWaitSnapshotToArmed() throws Exception {
        env.sentry = ParkingSession.SENTRY_LOCK_WAIT;
        startEnabled();
        ParkingHooks.onAccOff(34);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();
        assertEquals(ParkingSession.SENTRY_LOCK_WAIT, s.sentryState);
        // A finalized sentry clip can only exist if sentry armed after the snapshot.
        ParkingHooks.onEventFinalized(new File(tmp, "sentry_x.mp4"), Collections.emptyList(), env.now, true);
        await(() -> ParkingSession.SENTRY_ARMED.equals(controller.store().getSession(s.sessionId).sentryState), 3000);
    }

    // ==================== SIGNAGE AT ARRIVAL ====================

    @Test
    public void signageIsReadRightAfterTheArrivalStillsNotOnlyWhenCharging() throws Exception {
        env.ocrText = "B2";
        env.charging = false;                           // on the 12 V battery, sentry armed
        startEnabled();
        ParkingHooks.onAccOff(21);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();
        env.now += 90_000;
        controller.arrivedAttempt(s.sessionId, 1);
        assertEquals(1, env.ocrOpens);
        assertEquals("the four stills are enough when they yield a level: no clip decode",
                4, env.ocrFrames);
        assertEquals(ParkingSession.SIGNAGE_DONE, s.signageState);
        assertEquals("B2", new JSONObject(s.signageJson).getString("level"));
        NotificationEvent started = env.published.get(0);
        assertTrue("the Parked message carries the level while the owner walks away: " + started.body,
                started.body.contains("B2"));
        assertEquals(ParkingSession.SIGNAGE_DONE, controller.store().getSession(s.sessionId).signageState);
    }

    @Test
    public void signageReadIsDeferredWhileASentryClipRecordsAndRetriedWhileParked() throws Exception {
        env.ocrText = "LEVEL 3";
        startEnabled();
        ParkingHooks.onAccOff(22);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();
        env.now += 90_000;
        env.recording = true;                            // a sentry clip is being encoded
        controller.arrivedAttempt(s.sessionId, 1);
        assertEquals(0, env.ocrOpens);
        assertEquals(ParkingSession.SIGNAGE_PENDING, s.signageState);
        assertEquals("Parked still goes out, just without a level", 1, env.published.size());
        assertFalse(env.published.get(0).body.contains("Level 3"));

        controller.retryOpenSessionSignage(s.sessionId, 1);   // what the 60 s retry runs
        assertEquals("still recording: still deferred", 0, env.ocrOpens);
        env.recording = false;
        controller.retryOpenSessionSignage(s.sessionId, 2);
        assertEquals(1, env.ocrOpens);
        assertEquals("Level 3", new JSONObject(controller.store().getSession(s.sessionId).signageJson).getString("level"));
    }

    @Test
    public void withoutModelsTheSessionIsMarkedUnavailableOnce() throws Exception {
        env.ocrText = null;
        startEnabled();
        ParkingHooks.onAccOff(23);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();
        env.now += 90_000;
        controller.arrivedAttempt(s.sessionId, 1);
        assertEquals(ParkingSession.SIGNAGE_UNAVAILABLE, controller.store().getSession(s.sessionId).signageState);
        // Not pending any more: the catch-up queue will not spin on it.
        assertTrue(controller.store().listSessionsWithSignageState(ParkingSession.SIGNAGE_PENDING, 10).isEmpty());
    }

    // ==================== DOOR EDGES ====================

    @Test
    public void doorActivityRightAfterSwitchOffIsTheDriverLeavingNotAReturn() throws Exception {
        startEnabled();
        ParkingHooks.onAccOff(12);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();
        assertNotNull(env.doorListener);

        env.now += 8_000;   controller.handleDoorEvent(true);     // driver door opens
        env.now += 6_000;   controller.handleDoorEvent(false);    // …and closes
        env.now += 40_000;  controller.handleDoorEvent(true);     // passenger grabs a bag
        env.now += 5_000;   controller.handleDoorEvent(false);
        assertTrue("still parked", s.isOpen());
        assertEquals(s, controller.currentSession());

        // Unloading the boot for four minutes: activity never pauses for a
        // minute, so the door opening past the 3-minute mark is still not a return.
        env.now += 41_000;  controller.handleDoorEvent(true);     // t = 100 s
        env.now += 30_000;  controller.handleDoorEvent(false);    // t = 130 s
        env.now += 40_000;  controller.handleDoorEvent(true);     // t = 170 s
        env.now += 30_000;  controller.handleDoorEvent(false);    // t = 200 s
        env.now += 40_000;  controller.handleDoorEvent(true);     // t = 240 s: 40 s after the last edge
        env.now += 10_000;  controller.handleDoorEvent(false);    // t = 250 s
        assertTrue(s.isOpen());
        assertEquals(s, controller.currentSession());

        // Three hours of silence, then a door opens: the owner is back.
        env.now += 3 * 3_600_000L;
        controller.handleDoorEvent(true);
        assertNull(controller.currentSession());
        ParkingSession closed = controller.store().getSession(s.sessionId);
        assertEquals(ParkingSession.END_DOOR_OPEN, closed.endTrigger);
        // A door closing never ends anything.
        assertEquals(1, env.published.size());
        controller.handleDoorEvent(false);
        assertEquals(1, env.published.size());
    }

    @Test
    public void doorEdgesFlowThroughTheEnvironmentListener() throws Exception {
        startEnabled();
        ParkingHooks.onAccOff(13);
        await(() -> controller.currentSession() != null, 3000);
        String sid = controller.currentSession().sessionId;
        env.now += 3 * 3_600_000L;
        env.doorListener.onDoor(true);
        await(() -> controller.currentSession() == null, 3000);
        assertEquals(ParkingSession.END_DOOR_OPEN, controller.store().getSession(sid).endTrigger);
    }

    @Test
    public void vehiclesThatArrivedAndStayedCountAsCameOrWent() throws Exception {
        startEnabled();
        ParkingHooks.onAccOff(14);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();
        env.now += 90_000;
        controller.arrivedAttempt(s.sessionId, 1);

        env.now += 20 * 60_000L;
        env.baselineListener.onEntryAdded(new DetectionBaseline.EntrySnapshot(7, 1, 2, "VEHICLE",
                0.4f, 0.6f, 0.3f, 0.3f, env.now, env.now, 3, true, false), "event_end");
        await(() -> controller.store().countNeighbours(s.sessionId, false) == 1, 3000);

        env.now += 3_600_000L;
        controller.handleReturnSignal(ParkingSession.END_UNLOCK);
        ParkingSession closed = controller.store().getSession(s.sessionId);
        assertEquals(1, closed.neighbourCount);
        NotificationEvent ended = env.published.get(1);
        assertTrue(ended.body, ended.body.contains("1 vehicles came or went"));
        assertEquals(1, ended.data.getInt("neighboursMoved"));
    }

    @Test
    public void unknownEventCountIsNotReportedAsZero() throws Exception {
        startEnabled();
        ParkingHooks.onAccOff(15);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();
        env.now += 90_000;
        controller.arrivedAttempt(s.sessionId, 1);
        env.now += 3_600_000L;
        env.sentryEvents = -1;             // recordings index not available
        env.criticalEvents = -1;
        controller.handleReturnSignal(ParkingSession.END_UNLOCK);
        NotificationEvent ended = env.published.get(1);
        assertFalse(ended.body, ended.body.contains("events"));
        assertFalse(ended.body, ended.body.contains("Nothing critical"));
        assertFalse(ended.data.has("events"));
        assertEquals(0, controller.store().getSession(s.sessionId).eventCount);
    }

    @Test
    public void finalizedEventsCountAndBaselineSeedsFeedTheTimeline() throws Exception {
        startEnabled();
        ParkingHooks.onAccOff(9);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();

        env.baselineListener.onEntrySeeded(1, Collections.singletonList(
                new DetectionBaseline.EntrySnapshot(1, 1, 2, "VEHICLE", 0.4f, 0.6f, 0.3f, 0.3f,
                        env.now, env.now, 3, true, false)));
        await(() -> controller.store().countNeighbours(s.sessionId, false) == 1, 3000);

        Actor passer = ParkingNeighbourObserverTest.mover(31, 2, 0.5f, 0.5f, 0.3f, 0.3f, Actor.Proximity.VERY_CLOSE);
        ParkingHooks.onEventFinalized(new File(tmp, "sentry_a.mp4"), Collections.singletonList(passer), env.now, false);
        ParkingHooks.onEventFinalized(new File(tmp, "sentry_a.mp4"), Collections.singletonList(passer), env.now, true);
        await(() -> controller.store().getSession(s.sessionId).eventCount == 1, 3000);
        assertNotNull(controller.store().findNeighbourByKey(s.sessionId, "a31"));

        // Delete is refused while open, allowed once closed.
        assertFalse(controller.deleteSession(s.sessionId));
        controller.handleReturnSignal(ParkingSession.END_DOOR_OPEN);
        assertTrue(controller.deleteSession(s.sessionId));
        assertNull(controller.store().getSession(s.sessionId));
        assertFalse(controller.sessionDir(s.sessionId).exists());
    }

    @Test
    public void retentionPruneDeletesOldClosedSessionsAndTheirAssets() throws Exception {
        startEnabled();
        ParkingStore st = controller.store();
        ParkingSession old = ParkingStoreTest.session("park_20200101_000000", env.now - 200L * 86_400_000L);
        old.endedMs = old.startedMs + 3_600_000L;
        st.insertSession(old);
        File oldDir = controller.sessionDir(old.sessionId);
        assertTrue(oldDir.mkdirs());
        Files.write(new File(oldDir, "arrived_front.jpg").toPath(), new byte[] {1, 2, 3});

        ParkingSession recent = ParkingStoreTest.session("park_20260917_090000", env.now - 86_400_000L);
        recent.endedMs = recent.startedMs + 1000;
        st.insertSession(recent);

        controller.prune();
        assertNull(st.getSession(old.sessionId));
        assertFalse(oldDir.exists());
        assertNotNull(st.getSession(recent.sessionId));
    }

    @Test
    public void unsetClockSessionsAreNeverAgedOutAndCarryNoDuration() throws Exception {
        startEnabled();
        ParkingStore st = controller.store();
        // Cold boot before GPS/network time: the clock still reads 1970.
        ParkingSession epoch = ParkingStoreTest.session("park_19700101_000500", 300_000L);
        epoch.endedMs = 600_000L;
        st.insertSession(epoch);
        controller.prune();
        assertNotNull("age is meaningless for an unset clock: retention keeps it", st.getSession(epoch.sessionId));
        assertTrue(st.getSession(epoch.sessionId).toJson().getBoolean("clockUntrusted"));

        new ParkingNotifier(env).publishEnded(epoch, 2, 0, false);
        String body = env.published.get(env.published.size() - 1).body;
        assertFalse(body, body.contains("Away"));
        assertTrue(body, body.contains("2 events"));
    }

    @Test
    public void orphanAssetDirectoriesAreReclaimedByPrune() throws Exception {
        startEnabled();
        File orphan = controller.sessionDir("park_20250101_101010");
        assertTrue(orphan.mkdirs());
        Files.write(new File(orphan, "arrived_front.jpg").toPath(), new byte[] {1});
        controller.prune();
        assertFalse("no row → the folder is unreachable garbage", orphan.exists());
    }

    @Test
    public void sessionIdFormat() {
        String id = ParkingController.sessionIdFor(1_726_560_900_000L);
        assertTrue(id, id.matches("park_\\d{8}_\\d{6}"));
    }

    // ==================== CONFIGURABLE END TRIGGER ====================

    @Test
    public void powerOnModeIgnoresDoorAndUnlockButRefreshesStills() throws Exception {
        source.set("endTrigger", "power_on");
        startEnabled();
        ParkingHooks.onAccOff(7);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();

        // Owner returns hours later: door and unlock refresh the returned
        // stills but close nothing.
        env.now += 3 * 3_600_000L;
        controller.handleDoorEvent(true);
        controller.handleReturnSignal(ParkingSession.END_UNLOCK);
        assertEquals(s, controller.currentSession());
        ParkingSession row = controller.store().getSession(s.sessionId);
        assertTrue("still open after door + unlock", row.isOpen());
        assertTrue("returned stills taken on the walk-up", row.returnedSnapshotOk);

        // Power-on is the configured close.
        env.now += 4 * 60_000L;
        env.accOn = true;
        controller.handleAccOn(8);
        assertNull(controller.currentSession());
        ParkingSession closed = controller.store().getSession(s.sessionId);
        assertFalse(closed.isOpen());
        assertEquals(ParkingSession.END_ACC_ON, closed.endTrigger);
    }

    @Test
    public void driveAwayModeClosesOnGearLeavingP() throws Exception {
        source.set("endTrigger", "drive_away");
        startEnabled();
        ParkingHooks.onAccOff(1);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();

        env.now += 2 * 3_600_000L;
        env.accOn = true;
        controller.handleAccOn(2);
        assertEquals("ACC-on must not close in drive_away mode", s, controller.currentSession());
        assertTrue(controller.store().getSession(s.sessionId).isOpen());

        env.gearInPark = Boolean.TRUE;                 // still in P: keep waiting
        controller.gearWatchAttempt(s.sessionId, 1);
        assertEquals(s, controller.currentSession());

        env.now += 6_000;
        env.gearInPark = Boolean.FALSE;                // shifted out of P
        controller.gearWatchAttempt(s.sessionId, 2);
        assertNull(controller.currentSession());
        ParkingSession closed = controller.store().getSession(s.sessionId);
        assertFalse(closed.isOpen());
        assertEquals(ParkingSession.END_DRIVE_AWAY, closed.endTrigger);
    }

    @Test
    public void driveAwayFallsBackToAccOnWithoutGearReading() throws Exception {
        source.set("endTrigger", "drive_away");
        startEnabled();
        ParkingHooks.onAccOff(1);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();

        env.now += 3_600_000L;
        env.accOn = true;
        controller.handleAccOn(2);
        assertNotNull("no close while the gear is unknown", controller.currentSession());

        // env.gearInPark stays null (no fresh reading, e.g. no gear feed on this
        // trim): the watch gives up at its cap and closes as a power-on end.
        controller.gearWatchAttempt(s.sessionId, ParkingController.GEAR_WATCH_MAX_ATTEMPTS);
        assertNull(controller.currentSession());
        assertEquals(ParkingSession.END_ACC_ON,
                controller.store().getSession(s.sessionId).endTrigger);
    }

    @Test
    public void driveAwaySessionSupersededByNextSwitchOffInP() throws Exception {
        source.set("endTrigger", "drive_away");
        startEnabled();
        ParkingHooks.onAccOff(1);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession first = controller.currentSession();

        env.now += 60_000;
        env.accOn = true;
        controller.handleAccOn(2);                     // sat in the car with it on…
        assertEquals(first, controller.currentSession());

        env.now += 300_000;
        env.accOn = false;
        controller.handleAccOff(3);                    // …switched off without leaving P
        ParkingSession again = controller.store().getSession(first.sessionId);
        assertFalse(again.isOpen());
        assertEquals(ParkingSession.END_SUPERSEDED, again.endTrigger);
        ParkingSession next = controller.currentSession();
        assertNotNull("a fresh session carries the park onward", next);
        assertFalse(first.sessionId.equals(next.sessionId));
    }

    // ==================== ENERGY BOOKENDS ====================

    @Test
    public void energyBookendsPreferBmsKwhAndReadWithDecimals() throws Exception {
        startEnabled();
        env.soc = 78; env.remainKwh = 61.42; env.nominalKwh = 82.56;
        ParkingHooks.onAccOff(1);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();
        assertEquals(78.0, s.startSocPercent, 1e-9);
        assertEquals(61.42, s.startRemainKwh, 1e-9);

        // Integer gauge did not move, but the BMS energy did: a real figure, not "0%".
        env.now += 90 * 60_000L;
        env.remainKwh = 60.18;
        controller.handleReturnSignal(ParkingSession.END_UNLOCK);
        ParkingSession closed = controller.store().getSession(s.sessionId);
        assertEquals(78.0, closed.endSocPercent, 1e-9);
        assertEquals(60.18, closed.endRemainKwh, 1e-9);
        assertEquals(ParkingSession.ENERGY_SRC_BMS, closed.energySource());
        assertEquals(-1.24, closed.energyDeltaKwh(), 1e-9);
        assertFalse(closed.chargedWhileParked);
        assertTrue("no SoC step → no capacity estimate", Double.isNaN(closed.energyEstKwh));
        String body = env.published.get(env.published.size() - 1).body;
        assertTrue(body, body.contains("1.24 kWh used"));
        assertFalse("a flat SoC is not reported", body.contains("SoC"));
    }

    @Test
    public void liveEnergyUsesBmsKwhWhilePlugged() throws Exception {
        startEnabled();
        env.soc = 40; env.remainKwh = 30.0;
        ParkingHooks.onAccOff(1);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();

        env.soc = 40; env.remainKwh = 30.02;           // meter jitter: hidden
        JSONObject live = controller.liveEnergyJson(s);
        assertNotNull(live);
        assertTrue(live.getBoolean("live"));
        assertFalse(live.getBoolean("measurable"));

        env.soc = 52; env.remainKwh = 39.85;           // charging in the garage
        live = controller.liveEnergyJson(s);
        assertTrue(live.getBoolean("measurable"));
        assertTrue(live.getBoolean("charged"));
        assertEquals(9.85, live.getDouble("kwh"), 1e-9);
        assertEquals(12.0, live.getDouble("socDelta"), 1e-9);
        assertEquals("bms", live.getString("source"));
        assertTrue("display-only: the row is untouched", Double.isNaN(s.endRemainKwh));
    }

    @Test
    public void signageQueueSkipsTheLiveSession() throws Exception {
        source.set("endTrigger", "drive_away");
        startEnabled();
        env.ocrText = "LEVEL B2";                      // models present
        ParkingHooks.onAccOff(1);
        await(() -> controller.currentSession() != null, 3000);
        ParkingSession s = controller.currentSession();

        env.accOn = true;
        controller.handleAccOn(2);                     // drive_away: row is still open
        controller.runSignageQueue(true);              // what the +2 min queue runs
        assertEquals("the queue must not touch the LIVE row (close-path aliasing)",
                ParkingSession.SIGNAGE_PENDING,
                controller.store().getSession(s.sessionId).signageState);
        assertEquals(0, env.ocrOpens);
    }
}
