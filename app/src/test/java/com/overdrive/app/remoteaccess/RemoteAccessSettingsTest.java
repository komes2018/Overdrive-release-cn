package com.overdrive.app.remoteaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/** Tests saved-rule validation without starting Android services or touching configuration files. */
public class RemoteAccessSettingsTest {
    private static final String PHONE = "AA:BB:CC:DD:EE:01";
    private static final String SECOND_PHONE = "AA:BB:CC:DD:EE:02";

    @Test
    public void missingSettingsStartDisabledAndActionsStartInDryRun() throws Exception {
        JSONObject defaults = RemoteAccessSettings.fromPersisted(null);
        assertFalse(defaults.getBoolean("enabled"));
        assertFalse(defaults.getBoolean("unlockEnabled"));
        assertFalse(defaults.getBoolean("lockEnabled"));
        assertTrue(defaults.getBoolean("dryRun"));
        assertFalse(defaults.getBoolean("notifyPush"));
        assertFalse(defaults.getBoolean("notifyTelegram"));
        assertFalse(defaults.getBoolean("advancedMode"));
        assertFalse(defaults.getBoolean("keyFobEnabled"));
        assertFalse(defaults.getBoolean("keyFobLockEnabled"));
        assertFalse(defaults.getBoolean("restrictLockToOutsideSafeZones"));
        assertFalse(defaults.getBoolean("restrictUnlockToOutsideSafeZones"));
        assertEquals(0, defaults.getJSONArray("proximitySafeZoneIds").length());
        // 20 Oct snappy locks (RemoteAccessSettingsSnappyLocksTest pins the full set).
        assertEquals(5000, defaults.getLong("keyFobLockWindowMs"));
        assertEquals("near", defaults.getString("unlockTrigger"));
        assertEquals(0, defaults.getJSONArray("phones").length());
        assertEquals("", defaults.getString("deviceAddress"));
        assertEquals(0, defaults.getLong("revision"));
        // 18 Oct snappy defaults (RemoteAccessSettingsSnappyTimingTest pins the full set).
        assertEquals(2000, defaults.getLong("nearWindowMs"));
        assertEquals(1000, defaults.getLong("nearDwellMs"));
        assertEquals(6000, defaults.getLong("windowMs"));
        assertEquals(2000, defaults.getLong("dwellMs"));
        assertEquals(10000, defaults.getLong("cooldownMs"));
    }

    @Test
    public void enablingRequiresASelectedPhone() throws Exception {
        rejected(RemoteAccessSettings.defaults(), new JSONObject().put("enabled", true));
    }

