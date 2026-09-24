package com.overdrive.app.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

public class DaemonKeepaliveServiceSecurityContractTest {

    @Test
    public void lifecycleAndAvmControlLaneNeverWaitOnTheConfigLock()
            throws Exception {
        String service = new String(
                Files.readAllBytes(Paths.get(
                        "src/main/java/com/overdrive/app/services/"
                                + "DaemonKeepaliveService.kt")),
                StandardCharsets.UTF_8);
        int onCreateStart = service.indexOf("override fun onCreate()");
        int onStartStart = service.indexOf(
                "override fun onStartCommand(", onCreateStart);
        int onDestroyStart = service.indexOf(
                "override fun onDestroy()", onStartStart);
        assertTrue(onCreateStart >= 0);
        assertTrue(onStartStart > onCreateStart);
        assertTrue(onDestroyStart > onStartStart);

        String onCreate = service.substring(onCreateStart, onStartStart);
        String onStart = service.substring(onStartStart, onDestroyStart);
        assertTrue(onCreate.contains(".isVehicleOnOnlyModeSnapshot()"));
        assertTrue(onCreate.contains(
                "ProcessRevivalReceiver.schedule(applicationContext, onOnlySnapshot)"));
        assertFalse(onCreate.contains(".isVehicleOnOnlyMode()"));
        assertFalse(onCreate.contains(".refreshActiveMode()"));
        assertTrue(onCreate.indexOf("startForegroundWithNotification()")
                < onCreate.indexOf(".isVehicleOnOnlyModeSnapshot()"));
        assertTrue(onCreate.contains(
                "startupOnOnlySnapshot = onOnlySnapshot"));

        assertTrue(onStart.contains(
                ".refreshActiveModeFromCommittedMarker()"));
        assertTrue(onStart.contains(".isVehicleOnOnlyModeSnapshot()"));
        assertTrue(onStart.contains(
                "val startupSnapshot = startupOnOnlySnapshot"));
        assertFalse(onStart.contains(".isVehicleOnOnlyMode()"));
        assertFalse(onStart.contains(".refreshActiveMode()"));

        int receiverStart = service.indexOf(
                "private fun registerPowerStateReceiver()");
        int receiverEnd = service.indexOf(
                "private fun unregisterPowerStateReceiver()", receiverStart);
        assertTrue(receiverStart >= 0);
        assertTrue(receiverEnd > receiverStart);
        String receiver = service.substring(receiverStart, receiverEnd);
        assertTrue(receiver.contains(".isVehicleOnOnlyModeSnapshot()"));
        assertFalse(receiver.contains(".isVehicleOnOnlyMode()"));

        int telemetryStart = service.indexOf(
                "private fun syncVehicleTelemetry()");
        int telemetryEnd = service.indexOf(
                "/**", telemetryStart);
        assertTrue(telemetryStart >= 0);
        assertTrue(telemetryEnd > telemetryStart);
        String telemetry = service.substring(telemetryStart, telemetryEnd);
        int worker = telemetry.indexOf("Thread({");
        int refresh = telemetry.indexOf(".refreshActiveMode()");
        assertTrue(worker >= 0);
        assertTrue(refresh > worker);
    }

    @Test
    public void exportedKeepaliveRequiresTheShellPermission() throws Exception {
        String manifest = new String(
                Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")),
                StandardCharsets.UTF_8);
        int service = manifest.indexOf(
                "android:name=\"com.overdrive.app.services.DaemonKeepaliveService\"");
        int end = manifest.indexOf("/>", service);
        assertTrue(service >= 0);
        assertTrue(end > service);
        String declaration = manifest.substring(service, end);
        assertTrue(declaration.contains("android:exported=\"true\""));
        assertTrue(declaration.contains(
                "android:permission=\"android.permission.DUMP\""));
    }
}
