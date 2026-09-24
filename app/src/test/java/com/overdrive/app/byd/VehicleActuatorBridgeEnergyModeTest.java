package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.logging.DaemonLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/** Pins ordered, conflating delivery from the daemon process to the actuator service. */
public class VehicleActuatorBridgeEnergyModeTest {

    private DaemonLogger.Config previousLogConfig;

    @Before
    public void disableAndroidAndFileLogging() {
        previousLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
    }

    @After
    public void restoreLogging() {
        DaemonLogger.configure(previousLogConfig);
    }

    @Test
    public void delayedOlderGenerationCannotReplaceNewerDispatch() {
        VehicleActuatorBridge.EnergyGenerationGate gate =
                new VehicleActuatorBridge.EnergyGenerationGate();

        assertTrue(gate.claim(100L));
        assertTrue(gate.claim(300L));
        assertFalse(gate.claim(200L));
        assertFalse(gate.claim(300L));
        assertTrue(gate.isCurrent(300L));
    }

    @Test
    public void definiteLaunchFailureRetriesOnceAndReturnsRetryOutcome() {
        VehicleActuatorBridge.EnergyGenerationGate gate =
                new VehicleActuatorBridge.EnergyGenerationGate();
        int[] calls = {0};

        boolean launched = VehicleActuatorBridge.dispatchEnergyMode(
                3, 11L, gate, (command, tag, timeoutMs) ->
                        calls[0]++ == 0
                                ? VehicleActuatorBridge.LaunchOutcome.FAILURE
                                : VehicleActuatorBridge.LaunchOutcome.SUCCESS);

        assertTrue(launched);
        assertEquals(2, calls[0]);
    }

    @Test
    public void timeoutIsNotRetriedOrReportedAsSuccess() {
        VehicleActuatorBridge.EnergyGenerationGate gate =
                new VehicleActuatorBridge.EnergyGenerationGate();
        int[] calls = {0};

        boolean launched = VehicleActuatorBridge.dispatchEnergyMode(
                1, 12L, gate, (command, tag, timeoutMs) -> {
                    calls[0]++;
                    return VehicleActuatorBridge.LaunchOutcome.TIMEOUT;
                });

        assertFalse(launched);
        assertEquals(1, calls[0]);
    }

    @Test
    public void bridgeCommandCarriesModeAndSourceGenerationAsStrings() {
        String command = VehicleActuatorBridge.buildEnergyCommand(
                new VehicleActuatorBridge.EnergyDispatch(3, 9876543210L));

        assertTrue(command.contains(
                "-n com.overdrive.app/.services.EnergyModeActuatorService"));
        assertTrue(command.contains("--es action energy_mode"));
        assertTrue(command.contains("--es mode 3"));
        assertTrue(command.contains("--es request_generation 9876543210"));
    }

    @Test
    public void standaloneCommandUsesTheSystemPackageContextPath() {
        String command = VehicleActuatorBridge.buildStandaloneEnergyCommand(
                3, 9876543210L);

        assertEquals(
                "/system/bin/app_process /system/bin --nice-name=overdrive_byd_mode "
                        + "com.overdrive.app.byd.BydModeCommand energy 3 9876543210",
                command);
        assertTrue(BydModeCommand.validArguments(new String[] {"energy", "1", "10"}));
        assertTrue(BydModeCommand.validArguments(new String[] {"energy", "3", "11"}));
        assertTrue(BydModeCommand.validArguments(new String[] {"drive", "4"}));
        assertFalse(BydModeCommand.validArguments(new String[] {"energy", "3"}));
        assertFalse(BydModeCommand.validArguments(new String[] {"energy", "2", "12"}));
        assertFalse(BydModeCommand.validArguments(new String[] {"energy", "3", "0"}));
        assertFalse(BydModeCommand.validArguments(new String[] {"drive", "5"}));
        assertEquals(null, VehicleActuatorBridge.buildStandaloneEnergyCommand(2, 12L));
        assertEquals(null, VehicleActuatorBridge.buildStandaloneEnergyCommand(3, 0L));
        assertEquals(
                "/system/bin/app_process /system/bin --nice-name=overdrive_byd_mode "
                        + "com.overdrive.app.byd.BydModeCommand drive 4",
                VehicleActuatorBridge.buildStandaloneDriveCommand(4));
    }

