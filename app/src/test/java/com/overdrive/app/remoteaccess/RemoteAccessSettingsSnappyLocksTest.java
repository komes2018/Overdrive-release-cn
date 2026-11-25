package com.overdrive.app.remoteaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 20 Oct snappy locks, settings half (C1/C3/C5): the phones' {@code unlockTrigger} defaults to
 * {@code near} with the {@code unlockTriggerExplicit} migration; {@code lockWaitsForKeyAbsence} (off)
 * and {@code keyFobLockSamples} (1, range 1-3); the 5 s key fob window with the
 * {@code keyFobLockExplicit} migration; normal mode forcing every default. Style of
 * {@link RemoteAccessSettingsSnappyTimingTest}.
 */
public class RemoteAccessSettingsSnappyLocksTest {
    private static final String PHONE = "AA:BB:CC:DD:EE:01";

    // ── C1: phones unlock on Near by default ─────────────────────────────────────────────────────

    @Test
    public void phonesUnlockOnNearByDefaultAndTheMarkerIsNotADefault() throws Exception {
        JSONObject defaults = RemoteAccessSettings.defaults();
        assertEquals("near", defaults.getString("unlockTrigger"));
        assertFalse(defaults.has(RemoteAccessSettings.UNLOCK_TRIGGER_EXPLICIT_KEY));
        assertEquals(ProximityEvaluator.UnlockTrigger.NEAR, RemoteAccessSettings.evaluatorConfig(defaults).unlockTrigger);
        assertEquals("near", RemoteAccessSettings.fromPersisted(null).getString("unlockTrigger"));
        JSONObject effective = RemoteAccessSettings.effective(trustedPhone());
        assertEquals(ProximityEvaluator.UnlockTrigger.NEAR, RemoteAccessSettings.evaluatorConfig(effective,
                RemoteAccessSettings.phone(effective, PHONE)).unlockTrigger);
    }

    @Test
    public void unmarkedPersistedAwayToNearAdoptsNearAndKeepsTheRestOfTheSection() throws Exception {
        JSONObject persisted = trustedPhone().put("advancedMode", true).put("unlockTrigger", "away_to_near")
                .put("unlockEnabled", true).put("dryRun", false);
        persisted.remove(RemoteAccessSettings.UNLOCK_TRIGGER_EXPLICIT_KEY);
        JSONObject restored = RemoteAccessSettings.fromPersisted(persisted);
        assertEquals("near", restored.getString("unlockTrigger"));
        assertFalse(restored.has(RemoteAccessSettings.UNLOCK_TRIGGER_EXPLICIT_KEY));
        assertTrue(restored.getBoolean("enabled"));
        assertTrue(restored.getBoolean("unlockEnabled"));
        assertFalse(restored.getBoolean("dryRun"));
        assertEquals(PHONE, restored.getString("deviceAddress"));
        // A false or malformed marker counts as absent and never revokes the section.
        for (Object marker : new Object[] {false, "true", 1, JSONObject.NULL}) {
            JSONObject read = RemoteAccessSettings.fromPersisted(new JSONObject(persisted.toString())
                    .put(RemoteAccessSettings.UNLOCK_TRIGGER_EXPLICIT_KEY, marker));
            assertEquals(String.valueOf(marker), "near", read.getString("unlockTrigger"));
            assertTrue(String.valueOf(marker), read.getBoolean("enabled"));
            assertFalse(String.valueOf(marker), read.has(RemoteAccessSettings.UNLOCK_TRIGGER_EXPLICIT_KEY));
        }
        // An unmarked near is the default anyway and stays.
        assertEquals("near", RemoteAccessSettings.fromPersisted(trustedPhone().put("unlockTrigger", "near"))
                .getString("unlockTrigger"));
    }

