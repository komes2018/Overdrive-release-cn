package com.overdrive.app.remoteaccess;

import android.content.Context;
import android.hardware.IBYDAutoEvent;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the production reflective registration, setter and callback paths. */
public class KeyFobPresenceSearchModeTest {
    private static final int RESULT = 1077215250, SEARCH = 1084842000;
    private static final String A = "00000000-0000-0000-0000-000000000001";
    private static final String B = "00000000-0000-0000-0000-000000000002";
    private static final String C = "00000000-0000-0000-0000-000000000003";

    public interface Listener {
        void onDataChanged(IBYDAutoEvent event);
        void onDataEventChanged(int feature, Value value);
        void onError(int code, String reason);
    }

    public static final class Value { public int intValue; }

    public static final class Event implements IBYDAutoEvent {
        private final int raw;
        Event(int raw) { this.raw = raw; }
        @Override public int getDeviceType() { return 1007; }
        @Override public int getEventType() { return RESULT; }
        @Override public int getValue() { return raw; }
        @Override public double getDoubleValue() { return 0; }
        @Override public byte[] getBufferData() { return null; }
        @Override public Object getData() { return null; }
        @Override public void setData(Object data) {}
    }

    public static final class Properties {
        public static String get(String key, String fallback) { return "CANFD"; }
    }

    public static final class Ids {
        public static final int INSTRUMENT_SEEK_PHISICAL_KEY_RESULT = RESULT;
        public static final int INSTRUMENT_SEARCH_APPLE_KEY_SET = SEARCH;
    }

    public static final class Features {
        public static Set<Integer> getFeatureIdsFromDevice(int device) {
            return device == 1007 ? new HashSet<>(java.util.Arrays.asList(RESULT, SEARCH))
                    : Collections.emptySet();
        }
    }

    public static final class ListenerMap {
        final ArrayList<Listener> listeners = new ArrayList<>();
        public boolean isListenerEmpty() { return listeners.isEmpty(); }
        public boolean isListenerAllIdsEmpty() { return true; }
        public Collection<Listener> getIdListeners(int feature) { return listeners; }
    }

    public static final class Device {
        public final ListenerMap mIBYDAutoListenerMap = new ListenerMap();
        Runnable onRegister = () -> {};
        boolean cleanupFails;
        int registrations, unregisters;
        Listener current, last;
        public void registerListener(Listener listener, int[] features) {
            assertArrayEquals(new int[]{RESULT}, features);
            registrations++;
            last = current = listener;
            mIBYDAutoListenerMap.listeners.add(listener);
            onRegister.run();
        }
        public void unregisterListener(Listener listener) {
            unregisters++;
            if (cleanupFails) throw new IllegalStateException("fixture_cleanup_failed");
            mIBYDAutoListenerMap.listeners.remove(listener);
            if (current == listener) current = null;
        }
    }

    public static final class Manager {
        Runnable onRead = () -> {}, onRequest = () -> {};
        int requests, cached = 65535;
        public int getInt(int device, int feature) {
            assertEquals(1007, device);
            assertEquals(RESULT, feature);
            onRead.run();
            return cached;
        }
        public int setInt(int device, int feature, int value) {
            assertEquals(1007, device);
            assertEquals(SEARCH, feature);
            assertEquals(2, value);
            requests++;
            onRequest.run();
            return 0;
        }
    }