    @Test
    public void standaloneHelperIsThePrimaryPowerTerrainPath() throws Exception {
        String collector = new String(
                Files.readAllBytes(Paths.get(
                        "src/main/java/com/overdrive/app/byd/BydDataCollector.java")),
                StandardCharsets.UTF_8);
        int methodStart = collector.indexOf("public boolean setEnergyMode(int mode)");
        int methodEnd = collector.indexOf("private static final class EnergyRequest", methodStart);
        String method = collector.substring(methodStart, methodEnd);

        int standalone = method.indexOf(
                "dispatchStandaloneEnergyMode(\n                                context, mode, generation)");
        int service = method.indexOf("dispatchEnergyMode(context, mode, generation)");
        assertTrue(standalone >= 0);
        assertTrue(service > standalone);
        int ownershipCheck = method.indexOf("isEnergyActuatorOwnershipAvailable(");
        int daemonSetter = method.indexOf("invokeOptionalModeSetterBounded(");
        assertTrue(ownershipCheck > service);
        assertTrue(daemonSetter > ownershipCheck);
    }

    @Test
    public void genericEnergyListenerFallbackFeedsRawModeDiagnostics() throws Exception {
        String collector = new String(
                Files.readAllBytes(Paths.get(
                        "src/main/java/com/overdrive/app/byd/BydDataCollector.java")),
                StandardCharsets.UTF_8);
        int typed = collector.indexOf("BydDeviceHelper.registerEnergyListener(");
        int fallback = collector.indexOf("BydDeviceHelper.registerListener(", typed);
        int fallbackEnd = collector.indexOf(
                "Energy listener registered (generic diagnostic fallback)", fallback);
        String fallbackRegistration = collector.substring(fallback, fallbackEnd);

        assertTrue(fallbackRegistration.contains("onEnergyCallback("));
        assertTrue(fallbackRegistration.contains("energyListenerDevice, method, args"));
        assertFalse(fallbackRegistration.contains("this::onDisplayCallback"));

        int handlerStart = collector.indexOf("private void onEnergyCallback(");
        int handlerEnd = collector.indexOf("private void onGenericCallback(", handlerStart);
        String handler = collector.substring(handlerStart, handlerEnd);
        assertTrue(handler.contains("\"onDataChanged\".equals(method)"));
        assertTrue(handler.contains("\"onDataEventChanged\".equals(method)"));
        assertTrue(handler.contains("[mode-diag] energy feature event id="));
    }

    @Test
    public void standaloneGenerationGuardRejectsStaleOrCancelledMarkers() {
        VehicleActuatorBridge.PublishedEnergyRequest desired =
                VehicleActuatorBridge.PublishedEnergyRequest.desired(100L, 3);

        assertTrue(VehicleActuatorBridge.matchesPublishedEnergyRequest(
                desired, 3, 100L));
        assertFalse(VehicleActuatorBridge.matchesPublishedEnergyRequest(
                desired, 1, 100L));
        assertFalse(VehicleActuatorBridge.matchesPublishedEnergyRequest(
                desired, 3, 101L));
        assertFalse(VehicleActuatorBridge.matchesPublishedEnergyRequest(
                desired.asCancelled(), 3, 100L));
    }

