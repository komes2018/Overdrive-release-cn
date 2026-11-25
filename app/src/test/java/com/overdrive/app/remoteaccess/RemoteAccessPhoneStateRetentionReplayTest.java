package com.overdrive.app.remoteaccess;

import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.AWAY;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.UNKNOWN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * M2 (18 Oct): the daemon's {@code reset()} keeps a phone's PhoneState (evaluator, history,
 * first-connected time) when the phone's own entry and the shared phone rules are unchanged, even
 * though the revision moved because ANOTHER phone was added, removed or edited; and {@code relay()}
 * adopts the app collector's restarted instance onto that retained state inside a short window.
 * This replays both decisions against a real evaluator (the handler cannot run on the JVM; its
 * wiring is pinned in RemoteAccessApiHandlerMultiPhoneTest).
 */
public class RemoteAccessPhoneStateRetentionReplayTest {
    private static final String OWNER = "AA:BB:CC:DD:EE:01";
    private static final String SECOND = "AA:BB:CC:DD:EE:02";
    private static final String MODE = "00000000-0000-0000-0000-000000000001";
    private static final long ADOPTION_WINDOW_MS = 30_000; // SOURCE_INSTANCE_ADOPTION_WINDOW_MS

    /** The daemon's PhoneState, reduced to what reset()/relay() retain or rebuild. */
    private static final class PhoneState {
        final JSONObject profile;
        final ProximityEvaluator evaluator;
        String sourceInstance = "";
        long firstConnectedElapsedMs = -1;
        long adoptInstanceUntilElapsedMs = -1;
        long generationStartedElapsedMs = -1;
        PhoneState(JSONObject profile, JSONObject effective) throws Exception {
            this.profile = new JSONObject(profile.toString());
            evaluator = new ProximityEvaluator(RemoteAccessSettings.evaluatorConfig(effective, profile));
        }
    }

    private static JSONObject profile(String address, boolean enabled, int near, int away) throws Exception {
        return new JSONObject().put("address", address).put("name", "Phone " + address.substring(15))
                .put("enabled", enabled).put("nearThreshold", near).put("awayThreshold", away);
    }

    private static JSONObject saved(long revision, JSONObject... phones) throws Exception {
        JSONArray roster = new JSONArray();
        for (JSONObject phone : phones) roster.put(phone);
        return RemoteAccessSettings.merge(RemoteAccessSettings.defaults(), new JSONObject()
                .put("enabled", true).put("advancedMode", true).put("unlockEnabled", true).put("lockEnabled", true)
                .put("dryRun", false).put("keepBluetoothOn", true).put("deviceAddress", OWNER)
                .put("phones", roster)).put("revision", revision);
    }

    /** reset()'s phone decision: retained when the shared rules and the phone's own entry are unchanged. */
    private static PhoneState afterReset(PhoneState current, JSONObject oldSaved, JSONObject newSaved,
            String address, long now) throws Exception {
        JSONObject oldEffective = RemoteAccessSettings.effective(oldSaved);
        JSONObject newEffective = RemoteAccessSettings.effective(newSaved);
        JSONObject nextProfile = RemoteAccessSettings.phone(newEffective, address);
        boolean retain = current != null
                && MultiPhoneProximityPolicy.canRetainPhoneRules(oldEffective, newEffective)
                && MultiPhoneProximityPolicy.sameProfile(current.profile, nextProfile);
        if (!retain) return new PhoneState(nextProfile, newEffective);
        current.adoptInstanceUntilElapsedMs = now + ADOPTION_WINDOW_MS;
        return current;
    }

    /** relay()'s instance-change handling: adopt inside the window, else reset exactly as before. */
    private static void onInstance(PhoneState phone, String instance, long now) {
        if (instance.equals(phone.sourceInstance)) return;
        boolean adopt = phone.adoptInstanceUntilElapsedMs >= 0 && now <= phone.adoptInstanceUntilElapsedMs
                && !phone.sourceInstance.isEmpty();
        phone.adoptInstanceUntilElapsedMs = -1;
        phone.generationStartedElapsedMs = now;
        if (!adopt) {
            phone.firstConnectedElapsedMs = -1;
            phone.evaluator.reset();
        }
        phone.sourceInstance = instance;
    }

    private static BluetoothConnectionEvidence proof(boolean connected, long at) throws Exception {
        return BluetoothConnectionEvidence.parse(new JSONObject().put("connectionReadKnown", true)
                .put("connectionState", connected ? "connected" : "disconnected")
                .put("radioReadKnown", true).put("radioOn", true)
                .put("connectionObservedAtElapsedMs", at).put("connectionModeGeneration", MODE));
    }

    private static ProximityEvaluator.Snapshot observe(PhoneState phone, long at, Integer raw, boolean connected)
            throws Exception {
        if (connected && phone.firstConnectedElapsedMs < 0) phone.firstConnectedElapsedMs = at;
        return phone.evaluator.observe(OWNER, raw, at, connected, true, proof(connected, at));
    }

