package com.overdrive.app.remoteaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.overdrive.app.byd.DiLink5LockRail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Settings {@code di5LockRails} / {@code di5CarSvcLockMaxEventAgeSec}: PUT rejects an invalid
 * value, a persisted invalid value falls back to the default without resetting the section, and
 * (55.5) the defaults are {@code typed-cabin,carsvc} / 24 h behind an explicit-save marker that
 * lets a deliberate rollback persist while defaults-filled copies from 55.4 are migrated.
 */
public class RemoteAccessSettingsDi5LockRailsTest {

    private static final EnumSet<DiLink5LockRail> DEFAULT_RAILS =
            EnumSet.of(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.CARSVC);

    /** B-T1: defaults() yields rails {TYPED_CABIN, CARSVC} and 86400, in canonical form. */
    @Test
    public void defaultsEnableTypedAndCarsvcRailsWithADayEventAge() throws Exception {
        JSONObject defaults = RemoteAccessSettings.fromPersisted(null);
        assertEquals("typed-cabin,carsvc", defaults.getString("di5LockRails"));
        assertEquals(86_400L, defaults.getLong("di5CarSvcLockMaxEventAgeSec"));
        assertEquals(DEFAULT_RAILS, RemoteAccessSettings.di5LockRails(defaults));
        assertEquals(86_400L, RemoteAccessSettings.di5CarSvcLockMaxEventAgeSec(defaults));
        assertEquals("typed-cabin,carsvc", RemoteAccessSettings.defaults().getString("di5LockRails"));
        assertEquals(86_400, RemoteAccessSettings.defaults().getInt("di5CarSvcLockMaxEventAgeSec"));
        assertEquals("typed-cabin,carsvc", RemoteAccessSettings.DI5_LOCK_RAILS_DEFAULT);
        assertEquals(86_400L, RemoteAccessSettings.DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_DEFAULT);
        // The default is what parseTags/the validator emit: a canonical round trip is a no-op.
        assertEquals(RemoteAccessSettings.DI5_LOCK_RAILS_DEFAULT,
                DiLink5LockRail.toTags(DiLink5LockRail.parseTags(RemoteAccessSettings.DI5_LOCK_RAILS_DEFAULT)));
        assertEquals(RemoteAccessSettings.DI5_LOCK_RAILS_DEFAULT, RemoteAccessSettings.merge(
                RemoteAccessSettings.defaults(),
                new JSONObject().put("di5LockRails", "carsvc, typed-cabin")).getString("di5LockRails"));
        // The marker is never part of the defaults (implicitly false) and the 24 h bound is the max.
        assertFalse(defaults.has("di5LockSettingsExplicit"));
        assertFalse(RemoteAccessSettings.defaults().has(RemoteAccessSettings.DI5_LOCK_SETTINGS_EXPLICIT_KEY));
        assertEquals("di5LockSettingsExplicit", RemoteAccessSettings.DI5_LOCK_SETTINGS_EXPLICIT_KEY);
        assertEquals(RemoteAccessSettings.DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_MAX,
                RemoteAccessSettings.DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_DEFAULT);
    }