    @Test
    public void appOwnedGenerationCannotTransferToTheDaemonActuator() {
        VehicleActuatorBridge.PublishedEnergyRequest desired =
                VehicleActuatorBridge.PublishedEnergyRequest.desired(100L, 3);
        VehicleActuatorBridge.PublishedEnergyRequest appOwned = desired.withActuation(
                1,
                true,
                false,
                false,
                VehicleActuatorBridge.ENERGY_ACTUATOR_APP,
                0);

        assertTrue(VehicleActuatorBridge.isEnergyActuatorOwnershipAvailable(
                desired, VehicleActuatorBridge.ENERGY_ACTUATOR_DAEMON));
        assertTrue(VehicleActuatorBridge.isEnergyActuatorOwnershipAvailable(
                appOwned, VehicleActuatorBridge.ENERGY_ACTUATOR_APP));
        assertFalse(VehicleActuatorBridge.isEnergyActuatorOwnershipAvailable(
                appOwned, VehicleActuatorBridge.ENERGY_ACTUATOR_DAEMON));
    }

    @Test
    public void kernelBootIdIsTheSameTokenForEveryProcessInTheBoot() {
        assertEquals(
                "boot-12345678-abcd-4321-abcd-1234567890ab",
                VehicleActuatorBridge.buildEnergyBootToken(
                        "12345678-abcd-4321-abcd-1234567890ab"));
    }

    @Test
    public void androidUidMapsToItsSettingsProviderUser() {
        assertEquals(0, VehicleActuatorBridge.userIdForUid(2000));
        assertEquals(10, VehicleActuatorBridge.userIdForUid(1_012_345));
    }

    @Test
    public void bridgeGenerationPlausibilityUsesBoundedBootClockWindow() {
        long now = 100_000_000_000L;

        assertTrue(VehicleActuatorBridge.isPlausibleEnergyGeneration(now, now));
        assertTrue(VehicleActuatorBridge.isPlausibleEnergyGeneration(
                now - TimeUnit.SECONDS.toNanos(60L), now));
        assertFalse(VehicleActuatorBridge.isPlausibleEnergyGeneration(
                now - TimeUnit.SECONDS.toNanos(61L), now));
        assertFalse(VehicleActuatorBridge.isPlausibleEnergyGeneration(
                now + TimeUnit.SECONDS.toNanos(31L), now));
    }

    @Test
    public void staleDesiredMarkersAreBlockedButCancellationCanStillFinish() {
        long now = TimeUnit.MINUTES.toNanos(10L);
        VehicleActuatorBridge.PublishedEnergyRequest desired =
                VehicleActuatorBridge.PublishedEnergyRequest.desired(
                        now - TimeUnit.MINUTES.toNanos(5L), 3);

        assertFalse(VehicleActuatorBridge.isPlausibleEnergyMarker(desired, now));
        assertTrue(VehicleActuatorBridge.isPlausibleEnergyMarker(
                desired.asCancelled(), now));
    }

    @Test
    public void bridgeDispatchAcceptsOnlyFieldValidatedUserModes() {
        assertTrue(VehicleActuatorBridge.isUserWritableEnergyMode(1));
        assertTrue(VehicleActuatorBridge.isUserWritableEnergyMode(3));
        assertFalse(VehicleActuatorBridge.isUserWritableEnergyMode(0));
        assertFalse(VehicleActuatorBridge.isUserWritableEnergyMode(2));
        assertFalse(VehicleActuatorBridge.isUserWritableEnergyMode(4));
        assertFalse(VehicleActuatorBridge.isUserWritableEnergyMode(5));

        int[] launches = {0};
        for (int mode : new int[] {2, 4, 5}) {
            assertFalse(VehicleActuatorBridge.dispatchEnergyMode(
                    mode,
                    100L + mode,
                    new VehicleActuatorBridge.EnergyGenerationGate(),
                    (command, tag, timeoutMs) -> {
                        launches[0]++;
                        return VehicleActuatorBridge.LaunchOutcome.SUCCESS;
                    }));
        }
        assertEquals(0, launches[0]);
    }

    @Test
    public void evAndHevMapToTheSdkRawEnergyCommand() {
        assertEquals(1, VehicleActuatorBridge.rawEnergyModeValue(1));
        assertEquals(2, VehicleActuatorBridge.rawEnergyModeValue(3));
        assertEquals(-1, VehicleActuatorBridge.rawEnergyModeValue(0));
        assertEquals(-1, VehicleActuatorBridge.rawEnergyModeValue(2));
    }

