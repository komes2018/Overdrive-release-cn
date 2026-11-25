package com.overdrive.app.remoteaccess;

import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.AWAY;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.NEAR;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.TRANSITION;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.UNKNOWN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 18 Oct multi-phone fix (M1/M2): an enabled phone that is absent or unreadable no longer vetoes
 * every lock forever. Rule (a): a fresh positive native "disconnected" read is Away. Rule (b): a
 * phone that never connected in this monitor generation is Away once the monitor has run 15 s
 * (60 s before 20 Oct) with the radio On. Everything else vetoes exactly as before; the original constructors keep the
 * original verdict. Plus the retention predicates reset() uses for M2.
 */
public class MultiPhoneProximityPolicyAbsentPhoneTest {
    private static final long NOW = 3_000_000;
    private static final long STALE = 5_000;
    private static final long GRACE = MultiPhoneProximityPolicy.ABSENT_PHONE_GRACE_MS;

    /** The departing phone: fresh connected RSSI Away, as today. */
    private static MultiPhoneProximityPolicy.Observation departing() {
        return new MultiPhoneProximityPolicy.Observation(true, true, true, AWAY, NOW);
    }

    /** The departing phone after its native disconnection was confirmed (phone=2 at 18:17 in the log). */
    private static MultiPhoneProximityPolicy.Observation departedDisconnected() {
        return new MultiPhoneProximityPolicy.Observation(true, false, false, AWAY, NOW, true);
    }

    /** An absent roster member: not connected, no RSSI, evaluator Unknown, no signal timestamp. */
    private static MultiPhoneProximityPolicy.Observation absent(boolean connectionKnown, boolean radioOn,
            boolean neverConnected, long monitorStarted, long nativeObserved, long lastNear) {
        return new MultiPhoneProximityPolicy.Observation(true, false, false, UNKNOWN, -1, false,
                connectionKnown, radioOn, neverConnected, monitorStarted, nativeObserved, lastNear);
    }

    private static boolean all(MultiPhoneProximityPolicy.Observation... observations) {
        return MultiPhoneProximityPolicy.allAway(Arrays.asList(observations), NOW, STALE);
    }

    @Test
    public void grace15sAndRadioOnBeforeANeverConnectedPhoneCountsAsAway() {
        // 20 Oct (snappy locks): 15 s, down from 60 s.
        assertEquals(15_000L, GRACE);
        // Rule (b): 14.999 s after the monitor started the absent phone still vetoes; at 15 s it is Away.
        assertFalse(all(departing(), absent(false, true, true, NOW - GRACE + 1, NOW, -1)));
        assertTrue(all(departing(), absent(false, true, true, NOW - GRACE, NOW, -1)));
        assertTrue(all(departing(), absent(false, true, true, NOW - 10 * GRACE, NOW - 2_000, -1)));
        // Roster order does not matter.
        assertTrue(all(absent(false, true, true, NOW - GRACE, NOW, -1), departing()));
        // An unknown or future monitor start never satisfies the grace.
        assertFalse(all(departing(), absent(false, true, true, -1, NOW, -1)));
        assertFalse(all(departing(), absent(false, true, true, NOW + 1, NOW, -1)));
    }

    @Test
    public void positiveDisconnectionReadIsAwayImmediately() {
        // Rule (a): the monitor just started and the phone connected earlier; the fresh native read says disconnected.
        assertTrue(all(departing(), absent(true, true, false, NOW, NOW, -1)));
        assertTrue(all(departing(), absent(true, true, true, NOW, NOW, NOW - STALE - 1)));
        assertTrue(all(departedDisconnected(), absent(true, true, false, NOW, NOW - STALE, -1)));
        // A phone that connected once and now lacks a positive read vetoes, however long the monitor ran.
        assertFalse(all(departing(), absent(false, true, false, NOW - 10 * GRACE, NOW, -1)));
    }

    @Test
    public void aConnectedOtherPhoneStillBlocksWhateverItsStateOrNativeRead() {
        for (ProximityEvaluator.State state : new ProximityEvaluator.State[] {NEAR, TRANSITION, UNKNOWN, AWAY, null}) {
            MultiPhoneProximityPolicy.Observation connected = new MultiPhoneProximityPolicy.Observation(
                    true, true, state == AWAY, state, NOW, false, true, true, false, NOW - GRACE, NOW, -1);
            // A connected Away phone with raw RSSI is today's departure evidence, nothing new.
            assertEquals(state == AWAY, all(departing(), connected));
        }
        assertFalse(all(departing(), new MultiPhoneProximityPolicy.Observation(
                true, true, false, UNKNOWN, NOW, false, true, true, true, NOW - GRACE, NOW, -1)));
    }

