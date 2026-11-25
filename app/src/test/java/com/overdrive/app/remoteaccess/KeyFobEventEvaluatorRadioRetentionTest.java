package com.overdrive.app.remoteaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * T5: the daemon's {@code reset()} keeps the keyfob EVENT evaluator across a radio-only change
 * (the shared parked Bluetooth switch) under the same predicate that already keeps the presence
 * evaluator, so an armed {@code away_to_near} rail is not disarmed by the toggle. A1 widens this:
 * a generic save that changes no fob rule (master switch, fob detection, fob locking, Learning
 * mode, unlock trigger) also keeps it and the evaluator adopts the new revision in place; a fob
 * rule change still rebuilds it. This test replays reset()'s decision against a real evaluator
 * (the handler itself cannot run on the JVM; its wiring is pinned in
 * RemoteAccessApiHandlerBluetoothAliasApplyTest) and the app worker's matching predicate.
 */
public class KeyFobEventEvaluatorRadioRetentionTest {
    private static final String MONITOR = "app/src/main/java/com/overdrive/app/remoteaccess/KeyFobAccessMonitor.java";

    private static JSONObject saved(boolean keepBluetoothOn, long revision, String unlockTrigger,
                                    boolean keyFobLockEnabled) throws Exception {
        // 19 Oct: the fob rail's Away→Near rule is an Advanced opt-in (the default, near, needs no
        // arming); these arming-retention tests save it explicitly, as reset() builds from it.
        return RemoteAccessSettings.merge(RemoteAccessSettings.defaults(), new JSONObject()
                .put("enabled", true).put("keyFobEnabled", true).put("keyFobLockEnabled", keyFobLockEnabled)
                .put("unlockEnabled", true).put("unlockTrigger", unlockTrigger)
                .put("advancedMode", true).put("keyFobUnlockTrigger", "away_to_near")
                .put("keepBluetoothOn", keepBluetoothOn)).put("revision", revision);
    }

    private static JSONObject observation(long serial, long event, int mask, long elapsed, long revision)
            throws Exception {
        return new JSONObject().put("revision", revision).put("instanceId", "source-a")
                .put("sourceGeneration", 1L).put("observationSequence", serial)
                .put("eventSequence", event).put("rawMask", mask)
                .put("eventObservedAtElapsedMs", event == 0 ? -1L : elapsed)
                .put("runtime", new JSONObject().put("active", true).put("registered", true)
                        .put("tableRegistered", true).put("reason", "listening"))
                .put("scope", new JSONObject().put("power", 0).put("driverLock", 2)
                        .put("observedAtElapsedMs", elapsed));
    }