    @Test
    public void keyFobCanEnableMasterWithoutAnyBluetoothPhones() throws Exception {
        JSONObject saved = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("enabled", true).put("keyFobEnabled", true));
        assertTrue(saved.getBoolean("enabled"));
        assertTrue(saved.getBoolean("keyFobEnabled"));
        assertEquals("", saved.getString("deviceAddress"));
        assertEquals(0, saved.getJSONArray("phones").length());
        rejected(saved, new JSONObject().put("keyFobEnabled", false));
        JSONObject disabled = RemoteAccessSettings.merge(saved,
                new JSONObject().put("enabled", false).put("keyFobEnabled", false));
        assertFalse(disabled.getBoolean("enabled"));
    }

    @Test
    public void keyFobDoesNotBypassSelectionWhenBluetoothProfilesAreEnabled() throws Exception {
        JSONObject enabled = trustedPair().put("keyFobEnabled", true);
        rejected(enabled, new JSONObject().put("deviceAddress", ""));
    }

    /** 20 Oct: a section without the trigger reads the new default (near) instead of away_to_near. */
    @Test
    public void legacySettingsWithoutTheTriggerAdoptTheNearDefault() throws Exception {
        JSONObject legacy = trustedPair();
        legacy.remove("unlockTrigger");
        JSONObject restored = RemoteAccessSettings.fromPersisted(legacy);
        assertTrue(restored.getBoolean("enabled"));
        assertEquals("near", restored.getString("unlockTrigger"));
        assertEquals(ProximityEvaluator.UnlockTrigger.NEAR,
                RemoteAccessSettings.evaluatorConfig(restored).unlockTrigger);
    }

    @Test
    public void bothUnlockModesRoundTripWithoutChangingConsentOrPhoneProfiles() throws Exception {
        JSONObject current = trustedPair().put("unlockEnabled", true)
                .put("lockEnabled", true).put("dryRun", false).put("revision", 17L);
        String roster = current.getJSONArray("phones").toString();
        for (String mode : new String[] {"near", "away_to_near"}) {
            JSONObject saved = RemoteAccessSettings.merge(current,
                    new JSONObject().put("unlockTrigger", mode));
            JSONObject restored = RemoteAccessSettings.fromPersisted(saved);
            assertEquals(mode, restored.getString("unlockTrigger"));
            assertTrue(restored.getBoolean("enabled"));
            assertTrue(restored.getBoolean("unlockEnabled"));
            assertTrue(restored.getBoolean("lockEnabled"));
            assertFalse(restored.getBoolean("dryRun"));
            assertEquals(17L, restored.getLong("revision"));
            assertEquals(roster, restored.getJSONArray("phones").toString());
            assertEquals("near", current.getString("unlockTrigger"));
        }
    }

    @Test
    public void unlockModesRejectNullWrongTypesAndUnsupportedValues() throws Exception {
        for (Object invalid : new Object[] {JSONObject.NULL, true, 0, new JSONArray(), new JSONObject(),
                "", "NEAR", " near ", "connected", "away_to_near_then_near"}) {
            rejected(RemoteAccessSettings.defaults(),
                    new JSONObject().put("unlockTrigger", invalid));
        }
    }

    /** 19 Oct: the key fob rail's own trigger defaults to near and is validated like unlockTrigger. */
    @Test
    public void keyFobUnlockTriggerDefaultsToNearAndValidatesLikeTheUnlockTrigger() throws Exception {
        assertEquals("keyFobUnlockTrigger", RemoteAccessSettings.KEY_FOB_UNLOCK_TRIGGER_KEY);
        assertEquals("near", RemoteAccessSettings.KEY_FOB_UNLOCK_TRIGGER_DEFAULT);
        assertEquals("near", RemoteAccessSettings.defaults().getString("keyFobUnlockTrigger"));
        assertEquals("near", RemoteAccessSettings.fromPersisted(null).getString("keyFobUnlockTrigger"));
        // The phones' trigger keeps its own default (near since 20 Oct); the two are independent.
        assertEquals("near", RemoteAccessSettings.defaults().getString("unlockTrigger"));
        // A persisted section from a build without the key (no migration needed) reads the default.
        // The phones' away_to_near is marked as chosen so that 20 Oct migration keeps it.
        JSONObject legacy = trustedPair().put("unlockTrigger", "away_to_near")
                .put(RemoteAccessSettings.UNLOCK_TRIGGER_EXPLICIT_KEY, true);
        legacy.remove("keyFobUnlockTrigger");
        JSONObject restored = RemoteAccessSettings.fromPersisted(legacy);
        assertTrue(restored.getBoolean("enabled"));
        assertEquals("near", restored.getString("keyFobUnlockTrigger"));
        assertEquals("away_to_near", restored.getString("unlockTrigger"));
        // Both values round-trip through a PUT and persistence without touching the phones' trigger.
        for (String mode : new String[] {"away_to_near", "near"}) {
            JSONObject saved = RemoteAccessSettings.merge(trustedPair(),
                    new JSONObject().put("keyFobUnlockTrigger", mode));
            assertEquals(mode, saved.getString("keyFobUnlockTrigger"));
            assertEquals("near", saved.getString("unlockTrigger"));
            assertEquals(mode, RemoteAccessSettings.fromPersisted(saved).getString("keyFobUnlockTrigger"));
        }
        for (Object invalid : new Object[] {JSONObject.NULL, true, 0, new JSONArray(), new JSONObject(),
                "", "NEAR", " near ", "connected", "away_to_near_then_near"}) {
            rejected(RemoteAccessSettings.defaults(),
                    new JSONObject().put("keyFobUnlockTrigger", invalid));
        }
        // A corrupt persisted value revokes the section rather than guessing, like unlockTrigger.
        JSONObject corrupt = trustedPair().put("keyFobEnabled", true).put("keyFobUnlockTrigger", "unsupported");
        JSONObject revoked = RemoteAccessSettings.fromPersisted(corrupt);
        assertFalse(revoked.getBoolean("enabled"));
        assertFalse(revoked.getBoolean("keyFobEnabled"));
        assertEquals("near", revoked.getString("keyFobUnlockTrigger"));
    }

    @Test
    public void normalModeForcesTheDefaultKeyFobTriggerAndAdvancedModeKeepsTheChoice() throws Exception {
        JSONObject saved = RemoteAccessSettings.merge(trustedPair(),
                new JSONObject().put("keyFobEnabled", true).put("keyFobUnlockTrigger", "away_to_near"));
        assertFalse(saved.getBoolean("advancedMode"));
        assertEquals("away_to_near", saved.getString("keyFobUnlockTrigger"));
        assertEquals("near", RemoteAccessSettings.effective(saved).getString("keyFobUnlockTrigger"));
        // 20 Oct: normal mode forces the phones' trigger to its default (near) as well.
        assertEquals("near", RemoteAccessSettings.effective(
                new JSONObject(saved.toString()).put("unlockTrigger", "away_to_near")).getString("unlockTrigger"));
        saved.put("advancedMode", true);
        assertEquals("away_to_near", RemoteAccessSettings.effective(saved).getString("keyFobUnlockTrigger"));
        assertEquals("near", RemoteAccessSettings.effective(
                saved.put("keyFobUnlockTrigger", "near")).getString("keyFobUnlockTrigger"));
    }

    @Test
    public void corruptSavedUnlockModeRevokesMonitoringRatherThanGuessing() throws Exception {
        JSONObject corrupt = trustedPair().put("unlockEnabled", true)
                .put("dryRun", false).put("unlockTrigger", "unsupported");
        JSONObject restored = RemoteAccessSettings.fromPersisted(corrupt);
        assertFalse(restored.getBoolean("enabled"));
        assertFalse(restored.getBoolean("unlockEnabled"));
        assertTrue(restored.getBoolean("dryRun"));
        assertEquals("near", restored.getString("unlockTrigger"));
    }

    @Test
    public void unlockModeRemainsAvailableInNormalAndAdvancedModesForEveryPhone() throws Exception {
        for (boolean advanced : new boolean[] {false, true}) {
            JSONObject saved = trustedPair().put("advancedMode", advanced).put("unlockTrigger", "near");
            JSONObject effective = RemoteAccessSettings.effective(saved);
            assertEquals("near", effective.getString("unlockTrigger"));
            assertEquals(ProximityEvaluator.UnlockTrigger.NEAR,
                    RemoteAccessSettings.evaluatorConfig(effective).unlockTrigger);
            for (int i = 0; i < effective.getJSONArray("phones").length(); i++)
                assertEquals(ProximityEvaluator.UnlockTrigger.NEAR,
                        RemoteAccessSettings.evaluatorConfig(effective,
                                effective.getJSONArray("phones").getJSONObject(i)).unlockTrigger);
        }
    }

    @Test
    public void selectionAndEnablingCanBeSavedTogetherAndAddressIsNormalized() throws Exception {
        JSONObject saved = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("enabled", true).put("deviceAddress", "  aa:bb:cc:dd:ee:01 ")
                        .put("deviceName", "  Driver's phone  "));
        assertTrue(saved.getBoolean("enabled"));
        assertEquals(PHONE, saved.getString("deviceAddress"));
        assertEquals("Driver's phone", saved.getString("deviceName"));
    }

    @Test
    public void masterCanTurnOffWhileKeepingSavedActionPreferences() throws Exception {
        JSONObject enabled = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("deviceAddress", PHONE).put("enabled", true)
                        .put("unlockEnabled", true).put("lockEnabled", true).put("dryRun", false));
        JSONObject disabled = RemoteAccessSettings.merge(enabled,
                new JSONObject().put("enabled", false));
        assertFalse(disabled.getBoolean("enabled"));
        assertTrue(disabled.getBoolean("unlockEnabled"));
        assertTrue(disabled.getBoolean("lockEnabled"));
        assertFalse(disabled.getBoolean("dryRun"));
        assertEquals(PHONE, disabled.getString("deviceAddress"));
    }

    @Test
    public void clearingSelectionRequiresDisablingMasterInSamePatch() throws Exception {
        JSONObject enabled = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("deviceAddress", PHONE).put("enabled", true));
        rejected(enabled, new JSONObject().put("deviceAddress", ""));
        JSONObject cleared = RemoteAccessSettings.merge(enabled,
                new JSONObject().put("enabled", false).put("deviceAddress", ""));
        assertFalse(cleared.getBoolean("enabled"));
        assertEquals("", cleared.getString("deviceAddress"));
    }

    @Test
    public void allBooleansRequireActualBooleanValues() throws Exception {
        String[] keys = {"enabled", "unlockEnabled", "lockEnabled", "dryRun", "keepBluetoothOn",
                "notifyPush", "notifyTelegram", "advancedMode", "keyFobEnabled", "keyFobLockEnabled",
                "restrictLockToOutsideSafeZones", "restrictUnlockToOutsideSafeZones", "cloudFallback"};
        Object[] wrongTypes = {0, 1, "true", "false", JSONObject.NULL};
        for (String key : keys) {
            for (Object wrongType : wrongTypes) {
                rejected(RemoteAccessSettings.defaults(), new JSONObject().put(key, wrongType));
            }
        }
    }
    /** 17 Oct cloud fallback actuator switch: on by default, a saved false is respected, PUT validated. */
    @Test
    public void cloudFallbackDefaultsOnPersistsFalseAndRejectsNonBooleans() throws Exception {
        assertEquals("cloudFallback", RemoteAccessSettings.CLOUD_FALLBACK_KEY);
        assertTrue(RemoteAccessSettings.CLOUD_FALLBACK_DEFAULT);
        JSONObject defaults = RemoteAccessSettings.defaults();
        assertTrue(defaults.getBoolean("cloudFallback"));
        assertTrue(RemoteAccessSettings.cloudFallback(defaults));
        assertTrue(RemoteAccessSettings.cloudFallback(RemoteAccessSettings.fromPersisted(null)));
        // A persisted false survives read() and the accessor; the rest of the section is untouched.
        JSONObject restored = RemoteAccessSettings.fromPersisted(trustedPair().put("cloudFallback", false));
        assertFalse(restored.getBoolean("cloudFallback"));
        assertFalse(RemoteAccessSettings.cloudFallback(restored));
        assertTrue(restored.getBoolean("enabled"));
        assertEquals(PHONE, restored.getString("deviceAddress"));
        // A PUT of false merges without touching the revision; a PUT of true restores it.
        JSONObject off = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("cloudFallback", false));
        assertFalse(off.getBoolean("cloudFallback"));
        assertEquals(0, off.getLong("revision"));
        assertTrue(RemoteAccessSettings.merge(off, new JSONObject().put("cloudFallback", true))
                .getBoolean("cloudFallback"));
        // Normal mode passes the boolean through unchanged (only the timing numbers are replaced).
        assertFalse(RemoteAccessSettings.effective(off).getBoolean("cloudFallback"));
        assertTrue(RemoteAccessSettings.effective(RemoteAccessSettings.defaults()).getBoolean("cloudFallback"));
        // A persisted non-boolean revokes the whole section to defaults, like every other boolean.
        JSONObject corrupt = RemoteAccessSettings.fromPersisted(trustedPair().put("cloudFallback", "true"));
        assertFalse(corrupt.getBoolean("enabled"));
        assertTrue(corrupt.getBoolean("dryRun"));
        assertTrue(corrupt.getBoolean("cloudFallback"));
        // The accessor is safe on odd input: null → false (never a cloud command), {} → the default.
        assertFalse(RemoteAccessSettings.cloudFallback(null));
        assertTrue(RemoteAccessSettings.cloudFallback(new JSONObject()));
        assertFalse(RemoteAccessSettings.cloudFallback(new JSONObject().put("cloudFallback", false)));
    }

    @Test
    public void numericSettingsRejectStringsBooleansNullAndFractions() throws Exception {
        String[] keys = {"nearThreshold", "awayThreshold", "windowMs", "dwellMs",
                "nearWindowMs", "nearDwellMs", "staleMs", "cooldownMs", "keyFobLockWindowMs"};
        Object[] wrongTypes = {"4000", true, JSONObject.NULL, 4000.25};
        for (String key : keys) {
            for (Object wrongType : wrongTypes) {
                rejected(RemoteAccessSettings.defaults(), new JSONObject().put(key, wrongType));
            }
        }
    }

    @Test
    public void wholeNumericValuesAreAcceptedWithoutTruncation() throws Exception {
        JSONObject saved = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("windowMs", 15000.0).put("nearThreshold", -12.0));
        assertEquals(15000L, saved.getLong("windowMs"));
        assertEquals(-12, saved.getInt("nearThreshold"));
    }

    @Test
    public void keyFobLockWindowAcceptsOnlyBoundedFiveSecondSteps() throws Exception {
        for (long valid : new long[] {5000, 10000, 15000, 20000, 60000, 300000}) {
            JSONObject saved = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                    new JSONObject().put("keyFobLockWindowMs", valid));
            assertEquals(valid, saved.getLong("keyFobLockWindowMs"));
            assertEquals(valid, RemoteAccessSettings.fromPersisted(saved).getLong("keyFobLockWindowMs"));
        }
        for (long invalid : new long[] {0, 4999, 5001, 7500, 12000, 299999, 305000})
            rejected(RemoteAccessSettings.defaults(),
                    new JSONObject().put("keyFobLockWindowMs", invalid));
    }

    @Test
    public void normalModeUsesDefaultFobWindowAndRetainsCustomWindowForAdvancedMode() throws Exception {
        JSONObject saved = trustedPair().put("keyFobLockWindowMs", 30000);
        assertEquals(5000, RemoteAccessSettings.effective(saved).getLong("keyFobLockWindowMs"));
        assertEquals(30000, saved.getLong("keyFobLockWindowMs"));
        saved.put("advancedMode", true);
        assertEquals(30000, RemoteAccessSettings.effective(saved).getLong("keyFobLockWindowMs"));
        // 20 Oct: 5 s is the default; a slower custom window is Advanced-only, normal mode forces 5 s.
        JSONObject slow = trustedPair().put("keyFobLockWindowMs", 15000);
        assertEquals(5000, RemoteAccessSettings.effective(slow).getLong("keyFobLockWindowMs"));
        slow.put("advancedMode", true);
        assertEquals(15000, RemoteAccessSettings.effective(slow).getLong("keyFobLockWindowMs"));
    }

    @Test
    public void invalidPersistedFobWindowRevokesMonitoring() throws Exception {
        JSONObject saved = trustedPair().put("keyFobLockWindowMs", 12000);
        JSONObject restored = RemoteAccessSettings.fromPersisted(saved);
        assertFalse(restored.getBoolean("enabled"));
        assertFalse(restored.getBoolean("keyFobLockEnabled"));
        assertEquals(5000, restored.getLong("keyFobLockWindowMs"));
    }

    @Test
    public void overlappingOrReversedThresholdsAreRejected() throws Exception {
        rejected(RemoteAccessSettings.defaults(),
                new JSONObject().put("nearThreshold", -24).put("awayThreshold", -24));
        rejected(RemoteAccessSettings.defaults(),
                new JSONObject().put("nearThreshold", -30).put("awayThreshold", -20));
    }

    @Test
    public void rawSignalRangeIsBoundedAndEndpointsAreValid() throws Exception {
        rejected(RemoteAccessSettings.defaults(), new JSONObject().put("awayThreshold", -128));
        rejected(RemoteAccessSettings.defaults(), new JSONObject().put("nearThreshold", 21));
        JSONObject valid = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("awayThreshold", -127).put("nearThreshold", 20));
        assertEquals(-127, valid.getInt("awayThreshold"));
        assertEquals(20, valid.getInt("nearThreshold"));
    }

    @Test
    public void timingBoundsPreventUnboundedWorkersAndUnstableRules() throws Exception {
        String[] keys = {"windowMs", "dwellMs", "nearWindowMs", "nearDwellMs", "staleMs", "cooldownMs"};
        // 18 Oct floors (ceilings unchanged).
        long[] minimums = {4000, 1000, 2000, 1000, 4000, 5000};
        long[] maximums = {60000, 30000, 60000, 30000, 15000, 300000};
        for (int index = 0; index < keys.length; index++) {
            String key = keys[index];
            rejected(RemoteAccessSettings.defaults(), new JSONObject().put(key, minimums[index] - 1));
            rejected(RemoteAccessSettings.defaults(), new JSONObject().put(key, maximums[index] + 1));
            assertEquals(minimums[index], RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                    new JSONObject().put(key, minimums[index])).getLong(key));
            assertEquals(maximums[index], RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                    new JSONObject().put(key, maximums[index])).getLong(key));
        }
    }

    @Test
    public void unknownClientKeysAreRejected() throws Exception {
        rejected(RemoteAccessSettings.defaults(), new JSONObject().put("autoUnlockAnyPhone", true));
    }

    @Test
    public void clientsCannotAssignRevision() throws Exception {
        JSONObject current = RemoteAccessSettings.defaults().put("revision", 8L);
        JSONObject saved = RemoteAccessSettings.merge(current,
                new JSONObject().put("revision", 999999L).put("dwellMs", 6000));
        assertEquals(8L, saved.getLong("revision"));
        assertEquals(6000, saved.getInt("dwellMs"));
    }

    @Test
    public void malformedAddressesAndNonTextDeviceFieldsAreRejected() throws Exception {
        for (String address : new String[] {"phone", "AA:BB:CC:DD:EE", "GG:BB:CC:DD:EE:01",
                "AA-BB-CC-DD-EE-01", "AA:BB:CC:DD:EE:01:02"}) {
            rejected(RemoteAccessSettings.defaults(), new JSONObject().put("deviceAddress", address));
        }
        rejected(RemoteAccessSettings.defaults(), new JSONObject().put("deviceAddress", true));
        rejected(RemoteAccessSettings.defaults(), new JSONObject().put("deviceName", 123));
        rejected(RemoteAccessSettings.defaults(), new JSONObject().put("deviceName", JSONObject.NULL));
    }

    @Test
    public void deviceNameLengthIsBounded() throws Exception {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 121; i++) longName.append('x');
        rejected(RemoteAccessSettings.defaults(), new JSONObject().put("deviceName", longName.toString()));
    }

    @Test
    public void mergePreservesCurrentSettingsAndNeverMutatesInputs() throws Exception {
        JSONObject current = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("deviceAddress", PHONE).put("nearThreshold", -10));
        JSONObject patch = new JSONObject().put("awayThreshold", -30);
        String before = current.toString();
        String patchBefore = patch.toString();
        JSONObject saved = RemoteAccessSettings.merge(current, patch);
        assertEquals(-10, saved.getInt("nearThreshold"));
        assertEquals(-30, saved.getInt("awayThreshold"));
        assertEquals(PHONE, saved.getString("deviceAddress"));
        assertEquals(before, current.toString());
        assertEquals(patchBefore, patch.toString());

        rejected(current, new JSONObject().put("enabled", true).put("nearThreshold", -40));
        assertEquals(before, current.toString());
    }

    @Test
    public void defaultsAreIndependentObjects() throws Exception {
        JSONObject first = RemoteAccessSettings.defaults();
        first.put("enabled", true).put("deviceAddress", PHONE);
        JSONObject second = RemoteAccessSettings.defaults();
        assertFalse(second.getBoolean("enabled"));
        assertEquals("", second.getString("deviceAddress"));
    }

    @Test
    public void persistedUnknownFieldsAreIgnoredAndMissingFieldsRemainDisabled() throws Exception {
        JSONObject restored = RemoteAccessSettings.fromPersisted(
                new JSONObject().put("futureSignalProvider", "future provider")
                        .put("deviceName", "Existing phone"));
        assertFalse(restored.getBoolean("enabled"));
        assertTrue(restored.getBoolean("dryRun"));
        assertEquals("Existing phone", restored.getString("deviceName"));
        assertFalse(restored.has("futureSignalProvider"));
    }

    @Test
    public void corruptPersistedSettingsFallBackToFullyDisabledDefaults() throws Exception {
        JSONObject[] corruptFields = {
                new JSONObject().put("dwellMs", "4000"),
                new JSONObject().put("dryRun", "false"),
                new JSONObject().put("deviceAddress", "invalid"),
                new JSONObject().put("nearThreshold", -40),
                new JSONObject().put("revision", -1)
        };
        for (JSONObject corruptField : corruptFields) {
            JSONObject saved = new JSONObject().put("deviceAddress", PHONE).put("enabled", true)
                    .put("unlockEnabled", true).put("lockEnabled", true).put("dryRun", false);
            java.util.Iterator<String> keys = corruptField.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                saved.put(key, corruptField.get(key));
            }
            JSONObject restored = RemoteAccessSettings.fromPersisted(saved);
            assertFalse(restored.getBoolean("enabled"));
            assertFalse(restored.getBoolean("unlockEnabled"));
            assertFalse(restored.getBoolean("lockEnabled"));
            assertTrue(restored.getBoolean("dryRun"));
            assertEquals("", restored.getString("deviceAddress"));
            assertEquals(0, restored.getLong("revision"));
        }
    }

    @Test
    public void validPersistedRulesRetainServerRevisionAndSelectedPhone() throws Exception {
        JSONObject saved = new JSONObject().put("deviceAddress", PHONE).put("enabled", true)
                .put("unlockEnabled", true).put("revision", 42);
        JSONObject restored = RemoteAccessSettings.fromPersisted(saved);
        assertTrue(restored.getBoolean("enabled"));
        assertTrue(restored.getBoolean("unlockEnabled"));
        assertTrue(restored.getBoolean("dryRun"));
        assertEquals(PHONE, restored.getString("deviceAddress"));
        assertEquals(42, restored.getLong("revision"));
    }

    @Test
    public void legacySinglePhoneMigratesWithItsNameAndSavedThresholds() throws Exception {
        JSONObject restored = RemoteAccessSettings.fromPersisted(new JSONObject()
                .put("enabled", true).put("deviceAddress", PHONE).put("deviceName", "Owner")
                .put("nearThreshold", -8).put("awayThreshold", -35));
        JSONArray phones = restored.getJSONArray("phones");
        assertEquals(1, phones.length());
        JSONObject profile = phones.getJSONObject(0);
        assertEquals(PHONE, profile.getString("address"));
        assertEquals("Owner", profile.getString("name"));
        assertTrue(profile.getBoolean("enabled"));
        assertEquals(-8, profile.getInt("nearThreshold"));
        assertEquals(-35, profile.getInt("awayThreshold"));
    }

    @Test
    public void multiplePhonesKeepDifferentCalibrationAndSelectedCompatibilityFields() throws Exception {
        JSONObject saved = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("enabled", true).put("deviceAddress", SECOND_PHONE)
                        .put("phones", new JSONArray()
                                .put(phone(PHONE, "Owner", true, -8, -35))
                                .put(phone(SECOND_PHONE, "Family", true, -18, -40))));
        assertEquals(2, saved.getJSONArray("phones").length());
        assertEquals(-8, saved.getJSONArray("phones").getJSONObject(0).getInt("nearThreshold"));
        assertEquals(-35, saved.getJSONArray("phones").getJSONObject(0).getInt("awayThreshold"));
        assertEquals("Family", saved.getString("deviceName"));
        assertEquals(-18, saved.getInt("nearThreshold"));
        assertEquals(-40, saved.getInt("awayThreshold"));
    }

    @Test
    public void selectingAnotherPhoneRefreshesGlobalCompatibilityCalibration() throws Exception {
        JSONObject current = trustedPair();
        JSONObject selected = RemoteAccessSettings.merge(current,
                new JSONObject().put("deviceAddress", SECOND_PHONE));
        assertEquals("Family", selected.getString("deviceName"));
        assertEquals(-18, selected.getInt("nearThreshold"));
        assertEquals(-40, selected.getInt("awayThreshold"));
        assertEquals(-8, selected.getJSONArray("phones").getJSONObject(0).getInt("nearThreshold"));
    }

    @Test
    public void legacyPartialThresholdEditUpdatesSelectedPhoneAndPreservesOthers() throws Exception {
        JSONObject current = trustedPair();
        // -30 is valid relative to Owner's -35 Away value, not the factory -24.
        JSONObject saved = RemoteAccessSettings.merge(current,
                new JSONObject().put("nearThreshold", -30));
        assertEquals(-30, saved.getInt("nearThreshold"));
        assertEquals(-35, saved.getInt("awayThreshold"));
        assertEquals(-30, saved.getJSONArray("phones").getJSONObject(0).getInt("nearThreshold"));
        assertEquals(-18, saved.getJSONArray("phones").getJSONObject(1).getInt("nearThreshold"));
        assertEquals(-40, saved.getJSONArray("phones").getJSONObject(1).getInt("awayThreshold"));
    }

    @Test
    public void normalizedDuplicateAddressesAreRejectedEvenIfNamesDiffer() throws Exception {
        rejected(RemoteAccessSettings.defaults(), new JSONObject().put("phones", new JSONArray()
                .put(phone(PHONE, "Owner", true, -8, -35))
                .put(phone("  aa:bb:cc:dd:ee:01  ", "Different label", true, -18, -40))));
    }

    @Test
    public void trustedListMustBeAnArrayOfPhoneObjects() throws Exception {
        for (Object invalid : new Object[] {"phones", new JSONObject(), JSONObject.NULL, true}) {
            rejected(RemoteAccessSettings.defaults(), new JSONObject().put("phones", invalid));
        }
        rejected(RemoteAccessSettings.defaults(), new JSONObject().put("phones", new JSONArray().put("phone")));
        rejected(RemoteAccessSettings.defaults(), new JSONObject().put("phones", new JSONArray().put(JSONObject.NULL)));
    }

    @Test
    public void eightProfilesAreSupportedAndNineAreRejected() throws Exception {
        JSONArray phones = new JSONArray();
        for (int i = 1; i <= 8; i++) phones.put(phone(
                String.format(java.util.Locale.US, "AA:BB:CC:DD:EE:%02X", i),
                "Phone " + i, true, -12, -24));
        JSONObject saved = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("enabled", true).put("deviceAddress", PHONE).put("phones", phones));
        assertEquals(8, saved.getJSONArray("phones").length());
        phones.put(phone("AA:BB:CC:DD:EE:09", "Phone 9", true, -12, -24));
        rejected(RemoteAccessSettings.defaults(), new JSONObject().put("phones", phones));
    }

    @Test
    public void masterRequiresAtLeastOneEnabledTrustedPhone() throws Exception {
        JSONArray disabled = new JSONArray().put(phone(PHONE, "Owner", false, -12, -24));
        rejected(RemoteAccessSettings.defaults(), new JSONObject().put("enabled", true)
                .put("deviceAddress", PHONE).put("phones", disabled));
        JSONObject saved = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("deviceAddress", PHONE).put("phones", disabled));
        assertFalse(saved.getBoolean("enabled"));
        assertFalse(saved.getJSONArray("phones").getJSONObject(0).getBoolean("enabled"));
    }

    @Test
    public void selectedPhoneMustBelongToTheTrustedList() throws Exception {
        rejected(RemoteAccessSettings.defaults(), new JSONObject().put("deviceAddress", SECOND_PHONE)
                .put("phones", new JSONArray().put(phone(PHONE, "Owner", true, -12, -24))));
        rejected(RemoteAccessSettings.defaults(), new JSONObject().put("enabled", true)
                .put("phones", new JSONArray().put(phone(PHONE, "Owner", true, -12, -24))));
    }

    @Test
    public void removingLastPhoneRequiresMasterOffAndSelectionCleared() throws Exception {
        JSONObject current = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("enabled", true).put("deviceAddress", PHONE)
                        .put("phones", new JSONArray().put(phone(PHONE, "Owner", true, -12, -24))));
        rejected(current, new JSONObject().put("phones", new JSONArray()).put("deviceAddress", ""));
        JSONObject removed = RemoteAccessSettings.merge(current, new JSONObject()
                .put("enabled", false).put("deviceAddress", "").put("phones", new JSONArray()));
        assertFalse(removed.getBoolean("enabled"));
        assertEquals(0, removed.getJSONArray("phones").length());
        assertEquals("", removed.getString("deviceAddress"));
    }

    @Test
    public void profileTypesAndThresholdsAreStrictIncludingDisabledProfiles() throws Exception {
        JSONObject[] invalidProfiles = {
                phone(PHONE, "Owner", true, -12, -24).put("enabled", "true"),
                phone(PHONE, "Owner", true, -12, -24).put("enabled", 1),
                phone(PHONE, "Owner", true, -12, -24).put("name", 1),
                phone(PHONE, "Owner", true, -12, -24).put("address", 1),
                phone(PHONE, "Owner", true, -12, -24).put("nearThreshold", "-12"),
                phone(PHONE, "Owner", true, -12, -24).put("awayThreshold", false),
                phone(PHONE, "Owner", true, -12, -24).put("nearThreshold", -12.5),
                phone(PHONE, "Owner", true, -24, -24),
                phone(PHONE, "Owner", true, -30, -20),
                phone(PHONE, "Owner", false, 21, -24),
                phone(PHONE, "Owner", false, -12, -128)
        };
        for (JSONObject invalid : invalidProfiles) {
            rejected(RemoteAccessSettings.defaults(), new JSONObject().put("phones", new JSONArray().put(invalid)));
        }
    }

    @Test
    public void phoneObjectsAndNamesAreNormalizedWithoutAliasingInputs() throws Exception {
        JSONObject profile = phone("  aa:bb:cc:dd:ee:01  ", "  Owner  ", true, -8, -35);
        JSONArray list = new JSONArray().put(profile);
        JSONObject patch = new JSONObject().put("phones", list).put("deviceAddress", PHONE);
        JSONObject saved = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(), patch);
        profile.put("name", "Changed after save").put("nearThreshold", -20);
        list.put(phone(SECOND_PHONE, "Extra", true, -12, -24));
        assertEquals(1, saved.getJSONArray("phones").length());
        assertEquals(PHONE, saved.getJSONArray("phones").getJSONObject(0).getString("address"));
        assertEquals("Owner", saved.getJSONArray("phones").getJSONObject(0).getString("name"));
        assertEquals(-8, saved.getJSONArray("phones").getJSONObject(0).getInt("nearThreshold"));
        JSONObject next = RemoteAccessSettings.merge(saved, new JSONObject().put("cooldownMs", 40000));
        next.getJSONArray("phones").getJSONObject(0).put("name", "Changed merged result");
        assertEquals("Owner", saved.getJSONArray("phones").getJSONObject(0).getString("name"));
    }

    @Test
    public void corruptPersistedPhoneRosterRevokesAllMonitoringAndNotifications() throws Exception {
        JSONObject saved = new JSONObject().put("enabled", true).put("deviceAddress", PHONE)
                .put("notifyPush", true).put("notifyTelegram", true).put("phones", new JSONArray()
                        .put(phone(PHONE, "Owner", true, -12, -24))
                        .put(phone(PHONE.toLowerCase(java.util.Locale.US), "Duplicate", true, -12, -24)));
        JSONObject restored = RemoteAccessSettings.fromPersisted(saved);
        assertFalse(restored.getBoolean("enabled"));
        assertFalse(restored.getBoolean("notifyPush"));
        assertFalse(restored.getBoolean("notifyTelegram"));
        assertEquals(0, restored.getJSONArray("phones").length());
    }

    @Test
    public void profilesSupplyTheirOwnThresholdsAndShareOnlyTimingConfiguration() throws Exception {
        JSONObject config = trustedPair().put("windowMs", 20000).put("dwellMs", 6000);
        JSONObject phone = RemoteAccessSettings.phone(config, SECOND_PHONE.toLowerCase(java.util.Locale.US));
        ProximityEvaluator.Config evaluator = RemoteAccessSettings.evaluatorConfig(config, phone);
        assertEquals(-18, evaluator.nearThreshold);
        assertEquals(-40, evaluator.awayThreshold);
        assertEquals(20000, evaluator.windowMs);
        assertEquals(6000, evaluator.stableMs);
    }

    @Test
    public void normalModeUsesDefaultsForEveryPhoneWithoutDiscardingSavedCalibration() throws Exception {
        JSONObject saved = trustedPair().put("windowMs", 20000).put("dwellMs", 6000)
                .put("staleMs", 10000).put("cooldownMs", 40000);
        String before = saved.toString();
        JSONObject effective = RemoteAccessSettings.effective(saved);
        JSONObject defaults = RemoteAccessSettings.defaults();
        for (String key : new String[] {"nearThreshold", "awayThreshold", "windowMs", "dwellMs",
                "nearWindowMs", "nearDwellMs", "staleMs", "cooldownMs"}) {
            assertEquals(defaults.getLong(key), effective.getLong(key));
        }
        for (int i = 0; i < effective.getJSONArray("phones").length(); i++) {
            JSONObject profile = effective.getJSONArray("phones").getJSONObject(i);
            ProximityEvaluator.Config evaluator = RemoteAccessSettings.evaluatorConfig(effective, profile);
            assertEquals(-12, evaluator.nearThreshold);
            assertEquals(-24, evaluator.awayThreshold);
            assertEquals(6000, evaluator.windowMs);
            assertEquals(2000, evaluator.stableMs);
            assertEquals(5000, evaluator.staleMs);
            assertEquals(2000, evaluator.nearWindowMs);
            assertEquals(1000, evaluator.nearStableMs);
            assertTrue(evaluator.separateNearConfirmation);
        }
        assertEquals(before, saved.toString());
        assertEquals(-8, saved.getJSONArray("phones").getJSONObject(0).getInt("nearThreshold"));
        assertEquals(-40, saved.getJSONArray("phones").getJSONObject(1).getInt("awayThreshold"));
    }

    @Test
    public void advancedModeHonorsEachSavedProfileAndTimingValues() throws Exception {
        JSONObject saved = trustedPair().put("advancedMode", true)
                .put("windowMs", 24000).put("dwellMs", 7000).put("staleMs", 9000)
                .put("cooldownMs", 50000);
        JSONObject effective = RemoteAccessSettings.effective(saved);
        assertEquals(saved.toString(), effective.toString());
        ProximityEvaluator.Config first = RemoteAccessSettings.evaluatorConfig(effective,
                RemoteAccessSettings.phone(effective, PHONE));
        ProximityEvaluator.Config second = RemoteAccessSettings.evaluatorConfig(effective,
                RemoteAccessSettings.phone(effective, SECOND_PHONE));
        assertEquals(-8, first.nearThreshold);
        assertEquals(-35, first.awayThreshold);
        assertEquals(-18, second.nearThreshold);
        assertEquals(-40, second.awayThreshold);
        assertEquals(24000, second.windowMs);
        assertEquals(7000, second.stableMs);
        assertEquals(9000, second.staleMs);
        assertEquals(50000, effective.getLong("cooldownMs"));
    }

    @Test
    public void advancedOffAndOnRestoresCustomSettingsWhileConsentAndRosterStayIntact() throws Exception {
        JSONObject advanced = trustedPair().put("advancedMode", true)
                .put("unlockEnabled", true).put("lockEnabled", true).put("dryRun", false)
                .put("notifyPush", true).put("notifyTelegram", true).put("revision", 42)
                .put("windowMs", 22000);
        JSONObject basic = RemoteAccessSettings.merge(advanced,
                new JSONObject().put("advancedMode", false));
        JSONObject effective = RemoteAccessSettings.effective(basic);
        for (String key : new String[] {"enabled", "unlockEnabled", "lockEnabled", "dryRun",
                "notifyPush", "notifyTelegram", "keepBluetoothOn", "advancedMode",
                "deviceAddress", "deviceName", "revision"}) {
            assertEquals(basic.get(key), effective.get(key));
        }
        assertEquals(2, effective.getJSONArray("phones").length());
        assertEquals(PHONE, effective.getJSONArray("phones").getJSONObject(0).getString("address"));
        assertEquals("Family", effective.getJSONArray("phones").getJSONObject(1).getString("name"));
        JSONObject restored = RemoteAccessSettings.effective(RemoteAccessSettings.merge(basic,
                new JSONObject().put("advancedMode", true)));
        assertEquals(22000, restored.getLong("windowMs"));
        assertEquals(-8, restored.getJSONArray("phones").getJSONObject(0).getInt("nearThreshold"));
        assertEquals(-40, restored.getJSONArray("phones").getJSONObject(1).getInt("awayThreshold"));
    }

    @Test
    public void effectiveSettingsAndPhoneObjectsNeverAliasSavedValues() throws Exception {
        for (boolean advanced : new boolean[] {false, true}) {
            JSONObject saved = trustedPair().put("advancedMode", advanced);
            String before = saved.toString();
            JSONObject effective = RemoteAccessSettings.effective(saved);
            effective.put("enabled", false).put("windowMs", 60000);
            effective.getJSONArray("phones").getJSONObject(0)
                    .put("enabled", false).put("nearThreshold", -100).put("name", "Changed");
            assertEquals(before, saved.toString());
        }
    }

    @Test
    public void legacyAdvancedDefaultsAdoptFastArrivalWithoutChangingThresholdsOrDeparture() throws Exception {
        // 18 Oct: the old defaults (never chosen, no timingExplicit marker) adopt the current defaults.
        JSONObject legacy = trustedPair().put("advancedMode", true).put("windowMs", 15000)
                .put("dwellMs", 4000).put("unlockEnabled", true).put("dryRun", false);
        legacy.remove("nearWindowMs");
        legacy.remove("nearDwellMs");
        String roster = legacy.getJSONArray("phones").toString();
        JSONObject restored = RemoteAccessSettings.fromPersisted(legacy);
        assertEquals(2000, restored.getLong("nearWindowMs"));
        assertEquals(1000, restored.getLong("nearDwellMs"));
        assertEquals(6000, restored.getLong("windowMs"));
        assertEquals(2000, restored.getLong("dwellMs"));
        assertEquals(roster, restored.getJSONArray("phones").toString());
        assertTrue(restored.getBoolean("unlockEnabled"));
        assertFalse(restored.getBoolean("dryRun"));
    }

    @Test
    public void legacyAdvancedCustomTimingsArePreservedIndependently() throws Exception {
        // {saved windowMs, saved dwellMs, restored windowMs, restored dwellMs, nearWindowMs, nearDwellMs}:
        // a custom value persists and is copied to the arrival control; an old default (15000 / 4000,
        // never chosen) adopts the 18 Oct default and the arrival control gets its own default.
        for (long[] timing : new long[][] {{24000, 4000, 24000, 2000, 24000, 1000},
                {15000, 7000, 6000, 7000, 2000, 7000}, {22000, 6000, 22000, 6000, 22000, 6000}}) {
            JSONObject legacy = trustedPair().put("advancedMode", true)
                    .put("windowMs", timing[0]).put("dwellMs", timing[1]);
            legacy.remove("nearWindowMs");
            legacy.remove("nearDwellMs");
            JSONObject restored = RemoteAccessSettings.fromPersisted(legacy);
            assertEquals(timing[2], restored.getLong("windowMs"));
            assertEquals(timing[3], restored.getLong("dwellMs"));
            assertEquals(timing[4], restored.getLong("nearWindowMs"));
            assertEquals(timing[5], restored.getLong("nearDwellMs"));
        }
    }

    @Test
    public void explicitArrivalCustomTimingSurvivesReloadAndNormalModeRoundTrip() throws Exception {
        JSONObject custom = trustedPair().put("advancedMode", true).put("nearWindowMs", 9000)
                .put("nearDwellMs", 5000).put("windowMs", 15000).put("dwellMs", 4000);
        JSONObject restored = RemoteAccessSettings.fromPersisted(custom);
        assertEquals(9000, restored.getLong("nearWindowMs"));
        assertEquals(5000, restored.getLong("nearDwellMs"));
        JSONObject normal = RemoteAccessSettings.merge(restored, new JSONObject().put("advancedMode", false));
        JSONObject active = RemoteAccessSettings.effective(normal);
        assertEquals(2000, active.getLong("nearWindowMs"));
        assertEquals(1000, active.getLong("nearDwellMs"));
        JSONObject advanced = RemoteAccessSettings.effective(
                RemoteAccessSettings.merge(normal, new JSONObject().put("advancedMode", true)));
        assertEquals(9000, advanced.getLong("nearWindowMs"));
        assertEquals(5000, advanced.getLong("nearDwellMs"));
        ProximityEvaluator.Config evaluator = RemoteAccessSettings.evaluatorConfig(advanced);
        assertEquals(9000, evaluator.nearWindowMs);
        assertEquals(5000, evaluator.nearStableMs);
        // dwellMs 4000 was the old default (no marker): it adopted the 18 Oct default.
        assertEquals(2000, evaluator.stableMs);
    }

    @Test
    public void keyFobLockingRequiresSeparateOptInAndAnEnabledNativeSource() throws Exception {
        JSONObject legacy = trustedPair().put("keyFobEnabled", true);
        legacy.remove("keyFobLockEnabled");
        assertFalse(RemoteAccessSettings.fromPersisted(legacy).getBoolean("keyFobLockEnabled"));
        rejected(RemoteAccessSettings.defaults(), new JSONObject().put("keyFobLockEnabled", true));
        JSONObject enabled = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("enabled", true).put("keyFobEnabled", true)
                        .put("keyFobLockEnabled", true));
        assertTrue(enabled.getBoolean("keyFobLockEnabled"));
        assertFalse(enabled.getBoolean("lockEnabled"));
        assertTrue(enabled.getBoolean("dryRun"));
        assertEquals(0, enabled.getJSONArray("phones").length());
        rejected(enabled, new JSONObject().put("keyFobEnabled", false));
        JSONObject disabled = RemoteAccessSettings.merge(enabled,
                new JSONObject().put("enabled", false).put("keyFobEnabled", false)
                        .put("keyFobLockEnabled", false));
        assertFalse(disabled.getBoolean("keyFobLockEnabled"));
    }

    @Test
    public void legacyRulesDoNotAcquireLocationRestrictions() throws Exception {
        JSONObject legacy = trustedPair().put("lockEnabled", true).put("unlockEnabled", true)
                .put("dryRun", false);
        legacy.remove("restrictLockToOutsideSafeZones");
        legacy.remove("restrictUnlockToOutsideSafeZones");
        legacy.remove("proximitySafeZoneIds");
        JSONObject restored = RemoteAccessSettings.fromPersisted(legacy);
        assertTrue(restored.getBoolean("enabled"));
        assertTrue(restored.getBoolean("lockEnabled"));
        assertTrue(restored.getBoolean("unlockEnabled"));
        assertFalse(restored.getBoolean("dryRun"));
        assertFalse(restored.getBoolean("restrictLockToOutsideSafeZones"));
        assertFalse(restored.getBoolean("restrictUnlockToOutsideSafeZones"));
        assertEquals(0, restored.getJSONArray("proximitySafeZoneIds").length());
        assertEquals(legacy.getJSONArray("phones").toString(), restored.getJSONArray("phones").toString());
    }

    @Test
    public void zoneRestrictionsRoundTripIndependentlyInNormalAndAdvancedModes() throws Exception {
        for (boolean advanced : new boolean[] {false, true}) {
            for (String action : new String[] {"lock", "unlock", "both"}) {
                boolean lock = !"unlock".equals(action), unlock = !"lock".equals(action);
                JSONObject saved = RemoteAccessSettings.merge(trustedPair(),
                        new JSONObject().put("advancedMode", advanced)
                                .put("restrictLockToOutsideSafeZones", lock)
                                .put("restrictUnlockToOutsideSafeZones", unlock)
                                .put("proximitySafeZoneIds", new JSONArray().put("home-1").put("work_2")));
                JSONObject restored = RemoteAccessSettings.fromPersisted(saved);
                JSONObject effective = RemoteAccessSettings.effective(restored);
                assertEquals(lock, effective.getBoolean("restrictLockToOutsideSafeZones"));
                assertEquals(unlock, effective.getBoolean("restrictUnlockToOutsideSafeZones"));
                assertEquals("[\"home-1\",\"work_2\"]", effective.getJSONArray("proximitySafeZoneIds").toString());
                assertTrue(effective.getBoolean("enabled"));
                assertFalse(effective.getBoolean("lockEnabled"));
                assertFalse(effective.getBoolean("unlockEnabled"));
                assertTrue(effective.getBoolean("dryRun"));
            }
        }
    }

    @Test
    public void enabledZoneRestrictionRequiresSelectionEvenWithMasterOff() throws Exception {
        for (String key : new String[] {"restrictLockToOutsideSafeZones", "restrictUnlockToOutsideSafeZones"})
            rejected(RemoteAccessSettings.defaults(), new JSONObject().put(key, true));
        JSONObject saved = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("restrictLockToOutsideSafeZones", true)
                        .put("proximitySafeZoneIds", new JSONArray().put("home")));
        assertFalse(saved.getBoolean("enabled"));
        rejected(saved, new JSONObject().put("proximitySafeZoneIds", new JSONArray()));
        JSONObject cleared = RemoteAccessSettings.merge(saved,
                new JSONObject().put("restrictLockToOutsideSafeZones", false)
                        .put("proximitySafeZoneIds", new JSONArray()));
        assertFalse(cleared.getBoolean("restrictLockToOutsideSafeZones"));
        assertEquals(0, cleared.getJSONArray("proximitySafeZoneIds").length());
    }

    @Test
    public void zoneSelectionRequiresUniqueBoundedOpaqueTextIds() throws Exception {
        for (Object invalid : new Object[] {JSONObject.NULL, "home", true, 1, new JSONObject()})
            rejected(RemoteAccessSettings.defaults(), new JSONObject().put("proximitySafeZoneIds", invalid));
        for (Object invalid : new Object[] {JSONObject.NULL, true, 1, "", " home", "home ",
                "../home", "<home>", new JSONObject(), new JSONArray()})
            rejected(RemoteAccessSettings.defaults(), new JSONObject()
                    .put("proximitySafeZoneIds", new JSONArray().put(invalid)));
        rejected(RemoteAccessSettings.defaults(), new JSONObject()
                .put("proximitySafeZoneIds", new JSONArray().put("home").put("home")));
        StringBuilder longId = new StringBuilder();
        for (int i = 0; i < 121; i++) longId.append('x');
        rejected(RemoteAccessSettings.defaults(), new JSONObject()
                .put("proximitySafeZoneIds", new JSONArray().put(longId.toString())));
        JSONArray ten = new JSONArray();
        for (int i = 0; i < 10; i++) ten.put("zone_" + i);
        JSONObject saved = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("proximitySafeZoneIds", ten));
        assertEquals(10, saved.getJSONArray("proximitySafeZoneIds").length());
        ten.put("zone_10");
        rejected(saved, new JSONObject().put("proximitySafeZoneIds", ten));
        assertEquals(10, saved.getJSONArray("proximitySafeZoneIds").length());
    }

    @Test
    public void savedZoneSelectionIsCopiedAndRetainedWhenRestrictionIsDisabled() throws Exception {
        JSONArray selected = new JSONArray().put("home");
        JSONObject saved = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("proximitySafeZoneIds", selected)
                        .put("restrictUnlockToOutsideSafeZones", true));
        selected.put("work");
        assertEquals("[\"home\"]", saved.getJSONArray("proximitySafeZoneIds").toString());
        JSONObject disabled = RemoteAccessSettings.merge(saved,
                new JSONObject().put("restrictUnlockToOutsideSafeZones", false));
        assertEquals("[\"home\"]", disabled.getJSONArray("proximitySafeZoneIds").toString());
        assertTrue(saved.getBoolean("restrictUnlockToOutsideSafeZones"));
        assertFalse(disabled.getBoolean("restrictUnlockToOutsideSafeZones"));
    }

    @Test
    public void corruptSavedZoneProtectionRevokesMonitoringInsteadOfAllowingCommands() throws Exception {
        for (JSONObject bad : new JSONObject[] {
                new JSONObject().put("restrictUnlockToOutsideSafeZones", true),
                new JSONObject().put("restrictLockToOutsideSafeZones", "true"),
                new JSONObject().put("proximitySafeZoneIds", new JSONArray().put(1))
        }) {
            JSONObject original = trustedPair().put("lockEnabled", true).put("unlockEnabled", true)
                    .put("dryRun", false);
            java.util.Iterator<String> keys = bad.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                original.put(key, bad.get(key));
            }
            JSONObject restored = RemoteAccessSettings.fromPersisted(original);
            assertFalse(restored.getBoolean("enabled"));
            assertFalse(restored.getBoolean("lockEnabled"));
            assertFalse(restored.getBoolean("unlockEnabled"));
            assertTrue(restored.getBoolean("dryRun"));
        }
    }

    private static JSONObject phone(String address, String name, boolean enabled, int near, int away)
            throws Exception {
        return new JSONObject().put("address", address).put("name", name).put("enabled", enabled)
                .put("nearThreshold", near).put("awayThreshold", away);
    }

    private static JSONObject trustedPair() throws Exception {
        return RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("enabled", true).put("deviceAddress", PHONE).put("phones", new JSONArray()
                        .put(phone(PHONE, "Owner", true, -8, -35))
                        .put(phone(SECOND_PHONE, "Family", true, -18, -40))));
    }

    private static void rejected(JSONObject current, JSONObject patch) throws Exception {
        try {
            RemoteAccessSettings.merge(current, patch);
            fail("Expected settings rejection: " + patch);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        } catch (JSONException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }

}