    @Test
    public void freshNearOrTransitionEvidenceBlocksBothRules() {
        // The last connected non-Away observation within staleMs vetoes; one millisecond older does not.
        assertFalse(all(departing(), absent(true, true, false, NOW - GRACE, NOW, NOW - STALE)));
        assertTrue(all(departing(), absent(true, true, false, NOW - GRACE, NOW, NOW - STALE - 1)));
        assertFalse(all(departing(), absent(false, true, true, NOW - GRACE, NOW, NOW - STALE)));
        assertTrue(all(departing(), absent(false, true, true, NOW - GRACE, NOW, NOW - STALE - 1)));
        assertFalse(all(departing(), absent(true, true, true, NOW - GRACE, NOW, NOW + 1)));
        // A disconnected phone whose signal still says Near or Transition vetoes.
        for (ProximityEvaluator.State state : new ProximityEvaluator.State[] {NEAR, TRANSITION}) {
            assertFalse(all(departing(), new MultiPhoneProximityPolicy.Observation(true, false, false, state,
                    NOW, false, true, true, true, NOW - GRACE, NOW, -1)));
        }
        // Raw RSSI present without a connection is contradictory evidence and vetoes.
        assertFalse(all(departing(), new MultiPhoneProximityPolicy.Observation(true, false, true, UNKNOWN,
                NOW, false, true, true, true, NOW - GRACE, NOW, -1)));
    }

    @Test
    public void radioOffOrUnknownWithoutAPositiveDisconnectionReadBlocks() {
        assertFalse(all(departing(), absent(false, false, true, NOW - GRACE, NOW, -1)));
        assertFalse(all(departing(), absent(false, false, true, NOW - 10 * GRACE, NOW, -1)));
        // A "known disconnection" with the radio Off is contradictory and fails closed.
        assertFalse(all(departing(), absent(true, false, false, NOW - GRACE, NOW, -1)));
    }

    @Test
    public void staleMissingOrFutureNativeReadsBlockBothRules() {
        for (long nativeObserved : new long[] {-1, NOW + 1, NOW - STALE - 1}) {
            assertFalse(all(departing(), absent(true, true, false, NOW - GRACE, nativeObserved, -1)));
            assertFalse(all(departing(), absent(false, true, true, NOW - GRACE, nativeObserved, -1)));
        }
        assertTrue(all(departing(), absent(true, true, false, NOW - GRACE, NOW - STALE, -1)));
        assertTrue(all(departing(), absent(false, true, true, NOW - GRACE, NOW - STALE, -1)));
    }

    @Test
    public void originalConstructorsKeepTheOriginalVerdictForAbsentPhones() {
        MultiPhoneProximityPolicy.Observation legacy =
                new MultiPhoneProximityPolicy.Observation(true, false, false, UNKNOWN, -1);
        MultiPhoneProximityPolicy.Observation legacyWithProof =
                new MultiPhoneProximityPolicy.Observation(true, false, false, UNKNOWN, NOW, false);
        assertFalse(all(departing(), legacy));
        assertFalse(all(departing(), legacyWithProof));
        assertFalse(legacy.connectionKnown);
        assertFalse(legacy.radioOn);
        assertFalse(legacy.neverConnected);
        assertEquals(-1, legacy.monitorStartedElapsedMs);
        assertEquals(-1, legacy.nativeObservedMs);
        assertEquals(-1, legacy.lastNearEvidenceMs);
        assertFalse(MultiPhoneProximityPolicy.absentAway(legacy, NOW, STALE));
        assertFalse(MultiPhoneProximityPolicy.absentAway(null, NOW, STALE));
        assertFalse(MultiPhoneProximityPolicy.awayForLock(null, NOW, STALE));
        assertFalse(MultiPhoneProximityPolicy.awayForLock(departing(), -1, STALE));
        assertFalse(MultiPhoneProximityPolicy.awayForLock(departing(), NOW, 0));
        assertTrue(MultiPhoneProximityPolicy.awayForLock(departing(), NOW, STALE));
        assertTrue(MultiPhoneProximityPolicy.awayForLock(departedDisconnected(), NOW, STALE));
    }