    /** B-T2: a 55.4 car whose generic saves echoed the old defaults gets the new defaults on read. */
    @Test
    public void persistedDefaultsFilledByAnOlderBuildAreMigratedToTheNewDefaults() throws Exception {
        RemoteAccessSettings.resetDi5PersistedFallbackForTest();
        JSONObject persisted = new JSONObject()
                .put("enabled", true).put("keyFobEnabled", true).put("dryRun", false)
                .put("revision", 9L)
                .put("di5LockRails", "typed-cabin").put("di5CarSvcLockMaxEventAgeSec", 900);
        JSONObject restored = RemoteAccessSettings.fromPersisted(persisted);
        assertEquals("typed-cabin,carsvc", restored.getString("di5LockRails"));
        assertEquals(86_400L, restored.getLong("di5CarSvcLockMaxEventAgeSec"));
        assertEquals(DEFAULT_RAILS, RemoteAccessSettings.di5LockRails(restored));
        assertEquals(86_400L, RemoteAccessSettings.di5CarSvcLockMaxEventAgeSec(restored));
        assertFalse(restored.has("di5LockSettingsExplicit"));
        // The rest of the section is untouched and no fallback is reported (nothing was invalid).
        assertTrue(restored.getBoolean("enabled"));
        assertTrue(restored.getBoolean("keyFobEnabled"));
        assertFalse(restored.getBoolean("dryRun"));
        assertEquals(9L, restored.getLong("revision"));
        assertNull(RemoteAccessSettings.di5PersistedFallbackDetail());
        // Any non-true marker counts as absent: false, a string, null.
        for (Object marker : new Object[] {false, "true", JSONObject.NULL}) {
            JSONObject odd = RemoteAccessSettings.fromPersisted(new JSONObject()
                    .put("enabled", true).put("keyFobEnabled", true)
                    .put("di5LockRails", "typed-cabin").put("di5CarSvcLockMaxEventAgeSec", 900)
                    .put("di5LockSettingsExplicit", marker));
            assertEquals(String.valueOf(marker), "typed-cabin,carsvc", odd.getString("di5LockRails"));
            assertEquals(String.valueOf(marker), 86_400L, odd.getLong("di5CarSvcLockMaxEventAgeSec"));
            assertFalse(String.valueOf(marker), odd.has("di5LockSettingsExplicit"));
            // A malformed marker never revokes monitoring for the whole section.
            assertTrue(String.valueOf(marker), odd.getBoolean("enabled"));
        }
    }

    /** B-T3: an explicit (marker true) typed-only rollback survives read. */
    @Test
    public void explicitTypedOnlyRollbackIsPreservedAcrossReads() throws Exception {
        JSONObject persisted = new JSONObject()
                .put("enabled", true).put("keyFobEnabled", true).put("revision", 10L)
                .put("di5LockRails", "typed-cabin").put("di5CarSvcLockMaxEventAgeSec", 900)
                .put("di5LockSettingsExplicit", true);
        JSONObject restored = RemoteAccessSettings.fromPersisted(persisted);
        assertEquals("typed-cabin", restored.getString("di5LockRails"));
        assertEquals(900L, restored.getLong("di5CarSvcLockMaxEventAgeSec"));
        assertEquals(EnumSet.of(DiLink5LockRail.TYPED_CABIN), RemoteAccessSettings.di5LockRails(restored));
        assertTrue(restored.getBoolean("di5LockSettingsExplicit"));
        // A restart re-reads the same persisted object: idempotent.
        JSONObject again = RemoteAccessSettings.fromPersisted(new JSONObject(restored.toString()));
        assertEquals("typed-cabin", again.getString("di5LockRails"));
        assertTrue(again.getBoolean("di5LockSettingsExplicit"));
        // effective() carries the marker and the rails like every other saved key.
        JSONObject effective = RemoteAccessSettings.effective(restored);
        assertEquals("typed-cabin", effective.getString("di5LockRails"));
        assertTrue(effective.getBoolean("di5LockSettingsExplicit"));
    }

