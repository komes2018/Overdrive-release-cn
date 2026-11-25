package com.overdrive.app.remoteaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Exercises the save boundary with real settings merge and a counted pairing lookup. */
public class RemoteAccessPhoneSavePolicyTest {
    private static final String FIRST = "AA:BB:CC:DD:EE:01";
    private static final String SECOND = "AA:BB:CC:DD:EE:02";
    private static final String THIRD = "AA:BB:CC:DD:EE:03";
    private static final String UNPAIRED = "Every enabled trusted phone must be paired with the car";

    private static final class Reader implements RemoteAccessPhoneSavePolicy.PairingReader {
        final JSONObject response;
        int calls;
        Reader(JSONObject response) { this.response = response; }
        @Override public JSONObject read() { calls++; return response; }
    }

    @Test
    public void keyFobLockSaveDoesNotReadEmptyPairingListWithBluetoothOff() throws Exception {
        JSONObject current = settings(true, phone(FIRST, true));
        JSONObject next = RemoteAccessSettings.merge(current,
                new JSONObject().put("keyFobEnabled", true).put("keyFobLockEnabled", true));
        Reader reader = new Reader(paired());

        assertNull(RemoteAccessPhoneSavePolicy.validateRequiredPairings(current, next, reader));
        assertEquals(0, reader.calls);
        assertTrue(next.getBoolean("keyFobLockEnabled"));
        assertTrue(next.getBoolean("keyFobEnabled"));
        assertFalse(current.getBoolean("keyFobLockEnabled"));
    }

    @Test
    public void unrelatedTimingThresholdNameAndRadioEditsRequireNoPairingLookup() throws Exception {
        JSONObject current = settings(true, phone(FIRST, true));
        JSONObject changedPhone = phone(FIRST.toLowerCase(), true).put("name", "Renamed phone")
                .put("nearThreshold", -10).put("awayThreshold", -30);
        JSONObject next = RemoteAccessSettings.merge(current,
                new JSONObject().put("phones", new JSONArray().put(changedPhone))
                        .put("advancedMode", true).put("keepBluetoothOn", false)
                        .put("nearWindowMs", 6000).put("keyFobLockWindowMs", 20000)
                        .put("unlockEnabled", true).put("lockEnabled", true));
        Reader unavailable = new Reader(null);

        assertNull(RemoteAccessPhoneSavePolicy.validateRequiredPairings(current, next, unavailable));
        assertEquals(0, unavailable.calls);
        assertEquals("Renamed phone", next.getJSONArray("phones").getJSONObject(0).getString("name"));
    }

    @Test
    public void SelectingAnotherAlreadyEnabledPhoneDoesNotReauthorizeIt() throws Exception {
        JSONObject current = settings(true, phone(FIRST, true), phone(SECOND, true));
        JSONObject next = RemoteAccessSettings.merge(current,
                new JSONObject().put("deviceAddress", SECOND.toLowerCase())
                        .put("phones", new JSONArray().put(phone(SECOND.toLowerCase(), true))
                                .put(phone(FIRST.toLowerCase(), true))));
        Reader unavailable = new Reader(null);
        assertNull(RemoteAccessPhoneSavePolicy.validateRequiredPairings(current, next, unavailable));
        assertEquals(0, unavailable.calls);
    }

    @Test
    public void NewEnabledPhoneMustBeCurrentlyPaired() throws Exception {
        JSONObject current = settings(true, phone(FIRST, true));
        JSONObject next = RemoteAccessSettings.merge(current, new JSONObject().put("phones",
                new JSONArray().put(phone(FIRST, true)).put(phone(SECOND, true))));
        Reader reader = new Reader(paired(FIRST));
        rejected(current, next, reader, UNPAIRED);
        assertEquals(1, reader.calls);
    }

    @Test
    public void NewlyEnabledPhoneIsCheckedWithoutRequiringUnchangedPhonesInRadioSnapshot() throws Exception {
        JSONObject current = settings(true, phone(FIRST, true));
        JSONObject next = RemoteAccessSettings.merge(current, new JSONObject().put("phones",
                new JSONArray().put(phone(FIRST, true)).put(phone(SECOND, true))));
        Reader reader = new Reader(paired(SECOND));
        assertSame(reader.response.getJSONArray("devices"),
                RemoteAccessPhoneSavePolicy.validateRequiredPairings(current, next, reader));
        assertEquals(1, reader.calls);
    }