    @Test
    public void disabledAbsentPhonesAreIgnoredAndAnAbsentOnlyRosterNeedsItsOwnDeparture() {
        MultiPhoneProximityPolicy.Observation disabled = new MultiPhoneProximityPolicy.Observation(
                false, false, false, UNKNOWN, -1, false, false, false, true, NOW - GRACE, NOW, -1);
        assertTrue(all(departing(), disabled));
        // Rule (b) alone can satisfy allAway, but a lock still needs the departing phone's own
        // confirmed departure (BluetoothProximityDecisionGuard.allows), so with a single phone
        // there is never a second phone to be "absent": single-phone outcomes are unchanged.
        assertTrue(all(absent(false, true, true, NOW - GRACE, NOW, -1)));
        assertFalse(MultiPhoneProximityPolicy.canLock(true, true, false,
                Arrays.asList(departing(), absent(true, true, false, NOW - GRACE, NOW, -1)), NOW, STALE));
        assertTrue(MultiPhoneProximityPolicy.canLock(true, true, true,
                Arrays.asList(departing(), absent(true, true, false, NOW - GRACE, NOW, -1)), NOW, STALE));
        assertTrue(MultiPhoneProximityPolicy.canLock(true, false, false,
                Arrays.asList(departing(), absent(false, true, true, NOW - GRACE, NOW, -1)), NOW, STALE));
    }

    /**
     * Replay of the 18 Oct DiLink 3 log: the owner's phone=1 read AWAY/confirmed_disconnection at
     * 17:47:41, a settings save added phone=2 at 17:47:49 and rebuilt every PhoneState, and phone=1
     * never connected again (connectionKnown=false radioKnown=true radioOn=false in the evaluator's
     * view, while the app's reader kept reporting a positive "disconnected" read). phone=2 was
     * connected Away (stable_away, raw -41/-47) for the departures at 18:15:54 and 18:16:55 and in
     * confirmed_disconnection for the one at 18:17:55. All three locks were blocked; now allowed.
     */
    @Test
    public void replayOfThe18OctDepartureDecisionsIsAllowed() {
        long saved = 0;                       // 17:47:49, phone=1's new generation starts
        long staleMs = 5_000;
        long[] decisions = {28 * 60_000 + 5_000, 29 * 60_000 + 6_000, 30 * 60_000 + 6_000}; // 18:15:54 / 18:16:55 / 18:17:55
        for (int i = 0; i < decisions.length; i++) {
            long now = decisions[i];
            MultiPhoneProximityPolicy.Observation phone2 = i < 2
                    ? new MultiPhoneProximityPolicy.Observation(true, true, true, AWAY, now - 1_000)
                    : new MultiPhoneProximityPolicy.Observation(true, false, false, AWAY, now - 1_000, true);
            // The reader's positive read (rule a), as the log's healthy proof shows ...
            MultiPhoneProximityPolicy.Observation phone1Read = new MultiPhoneProximityPolicy.Observation(
                    true, false, false, UNKNOWN, -1, false, true, true, true, saved, now - 2_500, -1);
            // ... and the unreadable variant (rule b), 28 minutes after the save with the radio On.
            MultiPhoneProximityPolicy.Observation phone1Unread = new MultiPhoneProximityPolicy.Observation(
                    true, false, false, UNKNOWN, -1, false, false, true, true, saved, now - 2_500, -1);
            assertTrue(MultiPhoneProximityPolicy.allAway(Arrays.asList(phone1Read, phone2), now, staleMs));
            assertTrue(MultiPhoneProximityPolicy.allAway(Arrays.asList(phone1Unread, phone2), now, staleMs));
            assertTrue(MultiPhoneProximityPolicy.canLock(true, false, false,
                    Arrays.asList(phone1Unread, phone2), now, staleMs));
            // Before the fix the same roster vetoed: the original constructor carries no absent-phone evidence.
            assertFalse(MultiPhoneProximityPolicy.allAway(Arrays.asList(
                    new MultiPhoneProximityPolicy.Observation(true, false, false, UNKNOWN, -1), phone2), now, staleMs));
        }
    }

    // ── M2 retention predicates used by reset() ──────────────────────────────────────────────────