    /**
     * B-T4: a PUT naming a DI5 key sets the marker; a PUT without one leaves it unchanged (absent
     * stays absent, true stays true); a subsequent generic save (full merged object persisted,
     * then read) carries marker and values along.
     */
    @Test
    public void putOfADi5KeySetsTheMarkerAndGenericSavesCarryItAlong() throws Exception {
        JSONObject current = RemoteAccessSettings.fromPersisted(null);
        JSONObject unrelated = RemoteAccessSettings.merge(current, new JSONObject().put("dryRun", false));
        assertFalse(unrelated.has("di5LockSettingsExplicit"));
        assertEquals("typed-cabin,carsvc", unrelated.getString("di5LockRails"));

        JSONObject rollback = RemoteAccessSettings.merge(current,
                new JSONObject().put("di5LockRails", "typed-cabin"));
        assertTrue(rollback.getBoolean("di5LockSettingsExplicit"));
        assertEquals("typed-cabin", rollback.getString("di5LockRails"));
        assertEquals(86_400L, rollback.getLong("di5CarSvcLockMaxEventAgeSec"));
        JSONObject ageOnly = RemoteAccessSettings.merge(current,
                new JSONObject().put("di5CarSvcLockMaxEventAgeSec", 3600));
        assertTrue(ageOnly.getBoolean("di5LockSettingsExplicit"));
        assertEquals(3_600L, ageOnly.getLong("di5CarSvcLockMaxEventAgeSec"));
        assertEquals("typed-cabin,carsvc", ageOnly.getString("di5LockRails"));
        // Naming a DI5 key wins over an explicit false marker in the same PUT.
        assertTrue(RemoteAccessSettings.merge(current, new JSONObject()
                .put("di5LockRails", "typed-cabin").put("di5LockSettingsExplicit", false))
                .getBoolean("di5LockSettingsExplicit"));

        // Generic save #1 (UI payload never names DI5 keys): persisted = full merged object.
        JSONObject afterRollbackRead = RemoteAccessSettings.fromPersisted(
                new JSONObject(rollback.toString()).put("revision", 11L));
        JSONObject genericSave = RemoteAccessSettings.merge(afterRollbackRead,
                new JSONObject().put("dryRun", false).put("notifyPush", true));
        assertTrue(genericSave.getBoolean("di5LockSettingsExplicit"));
        assertEquals("typed-cabin", genericSave.getString("di5LockRails"));
        // Generic save #2 after a restart: the persisted object is read again, still typed-only.
        JSONObject restarted = RemoteAccessSettings.fromPersisted(
                new JSONObject(genericSave.toString()).put("revision", 12L));
        assertEquals("typed-cabin", restarted.getString("di5LockRails"));
        assertTrue(restarted.getBoolean("di5LockSettingsExplicit"));
        assertEquals(EnumSet.of(DiLink5LockRail.TYPED_CABIN), RemoteAccessSettings.di5LockRails(restarted));
        assertFalse(restarted.getBoolean("dryRun"));
        assertTrue(restarted.getBoolean("notifyPush"));
        // merge still never assigns a revision; the handler does.
        assertEquals(0L, rollback.getLong("revision"));
    }

    /** B-T5: PUT {di5LockSettingsExplicit:false} resets both keys to the defaults on the next read. */
    @Test
    public void clearingTheMarkerResetsBothKeysToTheDefaultsOnNextRead() throws Exception {
        JSONObject explicit = RemoteAccessSettings.fromPersisted(new JSONObject()
                .put("di5LockRails", "typed-cabin").put("di5CarSvcLockMaxEventAgeSec", 900)
                .put("di5LockSettingsExplicit", true));
        assertEquals("typed-cabin", explicit.getString("di5LockRails"));
        JSONObject cleared = RemoteAccessSettings.merge(explicit,
                new JSONObject().put("di5LockSettingsExplicit", false));
        // The PUT result itself still carries the old values (the handler persists this object).
        assertFalse(cleared.getBoolean("di5LockSettingsExplicit"));
        assertEquals("typed-cabin", cleared.getString("di5LockRails"));
        JSONObject nextRead = RemoteAccessSettings.fromPersisted(new JSONObject(cleared.toString()));
        assertEquals("typed-cabin,carsvc", nextRead.getString("di5LockRails"));
        assertEquals(86_400L, nextRead.getLong("di5CarSvcLockMaxEventAgeSec"));
        assertEquals(DEFAULT_RAILS, RemoteAccessSettings.di5LockRails(nextRead));
        assertFalse(nextRead.has("di5LockSettingsExplicit"));
        // The marker is a validated boolean on PUT like every other boolean key.
        for (Object wrongType : new Object[] {0, 1, "true", "false", JSONObject.NULL}) {
            rejected(RemoteAccessSettings.defaults(), new JSONObject().put("di5LockSettingsExplicit", wrongType));
        }
    }