    private static DiLink5Platform.ActiveModeSnapshot mode(String selected, String generation)
            throws Exception {
        Method parse = DiLink5Platform.class.getDeclaredMethod("parseActiveModeMarker", byte[].class);
        parse.setAccessible(true);
        return (DiLink5Platform.ActiveModeSnapshot) parse.invoke(null,
                (selected + "\n" + generation + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static final class Fixture implements KeyFobPresenceSearch.Environment {
        final Device device = new Device();
        final Manager manager = new Manager();
        final KeyFobPresenceSearch search;
        DiLink5Platform.ActiveModeSnapshot committed;
        long now = 1_000, serial;
        boolean consent = true, api = true;
        int markerReads, wakes;
        Fixture() throws Exception {
            committed = mode("default", A);
            search = new KeyFobPresenceSearch(null, () -> wakes++, () -> consent, this);
        }
        Fixture(long intervalMs) throws Exception {
            committed = mode("default", A);
            search = new KeyFobPresenceSearch(null, () -> wakes++, () -> consent, intervalMs, this);
        }
        @Override public DiLink5Platform.ActiveModeSnapshot committedMode() {
            markerReads++;
            return committed;
        }
        @Override public boolean supportsNativeApi() { return api; }
        @Override public long elapsedRealtime() { return now; }
        @Override public Class<?> nativeType(String name) throws ClassNotFoundException {
            switch (name) {
                case "android.os.SystemProperties": return Properties.class;
                case "android.hardware.bydauto.BYDAutoFeatureIds": return Ids.class;
                case "android.hardware.bydauto.BYDAutoDeviceFeaturesMap": return Features.class;
                case "android.hardware.BYDAutoManager": return Manager.class;
                case "android.hardware.IBYDAutoListener": return Listener.class;
                case "android.hardware.IBYDAutoEvent": return IBYDAutoEvent.class;
                default: throw new ClassNotFoundException(name);
            }
        }
        @Override public Object autoService(Context context) { return manager; }
        @Override public Object instrumentDevice(Context context) { return device; }
        void tick(long at) { now = at; search.tick(at, true, false); }
        void emit(int raw, long at) {
            now = at;
            assertNotNull(device.current);
            device.current.onDataChanged(new Event(raw));
        }
        void known(int raw, long at) { emit(0, at); emit(raw, at + 1); }
        JSONObject runtime() throws Exception { return search.runtime(); }
        KeyFobPresenceEvaluator.Snapshot relay(KeyFobPresenceEvaluator evaluator, long at)
                throws Exception {
            now = at;
            JSONObject packet = search.appendObservation(new JSONObject()
                    .put("revision", 7L).put("instanceId", "same-worker")
                    .put("sourceGeneration", 1L).put("observationSequence", ++serial)
                    .put("eventSequence", 0L).put("rawMask", -1)
                    .put("eventObservedAtElapsedMs", -1L)
                    .put("runtime", new JSONObject().put("active", false).put("registered", false)
                            .put("tableRegistered", false).put("reason", "passive_can_unavailable"))
                    .put("scope", new JSONObject().put("power", 0).put("driverLock", 1)
                            .put("observedAtElapsedMs", at)));
            return evaluator.observe(KeyFobPresenceEvaluator.Observation.parse(packet,
                    KeyFobEventEvaluator.Observation.parse(packet)), at, true);
        }
    }

    @Test public void initiallyMissingCommitRecoversWithoutApplyOrSourceRestart() throws Exception {
        Fixture f = new Fixture();
        f.committed = null;
        f.tick(1_000);
        assertEquals(0, f.manager.requests);
        assertFalse(f.runtime().getBoolean("active"));
        f.committed = mode("default", B);
        f.tick(2_000);
        assertEquals(1, f.manager.requests);
        f.known(1, 2_100);
        assertEquals("present", f.runtime().getString("state"));
        assertEquals(1, f.runtime().getLong("sourceGeneration"));
    }

    @Test public void sameModeNewCommitRetiresOldQueryAndKeepsTenSecondSpacing() throws Exception {
        Fixture f = new Fixture();
        f.tick(1_000);
        f.known(1, 1_100);
        Listener old = f.device.current;
        f.committed = mode("default", B);
        f.tick(2_000);
        assertEquals(1, f.device.unregisters);
        assertEquals(1, f.manager.requests);
        assertEquals(2, f.runtime().getLong("sourceGeneration"));
        assertEquals("unknown", f.runtime().getString("state"));
        old.onDataChanged(new Event(0));
        old.onDataChanged(new Event(1));
        f.tick(10_999);
        assertEquals(1, f.manager.requests);
        f.tick(11_000);
        assertEquals(2, f.manager.requests);
        f.known(3, 11_100);
        assertEquals("absent", f.runtime().getString("state"));
        old.onDataChanged(new Event(1));
        assertEquals("absent", f.runtime().getString("state"));
    }

    @Test public void committedModeChangeStartsFreshAbsenceWindowAndEventuallyDeparts() throws Exception {
        Fixture f = new Fixture();
        KeyFobPresenceEvaluator evaluator = new KeyFobPresenceEvaluator(7, 10_000);
        f.tick(1_000);
        f.known(3, 1_100);
        assertTrue(f.relay(evaluator, 1_200).armed);
        f.committed = mode("default", B);
        f.tick(2_000);
        assertFalse(f.relay(evaluator, 2_100).armed);
        for (long at = 4_100; at < 11_000; at += 2_000) f.relay(evaluator, at);
        f.tick(11_000);
        f.known(3, 11_100);
        KeyFobPresenceEvaluator.Snapshot absent = f.relay(evaluator, 11_200);
        assertEquals("confirming_absence", absent.reason);
        assertEquals(1, absent.sampleCount);
        assertFalse(absent.lockCandidate);
        for (long at = 13_200; at < 21_000; at += 2_000) f.relay(evaluator, at);
        f.tick(21_000);
        f.known(3, 21_100);
        KeyFobPresenceEvaluator.Snapshot departed = f.relay(evaluator, 21_200);
        assertEquals(ProximityEvaluator.Event.DEPARTURE, departed.event);
        assertTrue(departed.fresh);
        assertTrue(departed.lockCandidate);
        assertEquals(2, f.runtime().getLong("sourceGeneration"));
    }

    @Test public void di5CommitStopsNativeSearchAndLegacyReturnRecoversFresh() throws Exception {
        Fixture f = new Fixture();
        f.tick(1_000);
        f.known(1, 1_100);
        f.committed = mode("dilink5", B);
        for (long at : new long[]{2_000, 11_000, 21_000}) f.tick(at);
        assertEquals(1, f.manager.requests);
        assertEquals(2, f.runtime().getLong("sourceGeneration"));
        assertFalse(f.runtime().getBoolean("active"));
        f.committed = mode("dilink4", C);
        f.tick(22_000);
        assertEquals(2, f.manager.requests);
        assertEquals("unknown", f.runtime().getString("state"));
        f.known(3, 22_100);
        assertEquals("absent", f.runtime().getString("state"));
    }

    @Test public void invalidatedCommitNeverQueriesUntilValidMarkerAppears() throws Exception {
        Fixture f = new Fixture();
        f.tick(1_000);
        f.known(1, 1_100);
        f.committed = null;
        for (long at : new long[]{2_000, 11_000, 21_000}) f.tick(at);
        assertEquals(1, f.manager.requests);
        assertEquals(2, f.runtime().getLong("sourceGeneration"));
        f.committed = mode("default", B);
        f.tick(22_000);
        assertEquals(2, f.manager.requests);
        assertEquals("unknown", f.runtime().getString("state"));
    }

    @Test public void failedExactListenerCleanupPreventsAdoptionAndPollingRetry() throws Exception {
        Fixture f = new Fixture();
        f.tick(1_000);
        Listener old = f.device.current;
        f.device.cleanupFails = true;
        f.committed = mode("default", B);
        f.tick(2_000);
        assertTrue(f.search.hasCleanupFailure());
        for (long at : new long[]{11_000, 21_000, 31_000}) f.tick(at);
        assertEquals(1, f.device.unregisters);
        assertEquals(1, f.manager.requests);
        old.onDataChanged(new Event(0));
        old.onDataChanged(new Event(1));
        assertEquals("unknown", f.runtime().getString("state"));
        f.device.cleanupFails = false;
        f.search.close(true);
        f.tick(32_000);
        assertEquals(2, f.device.unregisters);
        assertEquals(2, f.manager.requests);
    }

    @Test public void commitChangedInsideCachedReadOrRegistrationCannotIssueQuery()
            throws Exception {
        for (boolean duringRegister : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            Runnable change = () -> {
                try { f.committed = mode("default", B); }
                catch (Exception broken) { throw new AssertionError(broken); }
            };
            if (duringRegister) f.device.onRegister = change;
            else f.manager.onRead = change;
            f.tick(1_000);
            assertEquals(0, f.manager.requests);
            assertFalse(f.runtime().getBoolean("active"));
            f.device.onRegister = () -> {};
            f.manager.onRead = () -> {};
            f.tick(2_000);
            assertEquals(0, f.manager.requests);
            f.tick(11_000);
            assertEquals(1, f.manager.requests);
        }
    }

    @Test public void changedCommitInsideSetterRejectsSynchronousKnownCandidate()
            throws Exception {
        Fixture f = new Fixture();
        f.manager.onRequest = () -> {
            f.known(1, 1_010);
            try { f.committed = mode("default", B); }
            catch (Exception broken) { throw new AssertionError(broken); }
            f.device.current.onDataChanged(new Event(3));
        };
        f.tick(1_000);
        assertEquals(1, f.manager.requests);
        assertEquals("unknown", f.runtime().getString("state"));
        assertFalse(f.runtime().getBoolean("active"));
        Listener old = f.device.last;
        f.manager.onRequest = () -> {};
        f.tick(2_000);
        old.onDataChanged(new Event(0));
        old.onDataChanged(new Event(1));
        f.tick(10_999);
        assertEquals(1, f.manager.requests);
        f.tick(11_000);
        assertEquals(2, f.manager.requests);
        assertEquals("unknown", f.runtime().getString("state"));
    }

    @Test public void callbackAfterCommitChangeCannotPublishKnownBeforeWorkerReconciles()
            throws Exception {
        Fixture f = new Fixture();
        f.tick(1_000);
        f.committed = mode("default", B);
        f.known(1, 1_100);
        assertEquals("unknown", f.runtime().getString("state"));
        assertFalse(f.runtime().getBoolean("active"));
        f.tick(2_000);
        assertEquals(2, f.runtime().getLong("sourceGeneration"));
    }

    @Test public void disabledOrUnsupportedSourceDoesNotQueryAndLockedParkingStaysPaused()
            throws Exception {
        Fixture f = new Fixture();
        f.search.tick(1_000, false, false);
        assertEquals(0, f.markerReads);
        assertEquals(0, f.manager.requests);
        f.consent = false;
        f.tick(2_000);
        assertEquals(0, f.markerReads);
        f.consent = true;
        f.api = false;
        f.tick(3_000);
        assertEquals(0, f.manager.requests);
        f.api = true;
        for (long at : new long[]{4_000, 14_000, 24_000}) {
            f.now = at;
            f.search.tick(at, true, true);
        }
        assertEquals(0, f.manager.requests);
        assertEquals("paused_locked", f.runtime().getString("status"));
    }

    @Test public void searchHonoursTheConfiguredFiveSecondSpacingAndReportsIt() throws Exception {
        Fixture f = new Fixture(5_000);
        f.tick(1_000);
        assertEquals(1, f.manager.requests);
        f.known(3, 1_100);
        assertEquals("absent", f.runtime().getString("state"));
        f.tick(5_999);
        assertEquals(1, f.manager.requests);
        f.tick(6_000);
        assertEquals(2, f.manager.requests);
        assertEquals(5_000L, f.runtime().getLong("intervalMs"));
        assertEquals(KeyFobPresenceCycle.TIMEOUT_MS, f.runtime().getLong("timeoutMs"));
        // The idle wake-up follows the configured spacing; the default fixture still reports ten seconds.
        f.known(3, 6_100);
        assertEquals(4_900L, f.search.wakeInMs(6_100));
        Fixture slow = new Fixture();
        assertEquals(10_000L, slow.runtime().getLong("intervalMs"));
        slow.tick(1_000);
        slow.known(3, 1_100);
        slow.tick(10_999);
        assertEquals(1, slow.manager.requests);
        slow.tick(11_000);
        assertEquals(2, slow.manager.requests);
    }

    @Test public void stoppedOwnerCannotRebindOrQueryAfterExplicitCleanupRetry()
            throws Exception {
        Fixture f = new Fixture();
        f.tick(1_000);
        Listener old = f.device.current;
        f.consent = false;
        f.search.close(true);
        f.committed = mode("default", B);
        f.tick(11_000);
        old.onDataChanged(new Event(0));
        old.onDataChanged(new Event(1));
        assertEquals(1, f.manager.requests);
        assertEquals(1, f.device.registrations);
        assertEquals("unknown", f.runtime().getString("state"));
        assertFalse(f.runtime().getBoolean("active"));
    }
}