    /**
     * reset()'s construction of the event evaluator, from the effective settings. 19 Oct: the mode
     * comes from the fob rail's own {@code keyFobUnlockTrigger} (default near), no longer from the
     * phones' {@code unlockTrigger}; these tests save the Away→Near fob rule (Advanced) explicitly
     * because arming only matters in that mode.
     */
    private static KeyFobEventEvaluator build(JSONObject effective) {
        return new KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger.fromSetting(
                effective.optString("keyFobUnlockTrigger", "near")), effective.optLong("revision"),
                effective.optBoolean("keyFobLockEnabled"));
    }

    /**
     * reset()'s retention decision for the event evaluator: the radio-only predicate shared with
     * the presence evaluator, or (A1) unchanged fob rules; a retained evaluator adopts the revision.
     */
    private static KeyFobEventEvaluator afterReset(KeyFobEventEvaluator current, JSONObject oldSaved,
                                                   JSONObject newSaved) {
        JSONObject oldEffective = RemoteAccessSettings.effective(oldSaved);
        JSONObject newEffective = RemoteAccessSettings.effective(newSaved);
        boolean radioOnlyChange = KeyFobPresenceEvaluator.canRetainAcrossRadioChange(
                oldSaved, oldEffective, newSaved, newEffective);
        boolean fobRulesUnchanged = KeyFobEventEvaluator.canRetainAcrossSettingsChange(oldEffective, newEffective);
        boolean retainKeyFob = current != null && (radioOnlyChange || fobRulesUnchanged);
        if (!retainKeyFob) return build(newEffective);
        current.adoptRevision(newEffective.optLong("revision"));
        return current;
    }

    private static KeyFobEventEvaluator armed(JSONObject saved) throws Exception {
        KeyFobEventEvaluator evaluator = build(RemoteAccessSettings.effective(saved));
        long revision = saved.getLong("revision");
        // A fresh absence event (mask 4) arms the away_to_near rail.
        KeyFobEventEvaluator.Snapshot away = evaluator.observe(
                KeyFobEventEvaluator.Observation.parse(observation(1, 1, 4, 1_000L, revision)), 1_000L, true);
        assertTrue(away.armed);
        assertEquals("AWAY", away.lastKnownState);
        return evaluator;
    }

    @Test
    public void radioOnlyChangeKeepsTheArmedEvaluator() throws Exception {
        JSONObject before = saved(false, 7L, "away_to_near", false);
        JSONObject after = saved(true, 7L, "away_to_near", false);
        KeyFobEventEvaluator evaluator = armed(before);
        KeyFobEventEvaluator kept = afterReset(evaluator, before, after);
        assertSame(evaluator, kept);
        // Still armed on the next heartbeat, and the next detection event is an arrival.
        KeyFobEventEvaluator.Snapshot heartbeat = kept.observe(
                KeyFobEventEvaluator.Observation.parse(observation(2, 1, 4, 1_000L, 7L)), 1_800L, true);
        assertTrue(heartbeat.armed);
        assertFalse(heartbeat.arrival);
        KeyFobEventEvaluator.Snapshot near = kept.observe(
                KeyFobEventEvaluator.Observation.parse(observation(3, 2, 8, 2_500L, 7L)), 2_500L, true);
        assertTrue(near.arrival);
        // The opposite direction (ON -> OFF) is a radio-only change too.
        KeyFobEventEvaluator again = armed(after);
        assertSame(again, afterReset(again, after, before));
        // A radio-only change is the same predicate the presence evaluator already uses.
        assertTrue(KeyFobPresenceEvaluator.canRetainAcrossRadioChange(before,
                RemoteAccessSettings.effective(before), after, RemoteAccessSettings.effective(after)));
    }

    @Test
    public void anyOtherChangeRebuildsTheEvaluatorAsBefore() throws Exception {
        JSONObject before = saved(true, 7L, "away_to_near", false);
        // Mode, lock consent, Learning mode: each is a fob rule, so each rebuilds and disarms.
        // (A revision-only save and a radio+revision save now retain: see the A1 tests below.)
        JSONObject[] others = {
                saved(true, 7L, "near", false),
                saved(true, 7L, "away_to_near", true),
                saved(true, 7L, "away_to_near", false).put("dryRun", false),
                saved(true, 8L, "near", false)   // revision AND a fob rule: the rule wins
        };
        for (JSONObject after : others) {
            KeyFobEventEvaluator evaluator = armed(before);
            KeyFobEventEvaluator rebuilt = afterReset(evaluator, before, after);
            assertNotSame(after.toString(), evaluator, rebuilt);
            assertFalse(after.toString(), rebuilt.current(1_500L).armed);
            assertFalse(after.toString(), KeyFobPresenceEvaluator.canRetainAcrossRadioChange(before,
                    RemoteAccessSettings.effective(before), after, RemoteAccessSettings.effective(after)));
        }
        // No evaluator yet (first reset): built regardless of the predicate.
        KeyFobEventEvaluator first = afterReset(null, before, saved(false, 7L, "away_to_near", false));
        assertFalse(first.current(1_500L).armed);
    }

    @Test
    public void genericSaveWithUnchangedFobRulesKeepsTheArmedEvaluatorAndAdoptsTheRevision() throws Exception {
        JSONObject before = saved(true, 7L, "away_to_near", false);
        // A generic save: revision bump plus unrelated settings (lock master, cooldown, phones).
        JSONObject after = saved(true, 8L, "away_to_near", false).put("lockEnabled", true)
                .put("cooldownMs", 60_000L)
                .put("phones", new org.json.JSONArray().put(new JSONObject().put("enabled", true)
                        .put("address", "AA:BB:CC:DD:EE:FF").put("name", "Phone")));
        assertTrue(KeyFobEventEvaluator.canRetainAcrossSettingsChange(
                RemoteAccessSettings.effective(before), RemoteAccessSettings.effective(after)));
        assertFalse(KeyFobPresenceEvaluator.canRetainAcrossRadioChange(before,
                RemoteAccessSettings.effective(before), after, RemoteAccessSettings.effective(after)));
        KeyFobEventEvaluator evaluator = armed(before);
        assertSame(evaluator, afterReset(evaluator, before, after));
        // The retained evaluator follows the new revision: still armed, and the next 8 is an arrival.
        KeyFobEventEvaluator.Snapshot heartbeat = evaluator.observe(
                KeyFobEventEvaluator.Observation.parse(observation(2, 1, 4, 1_000L, 8L)), 1_800L, true);
        assertTrue(heartbeat.active);
        assertTrue(heartbeat.armed);
        assertTrue(evaluator.observe(
                KeyFobEventEvaluator.Observation.parse(observation(3, 2, 8, 2_500L, 8L)), 2_500L, true).arrival);
        // Radio AND revision together is no longer a rebuild either: both halves retain.
        KeyFobEventEvaluator radioAndRevision = armed(before);
        assertSame(radioAndRevision, afterReset(radioAndRevision, before, saved(false, 8L, "away_to_near", false)));
        assertTrue(radioAndRevision.observe(
                KeyFobEventEvaluator.Observation.parse(observation(2, 1, 4, 1_000L, 8L)), 1_800L, true).armed);
    }

    @Test
    public void fobRuleChangesStillRebuildTheEvaluator() throws Exception {
        JSONObject before = saved(true, 7L, "away_to_near", false);
        JSONObject[] ruleChanges = {
                saved(true, 8L, "near", false),
                saved(true, 8L, "away_to_near", true),
                saved(true, 8L, "away_to_near", false).put("dryRun", false),
                saved(true, 8L, "away_to_near", false).put("enabled", false),
                saved(true, 8L, "away_to_near", false).put("keyFobEnabled", false).put("keyFobLockEnabled", false)
        };
        for (JSONObject after : ruleChanges) {
            assertFalse(after.toString(), KeyFobEventEvaluator.canRetainAcrossSettingsChange(
                    RemoteAccessSettings.effective(before), RemoteAccessSettings.effective(after)));
            KeyFobEventEvaluator evaluator = armed(before);
            KeyFobEventEvaluator rebuilt = afterReset(evaluator, before, after);
            assertNotSame(after.toString(), evaluator, rebuilt);
            assertFalse(after.toString(), rebuilt.current(1_500L).armed);
        }
        assertFalse(KeyFobEventEvaluator.canRetainAcrossSettingsChange(null, RemoteAccessSettings.effective(before)));
        assertFalse(KeyFobEventEvaluator.canRetainAcrossSettingsChange(RemoteAccessSettings.effective(before), null));
    }

    @Test
    public void cycleOneReplayTheFirstDetectionAfterTheSaveNowUnlocks() throws Exception {
        // Relative to the lock confirmed at 14:07:43.166: the car's 4 at +10.8 s armed the rail, the
        // generic save at +20.1 s bumped the revision, and the lone 8 at +35.6 s was ignored.
        JSONObject before = saved(true, 8L, "away_to_near", true);
        JSONObject after = saved(true, 9L, "away_to_near", true).put("cooldownMs", 60_000L);
        KeyFobEventEvaluator evaluator = build(RemoteAccessSettings.effective(before));
        KeyFobEventEvaluator contrast = build(RemoteAccessSettings.effective(after));
        long serial = 0;
        for (long now = 0; now < 10_824; now += 2_000)
            evaluator.observe(KeyFobEventEvaluator.Observation.parse(observation(++serial, 0, -1, now, 8L)), now, true);
        assertTrue(evaluator.observe(
                KeyFobEventEvaluator.Observation.parse(observation(++serial, 1, 4, 10_824L, 8L)), 10_824L, true).armed);
        for (long now = 12_000; now < 20_110; now += 2_000)
            assertTrue(evaluator.observe(
                    KeyFobEventEvaluator.Observation.parse(heartbeat(++serial, 1, 4, 10_824L, now, 8L)), now, true).armed);
        assertSame(evaluator, afterReset(evaluator, before, after));
        for (long now = 22_000; now < 35_617; now += 2_000) {
            JSONObject envelope = heartbeat(++serial, 1, 4, 10_824L, now, 9L);
            assertTrue(evaluator.observe(KeyFobEventEvaluator.Observation.parse(envelope), now, true).armed);
            contrast.observe(KeyFobEventEvaluator.Observation.parse(envelope), now, true);
        }
        JSONObject detection = observation(++serial, 2, 8, 35_617L, 9L);
        assertTrue(evaluator.observe(KeyFobEventEvaluator.Observation.parse(detection), 35_617L, true).arrival);
        // The rebuilt evaluator of the old code never saw the 4, so the same frame is not an arrival.
        assertFalse(contrast.observe(KeyFobEventEvaluator.Observation.parse(detection), 35_617L, true).arrival);
    }

    private static JSONObject heartbeat(long serial, long event, int mask, long eventElapsed, long now,
                                        long revision) throws Exception {
        JSONObject envelope = observation(serial, event, mask, eventElapsed, revision);
        envelope.getJSONObject("scope").put("observedAtElapsedMs", now);
        return envelope;
    }

    /**
     * App side of A1: the worker restarts only when a fob rule changed (consent, locking opt-in,
     * effective window); a revision-only save is adopted in place. KeyFobAccessMonitor's statics
     * have no Android calls, so they run on the JVM; the in-place adoption is pinned by source.
     */
    @Test
    public void appWorkerKeepsItsInstanceAcrossARevisionOnlyChangeAndRestartsOnFobRuleChanges() throws Exception {
        JSONObject before = saved(true, 7L, "away_to_near", false);
        JSONObject[] sameRules = {
                saved(true, 8L, "away_to_near", false),
                saved(true, 7L, "near", false),
                saved(true, 7L, "away_to_near", false).put("dryRun", false),
                saved(true, 7L, "away_to_near", false).put("unlockEnabled", false),
                saved(true, 7L, "away_to_near", false).put("lockEnabled", true),
                saved(false, 7L, "away_to_near", false),
                saved(true, 7L, "away_to_near", false).put("cooldownMs", 60_000L),
                saved(true, 7L, "away_to_near", false).put("phones", new org.json.JSONArray()
                        .put(new JSONObject().put("enabled", true).put("address", "AA:BB:CC:DD:EE:FF").put("name", "P")))
        };
        for (JSONObject after : sameRules)
            assertTrue(after.toString(), KeyFobAccessMonitor.sameFobRules(before, after));
        JSONObject[] ruleChanges = {
                saved(true, 7L, "away_to_near", false).put("enabled", false),
                saved(true, 7L, "away_to_near", false).put("keyFobEnabled", false),
                saved(true, 7L, "away_to_near", true),
                saved(true, 7L, "away_to_near", false).put("advancedMode", true).put("keyFobLockWindowMs", 20_000L)
        };
        for (JSONObject after : ruleChanges)
            assertFalse(after.toString(), KeyFobAccessMonitor.sameFobRules(before, after));
        // The effective window: normal mode forces the default even when a custom value is saved.
        // (saved() is Advanced since 19 Oct, so normal mode is selected explicitly here; the default is 5 s
        // since 20 Oct.)
        assertEquals(5_000L, KeyFobAccessMonitor.lockWindowMs(
                before.put("keyFobLockWindowMs", 30_000L).put("advancedMode", false)));
        assertEquals(30_000L, KeyFobAccessMonitor.lockWindowMs(before.put("advancedMode", true)));
        // Wiring pins: the config is adopted in place in apply() and stillConsented(); the
        // heartbeat still publishes the live revision.
        String monitor = readRepositoryFile(MONITOR);
        assertTrue(monitor.contains("private volatile JSONObject config;"));
        assertTrue(monitor.contains("else if (!sameConfig(settings, instance.config)) instance.config = settings;"));
        assertTrue(monitor.contains("if (instance != null && (!enabled || !sameFobRules(settings, instance.config)"));
        String stillConsented = monitor.substring(monitor.indexOf("    private boolean stillConsented() {"));
        stillConsented = stillConsented.substring(0, stillConsented.indexOf("\n    }\n"));
        assertTrue(stillConsented.contains("if (!sameFobRules(live, config)) return false;"));
        assertTrue(stillConsented.contains(
                "if (live.optLong(\"revision\") != config.optLong(\"revision\")) config = live;"));
        String publish = monitor.substring(monitor.indexOf("    private boolean publish(KeyFobEventQueue.Event event) {"));
        publish = publish.substring(0, publish.indexOf("\n    }\n"));
        assertTrue(publish.contains(".put(\"revision\", config.optLong(\"revision\"))"));
        // The search spacing follows the effective window (B2).
        assertTrue(monitor.contains("KeyFobPresenceEvaluator.pollIntervalMs(lockWindowMs(settings))"));
    }

    private static String readRepositoryFile(String relative) throws Exception {
        java.nio.file.Path path = java.nio.file.Paths.get(System.getProperty("user.dir"), relative);
        if (!java.nio.file.Files.exists(path))
            path = java.nio.file.Paths.get(System.getProperty("user.dir")).getParent().resolve(relative);
        return new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 19 Oct: the fob rail's own trigger selects the evaluator's mode, so a change of it is a fob
     * rule change — the A1 predicate rebuilds (and disarms) the evaluator; the rebuilt one runs the
     * new mode. The handler constructs from {@code keyFobUnlockTrigger}, not the phones' trigger.
     */
    @Test
    public void keyFobUnlockTriggerChangeRebuildsTheEvaluatorInTheNewMode() throws Exception {
        JSONObject awayToNear = saved(true, 7L, "away_to_near", false);
        JSONObject near = saved(true, 8L, "away_to_near", false).put("keyFobUnlockTrigger", "near");
        assertEquals("away_to_near", RemoteAccessSettings.effective(awayToNear).getString("keyFobUnlockTrigger"));
        assertEquals("near", RemoteAccessSettings.effective(near).getString("keyFobUnlockTrigger"));
        assertFalse(KeyFobEventEvaluator.canRetainAcrossSettingsChange(
                RemoteAccessSettings.effective(awayToNear), RemoteAccessSettings.effective(near)));
        assertFalse(KeyFobEventEvaluator.canRetainAcrossSettingsChange(
                RemoteAccessSettings.effective(near), RemoteAccessSettings.effective(awayToNear)));
        KeyFobEventEvaluator evaluator = armed(awayToNear);
        KeyFobEventEvaluator rebuilt = afterReset(evaluator, awayToNear, near);
        assertNotSame(evaluator, rebuilt);
        assertFalse(rebuilt.current(1_500L).armed);
        // The rebuilt evaluator runs the new mode: the first fresh detection unlocks without arming.
        rebuilt.observe(KeyFobEventEvaluator.Observation.parse(observation(1, 0, -1, 1_000L, 8L)), 1_000L, true);
        assertTrue(rebuilt.observe(
                KeyFobEventEvaluator.Observation.parse(observation(2, 1, 8, 1_500L, 8L)), 1_500L, true).arrival);
        // Leaving Advanced mode forces the default (near): the effective trigger changed, so rebuild.
        JSONObject normal = saved(true, 8L, "away_to_near", false).put("advancedMode", false);
        assertEquals("near", RemoteAccessSettings.effective(normal).getString("keyFobUnlockTrigger"));
        assertFalse(KeyFobEventEvaluator.canRetainAcrossSettingsChange(
                RemoteAccessSettings.effective(awayToNear), RemoteAccessSettings.effective(normal)));
        KeyFobEventEvaluator advanced = armed(awayToNear);
        assertNotSame(advanced, afterReset(advanced, awayToNear, normal));
        // Same fob trigger plus a revision bump still retains (A1 unchanged).
        KeyFobEventEvaluator kept = armed(awayToNear);
        assertSame(kept, afterReset(kept, awayToNear, saved(true, 8L, "away_to_near", false)));
        // Wiring pin: reset() builds the evaluator from keyFobUnlockTrigger (default near).
        String handler = readRepositoryFile(HANDLER);
        assertTrue(handler.contains("            keyFob = new KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger.fromSetting(\n"
                + "                    effectiveConfig.optString(\"keyFobUnlockTrigger\", \"near\")), effectiveConfig.optLong(\"revision\"),\n"
                + "                    effectiveConfig.optBoolean(\"keyFobLockEnabled\"));"));
        assertFalse(handler.contains("effectiveConfig.optString(\"unlockTrigger\", \"away_to_near\")), effectiveConfig.optLong(\"revision\")"));
        String evaluatorSource = readRepositoryFile(EVALUATOR);
        assertTrue(evaluatorSource.contains("&& oldEffective.optString(\"keyFobUnlockTrigger\", \"near\")\n"
                + "                            .equals(newEffective.optString(\"keyFobUnlockTrigger\", \"near\"));"));
    }

    private static final String HANDLER = "app/src/main/java/com/overdrive/app/server/RemoteAccessApiHandler.java";
    private static final String EVALUATOR = "app/src/main/java/com/overdrive/app/remoteaccess/KeyFobEventEvaluator.java";

    @Test
    public void radioOnlyChangeLeavesEveryConstructorArgumentUnchanged() throws Exception {
        // The event evaluator is built from mode, revision and lock consent only; a radio-only
        // change (keepBluetoothOn) touches none of them, so retaining the instance is exact.
        JSONObject before = saved(false, 7L, "near", true);
        JSONObject after = saved(true, 7L, "near", true);
        JSONObject beforeEffective = RemoteAccessSettings.effective(before);
        JSONObject afterEffective = RemoteAccessSettings.effective(after);
        assertEquals(beforeEffective.optString("unlockTrigger"), afterEffective.optString("unlockTrigger"));
        assertEquals(beforeEffective.optString("keyFobUnlockTrigger"), afterEffective.optString("keyFobUnlockTrigger"));
        assertEquals(beforeEffective.optLong("revision"), afterEffective.optLong("revision"));
        assertEquals(beforeEffective.optBoolean("keyFobLockEnabled"), afterEffective.optBoolean("keyFobLockEnabled"));
        assertFalse(beforeEffective.optBoolean("keepBluetoothOn"));
        assertTrue(afterEffective.optBoolean("keepBluetoothOn"));
    }
}