    @Test
    public void markedAwayToNearPersistsAndAPutNamingTheTriggerSetsTheMarker() throws Exception {
        JSONObject chosen = RemoteAccessSettings.merge(trustedPhone().put("advancedMode", true),
                new JSONObject().put("unlockTrigger", "away_to_near"));
        assertTrue(chosen.getBoolean(RemoteAccessSettings.UNLOCK_TRIGGER_EXPLICIT_KEY));
        JSONObject restored = RemoteAccessSettings.fromPersisted(chosen);
        assertEquals("away_to_near", restored.getString("unlockTrigger"));
        assertTrue(restored.getBoolean(RemoteAccessSettings.UNLOCK_TRIGGER_EXPLICIT_KEY));
        // The marker rides along later generic saves (every save persists the merged object).
        JSONObject again = RemoteAccessSettings.fromPersisted(RemoteAccessSettings.merge(restored,
                new JSONObject().put("notifyPush", true)));
        assertEquals("away_to_near", again.getString("unlockTrigger"));
        // Naming near marks it too; a PUT that does not name the trigger never sets the marker.
        assertTrue(RemoteAccessSettings.merge(trustedPhone(), new JSONObject().put("unlockTrigger", "near"))
                .getBoolean(RemoteAccessSettings.UNLOCK_TRIGGER_EXPLICIT_KEY));
        assertFalse(RemoteAccessSettings.merge(trustedPhone(), new JSONObject().put("cooldownMs", 20000))
                .has(RemoteAccessSettings.UNLOCK_TRIGGER_EXPLICIT_KEY));
        rejected(trustedPhone(), new JSONObject().put(RemoteAccessSettings.UNLOCK_TRIGGER_EXPLICIT_KEY, "true"));
        // Away → Near stays selectable in Advanced mode; normal mode runs near whatever is saved.
        assertEquals("away_to_near", RemoteAccessSettings.effective(restored).getString("unlockTrigger"));
        assertEquals(ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR,
                RemoteAccessSettings.evaluatorConfig(RemoteAccessSettings.effective(restored)).unlockTrigger);
        JSONObject normal = new JSONObject(restored.toString()).put("advancedMode", false);
        assertEquals("near", RemoteAccessSettings.effective(normal).getString("unlockTrigger"));
        assertEquals("away_to_near", normal.getString("unlockTrigger"));
    }

    // ── C3: phone locking does not wait for the key search ───────────────────────────────────────

    @Test
    public void lockWaitsForKeyAbsenceIsOffByDefaultValidatedAndForcedOffInNormalMode() throws Exception {
        JSONObject defaults = RemoteAccessSettings.defaults();
        assertFalse(defaults.getBoolean(RemoteAccessSettings.LOCK_WAITS_FOR_KEY_ABSENCE_KEY));
        assertFalse(RemoteAccessSettings.lockWaitsForKeyAbsence(defaults));
        assertFalse(RemoteAccessSettings.lockWaitsForKeyAbsence(new JSONObject()));
        // A null object fails safe (wait).
        assertTrue(RemoteAccessSettings.lockWaitsForKeyAbsence(null));
        JSONObject on = RemoteAccessSettings.merge(trustedPhone().put("advancedMode", true),
                new JSONObject().put(RemoteAccessSettings.LOCK_WAITS_FOR_KEY_ABSENCE_KEY, true));
        assertTrue(RemoteAccessSettings.lockWaitsForKeyAbsence(RemoteAccessSettings.fromPersisted(on)));
        assertTrue(RemoteAccessSettings.lockWaitsForKeyAbsence(RemoteAccessSettings.effective(on)));
        JSONObject normal = new JSONObject(on.toString()).put("advancedMode", false);
        assertTrue(normal.getBoolean(RemoteAccessSettings.LOCK_WAITS_FOR_KEY_ABSENCE_KEY));
        assertFalse(RemoteAccessSettings.lockWaitsForKeyAbsence(RemoteAccessSettings.effective(normal)));
        for (Object invalid : new Object[] {"true", 1, JSONObject.NULL})
            rejected(trustedPhone(), new JSONObject().put(RemoteAccessSettings.LOCK_WAITS_FOR_KEY_ABSENCE_KEY, invalid));
        // An older section without the key reads off; a corrupt persisted value revokes the section.
        JSONObject legacy = trustedPhone();
        legacy.remove(RemoteAccessSettings.LOCK_WAITS_FOR_KEY_ABSENCE_KEY);
        JSONObject restored = RemoteAccessSettings.fromPersisted(legacy);
        assertTrue(restored.getBoolean("enabled"));
        assertFalse(restored.getBoolean(RemoteAccessSettings.LOCK_WAITS_FOR_KEY_ABSENCE_KEY));
        assertFalse(RemoteAccessSettings.fromPersisted(trustedPhone()
                .put(RemoteAccessSettings.LOCK_WAITS_FOR_KEY_ABSENCE_KEY, "yes")).getBoolean("enabled"));
    }

    // ── C5: key fob lock on the first absent result at a 5 s cadence ────────────────────────────