    @Test
    public void putCanonicalisesTheRailList() throws Exception {
        JSONObject saved = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("di5LockRails", "ota, typed-cabin"));
        assertEquals("typed-cabin,ota", saved.getString("di5LockRails"));
        assertEquals(EnumSet.of(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.OTA),
                RemoteAccessSettings.di5LockRails(saved));
        JSONObject carsvcOnly = RemoteAccessSettings.merge(saved,
                new JSONObject().put("di5LockRails", "carsvc"));
        assertEquals("carsvc", carsvcOnly.getString("di5LockRails"));
        assertEquals(EnumSet.of(DiLink5LockRail.CARSVC), RemoteAccessSettings.di5LockRails(carsvcOnly));
        JSONObject all = RemoteAccessSettings.merge(saved,
                new JSONObject().put("di5LockRails", "carsvc,ota,typed-cabin"));
        assertEquals("typed-cabin,ota,carsvc", all.getString("di5LockRails"));
        // merge never assigns a revision (existing rule).
        assertEquals(0L, all.getLong("revision"));
    }

    @Test
    public void putRejectsInvalidRailLists() throws Exception {
        JSONObject current = RemoteAccessSettings.defaults();
        rejected(current, new JSONObject().put("di5LockRails", "cloud"));
        rejected(current, new JSONObject().put("di5LockRails", "typed-cabin,cloud"));
        rejected(current, new JSONObject().put("di5LockRails", ""));
        rejected(current, new JSONObject().put("di5LockRails", "ota,ota"));
        rejected(current, new JSONObject().put("di5LockRails", "typed-cabin,ota,carsvc,ota"));
        rejected(current, new JSONObject().put("di5LockRails", 7));
        rejected(current, new JSONObject().put("di5LockRails", JSONObject.NULL));
        rejected(current, new JSONObject().put("di5LockRails", "OTA"));
    }

    @Test
    public void persistedInvalidRailListFallsBackWithoutResettingTheSection() throws Exception {
        RemoteAccessSettings.resetDi5PersistedFallbackForTest();
        JSONObject persisted = new JSONObject()
                .put("enabled", true).put("keyFobEnabled", true).put("notifyPush", true)
                .put("dryRun", false).put("revision", 12L)
                .put("di5LockRails", "cloud").put("di5LockSettingsExplicit", true);
        JSONObject restored = RemoteAccessSettings.fromPersisted(persisted);
        assertEquals("typed-cabin,carsvc", restored.getString("di5LockRails"));
        assertTrue(restored.getBoolean("enabled"));
        assertTrue(restored.getBoolean("keyFobEnabled"));
        assertTrue(restored.getBoolean("notifyPush"));
        assertFalse(restored.getBoolean("dryRun"));
        assertEquals(12L, restored.getLong("revision"));
        assertEquals(DEFAULT_RAILS, RemoteAccessSettings.di5LockRails(restored));
        String fallback = RemoteAccessSettings.di5PersistedFallbackDetail();
        assertNotNull(fallback);
        assertTrue(fallback, fallback.startsWith("di5LockRails=cloud"));
        // A valid explicit persisted list is kept verbatim (canonicalised).
        JSONObject valid = RemoteAccessSettings.fromPersisted(new JSONObject()
                .put("di5LockRails", "ota,typed-cabin").put("di5LockSettingsExplicit", true));
        assertEquals("typed-cabin,ota", valid.getString("di5LockRails"));
        // Without the marker an invalid copy is simply dropped: defaults, no fallback report.
        RemoteAccessSettings.resetDi5PersistedFallbackForTest();
        JSONObject dropped = RemoteAccessSettings.fromPersisted(new JSONObject()
                .put("enabled", true).put("keyFobEnabled", true).put("di5LockRails", "cloud"));
        assertEquals("typed-cabin,carsvc", dropped.getString("di5LockRails"));
        assertTrue(dropped.getBoolean("enabled"));
        assertNull(RemoteAccessSettings.di5PersistedFallbackDetail());
    }

    @Test
    public void carSvcEventAgeIsRangeCheckedOnPutAndFallsBackWhenPersisted() throws Exception {
        JSONObject current = RemoteAccessSettings.defaults();
        assertEquals(60L, RemoteAccessSettings.merge(current,
                new JSONObject().put("di5CarSvcLockMaxEventAgeSec", 60)).getLong("di5CarSvcLockMaxEventAgeSec"));
        assertEquals(86_400L, RemoteAccessSettings.merge(current,
                new JSONObject().put("di5CarSvcLockMaxEventAgeSec", 86400)).getLong("di5CarSvcLockMaxEventAgeSec"));
        assertEquals(1_800L, RemoteAccessSettings.merge(current,
                new JSONObject().put("di5CarSvcLockMaxEventAgeSec", 1800)).getLong("di5CarSvcLockMaxEventAgeSec"));
        rejected(current, new JSONObject().put("di5CarSvcLockMaxEventAgeSec", 59));
        rejected(current, new JSONObject().put("di5CarSvcLockMaxEventAgeSec", 86401));
        rejected(current, new JSONObject().put("di5CarSvcLockMaxEventAgeSec", "x"));
        rejected(current, new JSONObject().put("di5CarSvcLockMaxEventAgeSec", 120.5));
        rejected(current, new JSONObject().put("di5CarSvcLockMaxEventAgeSec", JSONObject.NULL));

        RemoteAccessSettings.resetDi5PersistedFallbackForTest();
        JSONObject restored = RemoteAccessSettings.fromPersisted(new JSONObject()
                .put("advancedMode", true).put("di5CarSvcLockMaxEventAgeSec", 59)
                .put("di5LockSettingsExplicit", true));
        assertEquals(86_400L, restored.getLong("di5CarSvcLockMaxEventAgeSec"));
        assertTrue(restored.getBoolean("advancedMode"));
        assertEquals(86_400L, RemoteAccessSettings.fromPersisted(new JSONObject()
                .put("di5CarSvcLockMaxEventAgeSec", 86401).put("di5LockSettingsExplicit", true))
                .getLong("di5CarSvcLockMaxEventAgeSec"));
        assertEquals(86_400L, RemoteAccessSettings.fromPersisted(new JSONObject()
                .put("di5CarSvcLockMaxEventAgeSec", "x").put("di5LockSettingsExplicit", true))
                .getLong("di5CarSvcLockMaxEventAgeSec"));
        assertEquals(3_600L, RemoteAccessSettings.fromPersisted(new JSONObject()
                .put("di5CarSvcLockMaxEventAgeSec", 3600).put("di5LockSettingsExplicit", true))
                .getLong("di5CarSvcLockMaxEventAgeSec"));
        String fallback = RemoteAccessSettings.di5PersistedFallbackDetail();
        assertNotNull(fallback);
        assertTrue(fallback, fallback.startsWith("di5CarSvcLockMaxEventAgeSec="));
    }

    @Test
    public void accessorsReturnDefaultsForMissingOrOddValues() throws Exception {
        assertEquals(DEFAULT_RAILS, RemoteAccessSettings.di5LockRails(new JSONObject()));
        assertEquals(86_400L, RemoteAccessSettings.di5CarSvcLockMaxEventAgeSec(new JSONObject()));
        assertEquals(DEFAULT_RAILS, RemoteAccessSettings.di5LockRails(null));
        assertEquals(86_400L, RemoteAccessSettings.di5CarSvcLockMaxEventAgeSec(null));
        assertEquals(DEFAULT_RAILS,
                RemoteAccessSettings.di5LockRails(new JSONObject().put("di5LockRails", "cloud")));
        assertEquals(DEFAULT_RAILS,
                RemoteAccessSettings.di5LockRails(new JSONObject().put("di5LockRails", 3)));
        assertEquals(86_400L, RemoteAccessSettings.di5CarSvcLockMaxEventAgeSec(
                new JSONObject().put("di5CarSvcLockMaxEventAgeSec", 10)));
        assertEquals(86_400L, RemoteAccessSettings.di5CarSvcLockMaxEventAgeSec(
                new JSONObject().put("di5CarSvcLockMaxEventAgeSec", "900")));
        assertEquals(900L, RemoteAccessSettings.di5CarSvcLockMaxEventAgeSec(
                new JSONObject().put("di5CarSvcLockMaxEventAgeSec", 900)));
        assertEquals(EnumSet.of(DiLink5LockRail.TYPED_CABIN),
                RemoteAccessSettings.di5LockRails(new JSONObject().put("di5LockRails", "typed-cabin")));
        assertEquals(EnumSet.of(DiLink5LockRail.OTA, DiLink5LockRail.CARSVC),
                RemoteAccessSettings.di5LockRails(new JSONObject().put("di5LockRails", "carsvc,ota")));
        assertEquals(7_200L, RemoteAccessSettings.di5CarSvcLockMaxEventAgeSec(
                new JSONObject().put("di5CarSvcLockMaxEventAgeSec", 7200)));
        // The accessor never hands out a mutable view.
        try {
            RemoteAccessSettings.di5LockRails(new JSONObject()).add(DiLink5LockRail.OTA);
            fail("rail set must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void effectiveSettingsKeepTheRailKeysInNormalMode() throws Exception {
        JSONObject saved = RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("di5LockRails", "ota").put("di5CarSvcLockMaxEventAgeSec", 120));
        JSONObject effective = RemoteAccessSettings.effective(saved);
        assertEquals("ota", effective.getString("di5LockRails"));
        assertEquals(120L, effective.getLong("di5CarSvcLockMaxEventAgeSec"));
    }

    @Test
    public void daemonAppliesTheRailSetOutsideTheIngressLock() throws Exception {
        String handler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/RemoteAccessApiHandler.java");
        int refresh = handler.indexOf("    private static void refreshSettings() throws Exception {");
        assertTrue(refresh > 0);
        String refreshBody = handler.substring(refresh, handler.indexOf("\n    }\n", refresh));
        assertTrue(refreshBody.contains("JSONObject disk = RemoteAccessSettings.read();"));
        assertTrue(refreshBody.contains("reset(disk);"));
        assertTrue(refreshBody.contains(
                "collector.setDiLink5LockRails(RemoteAccessSettings.di5LockRails(config));"));
        assertTrue(refreshBody.contains("collector.setDiLink5CarSvcLockMaxEventAgeSec("));
        assertTrue(refreshBody.contains("catch (Throwable ignored)"));
        assertTrue(refreshBody.indexOf("reset(disk);") < refreshBody.indexOf("setDiLink5LockRails("));

        int decision = handler.indexOf(
                "    private static void performDecision(ActionDecision decision, JSONObject settings) throws Exception {");
        assertTrue(decision > 0);
        int read = handler.indexOf("JSONObject live = RemoteAccessSettings.read();", decision);
        int apply = handler.indexOf("gate.setDiLink5LockRails(RemoteAccessSettings.di5LockRails(live));", decision);
        int revisionCheck = handler.indexOf(
                "if (!live.optBoolean(\"enabled\") || live.optLong(\"revision\") != settings.optLong(\"revision\")) return;",
                decision);
        assertTrue(read > decision && apply > read && revisionCheck > apply);
        // The handler never takes the collector's ingress lock itself (comments may name it).
        assertFalse(handler.contains("synchronized (diLink5IngressLock)"));
        assertFalse(handler.contains("synchronized (collector.diLink5IngressLock)"));
    }

    private static void rejected(JSONObject current, JSONObject patch) throws Exception {
        try {
            RemoteAccessSettings.merge(current, patch);
            fail("expected rejection of " + patch);
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static String readRepositoryFile(String relative) throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), relative);
        if (!Files.exists(path)) {
            path = Paths.get(System.getProperty("user.dir")).getParent().resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