    @Test
    public void ReenablingDisabledPhoneRequiresCurrentPairing() throws Exception {
        JSONObject current = settings(true, phone(FIRST, true), phone(SECOND, false));
        JSONObject next = RemoteAccessSettings.merge(current, new JSONObject().put("phones",
                new JSONArray().put(phone(FIRST, true)).put(phone(SECOND, true))));
        Reader reader = new Reader(paired(FIRST));
        rejected(current, next, reader, UNPAIRED);
        assertEquals(1, reader.calls);
    }

    @Test
    public void DisablingThenReenablingPreviouslyApprovedPhoneRequiresPairingAgain() throws Exception {
        JSONObject original = settings(true, phone(FIRST, true), phone(SECOND, true));
        JSONObject disabled = RemoteAccessSettings.merge(original, new JSONObject().put("phones",
                new JSONArray().put(phone(FIRST, true)).put(phone(SECOND, false))));
        Reader unavailable = new Reader(null);
        assertNull(RemoteAccessPhoneSavePolicy.validateRequiredPairings(original, disabled, unavailable));
        assertEquals(0, unavailable.calls);
        JSONObject reenabled = RemoteAccessSettings.merge(disabled, new JSONObject().put("phones",
                new JSONArray().put(phone(FIRST, true)).put(phone(SECOND, true))));
        Reader unpaired = new Reader(paired(FIRST));
        rejected(disabled, reenabled, unpaired, UNPAIRED);
        assertEquals(1, unpaired.calls);
    }

    @Test
    public void ChangingEnabledPhoneAddressRequiresPairingForReplacement() throws Exception {
        JSONObject current = settings(true, phone(FIRST, true));
        JSONObject next = RemoteAccessSettings.merge(current,
                new JSONObject().put("deviceAddress", SECOND).put("phones",
                        new JSONArray().put(phone(SECOND, true))));
        Reader reader = new Reader(paired(FIRST));
        rejected(current, next, reader, UNPAIRED);
        assertEquals(1, reader.calls);
        Reader replacementPaired = new Reader(paired(SECOND.toLowerCase()));
        assertSame(replacementPaired.response.getJSONArray("devices"),
                RemoteAccessPhoneSavePolicy.validateRequiredPairings(current, next, replacementPaired));
    }

    @Test
    public void AddingDisabledPhonesAndRemovingApprovedPhonesNeedsNoPairingLookup() throws Exception {
        JSONObject current = settings(true, phone(FIRST, true), phone(SECOND, true));
        JSONObject next = RemoteAccessSettings.merge(current, new JSONObject().put("phones",
                new JSONArray().put(phone(FIRST, true)).put(phone(THIRD, false))));
        Reader unavailable = new Reader(null);
        assertNull(RemoteAccessPhoneSavePolicy.validateRequiredPairings(current, next, unavailable));
        assertEquals(0, unavailable.calls);
    }

    @Test
    public void ReaddingRemovedEnabledPhoneRequiresFreshPairing() throws Exception {
        JSONObject current = settings(true, phone(FIRST, true), phone(SECOND, true));
        JSONObject removed = RemoteAccessSettings.merge(current, new JSONObject().put("phones",
                new JSONArray().put(phone(FIRST, true))));
        JSONObject restored = RemoteAccessSettings.merge(removed, new JSONObject().put("phones",
                new JSONArray().put(phone(FIRST, true)).put(phone(SECOND, true))));
        Reader reader = new Reader(paired(FIRST));
        rejected(removed, restored, reader, UNPAIRED);
        assertEquals(1, reader.calls);
    }

    @Test
    public void MasterOffToOnChecksEveryEnabledPhoneEvenWhenRosterIsUnchanged() throws Exception {
        JSONObject current = settings(false, phone(FIRST, true), phone(SECOND, true));
        JSONObject next = RemoteAccessSettings.merge(current, new JSONObject().put("enabled", true));
        Reader reader = new Reader(paired(FIRST));
        rejected(current, next, reader, UNPAIRED);
        assertEquals(1, reader.calls);
        Reader bothPaired = new Reader(paired(FIRST, SECOND));
        assertSame(bothPaired.response.getJSONArray("devices"),
                RemoteAccessPhoneSavePolicy.validateRequiredPairings(current, next, bothPaired));
    }