    @Test
    public void keyFobLockDefaultsAreFiveSecondsAndOneResultWithBoundedValidation() throws Exception {
        JSONObject defaults = RemoteAccessSettings.defaults();
        assertEquals(5000, defaults.getLong("keyFobLockWindowMs"));
        assertEquals(RemoteAccessSettings.KEY_FOB_LOCK_WINDOW_MS_DEFAULT, defaults.getLong("keyFobLockWindowMs"));
        assertEquals(1, defaults.getInt(RemoteAccessSettings.KEY_FOB_LOCK_SAMPLES_KEY));
        assertFalse(defaults.has(RemoteAccessSettings.KEY_FOB_LOCK_EXPLICIT_KEY));
        // The 5 s window runs the 5 s search cadence.
        assertEquals(KeyFobPresenceEvaluator.FAST_POLL_INTERVAL_MS,
                KeyFobPresenceEvaluator.pollIntervalMs(defaults.getLong("keyFobLockWindowMs")));
        for (int samples = 1; samples <= 3; samples++) {
            JSONObject saved = RemoteAccessSettings.merge(trustedPhone(),
                    new JSONObject().put(RemoteAccessSettings.KEY_FOB_LOCK_SAMPLES_KEY, samples));
            assertEquals(samples, saved.getInt(RemoteAccessSettings.KEY_FOB_LOCK_SAMPLES_KEY));
            assertEquals(samples, RemoteAccessSettings.fromPersisted(saved).getInt(RemoteAccessSettings.KEY_FOB_LOCK_SAMPLES_KEY));
            assertEquals(samples, RemoteAccessSettings.keyFobLockSamples(saved));
        }
        for (Object invalid : new Object[] {0, 4, -1, 1.5, "1", true, JSONObject.NULL})
            rejected(trustedPhone(), new JSONObject().put(RemoteAccessSettings.KEY_FOB_LOCK_SAMPLES_KEY, invalid));
        // The accessor the handler uses falls back to the default for anything odd.
        assertEquals(1, RemoteAccessSettings.keyFobLockSamples(null));
        assertEquals(1, RemoteAccessSettings.keyFobLockSamples(new JSONObject()));
        assertEquals(1, RemoteAccessSettings.keyFobLockSamples(new JSONObject().put("keyFobLockSamples", 4)));
        assertEquals(1, RemoteAccessSettings.keyFobLockSamples(new JSONObject().put("keyFobLockSamples", "2")));
        assertEquals(1, RemoteAccessSettings.keyFobLockSamples(new JSONObject().put("keyFobLockSamples", 2.5)));
        assertEquals(3, RemoteAccessSettings.keyFobLockSamples(new JSONObject().put("keyFobLockSamples", 3)));
        // A corrupt persisted count revokes the section, like every other number.
        assertFalse(RemoteAccessSettings.fromPersisted(trustedPhone().put("keyFobLockSamples", 9)).getBoolean("enabled"));
    }

    @Test
    public void unmarkedOldTenSecondWindowAdoptsFiveSecondsAndChosenValuesPersist() throws Exception {
        JSONObject persisted = trustedPhone().put("advancedMode", true).put("keyFobLockWindowMs", 10000);
        persisted.remove(RemoteAccessSettings.KEY_FOB_LOCK_EXPLICIT_KEY);
        JSONObject restored = RemoteAccessSettings.fromPersisted(persisted);
        assertEquals(5000, restored.getLong("keyFobLockWindowMs"));
        assertFalse(restored.has(RemoteAccessSettings.KEY_FOB_LOCK_EXPLICIT_KEY));
        assertTrue(restored.getBoolean("enabled"));
        // Any other unmarked value (a choice made before the marker existed) persists.
        for (long custom : new long[] {5000, 15000, 20000, 60000})
            assertEquals(custom, RemoteAccessSettings.fromPersisted(new JSONObject(persisted.toString())
                    .put("keyFobLockWindowMs", custom)).getLong("keyFobLockWindowMs"));
        // A PUT naming the window or the count marks the choice; a chosen 10 s then survives the read.
        JSONObject chosen = RemoteAccessSettings.merge(persisted, new JSONObject().put("keyFobLockWindowMs", 10000));
        assertTrue(chosen.getBoolean(RemoteAccessSettings.KEY_FOB_LOCK_EXPLICIT_KEY));
        assertEquals(10000, RemoteAccessSettings.fromPersisted(chosen).getLong("keyFobLockWindowMs"));
        JSONObject countOnly = RemoteAccessSettings.merge(persisted,
                new JSONObject().put(RemoteAccessSettings.KEY_FOB_LOCK_SAMPLES_KEY, 2));
        assertTrue(countOnly.getBoolean(RemoteAccessSettings.KEY_FOB_LOCK_EXPLICIT_KEY));
        JSONObject countRestored = RemoteAccessSettings.fromPersisted(countOnly);
        assertEquals(10000, countRestored.getLong("keyFobLockWindowMs"));
        assertEquals(2, countRestored.getInt(RemoteAccessSettings.KEY_FOB_LOCK_SAMPLES_KEY));
        // Other PUTs leave the marker unset; false or malformed markers count as absent.
        assertFalse(RemoteAccessSettings.merge(persisted, new JSONObject().put("notifyPush", true))
                .has(RemoteAccessSettings.KEY_FOB_LOCK_EXPLICIT_KEY));
        for (Object marker : new Object[] {false, "true", 1})
            assertEquals(String.valueOf(marker), 5000, RemoteAccessSettings.fromPersisted(new JSONObject(persisted.toString())
                    .put(RemoteAccessSettings.KEY_FOB_LOCK_EXPLICIT_KEY, marker)).getLong("keyFobLockWindowMs"));
        rejected(trustedPhone(), new JSONObject().put(RemoteAccessSettings.KEY_FOB_LOCK_EXPLICIT_KEY, "true"));
    }