    private static JSONObject settings(boolean enabled, boolean dryRun, String trigger, long staleMs) throws Exception {
        return new JSONObject().put("enabled", enabled).put("dryRun", dryRun).put("unlockTrigger", trigger)
                .put("staleMs", staleMs).put("windowMs", 15_000).put("dwellMs", 4_000)
                .put("nearWindowMs", 4_000).put("nearDwellMs", 2_000).put("revision", 7)
                .put("keepBluetoothOn", true).put("keyFobEnabled", false).put("cooldownMs", 30_000)
                .put("phones", new JSONArray().put(profile("AA:BB:CC:DD:EE:01", "Owner", true, -27, -29)));
    }

    private static JSONObject profile(String address, String name, boolean enabled, int near, int away) throws Exception {
        return new JSONObject().put("address", address).put("name", name).put("enabled", enabled)
                .put("nearThreshold", near).put("awayThreshold", away);
    }

    @Test
    public void phoneRulesAreRetainedAcrossRosterRevisionFobAndRadioChangesOnly() throws Exception {
        JSONObject base = settings(true, false, "away_to_near", 5_000);
        // Another phone added, the revision moved, fob rules and the shared radio switch changed: retained.
        JSONObject roster = settings(true, false, "away_to_near", 5_000).put("revision", 8)
                .put("keyFobEnabled", true).put("keepBluetoothOn", false).put("cooldownMs", 60_000);
        roster.getJSONArray("phones").put(profile("AA:BB:CC:DD:EE:02", "Second", true, -27, -29));
        assertTrue(MultiPhoneProximityPolicy.canRetainPhoneRules(base, roster));
        // Master switch, Learning mode, unlock trigger or any evaluator timing: rebuilt.
        assertFalse(MultiPhoneProximityPolicy.canRetainPhoneRules(base, settings(false, false, "away_to_near", 5_000)));
        assertFalse(MultiPhoneProximityPolicy.canRetainPhoneRules(base, settings(true, true, "away_to_near", 5_000)));
        assertFalse(MultiPhoneProximityPolicy.canRetainPhoneRules(base, settings(true, false, "near", 5_000)));
        assertFalse(MultiPhoneProximityPolicy.canRetainPhoneRules(base, settings(true, false, "away_to_near", 6_000)));
        for (String key : new String[] {"windowMs", "dwellMs", "nearWindowMs", "nearDwellMs"})
            assertFalse(key, MultiPhoneProximityPolicy.canRetainPhoneRules(base,
                    settings(true, false, "away_to_near", 5_000).put(key, base.getLong(key) + 1_000)));
        assertFalse(MultiPhoneProximityPolicy.canRetainPhoneRules(null, base));
        assertFalse(MultiPhoneProximityPolicy.canRetainPhoneRules(base, null));
    }

    @Test
    public void aPhoneIsRetainedOnlyWhenItsOwnEntryIsUnchanged() throws Exception {
        JSONObject owner = profile("AA:BB:CC:DD:EE:01", "Owner", true, -27, -29);
        assertTrue(MultiPhoneProximityPolicy.sameProfile(owner, profile("AA:BB:CC:DD:EE:01", "Owner", true, -27, -29)));
        assertTrue(MultiPhoneProximityPolicy.sameProfile(owner, profile("aa:bb:cc:dd:ee:01", "Owner", true, -27, -29)));
        // Its own threshold, enabled flag, name or address changed: a fresh state as before.
        assertFalse(MultiPhoneProximityPolicy.sameProfile(owner, profile("AA:BB:CC:DD:EE:01", "Owner", true, -26, -29)));
        assertFalse(MultiPhoneProximityPolicy.sameProfile(owner, profile("AA:BB:CC:DD:EE:01", "Owner", true, -27, -30)));
        assertFalse(MultiPhoneProximityPolicy.sameProfile(owner, profile("AA:BB:CC:DD:EE:01", "Owner", false, -27, -29)));
        assertFalse(MultiPhoneProximityPolicy.sameProfile(owner, profile("AA:BB:CC:DD:EE:01", "Renamed", true, -27, -29)));
        assertFalse(MultiPhoneProximityPolicy.sameProfile(owner, profile("AA:BB:CC:DD:EE:02", "Owner", true, -27, -29)));
        assertFalse(MultiPhoneProximityPolicy.sameProfile(owner, null));
        assertFalse(MultiPhoneProximityPolicy.sameProfile(null, owner));
        // A removed phone has no next entry.
        assertFalse(MultiPhoneProximityPolicy.sameProfile(owner,
                RemoteAccessSettings.phone(settings(true, false, "away_to_near", 5_000), "AA:BB:CC:DD:EE:02")));
    }
}