    @Test
    public void rawEnergyWriteUsesTheConfirmedFeatureAndEncoding() {
        GenericEnergyDevice energy = new GenericEnergyDevice();

        assertEquals(0, VehicleActuatorBridge.writeEnergyModeRaw(energy, 3));
        assertEquals(BydFeatureIds.ENERGY_MODE_SET, energy.featureId);
        assertEquals(2, energy.value);

        assertEquals(0, VehicleActuatorBridge.writeEnergyModeRaw(energy, 1));
        assertEquals(BydFeatureIds.ENERGY_MODE_SET, energy.featureId);
        assertEquals(1, energy.value);

        assertEquals(Integer.MIN_VALUE,
                VehicleActuatorBridge.writeEnergyModeRaw(energy, 2));
    }

    @Test
    public void externalSettingsUsesShellIdentityUserAndReleasesProvider() {
        RecordingExternalSettingsAccess access = new RecordingExternalSettingsAccess();

        VehicleActuatorBridge.GlobalSettingCall result =
                VehicleActuatorBridge.callExternalGlobalSetting(
                        2000,
                        "PUT_global",
                        "overdrive_test",
                        "value",
                        access);

        assertTrue(result.success);
        assertEquals("settings", access.authority);
        assertEquals(0, access.userId);
        assertEquals("OverDriveEnergyState", access.tag);
        assertEquals("com.android.shell", access.callingPackage);
        assertEquals("PUT_global", access.method);
        assertEquals("overdrive_test", access.key);
        assertEquals("value", access.value);
        assertTrue(access.released);
    }

    @Test
    public void externalSettingsReleasesProviderWhenCallFails() {
        RecordingExternalSettingsAccess access = new RecordingExternalSettingsAccess();
        access.failCall = true;

        VehicleActuatorBridge.GlobalSettingCall result =
                VehicleActuatorBridge.callExternalGlobalSetting(
                        2000,
                        "GET_global",
                        "overdrive_test",
                        null,
                        access);

        assertFalse(result.success);
        assertNotNull(result.failure);
        assertTrue(access.released);
    }

    @Test
    public void externalSettingsRejectsNonShellUidBeforeAcquisition() {
        RecordingExternalSettingsAccess access = new RecordingExternalSettingsAccess();

        VehicleActuatorBridge.GlobalSettingCall result =
                VehicleActuatorBridge.callExternalGlobalSetting(
                        10000,
                        "GET_global",
                        "overdrive_test",
                        null,
                        access);

        assertFalse(result.success);
        assertEquals(0, access.acquireCalls);
        assertFalse(access.released);
    }

    private static final class RecordingExternalSettingsAccess
            implements VehicleActuatorBridge.ExternalSettingsAccess {
        private final Object holder = new Object();
        int acquireCalls;
        String authority;
        int userId = -1;
        String tag;
        String callingPackage;
        String method;
        String key;
        String value;
        boolean released;
        boolean failCall;

        @Override
        public Object acquire(String authority, int userId, String tag) {
            acquireCalls++;
            this.authority = authority;
            this.userId = userId;
            this.tag = tag;
            return holder;
        }

        @Override
        public VehicleActuatorBridge.GlobalSettingCall call(
                Object holder,
                String callingPackage,
                String authority,
                String method,
                String key,
                int userId,
                String value) {
            this.callingPackage = callingPackage;
            this.authority = authority;
            this.method = method;
            this.key = key;
            this.userId = userId;
            this.value = value;
            if (failCall) throw new IllegalStateException("provider call failed");
            return VehicleActuatorBridge.GlobalSettingCall.success(null);
        }

        @Override
        public void release(String authority, Object holder) {
            released = this.holder == holder && "settings".equals(authority);
        }
    }

    public static final class GenericEnergyDevice {
        int featureId;
        int value;

        public int set(
                int[] featureIds,
                android.hardware.bydauto.BYDAutoEventValue value) {
            if (featureIds.length != 1) return -1;
            featureId = featureIds[0];
            this.value = value.intValue;
            return 0;
        }
    }
}