    @Test
    public void normalModeForcesTheSnappyDefaultsWhileAdvancedKeepsTheChoices() throws Exception {
        JSONObject custom = RemoteAccessSettings.merge(trustedPhone().put("advancedMode", true), new JSONObject()
                .put("keyFobLockWindowMs", 30000).put(RemoteAccessSettings.KEY_FOB_LOCK_SAMPLES_KEY, 3)
                .put(RemoteAccessSettings.LOCK_WAITS_FOR_KEY_ABSENCE_KEY, true).put("unlockTrigger", "away_to_near"));
        JSONObject advanced = RemoteAccessSettings.effective(custom);
        assertEquals(30000, advanced.getLong("keyFobLockWindowMs"));
        assertEquals(3, advanced.getInt(RemoteAccessSettings.KEY_FOB_LOCK_SAMPLES_KEY));
        assertTrue(advanced.getBoolean(RemoteAccessSettings.LOCK_WAITS_FOR_KEY_ABSENCE_KEY));
        assertEquals("away_to_near", advanced.getString("unlockTrigger"));
        JSONObject normalSaved = RemoteAccessSettings.merge(custom, new JSONObject().put("advancedMode", false));
        JSONObject normal = RemoteAccessSettings.effective(normalSaved);
        assertEquals(5000, normal.getLong("keyFobLockWindowMs"));
        assertEquals(1, normal.getInt(RemoteAccessSettings.KEY_FOB_LOCK_SAMPLES_KEY));
        assertFalse(normal.getBoolean(RemoteAccessSettings.LOCK_WAITS_FOR_KEY_ABSENCE_KEY));
        assertEquals("near", normal.getString("unlockTrigger"));
        // The saved object keeps the custom values for the next Advanced session.
        assertEquals(30000, normalSaved.getLong("keyFobLockWindowMs"));
        assertEquals(3, normalSaved.getInt(RemoteAccessSettings.KEY_FOB_LOCK_SAMPLES_KEY));
        assertTrue(normalSaved.getBoolean(RemoteAccessSettings.LOCK_WAITS_FOR_KEY_ABSENCE_KEY));
        assertEquals("away_to_near", normalSaved.getString("unlockTrigger"));
        JSONObject back = RemoteAccessSettings.effective(RemoteAccessSettings.fromPersisted(
                RemoteAccessSettings.merge(normalSaved, new JSONObject().put("advancedMode", true))));
        assertEquals(30000, back.getLong("keyFobLockWindowMs"));
        assertEquals(3, back.getInt(RemoteAccessSettings.KEY_FOB_LOCK_SAMPLES_KEY));
        assertTrue(back.getBoolean(RemoteAccessSettings.LOCK_WAITS_FOR_KEY_ABSENCE_KEY));
        assertEquals("away_to_near", back.getString("unlockTrigger"));
        // The relock grace and keyfob quiet period (B4/A2) are unchanged.
        assertEquals(10_000L, normal.getLong(RemoteAccessSettings.RELOCK_GRACE_MS_KEY));
        assertEquals(10_000L, normal.getLong(RemoteAccessSettings.POST_LOCK_QUIET_MS_KEY));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static JSONObject trustedPhone() throws Exception {
        return RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("enabled", true).put("deviceAddress", PHONE).put("phones", new JSONArray()
                        .put(new JSONObject().put("address", PHONE).put("name", "Owner").put("enabled", true)
                                .put("nearThreshold", -8).put("awayThreshold", -35))));
    }

    private static void rejected(JSONObject current, JSONObject patch) throws Exception {
        try {
            RemoteAccessSettings.merge(current, patch);
            fail("Expected settings rejection: " + patch);
        } catch (IllegalArgumentException | JSONException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }
}
