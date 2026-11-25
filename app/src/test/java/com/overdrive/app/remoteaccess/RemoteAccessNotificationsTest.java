package com.overdrive.app.remoteaccess;

import com.overdrive.app.notifications.NotificationEvent;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class RemoteAccessNotificationsTest {
    private JSONObject settings() throws Exception {
        return new JSONObject().put("enabled", true).put("dryRun", false).put("revision", 7L)
                .put("lockEnabled", true).put("unlockEnabled", true)
                .put("notifyPush", true).put("notifyTelegram", true);
    }

    private NotificationEvent event(JSONObject settings, String action, String outcome)
            throws Exception {
        return RemoteAccessNotifications.eventFor(settings, 7L, action, outcome,
                "Stable phone arrival", "Yash's iPhone");
    }

    @Test public void channelsRequireIndependentExplicitConsent() throws Exception {
        JSONObject settings = settings().put("notifyPush", false);
        NotificationEvent event = event(settings, "unlock", "confirmed");
        assertNotNull(event);
        assertTrue(event.isPushSuppressed());
        assertFalse(RemoteAccessNotifications.allows(settings, event.data, "push"));
        assertTrue(RemoteAccessNotifications.allows(settings, event.data, "telegram"));
        settings.put("notifyTelegram", false);
        assertNull(event(settings, "unlock", "confirmed"));
        settings.remove("notifyPush");
        settings.remove("notifyTelegram");
        assertNull(event(settings, "unlock", "confirmed"));
    }

    @Test public void disablingOrChangingSettingsCancelsQueuedDelivery() throws Exception {
        JSONObject settings = settings();
        NotificationEvent event = event(settings, "unlock", "confirmed");
        assertTrue(RemoteAccessNotifications.allows(settings, event.data, "push"));
        settings.put("enabled", false);
        assertFalse(RemoteAccessNotifications.allows(settings, event.data, "push"));
        assertFalse(RemoteAccessNotifications.allows(settings, event.data, "telegram"));
        settings.put("enabled", true).put("revision", 8L);
        assertFalse(RemoteAccessNotifications.allows(settings, event.data, "push"));
        assertFalse(RemoteAccessNotifications.allows(settings, event.data, "telegram"));
        assertNull(event(settings, "unlock", "confirmed"));
    }

    @Test public void learningAndDisabledActionsCannotNotify() throws Exception {
        JSONObject settings = settings().put("dryRun", true);
        assertNull(event(settings, "unlock", "confirmed"));
        settings.put("dryRun", false).put("unlockEnabled", false);
        assertNull(event(settings, "unlock", "confirmed"));
        assertNotNull(event(settings, "lock", "confirmed"));
        settings.remove("dryRun");
        assertNull(event(settings, "lock", "confirmed"));
    }

    @Test public void acceptedCommandDoesNotClaimVehicleUnlocked() throws Exception {
        NotificationEvent accepted = event(settings(), "unlock", "unconfirmed");
        assertEquals(NotificationEvent.Severity.CRITICAL, accepted.severity);
        assertTrue(accepted.title.endsWith("unconfirmed"));
        assertTrue(accepted.body.contains("was accepted"));
        assertTrue(accepted.body.contains("state was not confirmed"));
        assertFalse(accepted.body.startsWith("Vehicle unlocked"));
        assertEquals("/remote-access.html", accepted.clickUrl);
        assertFalse(accepted.isPreferencesBypassed());
        NotificationEvent failed = event(settings(), "unlock", "failed");
        assertTrue(failed.title.endsWith("failed"));
        assertFalse(failed.body.startsWith("Vehicle unlocked"));
        NotificationEvent confirmed = event(settings(), "unlock", "confirmed");
        assertTrue(confirmed.body.startsWith("Driver-door lock state is unlocked."));
    }

    @Test public void invalidAndNonterminalEventsAreRejected() throws Exception {
        assertNull(event(settings(), "open_trunk", "confirmed"));
        assertNull(event(settings(), "unlock", "already_target"));
        assertNull(event(settings(), "unlock", "learning"));
        assertNull(event(settings(), "unlock", "started"));
        NotificationEvent event = event(settings(), "unlock", "confirmed");
        event.data.remove("revision");
        assertFalse(RemoteAccessNotifications.allows(settings(), event.data, "push"));
        assertFalse(RemoteAccessNotifications.allows(settings(), new JSONObject(), "telegram"));
        assertFalse(RemoteAccessNotifications.allows(settings(), null, "telegram"));
        assertFalse(RemoteAccessNotifications.allows(settings(), event.data, "unknown"));
    }

    @Test public void phoneLabelRemainsPlainTextAndControlCharactersAreRemoved() throws Exception {
        NotificationEvent event = RemoteAccessNotifications.eventFor(settings(), 7L,
                "lock", "confirmed", "Departure\nstable",
                "Phone_*[test]\u0000\nlabel");
        assertEquals("Phone_*[test]  label", event.data.getString("deviceName"));
        assertEquals("Departure stable", event.data.getString("reason"));
        assertFalse(event.body.contains("\u0000"));
    }

    @Test public void physicalFobUnlockDoesNotRequireDepartureConsent() throws Exception {
        JSONObject settings = settings().put("keyFobEnabled", true);
        NotificationEvent event = RemoteAccessNotifications.eventFor(settings, 7L, "unlock",
                "confirmed", "Fresh physical key fob arrival", "Physical key fob", "key_fob_proximity");
        assertNotNull(event);
        assertEquals("key_fob_proximity", event.data.getString("source"));
        assertEquals(NotificationEvent.Severity.CRITICAL, event.severity);
        assertTrue(event.body.contains("Key: Physical key fob."));
        assertFalse(event.body.contains("Phone:"));
        assertTrue(RemoteAccessNotifications.allows(settings, event.data, "push"));
        settings.put("keyFobEnabled", false);
        assertFalse(RemoteAccessNotifications.allows(settings, event.data, "push"));
        assertFalse(RemoteAccessNotifications.allows(settings, event.data, "telegram"));
        assertNull(RemoteAccessNotifications.eventFor(settings, 7L, "unlock", "confirmed",
                "Fob arrival", "Physical key fob", "key_fob_proximity"));
        settings.put("keyFobEnabled", true);
        assertNull(RemoteAccessNotifications.eventFor(settings, 7L, "lock", "confirmed",
                "Fob departure", "Physical key fob", "key_fob_proximity"));
    }

    @Test public void physicalFobLockRequiresSourceActionAndChannelConsent() throws Exception {
        JSONObject settings = settings().put("keyFobEnabled", true)
                .put("keyFobLockEnabled", true).put("notifyPush", false);
        NotificationEvent event = RemoteAccessNotifications.eventFor(settings, 7L, "lock",
                "confirmed", "Fresh physical key fob departure", "Physical key fob", "key_fob_proximity");
        assertNotNull(event);
        assertEquals("key_fob_proximity", event.data.getString("source"));
        assertEquals(NotificationEvent.Severity.CRITICAL, event.severity);
        assertTrue(event.body.contains("Key: Physical key fob."));
        assertTrue(event.isPushSuppressed());
        assertFalse(RemoteAccessNotifications.allows(settings, event.data, "push"));
        assertTrue(RemoteAccessNotifications.allows(settings, event.data, "telegram"));

        settings.put("keyFobLockEnabled", false);
        assertFalse(RemoteAccessNotifications.allows(settings, event.data, "telegram"));
        assertNull(RemoteAccessNotifications.eventFor(settings, 7L, "lock", "confirmed",
                "Fob departure", "Physical key fob", "key_fob_proximity"));
        settings.put("keyFobLockEnabled", true).put("lockEnabled", false);
        assertFalse(RemoteAccessNotifications.allows(settings, event.data, "telegram"));
        settings.put("lockEnabled", true).put("keyFobEnabled", false);
        assertFalse(RemoteAccessNotifications.allows(settings, event.data, "telegram"));
    }

    @Test public void activePresenceLockUsesFobLabelAndExistingPriorityChannels() throws Exception {
        JSONObject settings = settings().put("keyFobEnabled", true).put("keyFobLockEnabled", true);
        NotificationEvent event = RemoteAccessNotifications.eventFor(settings, 7L, "lock",
                "confirmed", "Confirmed physical key fob absence", "Physical key fob", "key_fob_presence");
        assertNotNull(event);
        assertEquals("key_fob_presence", event.data.getString("source"));
        assertEquals(NotificationEvent.Severity.CRITICAL, event.severity);
        assertTrue(event.body.contains("Key: Physical key fob."));
        assertFalse(event.body.contains("Phone:"));
        assertTrue(RemoteAccessNotifications.allows(settings, event.data, "push"));
        assertTrue(RemoteAccessNotifications.allows(settings, event.data, "telegram"));
        settings.put("keyFobLockEnabled", false);
        assertFalse(RemoteAccessNotifications.allows(settings, event.data, "push"));
        assertFalse(RemoteAccessNotifications.allows(settings, event.data, "telegram"));
    }

    @Test public void presenceCannotNotifyUnlockAndRespectsMasterRevisionAndLearning() throws Exception {
        JSONObject settings = settings().put("keyFobEnabled", true).put("keyFobLockEnabled", true);
        assertNull(RemoteAccessNotifications.eventFor(settings, 7L, "unlock",
                "confirmed", "Key present", "Physical key fob", "key_fob_presence"));
        NotificationEvent event = RemoteAccessNotifications.eventFor(settings, 7L, "lock",
                "confirmed", "Key absent", "Physical key fob", "key_fob_presence");
        settings.put("dryRun", true);
        assertFalse(RemoteAccessNotifications.allows(settings, event.data, "push"));
        settings.put("dryRun", false).put("revision", 8L);
        assertFalse(RemoteAccessNotifications.allows(settings, event.data, "push"));
        settings.put("revision", 7L).put("enabled", false);
        assertFalse(RemoteAccessNotifications.allows(settings, event.data, "telegram"));
    }
}