    /** The owner's phone as in the 18 Oct log before the save: connected once, then confirmed disconnected. */
    private static PhoneState departedOwner(JSONObject saved) throws Exception {
        PhoneState owner = new PhoneState(RemoteAccessSettings.phone(RemoteAccessSettings.effective(saved), OWNER),
                RemoteAccessSettings.effective(saved));
        onInstance(owner, "instance-a", 0);
        observe(owner, 0, -20, true);
        observe(owner, 1_000, -20, true);
        observe(owner, 3_000, null, false);
        observe(owner, 6_000, null, false);
        ProximityEvaluator.Snapshot confirmed = observe(owner, 9_000, null, false);
        assertEquals(AWAY, confirmed.state);
        assertTrue(confirmed.lockCandidate);
        assertEquals("confirmed_disconnection", confirmed.evidenceKind);
        assertEquals(0, owner.firstConnectedElapsedMs);
        return owner;
    }

    @Test
    public void addingASecondPhoneKeepsTheOwnersAwayStateAcrossSaveAndCollectorRestart() throws Exception {
        JSONObject before = saved(7, profile(OWNER, true, -27, -29));
        JSONObject after = saved(8, profile(OWNER, true, -27, -29), profile(SECOND, true, -27, -29));
        PhoneState owner = departedOwner(before);
        long saveAt = 10_000;
        PhoneState kept = afterReset(owner, before, after, OWNER, saveAt);
        assertSame(owner, kept);
        assertEquals(0, kept.firstConnectedElapsedMs);
        // The app restarts its collector (new instanceId) two seconds later: adopted, not reset.
        onInstance(kept, "instance-b", saveAt + 2_000);
        assertEquals("instance-b", kept.sourceInstance);
        assertEquals(0, kept.firstConnectedElapsedMs);
        assertEquals(-1, kept.adoptInstanceUntilElapsedMs);
        ProximityEvaluator.Snapshot next = observe(kept, saveAt + 2_000, null, false);
        assertEquals(AWAY, next.state);
        assertTrue(next.lockCandidate);
        assertTrue(next.departureEligible);
        assertEquals("confirmed_disconnection", next.evidenceKind);
        // The newly added phone starts fresh, as before.
        PhoneState second = afterReset(null, before, after, SECOND, saveAt);
        assertEquals(UNKNOWN, second.evaluator.snapshot(saveAt).state);
        assertEquals(-1, second.firstConnectedElapsedMs);
        assertEquals("", second.sourceInstance);
    }

    @Test
    public void changingTheOwnersOwnThresholdOrASharedRuleRebuildsItsStateAsBefore() throws Exception {
        JSONObject before = saved(7, profile(OWNER, true, -27, -29));
        for (JSONObject after : new JSONObject[] {
                saved(8, profile(OWNER, true, -26, -29)),                       // its own near threshold
                saved(8, profile(OWNER, true, -27, -30)),                       // its own away threshold
                saved(8, profile(OWNER, false, -27, -29), profile(SECOND, true, -27, -29)), // its own enabled flag
                saved(8, profile(OWNER, true, -27, -29)).put("dryRun", true),   // Learning mode
                saved(8, profile(OWNER, true, -27, -29)).put("unlockTrigger", "away_to_near"), // trigger (default near since 20 Oct)
                saved(8, profile(OWNER, true, -27, -29)).put("staleMs", 6_000)}) {
            PhoneState owner = departedOwner(before);
            PhoneState rebuilt = afterReset(owner, before, after, OWNER, 10_000);
            assertNotSame(owner, rebuilt);
            assertEquals(UNKNOWN, rebuilt.evaluator.snapshot(10_000).state);
            assertEquals(-1, rebuilt.firstConnectedElapsedMs);
            assertEquals(-1, rebuilt.adoptInstanceUntilElapsedMs);
        }
        // A removed phone has no next entry and is simply gone from the roster.
        JSONObject withoutOwner = new JSONObject().put("phones", new JSONArray().put(profile(SECOND, true, -27, -29)));
        assertFalse(MultiPhoneProximityPolicy.sameProfile(departedOwner(before).profile,
                RemoteAccessSettings.phone(withoutOwner, OWNER)));
    }

    @Test
    public void anInstanceChangeOutsideTheWindowOrWithoutARetainingSaveResetsAsBefore() throws Exception {
        JSONObject before = saved(7, profile(OWNER, true, -27, -29));
        JSONObject after = saved(8, profile(OWNER, true, -27, -29), profile(SECOND, true, -27, -29));
        // Window expired: the restart arrives 31 s after the save.
        PhoneState late = afterReset(departedOwner(before), before, after, OWNER, 10_000);
        onInstance(late, "instance-b", 10_000 + ADOPTION_WINDOW_MS + 1);
        assertEquals(-1, late.firstConnectedElapsedMs);
        assertEquals(UNKNOWN, late.evaluator.snapshot(10_000 + ADOPTION_WINDOW_MS + 1).state);
        // No save at all (an app-process restart): today's reset.
        PhoneState restarted = departedOwner(before);
        onInstance(restarted, "instance-b", 12_000);
        assertEquals(-1, restarted.firstConnectedElapsedMs);
        assertEquals("disconnected_without_connection_history",
                observe(restarted, 12_000, null, false).reason);
        // Inside the window exactly once: a second instance change resets again.
        PhoneState kept = afterReset(departedOwner(before), before, after, OWNER, 10_000);
        onInstance(kept, "instance-b", 12_000);
        assertEquals(AWAY, observe(kept, 12_000, null, false).state);
        onInstance(kept, "instance-c", 14_000);
        assertEquals(UNKNOWN, kept.evaluator.snapshot(14_000).state);
        assertEquals(-1, kept.firstConnectedElapsedMs);
    }
}
