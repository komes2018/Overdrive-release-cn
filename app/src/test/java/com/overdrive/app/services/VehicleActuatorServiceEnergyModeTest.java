package com.overdrive.app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class VehicleActuatorServiceEnergyModeTest {

    @Test
    public void energyProcessDoesNotMoveExistingActuators() throws Exception {
        String manifest = new String(
                Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")),
                StandardCharsets.UTF_8);
        int existingService = manifest.indexOf(
                "android:name=\"com.overdrive.app.services.VehicleActuatorService\"");
        int existingServiceEnd = manifest.indexOf("</service>", existingService);
        int energyService = manifest.indexOf(
                "android:name=\"com.overdrive.app.services.EnergyModeActuatorService\"");
        int energyServiceEnd = manifest.indexOf("</service>", energyService);

        assertTrue(existingService >= 0);
        assertTrue(existingServiceEnd > existingService);
        assertFalse(manifest.substring(existingService, existingServiceEnd)
                .contains("android:process="));
        assertTrue(energyService >= 0);
        assertTrue(energyServiceEnd > energyService);
        assertTrue(manifest.substring(energyService, energyServiceEnd)
                .contains("android:process=\"com.byd.warning\""));
    }

    @Test
    public void energySettersUseTheRequiredPackageContexts() throws Exception {
        String service = new String(
                Files.readAllBytes(Paths.get(
                        "src/main/java/com/overdrive/app/services/VehicleActuatorService.java")),
                StandardCharsets.UTF_8);
        int writeStart = service.indexOf(
                "private static EnergyWriteResult writeEnergyModeDirect");
        int writeEnd = service.indexOf(
                "private static boolean isEnergyHalTaskCurrent", writeStart);
        String energyWrite = service.substring(writeStart, writeEnd);
        assertTrue(energyWrite.contains(
                "BydDeviceHelper.withBydPermissionBypass(task.appContext)"));
        assertTrue(energyWrite.contains("ENERGY_DEVICE, bydContext"));
        assertTrue(energyWrite.contains("enableDevice(\n                    bydContext"));
        assertTrue(service.contains(
                ".writeEnergyModeRaw(target, value)"));
        assertTrue(service.contains(
                ".writeMandatoryElectricState(target, value)"));
        assertTrue(service.contains(
                "readEnergyModeDirect(\n"
                        + "            Object device, boolean preferenceAxis)"));
        assertTrue(service.contains(
                "if (DiLink5Platform.isSelected()) {"));
        assertTrue(service.contains(
                "setter = device.getClass().getMethod(\n"
                        + "                                \"setEnergyMode\", int.class);"));

        String standalone = new String(
                Files.readAllBytes(Paths.get(
                        "src/main/java/com/overdrive/app/byd/BydModeCommand.java")),
                StandardCharsets.UTF_8);
        assertTrue(standalone.contains(
                "BydDeviceHelper.withBydPermissionBypass(createAppContext());"));
        assertTrue(standalone.contains("if (energyCommand) prepareEnergyDevice(energy);"));
        assertTrue(standalone.contains("readInt(energy, \"getEnergyMode\")"));
        assertTrue(standalone.contains("readInt(energy, \"getOperationMode\")"));
        assertTrue(standalone.contains(
                "VehicleActuatorBridge.writeEnergyModeRaw(energy, mode)"));
        assertTrue(standalone.contains(
                "VehicleActuatorBridge.writeMandatoryElectricState("));
        assertTrue(standalone.contains(
                "readSelectedEnergyMode(energy, preferenceAxis)"));
        assertTrue(standalone.contains(
                "DiLink5Platform.isSelected()"));
        assertTrue(standalone.contains("BydDeviceHelper.registerListener(energy"));
    }

    @Test
    public void parsesStringExtraUsedByAmBridge() {
        assertEquals(3, VehicleActuatorService.parseEnergyModeExtra("3", -1));
    }

    @Test
    public void parsesWhitespaceAroundStringExtra() {
        assertEquals(1, VehicleActuatorService.parseEnergyModeExtra(" 1 ", -1));
    }

    @Test
    public void retainsIntegerExtraCompatibility() {
        assertEquals(3, VehicleActuatorService.parseEnergyModeExtra(null, 3));
    }

    @Test
    public void rejectsMalformedStringInFavorOfLegacyExtra() {
        assertEquals(-1, VehicleActuatorService.parseEnergyModeExtra("hev", -1));
    }

    @Test
    public void parsesBridgeSourceGenerationAsLong() {
        assertEquals(1234567890123L,
                VehicleActuatorService.parseEnergyGenerationExtra("1234567890123", -1L));
        assertEquals(-1L, VehicleActuatorService.parseEnergyGenerationExtra("bad", -1L));
    }

    @Test
    public void sourceGenerationGateRejectsLateOrDuplicateDelivery() {
        VehicleActuatorService.SourceGenerationGate gate =
                new VehicleActuatorService.SourceGenerationGate();
        long now = 100_000_000_000L;

        assertNotNull(gate.claim(now - 100L, now));
        assertNull(gate.claim(now - 100L, now));
        assertNull(gate.claim(now - 101L, now));
        assertNotNull(gate.claim(now + 1L, now));
        assertTrue(gate.isCurrent(now + 1L));
        assertNull("missing/legacy generations cannot bypass ordering", gate.claim(-1L, now));
        assertNull("far-future values cannot poison the process gate",
                gate.claim(Long.MAX_VALUE, now));
    }

    @Test
    public void failedAdmissionCanRollbackOnlyItsOwnGeneration() {
        VehicleActuatorService.SourceGenerationGate gate =
                new VehicleActuatorService.SourceGenerationGate();
        long now = 100_000_000_000L;

        VehicleActuatorService.SourceGenerationGate.Claim first =
                gate.claim(now - 2L, now);
        VehicleActuatorService.SourceGenerationGate.Claim second =
                gate.claim(now - 1L, now);
        assertNotNull(first);
        assertNotNull(second);
        assertFalse("an old failed admission cannot erase a newer claim", gate.rollback(first));
        assertTrue(gate.rollback(second));
        assertTrue(gate.isCurrent(first.generation));
    }

    @Test
    public void sourceGenerationPlausibilityUsesBoundedBootClockWindow() {
        long now = TimeUnit.MINUTES.toNanos(10L);

        assertTrue(VehicleActuatorService.SourceGenerationGate.isPlausible(now, now));
        assertFalse(VehicleActuatorService.SourceGenerationGate.isPlausible(0L, now));
        assertTrue(VehicleActuatorService.SourceGenerationGate.isPlausible(
                now - TimeUnit.SECONDS.toNanos(60L), now));
        assertFalse(VehicleActuatorService.SourceGenerationGate.isPlausible(
                now - TimeUnit.SECONDS.toNanos(61L), now));
        assertFalse(VehicleActuatorService.SourceGenerationGate.isPlausible(
                now + TimeUnit.SECONDS.toNanos(31L), now));
        assertTrue(VehicleActuatorService.SourceGenerationGate.isPlausibleForMutation(
                now - TimeUnit.MINUTES.toNanos(5L), now));
        assertFalse(VehicleActuatorService.SourceGenerationGate.isPlausibleForMutation(
                now + TimeUnit.SECONDS.toNanos(31L), now));
    }

    @Test
    public void serviceAcceptsOnlyFieldValidatedUserEnergyModes() {
        assertTrue(VehicleActuatorService.isUserWritableEnergyMode(1));
        assertTrue(VehicleActuatorService.isUserWritableEnergyMode(3));
        assertFalse(VehicleActuatorService.isUserWritableEnergyMode(0));
        assertFalse(VehicleActuatorService.isUserWritableEnergyMode(2));
        assertFalse(VehicleActuatorService.isUserWritableEnergyMode(4));
        assertFalse(VehicleActuatorService.isUserWritableEnergyMode(5));
    }

    @Test
    public void stopCanBeAReadbackButIsNeverACommandOrRollbackTarget() {
        assertTrue(VehicleActuatorService.isReadableEnergySourceMode(0));
        assertFalse(VehicleActuatorService.isUserWritableEnergyMode(0));
        assertFalse(VehicleActuatorService.isSafeEnergyRollbackMode(0));
        assertTrue(VehicleActuatorService.isSafeEnergyRollbackMode(1));
        assertTrue(VehicleActuatorService.isSafeEnergyRollbackMode(5));
    }

    @Test
    public void newerEnergyRequestSupersedesQueuedOlderRequest() {
        VehicleActuatorService.EnergyModeArbiter arbiter =
                new VehicleActuatorService.EnergyModeArbiter();

        VehicleActuatorService.EnergyModeRequest ev = arbiter.submit(1);
        VehicleActuatorService.EnergyModeRequest hev = arbiter.submit(3);

        assertFalse(arbiter.isCurrent(ev));
        assertTrue(arbiter.isCurrent(hev));
        assertEquals(2, arbiter.pendingCount());
        assertEquals(1, arbiter.complete(ev));
        assertTrue(arbiter.isCurrent(hev));
        assertEquals(0, arbiter.complete(hev));
    }

    @Test
    public void repeatedModeStillSupersedesEarlierGeneration() {
        VehicleActuatorService.EnergyModeArbiter arbiter =
                new VehicleActuatorService.EnergyModeArbiter();

        VehicleActuatorService.EnergyModeRequest first = arbiter.submit(1);
        VehicleActuatorService.EnergyModeRequest second = arbiter.submit(1);

        assertFalse(arbiter.isCurrent(first));
        assertTrue(arbiter.isCurrent(second));
        arbiter.complete(first);
        arbiter.complete(second);
    }

    @Test
    public void burstQueueConflatesToOneLatestPendingRequest() {
        VehicleActuatorService.EnergyModeArbiter arbiter =
                new VehicleActuatorService.EnergyModeArbiter();
        VehicleActuatorService.EnergyModeQueue queue =
                new VehicleActuatorService.EnergyModeQueue();

        VehicleActuatorService.EnergyModeRequest first = arbiter.submit(1);
        VehicleActuatorService.EnergyModeQueue.Offer firstOffer = queue.offer(first);
        assertTrue(firstOffer.startWorker);
        assertNull(firstOffer.replaced);

        VehicleActuatorService.EnergyModeRequest second = arbiter.submit(3);
        VehicleActuatorService.EnergyModeQueue.Offer secondOffer = queue.offer(second);
        assertFalse(secondOffer.startWorker);
        assertSame(first, secondOffer.replaced);
        arbiter.complete(secondOffer.replaced);

        VehicleActuatorService.EnergyModeRequest latest = arbiter.submit(1);
        VehicleActuatorService.EnergyModeQueue.Offer latestOffer = queue.offer(latest);
        assertFalse(latestOffer.startWorker);
        assertSame(second, latestOffer.replaced);
        arbiter.complete(latestOffer.replaced);

        assertEquals(1, arbiter.pendingCount());
        assertSame(latest, queue.takeNext());
        assertNull(queue.takeNext());
        assertEquals(0, arbiter.complete(latest));
    }

    @Test
    public void canceledGenerationWakesAndInvalidatesWaiter() throws Exception {
        VehicleActuatorService.EnergyModeArbiter arbiter =
                new VehicleActuatorService.EnergyModeArbiter();
        VehicleActuatorService.EnergyModeRequest request = arbiter.submit(3);
        ExecutorService waiter = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> stayedCurrent =
                    waiter.submit(() -> arbiter.awaitWhileCurrent(request, 5000L));
            Thread.sleep(50L);
            arbiter.cancelAll();

            assertFalse(stayedCurrent.get(1L, TimeUnit.SECONDS));
            assertFalse(arbiter.isCurrent(request));
        } finally {
            waiter.shutdownNow();
            arbiter.complete(request);
        }
    }

    @Test
    public void enqueueFailureCleanupReleasesStrandedRequest() {
        VehicleActuatorService.EnergyModeArbiter arbiter =
                new VehicleActuatorService.EnergyModeArbiter();
        VehicleActuatorService.EnergyModeQueue queue =
                new VehicleActuatorService.EnergyModeQueue();
        VehicleActuatorService.EnergyModeRequest request = arbiter.submit(1);
        queue.offer(request);

        assertSame(request, queue.abortWorker());
        assertEquals(0, arbiter.complete(request));
        assertNull(queue.takeNext());
    }
}