    @Test
    public void MasterOffEditsKeepDraftsWithoutAuthorizingMonitoring() throws Exception {
        JSONObject current = settings(false, phone(FIRST, true));
        JSONObject next = RemoteAccessSettings.merge(current, new JSONObject().put("phones",
                new JSONArray().put(phone(FIRST, true)).put(phone(SECOND, true))));
        Reader unavailable = new Reader(null);
        assertNull(RemoteAccessPhoneSavePolicy.validateRequiredPairings(current, next, unavailable));
        assertEquals(0, unavailable.calls);
        JSONObject enabled = RemoteAccessSettings.merge(next, new JSONObject().put("enabled", true));
        Reader unpaired = new Reader(paired(FIRST));
        rejected(next, enabled, unpaired, UNPAIRED);
    }

    @Test
    public void DisablingMasterNeverRequiresPairingReader() throws Exception {
        JSONObject current = settings(true, phone(FIRST, true));
        JSONObject next = RemoteAccessSettings.merge(current, new JSONObject().put("enabled", false));
        Reader unavailable = new Reader(null);
        assertNull(RemoteAccessPhoneSavePolicy.validateRequiredPairings(current, next, unavailable));
        assertEquals(0, unavailable.calls);
    }

    @Test
    public void KeyFobOnlyMonitoringNeedsNoPairingLookup() throws Exception {
        JSONObject current = RemoteAccessSettings.defaults();
        JSONObject next = RemoteAccessSettings.merge(current,
                new JSONObject().put("enabled", true).put("keyFobEnabled", true));
        Reader unavailable = new Reader(null);
        assertNull(RemoteAccessPhoneSavePolicy.validateRequiredPairings(current, next, unavailable));
        assertEquals(0, unavailable.calls);
    }

    @Test
    public void UnavailableBridgeCannotAuthorizeNewPhoneOrStartMonitoring() throws Exception {
        JSONObject current = settings(false, phone(FIRST, true));
        JSONObject next = RemoteAccessSettings.merge(current, new JSONObject().put("enabled", true));
        for (JSONObject response : new JSONObject[] {null, new JSONObject().put("success", false)}) {
            Reader reader = new Reader(response);
            rejected(current, next, reader, "Open the car app before enabling monitoring");
            assertEquals(1, reader.calls);
        }
    }

    @Test
    public void MissingOrEmptyDeviceListCannotAuthorizeNewPhone() throws Exception {
        JSONObject current = settings(false, phone(FIRST, true));
        JSONObject next = RemoteAccessSettings.merge(current, new JSONObject().put("enabled", true));
        for (JSONObject response : new JSONObject[] {new JSONObject().put("success", true), paired()}) {
            Reader reader = new Reader(response);
            rejected(current, next, reader, UNPAIRED);
        }
    }

    private static JSONObject settings(boolean enabled, JSONObject... phones) throws Exception {
        JSONArray roster = new JSONArray();
        for (JSONObject phone : phones) roster.put(phone);
        return RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("enabled", enabled).put("keyFobEnabled", true)
                        .put("deviceAddress", phones[0].getString("address")).put("phones", roster));
    }

    private static JSONObject phone(String address, boolean enabled) throws Exception {
        return new JSONObject().put("address", address).put("name", "Phone").put("enabled", enabled)
                .put("nearThreshold", -12).put("awayThreshold", -24);
    }

    private static JSONObject paired(String... addresses) throws Exception {
        JSONArray devices = new JSONArray();
        for (String address : addresses) devices.put(new JSONObject().put("address", address));
        return new JSONObject().put("success", true).put("devices", devices);
    }

    private static void rejected(JSONObject current, JSONObject next, Reader reader, String message)
            throws Exception {
        try {
            RemoteAccessPhoneSavePolicy.validateRequiredPairings(current, next, reader);
            fail("Expected pairing authorization to reject the settings");
        } catch (IllegalArgumentException expected) {
            assertEquals(message, expected.getMessage());
        }
    }
}
