package com.overdrive.app.abrp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.logging.DaemonLogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SohEstimatorDurabilityTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private DaemonLogger.Config previousLoggerConfig;

    @Before
    public void disableAndroidLogging() {
        previousLoggerConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(new DaemonLogger.Config()
                .withConsoleLog(false)
                .withFileLog(false)
                .withStdoutLog(false));
    }

    @After
    public void restoreLoggerConfig() {
        DaemonLogger.configure(previousLoggerConfig);
    }

    @Test
    public void durableReplayPublishesCompleteProperties() throws Exception {
        File directory = temporaryFolder.newFolder("successful-replay");
        File destination = new File(directory, "soh.properties");
        SohEstimator estimator = estimatorWithNominal(destination, 20.0);

        assertTrue(estimator.applyCalibrationReplay(
                9.0, 50.0, 25.0, true, Double.NaN, 2_000L));

        Properties persisted = load(destination);
        assertEquals("90.0", persisted.getProperty("calibration_soh"));
        assertEquals("2000", persisted.getProperty("calibration_timestamp_ms"));
        assertEquals("20.0", persisted.getProperty("nominal_capacity_kwh"));
        assertTrue(persisted.containsKey("nominal_identity"));
        assertEquals(90.0, estimator.getCalibrationSoh(), 0.0);
        assertEquals(2_000L, estimator.getCalibrationTimestampMs());
    }

    @Test
    public void duplicateAndSupersededReplaysRepublishCurrentAnchor()
            throws Exception {
        File directory = temporaryFolder.newFolder("idempotent-replay");
        File destination = new File(directory, "soh.properties");
        SohEstimator estimator = estimatorWithNominal(destination, 20.0);

        assertTrue(estimator.applyCalibrationReplay(
                9.0, 50.0, 25.0, true, Double.NaN, 2_000L));

        Files.delete(destination.toPath());
        assertTrue(estimator.applyCalibrationReplay(
                9.0, 50.0, 25.0, true, Double.NaN, 2_000L));
        assertEquals("2000",
                load(destination).getProperty("calibration_timestamp_ms"));

        Files.delete(destination.toPath());
        assertTrue(estimator.applyCalibrationReplay(
                8.0, 50.0, 25.0, true, Double.NaN, 1_000L));
        Properties supersedingAnchor = load(destination);
        assertEquals("90.0",
                supersedingAnchor.getProperty("calibration_soh"));
        assertEquals("2000",
                supersedingAnchor.getProperty("calibration_timestamp_ms"));
    }

    @Test
    public void failedPersistenceRollsBackNewReplay() throws Exception {
        File nonDirectoryParent = temporaryFolder.newFile("not-a-directory");
        SohEstimator estimator = estimatorWithNominal(
                new File(nonDirectoryParent, "soh.properties"), 20.0);
        setField(estimator, "calibrationSoh", 82.0);
        setField(estimator, "calibrationTimestampMs", 1_000L);

        assertFalse(estimator.applyCalibrationReplay(
                9.0, 50.0, 25.0, true, Double.NaN, 2_000L));
        assertEquals(82.0, estimator.getCalibrationSoh(), 0.0);
        assertEquals(1_000L, estimator.getCalibrationTimestampMs());
    }

    @Test
    public void replayClassifiesPermanentPayloadFailuresAndTemporaryReadiness()
            throws Exception {
        File directory = temporaryFolder.newFolder("replay-outcomes");
        SohEstimator estimator = estimatorWithNominal(
                new File(directory, "soh.properties"), 20.0);

        assertEquals(
                SohEstimator.CalibrationReplayOutcome.PERMANENTLY_REJECTED,
                estimator.applyCalibrationReplayWithOutcome(
                        9.0, 10.0, 25.0, true, Double.NaN, 2_000L));
        assertEquals(
                SohEstimator.CalibrationReplayOutcome.PERMANENTLY_REJECTED,
                estimator.applyCalibrationReplayWithOutcome(
                        9.0, 50.0, Double.NaN, true, Double.NaN, 2_000L));
        assertEquals(
                SohEstimator.CalibrationReplayOutcome.PERMANENTLY_REJECTED,
                estimator.applyCalibrationReplayWithOutcome(
                        1.0, 50.0, 25.0, true, Double.NaN, 2_000L));

        setField(estimator, "nominalCapacityKwh", 0.0);
        assertEquals(
                SohEstimator.CalibrationReplayOutcome.RETRY_LATER,
                estimator.applyCalibrationReplayWithOutcome(
                        9.0, 50.0, 25.0, true, Double.NaN, 2_000L));
    }

    @Test
    public void atomicWriterRetainsPermissionsAndCleansTempFile()
            throws Exception {
        File directory = temporaryFolder.newFolder("permissions");
        File destination = new File(directory, "soh.properties");
        Files.write(destination.toPath(),
                "old=value\n".getBytes(StandardCharsets.ISO_8859_1));
        Set<PosixFilePermission> original = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_WRITE);
        Files.setPosixFilePermissions(destination.toPath(), original);

        Properties replacement = new Properties();
        replacement.setProperty("calibration_soh", "91.0");
        SohEstimator.persistPropertiesAtomically(destination, replacement);

        Set<PosixFilePermission> actual =
                Files.getPosixFilePermissions(destination.toPath());
        assertTrue(actual.containsAll(original));
        assertTrue(actual.contains(PosixFilePermission.GROUP_READ));
        assertTrue(actual.contains(PosixFilePermission.OTHERS_READ));
        assertEquals("91.0", load(destination).getProperty("calibration_soh"));
        File[] leftovers = directory.listFiles(
                file -> file.getName().startsWith("soh.properties.")
                        && file.getName().endsWith(".tmp"));
        assertEquals(0, leftovers == null ? 0 : leftovers.length);
    }

    @Test
    public void durableWriterOrdersSyncMoveAndDirectorySync()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/abrp/SohEstimator.java");
        String writer = between(source,
                "static void persistPropertiesAtomically",
                "private static Set<PosixFilePermission> readRetainedPermissions");
        int fileSync = writer.indexOf("output.getFD().sync();");
        int atomicMove = writer.indexOf("StandardCopyOption.ATOMIC_MOVE");
        int directorySync = writer.indexOf("forceDirectorySync(parent);");

        assertTrue(fileSync >= 0);
        assertTrue(atomicMove > fileSync);
        assertTrue(directorySync > atomicMove);
        assertFalse(writer.contains("AtomicMoveNotSupportedException"));

        String persistence = between(source,
                "private PersistenceOutcome persistEstimateWithOutcome()",
                "/**\n     * Publish one complete properties snapshot");
        int publication = persistence.indexOf(
                "publishProperties(props);");
        int success = persistence.indexOf("return outcome;", publication);
        assertTrue(publication >= 0);
        assertTrue(success > publication);
        assertTrue(persistence.contains(
                "PersistenceOutcome.COMMITTED_DURABILITY_UNCERTAIN"));
    }

    @Test
    public void clearTombstoneAtomicallyReplacesAllStaleState()
            throws Exception {
        File directory = temporaryFolder.newFolder("clear-tombstone");
        File destination = new File(directory, "soh.properties");
        Files.write(destination.toPath(), (
                "schema_version=3\n"
                        + "soh_percent=88.0\n"
                        + "nominal_capacity_kwh=20.0\n"
                        + "calibration_soh=84.0\n"
                        + "future_stale_key=must_not_survive\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        SohEstimator estimator = new SohEstimator(destination);
        setField(estimator, "resetModelEpoch", 100L);

        invoke(estimator, "persistClearedStateTombstone");

        Properties persisted = load(destination);
        assertEquals("3", persisted.getProperty("schema_version"));
        assertEquals("true", persisted.getProperty("state_cleared"));
        assertTrue(Long.parseLong(
                persisted.getProperty("last_updated")) > 0L);
        assertEquals("100",
                persisted.getProperty("reset_model_epoch"));
        assertFalse(persisted.containsKey("soh_percent"));
        assertFalse(persisted.containsKey("nominal_capacity_kwh"));
        assertFalse(persisted.containsKey("calibration_soh"));
        assertFalse(persisted.containsKey("future_stale_key"));
    }

    @Test
    public void schemaRewriteUsesFreshSnapshotAndPreservesOnlyNominal()
            throws Exception {
        File directory = temporaryFolder.newFolder("schema-rewrite");
        File destination = new File(directory, "soh.properties");
        Files.write(destination.toPath(), (
                "schema_version=1\n"
                        + "soh_percent=109.0\n"
                        + "calibration_soh=93.0\n"
                        + "legacy_unknown=stale\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        SohEstimator estimator = estimatorWithNominal(destination, 20.0);

        invoke(estimator, "writeSchemaStamp");

        Properties persisted = load(destination);
        assertEquals("3", persisted.getProperty("schema_version"));
        assertEquals("true", persisted.getProperty("state_cleared"));
        assertEquals("20.0",
                persisted.getProperty("nominal_capacity_kwh"));
        assertEquals("auto", persisted.getProperty("nominal_source"));
        assertEquals("101",
                persisted.getProperty("reset_model_epoch"));
        assertFalse(persisted.containsKey("soh_percent"));
        assertFalse(persisted.containsKey("calibration_soh"));
        assertFalse(persisted.containsKey("legacy_unknown"));
    }

    @Test
    public void capacitySohSnapshotIsPairedAndImmutable() throws Exception {
        File directory = temporaryFolder.newFolder("snapshot");
        SohEstimator estimator = estimatorWithNominal(
                new File(directory, "soh.properties"), 20.0);
        setField(estimator, "currentSoh", 88.0);

        SohEstimator.CapacitySohSnapshot first =
                captureSnapshot(estimator);
        assertEquals(20.0, first.getNominalCapacityKwh(), 0.0);
        assertEquals(88.0, first.getDisplaySoh(), 0.0);
        assertEquals(100L, first.getResetModelEpoch());
        assertTrue(first.hasDisplaySoh());

        setField(estimator, "nominalCapacityKwh", 30.0);
        setField(estimator, "currentSoh", 75.0);
        SohEstimator.CapacitySohSnapshot second =
                captureSnapshot(estimator);

        assertEquals(20.0, first.getNominalCapacityKwh(), 0.0);
        assertEquals(88.0, first.getDisplaySoh(), 0.0);
        assertEquals(100L, first.getResetModelEpoch());
        assertEquals(30.0, second.getNominalCapacityKwh(), 0.0);
        assertEquals(75.0, second.getDisplaySoh(), 0.0);
        assertEquals(100L, second.getResetModelEpoch());
    }

    @Test
    public void generationGuardMakesValidationAtomicWithExternalCommit()
            throws Exception {
        File directory = temporaryFolder.newFolder("generation-commit-guard");
        SohEstimator estimator = estimatorWithNominal(
                new File(directory, "soh.properties"), 20.0);
        long expectedGeneration =
                captureSnapshot(estimator).getEstimatorGeneration();
        CountDownLatch guardEntered = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        CountDownLatch mutationFinished = new CountDownLatch(1);
        AtomicBoolean callbackRan = new AtomicBoolean(false);
        AtomicBoolean guardResult = new AtomicBoolean(false);
        AtomicBoolean mutationApplied = new AtomicBoolean(false);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();

        Thread guardedCommit = new Thread(() -> {
            try {
                guardResult.set(estimator.runWithEstimatorGenerationGuard(
                        expectedGeneration, () -> {
                            callbackRan.set(true);
                            guardEntered.countDown();
                            if (!releaseCommit.await(2, TimeUnit.SECONDS)) {
                                throw new AssertionError(
                                        "timed out waiting to release guarded commit");
                            }
                        }));
            } catch (Throwable failure) {
                threadFailure.compareAndSet(null, failure);
            }
        }, "SohGenerationGuardTest");
        Thread mutation = new Thread(() -> {
            try {
                mutationStarted.countDown();
                mutationApplied.set(estimator.applyCalibrationReplay(
                        9.0, 50.0, 25.0, true,
                        Double.NaN, 2_000L));
            } catch (Throwable failure) {
                threadFailure.compareAndSet(null, failure);
            } finally {
                mutationFinished.countDown();
            }
        }, "SohGenerationMutationTest");

        guardedCommit.start();
        assertTrue(guardEntered.await(2, TimeUnit.SECONDS));
        mutation.start();
        assertTrue(mutationStarted.await(2, TimeUnit.SECONDS));
        long blockedDeadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(2);
        while (mutation.getState() != Thread.State.BLOCKED
                && System.nanoTime() < blockedDeadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertEquals(Thread.State.BLOCKED, mutation.getState());
        assertFalse(mutationFinished.await(0, TimeUnit.MILLISECONDS));
        releaseCommit.countDown();
        guardedCommit.join(2_000L);
        mutation.join(2_000L);

        assertFalse(guardedCommit.isAlive());
        assertFalse(mutation.isAlive());
        assertTrue(threadFailure.get() == null);
        assertTrue(callbackRan.get());
        assertTrue(guardResult.get());
        assertTrue(mutationApplied.get());
        assertTrue(captureSnapshot(estimator).getEstimatorGeneration()
                > expectedGeneration);

        AtomicBoolean staleCallbackRan = new AtomicBoolean(false);
        assertFalse(estimator.runWithEstimatorGenerationGuard(
                expectedGeneration, () -> staleCallbackRan.set(true)));
        assertFalse(staleCallbackRan.get());
    }

    @Test
    public void staleSeedCannotCommitAfterResetGenerationAdvances()
            throws Exception {
        File directory = temporaryFolder.newFolder("seed-reset-race");
        SohEstimator estimator = estimatorWithAdapters(
                new File(directory, "soh.properties"),
                20.0,
                true);
        setField(estimator, "currentSoh", 88.0);
        SohEstimator.CapacitySohSnapshot beforeReset =
                captureSnapshot(estimator);
        long seedGeneration = beforeReset.getEstimatorGeneration();

        estimator.reset();

        assertEquals("true",
                load(new File(directory, "soh.properties"))
                    .getProperty("state_cleared"));
        assertFalse(tryCommitInitialSeed(
                estimator, seedGeneration, 20.0, 88.0));
        assertEquals(-1.0, estimator.getCurrentSoh(), 0.0);

        SohEstimator.CapacitySohSnapshot afterReset =
                captureSnapshot(estimator);
        assertTrue(tryCommitInitialSeed(
                estimator,
                afterReset.getEstimatorGeneration(),
                20.0,
                88.0));
        SohEstimator.CapacitySohSnapshot afterReseed =
                captureSnapshot(estimator);

        assertEquals(
                beforeReset.getNominalCapacityKwh(),
                afterReseed.getNominalCapacityKwh(),
                0.0);
        assertEquals(
                beforeReset.getDisplaySoh(),
                afterReseed.getDisplaySoh(),
                0.0);
        assertTrue(
                afterReseed.getEstimatorGeneration()
                    > beforeReset.getEstimatorGeneration());
    }

    @Test
    public void rejectedNominalConfigMutationPreservesCalibrationAndFile()
            throws Exception {
        File directory = temporaryFolder.newFolder("rejected-config");
        File destination = new File(directory, "soh.properties");
        SohEstimator estimator = estimatorWithAdapters(
                destination, 20.0, false);
        setField(estimator, "currentSoh", 88.0);
        setField(estimator, "calibrationSoh", 82.0);
        setField(estimator, "calibrationTimestampMs", 1_000L);
        invoke(estimator, "persistEstimate");
        Properties before = load(destination);

        assertIllegalState(
            () -> estimator.setNominalCapacityKwhFromUser(30.0));
        assertIllegalState(estimator::clearUserNominal);

        assertEquals(20.0, estimator.getNominalCapacityKwh(), 0.0);
        assertEquals("user", estimator.getNominalSource());
        assertEquals(88.0, estimator.getCurrentSoh(), 0.0);
        assertEquals(82.0, estimator.getCalibrationSoh(), 0.0);
        assertEquals(before, load(destination));
    }

    @Test
    public void changedConfigNominalRejectsOldPersistedIdentity()
            throws Exception {
        File directory = temporaryFolder.newFolder("nominal-identity");
        File destination = new File(directory, "soh.properties");
        SohEstimator oldEstimator = estimatorWithAdapters(
                destination, 20.0, true);
        setField(oldEstimator, "currentSoh", 88.0);
        setField(oldEstimator, "calibrationSoh", 82.0);
        setField(oldEstimator, "calibrationTimestampMs", 1_000L);
        invoke(oldEstimator, "persistEstimate");
        String oldIdentity =
                load(destination).getProperty("nominal_identity");

        SohEstimator restored = estimatorWithAdapters(
                destination, 30.0, true);
        setField(restored, "nominalCapacityKwh", 0.0);
        setField(restored, "nominalSource", "unset");
        restored.init();

        assertEquals(30.0, restored.getNominalCapacityKwh(), 0.0);
        assertEquals("user", restored.getNominalSource());
        assertEquals(-1.0, restored.getCurrentSoh(), 0.0);
        assertEquals(-1.0, restored.getCalibrationSoh(), 0.0);
        assertFalse(oldIdentity.equals(
                load(destination).getProperty("nominal_identity")));
    }

    @Test
    public void fiveKwhConfiguredNominalRestoresAndLowerValueDefers()
            throws Exception {
        SohEstimator.PersistenceWriter writer = (file, properties) -> {
            store(file, properties);
            return SohEstimator.PersistenceOutcome.DURABLE;
        };

        SohEstimator accepted = new SohEstimator(
                new File(temporaryFolder.newFolder("five-kwh"), "soh.properties"),
                writer,
                fixedConfig(5.0, true));
        accepted.init();
        assertTrue(accepted.isInitializationReady());
        assertEquals(5.0, accepted.getNominalCapacityKwh(), 0.0);
        assertEquals("user", accepted.getNominalSource());

        SohEstimator rejected = new SohEstimator(
                new File(temporaryFolder.newFolder("below-five"), "soh.properties"),
                writer,
                fixedConfig(4.9, true));
        rejected.init();
        assertFalse(rejected.isInitializationReady());
        assertEquals(0.0, rejected.getNominalCapacityKwh(), 0.0);
    }

    @Test
    public void clearedConfigDoesNotRestoreNominalOnlyUserSnapshot()
            throws Exception {
        File directory = temporaryFolder.newFolder("cleared-config");
        File destination = new File(directory, "soh.properties");
        SohEstimator oldEstimator = estimatorWithAdapters(
                destination, 20.0, true);
        invoke(oldEstimator, "persistEstimate");
        assertEquals("user",
                load(destination).getProperty("nominal_source"));

        SohEstimator.PersistenceWriter writer = (file, properties) -> {
            store(file, properties);
            return SohEstimator.PersistenceOutcome.DURABLE;
        };
        SohEstimator restored = new SohEstimator(
                destination, writer, fixedConfig(0.0, true));
        restored.init();

        assertEquals(0.0, restored.getNominalCapacityKwh(), 0.0);
        assertEquals("unset", restored.getNominalSource());
        assertEquals("true",
                load(destination).getProperty("state_cleared"));
    }

    @Test
    public void unavailableConfigReadDoesNotDiscardUserBoundSnapshot()
            throws Exception {
        File directory = temporaryFolder.newFolder("unavailable-config");
        File destination = new File(directory, "soh.properties");
        SohEstimator original = estimatorWithAdapters(
                destination, 20.0, true);
        setField(original, "currentSoh", 88.0);
        setField(original, "calibrationSoh", 82.0);
        setField(original, "calibrationTimestampMs", 1_000L);
        invoke(original, "persistEstimate");
        Properties before = load(destination);
        byte[] bytesBefore = Files.readAllBytes(destination.toPath());
        AtomicInteger writes = new AtomicInteger();
        SohEstimator.PersistenceWriter writer = (file, properties) -> {
            writes.incrementAndGet();
            store(file, properties);
            return SohEstimator.PersistenceOutcome.DURABLE;
        };
        SohEstimator.UserNominalConfig unavailableConfig =
                new SohEstimator.UserNominalConfig() {
                    @Override
                    public boolean write(Object value) {
                        return true;
                    }

                    @Override
                    public double read() {
                        throw new IllegalStateException(
                                "transient config read failure");
                    }
                };
        SohEstimator restored = new SohEstimator(
                destination, writer, unavailableConfig);

        restored.init();

        assertFalse(restored.isInitializationReady());
        restored.setNominalCapacityKwh(30.0);
        assertEquals(0.0, restored.getNominalCapacityKwh(), 0.0);
        assertEquals(0, writes.get());
        assertEquals(before, load(destination));
        assertTrue(java.util.Arrays.equals(
                bytesBefore, Files.readAllBytes(destination.toPath())));
        assertEquals("user",
                load(destination).getProperty("nominal_source"));
        assertEquals("88.0",
                load(destination).getProperty("soh_percent"));
        assertEquals("82.0",
                load(destination).getProperty("calibration_soh"));
    }

    @Test
    public void malformedOptionalRestorePublishesNothing() throws Exception {
        File directory = temporaryFolder.newFolder("atomic-restore");
        File destination = new File(directory, "soh.properties");
        storeOldUserEstimate(destination);
        Properties malformed = load(destination);
        malformed.setProperty("calibration_soh", "not-a-number");
        store(destination, malformed);
        byte[] bytesBefore = Files.readAllBytes(destination.toPath());
        AtomicInteger writes = new AtomicInteger();
        SohEstimator.PersistenceWriter writer = (file, properties) -> {
            writes.incrementAndGet();
            store(file, properties);
            return SohEstimator.PersistenceOutcome.DURABLE;
        };
        SohEstimator estimator = new SohEstimator(
                destination, writer, fixedConfig(20.0, true));
        setField(estimator, "nominalCapacityKwh", 30.0);
        setField(estimator, "nominalSource", "auto");
        setField(estimator, "currentSoh", 75.0);
        setField(estimator, "calibrationSoh", 70.0);
        setField(estimator, "calibrationTimestampMs", 500L);

        estimator.init();

        assertFalse(estimator.isInitializationReady());
        assertEquals(30.0, estimator.getNominalCapacityKwh(), 0.0);
        assertEquals("auto", estimator.getNominalSource());
        assertEquals(75.0, estimator.getCurrentSoh(), 0.0);
        assertEquals(70.0, estimator.getCalibrationSoh(), 0.0);
        assertEquals(500L, estimator.getCalibrationTimestampMs());
        assertEquals(0, writes.get());
        assertTrue(java.util.Arrays.equals(
                bytesBefore, Files.readAllBytes(destination.toPath())));
    }

    @Test
    public void nonFiniteCalibrationPairPublishesNothing() throws Exception {
        File directory = temporaryFolder.newFolder("nonfinite-calibration");
        File destination = new File(directory, "soh.properties");
        storeOldUserEstimate(destination);
        Properties malformed = load(destination);
        malformed.setProperty("calibration_soh", "NaN");
        store(destination, malformed);
        byte[] bytesBefore = Files.readAllBytes(destination.toPath());
        AtomicInteger writes = new AtomicInteger();
        SohEstimator estimator = new SohEstimator(
                destination,
                (file, properties) -> {
                    writes.incrementAndGet();
                    store(file, properties);
                    return SohEstimator.PersistenceOutcome.DURABLE;
                },
                fixedConfig(20.0, true));

        estimator.init();

        assertFalse(estimator.isInitializationReady());
        assertEquals(0L, estimator.getCalibrationTimestampMs());
        assertEquals(0, writes.get());
        assertTrue(java.util.Arrays.equals(
                bytesBefore, Files.readAllBytes(destination.toPath())));
    }

    @Test
    public void orphanCalibrationTimestampPublishesNothing() throws Exception {
        File directory = temporaryFolder.newFolder("orphan-calibration");
        File destination = new File(directory, "soh.properties");
        storeOldUserEstimate(destination);
        Properties malformed = load(destination);
        malformed.remove("calibration_soh");
        store(destination, malformed);
        byte[] bytesBefore = Files.readAllBytes(destination.toPath());
        AtomicInteger writes = new AtomicInteger();
        SohEstimator estimator = new SohEstimator(
                destination,
                (file, properties) -> {
                    writes.incrementAndGet();
                    store(file, properties);
                    return SohEstimator.PersistenceOutcome.DURABLE;
                },
                fixedConfig(20.0, true));

        estimator.init();

        assertFalse(estimator.isInitializationReady());
        assertEquals(0L, estimator.getCalibrationTimestampMs());
        assertEquals(0, writes.get());
        assertTrue(java.util.Arrays.equals(
                bytesBefore, Files.readAllBytes(destination.toPath())));
    }

    @Test
    public void autoNominalCannotOverwriteActiveUserIdentity()
            throws Exception {
        File directory = temporaryFolder.newFolder("auto-vs-user");
        File destination = new File(directory, "soh.properties");
        SohEstimator estimator = new SohEstimator(
                destination,
                (file, properties) -> {
                    store(file, properties);
                    return SohEstimator.PersistenceOutcome.DURABLE;
                },
                fixedConfig(0.0, true));
        estimator.init();
        byte[] initializedBytes =
            Files.readAllBytes(destination.toPath());
        setField(estimator, "nominalCapacityKwh", 30.0);
        setField(estimator, "nominalSource", "user");
        setField(estimator, "currentSoh", 88.0);

        estimator.setNominalCapacityKwh(60.48);

        assertEquals(30.0, estimator.getNominalCapacityKwh(), 0.0);
        assertEquals("user", estimator.getNominalSource());
        assertEquals(88.0, estimator.getCurrentSoh(), 0.0);
        assertTrue(java.util.Arrays.equals(
                initializedBytes,
                Files.readAllBytes(destination.toPath())));
    }

    @Test
    public void autoIdentityChangeClearsEveryBoundAnchorBeforePublication()
            throws Exception {
        File directory = temporaryFolder.newFolder("auto-identity-change");
        File destination = new File(directory, "soh.properties");
        SohEstimator estimator = new SohEstimator(
                destination,
                (file, properties) -> {
                    store(file, properties);
                    return SohEstimator.PersistenceOutcome.DURABLE;
                },
                fixedConfig(0.0, true));
        estimator.init();
        estimator.setNominalCapacityKwh(20.0);
        setField(estimator, "currentSoh", 88.0);
        setField(estimator, "calibrationSoh", 82.0);
        setField(estimator, "calibrationTimestampMs", 1_000L);
        setField(estimator, "capacityAhSoh", 80.0);
        setField(estimator, "capacityAhTimestampMs", 1_100L);
        setField(estimator, "capacityAhDisabled", true);
        setField(estimator, "peakRemainKwhAtFull", 18.0);
        setField(estimator, "peakRemainKwhSamples", 3);
        setField(estimator, "peakRemainKwhTimestampMs", 1_200L);
        setField(estimator, "peakMismatchNotified", true);
        invoke(estimator, "persistEstimate");

        estimator.setNominalCapacityKwh(30.0);

        assertEquals(30.0, estimator.getNominalCapacityKwh(), 0.0);
        assertEquals("auto", estimator.getNominalSource());
        assertEquals(-1.0, estimator.getCurrentSoh(), 0.0);
        assertEquals(-1.0, estimator.getCalibrationSoh(), 0.0);
        assertEquals(-1.0, estimator.getCapacityAhSoh(), 0.0);
        assertEquals(-1.0, estimator.getPeakRemainKwhAtFull(), 0.0);
        Properties persisted = load(destination);
        assertEquals("30.0",
                persisted.getProperty("nominal_capacity_kwh"));
        assertFalse(persisted.containsKey("soh_percent"));
        assertFalse(persisted.containsKey("calibration_soh"));
        assertFalse(persisted.containsKey("capacity_ah_soh"));
        assertFalse(persisted.containsKey("peak_remain_kwh"));
    }

    @Test
    public void failedAutoIdentityPublicationPreservesPriorSnapshot()
            throws Exception {
        File directory = temporaryFolder.newFolder("failed-auto-identity");
        File destination = new File(directory, "soh.properties");
        AtomicInteger writes = new AtomicInteger();
        SohEstimator estimator = new SohEstimator(
                destination,
                (file, properties) -> {
                    if (writes.getAndIncrement() == 0) {
                        store(file, properties);
                        return SohEstimator.PersistenceOutcome.DURABLE;
                    }
                    return SohEstimator.PersistenceOutcome.FAILED;
                },
                fixedConfig(0.0, true));
        estimator.init();
        byte[] initializedBytes =
            Files.readAllBytes(destination.toPath());
        setField(estimator, "nominalCapacityKwh", 20.0);
        setField(estimator, "nominalSource", "auto");
        setField(estimator, "currentSoh", 88.0);
        setField(estimator, "calibrationSoh", 82.0);

        estimator.setNominalCapacityKwh(30.0);

        assertEquals(20.0, estimator.getNominalCapacityKwh(), 0.0);
        assertEquals("auto", estimator.getNominalSource());
        assertEquals(88.0, estimator.getCurrentSoh(), 0.0);
        assertEquals(82.0, estimator.getCalibrationSoh(), 0.0);
        assertTrue(java.util.Arrays.equals(
                initializedBytes,
                Files.readAllBytes(destination.toPath())));
    }

    @Test
    public void resetRetriesCommittedUncertainTombstoneUntilDurable()
            throws Exception {
        File directory = temporaryFolder.newFolder("reset-retry");
        File destination = new File(directory, "soh.properties");
        storeOldUserEstimate(destination);
        AtomicInteger writes = new AtomicInteger();
        SohEstimator.PersistenceWriter writer = (file, properties) -> {
            store(file, properties);
            return writes.getAndIncrement() == 0
                ? SohEstimator.PersistenceOutcome
                    .COMMITTED_DURABILITY_UNCERTAIN
                : SohEstimator.PersistenceOutcome.DURABLE;
        };
        SohEstimator estimator = new SohEstimator(
                destination, writer, fixedConfig(20.0, true));
        setField(estimator, "nominalCapacityKwh", 20.0);
        setField(estimator, "nominalSource", "user");
        setField(estimator, "currentSoh", 88.0);
        setField(estimator, "calibrationSoh", 82.0);
        setField(estimator, "calibrationTimestampMs", 1_000L);
        setField(estimator, "resetModelEpoch", 100L);

        estimator.reset();

        assertEquals(2, writes.get());
        assertEquals(-1.0, estimator.getCurrentSoh(), 0.0);
        assertEquals(-1.0, estimator.getCalibrationSoh(), 0.0);
        assertEquals(20.0, estimator.getNominalCapacityKwh(), 0.0);
        assertEquals("user", estimator.getNominalSource());
        Properties persisted = load(destination);
        assertEquals("true", persisted.getProperty("state_cleared"));
        assertEquals("101",
                persisted.getProperty("reset_model_epoch"));
        assertFalse(persisted.containsKey("soh_percent"));
        assertFalse(persisted.containsKey("calibration_soh"));
        assertFalse(persisted.containsKey("nominal_capacity_kwh"));

        SohEstimator restarted = new SohEstimator(
                destination,
                (file, properties) -> {
                    store(file, properties);
                    return SohEstimator.PersistenceOutcome.DURABLE;
                },
                fixedConfig(20.0, true));
        restarted.init();
        assertTrue(restarted.isInitializationReady());
        assertEquals(101L,
                captureSnapshot(restarted).getResetModelEpoch());
        assertEquals(20.0, restarted.getNominalCapacityKwh(), 0.0);
        assertEquals("user", restarted.getNominalSource());
    }

    @Test
    public void resetRejectsPersistentUncertaintyWithoutRepublishingOldState()
            throws Exception {
        File directory = temporaryFolder.newFolder(
                "reset-persistent-uncertainty");
        File destination = new File(directory, "soh.properties");
        storeOldUserEstimate(destination);
        AtomicInteger writes = new AtomicInteger();
        SohEstimator.PersistenceWriter writer = (file, properties) -> {
            writes.incrementAndGet();
            store(file, properties);
            return SohEstimator.PersistenceOutcome
                .COMMITTED_DURABILITY_UNCERTAIN;
        };
        SohEstimator estimator = new SohEstimator(
                destination, writer, fixedConfig(20.0, true));
        setField(estimator, "nominalCapacityKwh", 20.0);
        setField(estimator, "nominalSource", "user");
        setField(estimator, "currentSoh", 88.0);
        setField(estimator, "calibrationSoh", 82.0);
        setField(estimator, "calibrationTimestampMs", 1_000L);
        setField(estimator, "resetModelEpoch", 100L);

        assertIllegalState(estimator::reset);

        assertEquals(3, writes.get());
        assertEquals(-1.0, estimator.getCurrentSoh(), 0.0);
        assertEquals(-1.0, estimator.getCalibrationSoh(), 0.0);
        assertEquals(20.0, estimator.getNominalCapacityKwh(), 0.0);
        assertEquals("user", estimator.getNominalSource());
        Properties tombstone = load(destination);
        assertEquals("true", tombstone.getProperty("state_cleared"));
        assertEquals("101",
                tombstone.getProperty("reset_model_epoch"));
        assertFalse(tombstone.containsKey("soh_percent"));
        assertFalse(tombstone.containsKey("calibration_soh"));

        invoke(estimator, "persistEstimate");

        Properties republished = load(destination);
        assertFalse(republished.containsKey("soh_percent"));
        assertFalse(republished.containsKey("calibration_soh"));
    }

    @Test
    public void committedDirectorySyncFailureKeepsMemoryAlignedWithFile()
            throws Exception {
        File directory = temporaryFolder.newFolder("committed-uncertain");
        File destination = new File(directory, "soh.properties");
        AtomicInteger writes = new AtomicInteger();
        SohEstimator.PersistenceWriter writer = (file, properties) -> {
            store(file, properties);
            return writes.getAndIncrement() == 0
                ? SohEstimator.PersistenceOutcome
                    .COMMITTED_DURABILITY_UNCERTAIN
                : SohEstimator.PersistenceOutcome.DURABLE;
        };
        SohEstimator estimator = new SohEstimator(
                destination, writer, fixedConfig(20.0, true));
        setField(estimator, "nominalCapacityKwh", 20.0);
        setField(estimator, "nominalSource", "user");
        setField(estimator, "calibrationSoh", 82.0);
        setField(estimator, "calibrationTimestampMs", 1_000L);
        setField(estimator, "resetModelEpoch", 100L);
        long generationBefore =
                captureSnapshot(estimator).getEstimatorGeneration();

        assertFalse(estimator.applyCalibrationReplay(
                9.0, 50.0, 25.0, true, Double.NaN, 2_000L));
        assertEquals(90.0, estimator.getCalibrationSoh(), 0.0);
        assertEquals(2_000L, estimator.getCalibrationTimestampMs());
        assertEquals("90.0",
                load(destination).getProperty("calibration_soh"));
        assertTrue(
                captureSnapshot(estimator).getEstimatorGeneration()
                    > generationBefore);

        assertTrue(estimator.applyCalibrationReplay(
                9.0, 50.0, 25.0, true, Double.NaN, 2_000L));
    }

    @Test
    public void nominalSnapshotIsPairedImmutableAndProbeFree()
            throws Exception {
        File directory = temporaryFolder.newFolder("nominal-snapshot");
        SohEstimator estimator = estimatorWithNominal(
                new File(directory, "soh.properties"), 20.0);
        SohEstimator.NominalSnapshot first =
                estimator.getNominalSnapshot();

        setField(estimator, "nominalCapacityKwh", 30.0);
        setField(estimator, "nominalSource", "user");
        SohEstimator.NominalSnapshot second =
                estimator.getNominalSnapshot();

        assertEquals(20.0, first.getNominalCapacityKwh(), 0.0);
        assertEquals("auto", first.getNominalSource());
        assertEquals(100L, first.getResetModelEpoch());
        assertEquals(30.0, second.getNominalCapacityKwh(), 0.0);
        assertEquals("user", second.getNominalSource());
        assertEquals(100L, second.getResetModelEpoch());

        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/abrp/SohEstimator.java");
        String snapshotMethod = between(
                source,
                "public NominalSnapshot getNominalSnapshot()",
                "/**\n     * Immutable pair used by energy/session consumers");
        assertTrue(snapshotMethod.contains(
                "synchronized (autoDetectLock)"));
        assertFalse(snapshotMethod.contains("BydDataCollector"));
        assertFalse(snapshotMethod.contains("isPhev"));
    }

    @Test
    public void freshEpochIsDurableCoherentAndStableAcrossRestart()
            throws Exception {
        File directory = temporaryFolder.newFolder("fresh-epoch");
        File destination = new File(directory, "soh.properties");
        SohEstimator.PersistenceWriter writer = (file, properties) -> {
            store(file, properties);
            return SohEstimator.PersistenceOutcome.DURABLE;
        };
        SohEstimator first = new SohEstimator(
                destination, writer, fixedConfig(0.0, true));

        first.init();

        assertTrue(first.isInitializationReady());
        long epoch =
            captureSnapshot(first).getResetModelEpoch();
        assertTrue(epoch > 0L);
        assertEquals(epoch,
                first.getNominalSnapshot().getResetModelEpoch());
        assertEquals(String.valueOf(epoch),
                load(destination).getProperty("reset_model_epoch"));
        assertEquals("true",
                load(destination).getProperty("state_cleared"));

        SohEstimator restarted = new SohEstimator(
                destination, writer, fixedConfig(0.0, true));
        restarted.init();

        assertTrue(restarted.isInitializationReady());
        assertEquals(epoch,
                captureSnapshot(restarted).getResetModelEpoch());
        assertEquals(epoch,
                restarted.getNominalSnapshot().getResetModelEpoch());
    }

    @Test
    public void currentSchemaIsRepublishedDurablyBeforeReady()
            throws Exception {
        File directory = temporaryFolder.newFolder("current-schema-resync");
        File destination = new File(directory, "soh.properties");
        Properties current = new Properties();
        current.setProperty("schema_version", "3");
        current.setProperty("last_updated", "1000");
        current.setProperty("reset_model_epoch", "100");
        current.setProperty("nominal_capacity_kwh", "20.0");
        current.setProperty("nominal_source", "auto");
        current.setProperty("nominal_identity",
                "auto:" + Long.toHexString(
                    Double.doubleToLongBits(20.0)));
        current.setProperty("soh_percent", "88.0");
        store(destination, current);

        AtomicBoolean durable = new AtomicBoolean(false);
        AtomicInteger writes = new AtomicInteger();
        SohEstimator estimator = new SohEstimator(
                destination,
                (file, properties) -> {
                    writes.incrementAndGet();
                    store(file, properties);
                    return durable.get()
                        ? SohEstimator.PersistenceOutcome.DURABLE
                        : SohEstimator.PersistenceOutcome
                            .COMMITTED_DURABILITY_UNCERTAIN;
                },
                fixedConfig(0.0, true));

        estimator.init();

        assertFalse(estimator.isInitializationReady());
        assertEquals(3, writes.get());
        assertEquals(0.0, estimator.getNominalCapacityKwh(), 0.0);

        durable.set(true);
        estimator.init();

        assertTrue(estimator.isInitializationReady());
        assertEquals(4, writes.get());
        assertEquals(20.0, estimator.getNominalCapacityKwh(), 0.0);
        assertEquals(88.0, estimator.getCurrentSoh(), 0.0);
        assertEquals("1000",
                load(destination).getProperty("last_updated"));
    }

    @Test
    public void persistedNanNominalNeverPublishesReadyState()
            throws Exception {
        File directory = temporaryFolder.newFolder("nan-nominal");
        File destination = new File(directory, "soh.properties");
        Properties corrupt = new Properties();
        corrupt.setProperty("schema_version", "3");
        corrupt.setProperty("last_updated", "1000");
        corrupt.setProperty("reset_model_epoch", "100");
        corrupt.setProperty("nominal_capacity_kwh", "NaN");
        corrupt.setProperty("nominal_source", "auto");
        corrupt.setProperty("nominal_identity",
                "auto:" + Long.toHexString(
                    Double.doubleToLongBits(Double.NaN)));
        corrupt.setProperty("soh_percent", "88.0");
        store(destination, corrupt);
        AtomicInteger writes = new AtomicInteger();
        SohEstimator estimator = new SohEstimator(
                destination,
                (file, properties) -> {
                    writes.incrementAndGet();
                    store(file, properties);
                    return SohEstimator.PersistenceOutcome.DURABLE;
                },
                fixedConfig(0.0, true));

        estimator.init();

        assertFalse(estimator.isInitializationReady());
        assertEquals(0, writes.get());
        assertEquals(0.0, estimator.getNominalCapacityKwh(), 0.0);
    }

    @Test
    public void legacySnapshotGetsNonzeroDurableEpochBeforePublication()
            throws Exception {
        File directory = temporaryFolder.newFolder("legacy-epoch");
        File destination = new File(directory, "soh.properties");
        storeOldUserEstimate(destination);
        AtomicInteger writes = new AtomicInteger();
        SohEstimator estimator = new SohEstimator(
                destination,
                (file, properties) -> {
                    writes.incrementAndGet();
                    store(file, properties);
                    return SohEstimator.PersistenceOutcome.DURABLE;
                },
                fixedConfig(20.0, true));

        estimator.init();

        assertTrue(estimator.isInitializationReady());
        long epoch =
            captureSnapshot(estimator).getResetModelEpoch();
        assertTrue(epoch > 0L);
        assertTrue(epoch != 0L);
        assertEquals(epoch,
                estimator.getNominalSnapshot().getResetModelEpoch());
        assertEquals(String.valueOf(epoch),
                load(destination).getProperty("reset_model_epoch"));
        assertEquals(88.0, estimator.getCurrentSoh(), 0.0);
        assertEquals(82.0, estimator.getCalibrationSoh(), 0.0);
        assertEquals(1, writes.get());
    }

    @Test
    public void failedLegacyEpochBootstrapPublishesNothing()
            throws Exception {
        File directory = temporaryFolder.newFolder(
                "failed-legacy-epoch");
        File destination = new File(directory, "soh.properties");
        storeOldUserEstimate(destination);
        byte[] before = Files.readAllBytes(destination.toPath());
        AtomicInteger writes = new AtomicInteger();
        SohEstimator estimator = new SohEstimator(
                destination,
                (file, properties) -> {
                    writes.incrementAndGet();
                    return SohEstimator.PersistenceOutcome.FAILED;
                },
                fixedConfig(20.0, true));
        setField(estimator, "nominalCapacityKwh", 30.0);
        setField(estimator, "nominalSource", "auto");
        setField(estimator, "currentSoh", 75.0);
        setField(estimator, "resetModelEpoch", 77L);

        estimator.init();

        assertFalse(estimator.isInitializationReady());
        assertEquals(30.0, estimator.getNominalCapacityKwh(), 0.0);
        assertEquals("auto", estimator.getNominalSource());
        assertEquals(75.0, estimator.getCurrentSoh(), 0.0);
        assertEquals(77L,
                captureSnapshot(estimator).getResetModelEpoch());
        assertEquals(3, writes.get());
        assertTrue(java.util.Arrays.equals(
                before, Files.readAllBytes(destination.toPath())));
        assertFalse(load(destination).containsKey("reset_model_epoch"));
    }

    @Test
    public void autoIdentityEpochAdvancesOnceAndFailedUserChangeDoesNotPublish()
            throws Exception {
        File directory = temporaryFolder.newFolder(
                "identity-epoch");
        File destination = new File(directory, "soh.properties");
        AtomicBoolean rejectWrites = new AtomicBoolean(false);
        SohEstimator estimator = new SohEstimator(
                destination,
                (file, properties) -> {
                    if (rejectWrites.get()) {
                        return SohEstimator.PersistenceOutcome.FAILED;
                    }
                    store(file, properties);
                    return SohEstimator.PersistenceOutcome.DURABLE;
                },
                fixedConfig(0.0, true));
        estimator.init();
        long initial =
            captureSnapshot(estimator).getResetModelEpoch();

        estimator.setNominalCapacityKwh(20.0);
        long firstIdentity =
            captureSnapshot(estimator).getResetModelEpoch();
        assertEquals(initial + 1L, firstIdentity);
        estimator.setNominalCapacityKwh(20.0);
        assertEquals(firstIdentity,
                captureSnapshot(estimator).getResetModelEpoch());
        assertEquals(String.valueOf(firstIdentity),
                load(destination).getProperty("reset_model_epoch"));

        byte[] beforeRejectedChange =
            Files.readAllBytes(destination.toPath());
        rejectWrites.set(true);
        assertIllegalState(
            () -> estimator.setNominalCapacityKwhFromUser(30.0));

        assertEquals(20.0, estimator.getNominalCapacityKwh(), 0.0);
        assertEquals("auto", estimator.getNominalSource());
        assertEquals(firstIdentity,
                captureSnapshot(estimator).getResetModelEpoch());
        assertFalse(estimator.isInitializationReady());
        assertTrue(java.util.Arrays.equals(
                beforeRejectedChange,
                Files.readAllBytes(destination.toPath())));

        rejectWrites.set(false);
        estimator.setNominalCapacityKwhFromUser(30.0);
        assertTrue(estimator.isInitializationReady());
        assertEquals(30.0, estimator.getNominalCapacityKwh(), 0.0);
        assertEquals("user", estimator.getNominalSource());
        assertEquals(firstIdentity + 1L,
                captureSnapshot(estimator).getResetModelEpoch());
        assertEquals(String.valueOf(firstIdentity + 1L),
                load(destination).getProperty("reset_model_epoch"));
    }

    @Test
    public void modelSelectionInvalidationUsesEpochAdvancingReset()
            throws Exception {
        String models = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/ModelsApiHandler.java");
        String selection = between(
                models,
                "private static void handleSetSelected",
                "private static JSONObject findModel");
        assertOrdered(
                selection,
                "if (changesModelSelection)",
                "UnifiedConfigManager.runUnderConfigLock",
                "UnifiedConfigManager.readVehicleNominalKwhStrict",
                "estimator.runWithEstimatorLock",
                "estimator.resetFromConfigSnapshot(configuredUserNominal)",
                "UnifiedConfigManager.setVehicle(patch)",
                "estimator.autoDetectCarModelFromConfigSnapshot",
                "estimator.seedInitialEstimate");
        assertTrue(selection.contains(
                "double floor = selectedModelPhev ? 5.0 : 15.0;"));
        assertOrdered(
                selection,
                "patch.put(\"nominalKwh\", requestedNominalKwh)",
                "changesNominal",
                "? replacementNominal",
                "estimator.resetFromConfigSnapshot(configuredUserNominal)",
                "UnifiedConfigManager.setVehicle(patch)");
        String lookup = between(
                models,
                "public static double nominalKwhForSelectedModel",
                "public static double grossNameplateKwhForSelectedModel");
        assertTrue(lookup.contains(
                "UnifiedConfigManager.getSelectedVehicleModelIdStrict()"));
        assertTrue(lookup.contains(
                "throw new SelectedModelConfigUnavailableException"));

        String backup = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/config/ConfigBackupService.kt");
        String restore = between(
                backup,
                "fun applyBundle(",
                "private fun bundleSecretsDecrypt");
        assertOrdered(
                restore,
                "UnifiedConfigManager.runUnderConfigLock",
                "resetSohForModelRestore()");

        String estimator = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/abrp/SohEstimator.java");
        String detectionEntry = between(
                estimator,
                "public void autoDetectCarModel(android.content.Context context)",
                "private void autoDetectCarModelInternal");
        assertOrdered(
                detectionEntry,
                "UnifiedConfigManager.runUnderConfigLock",
                "readModelNominalFromManifest()",
                "autoDetectCarModelFromConfigSnapshot");
        String detectionLocked = between(
                estimator,
                "private void autoDetectCarModelInternal",
                "private double readModelNominalFromManifest");
        assertFalse(detectionLocked.contains("UnifiedConfigManager"));
        assertFalse(detectionLocked.contains(
                "readModelNominalFromManifest()"));
        String reset = between(
                estimator,
                "public void reset()",
                "// ==================== STATUS");
        assertOrdered(
                reset,
                "userNominalConfig.runUnderConfigLock",
                "readConfiguredUserNominalSnapshot()",
                "synchronized (autoDetectLock)",
                "nextResetModelEpoch(resetModelEpoch)",
                "persistClearedStateTombstoneWithDurabilityRetries",
                "resetModelEpoch = replacementEpoch");
    }

    @Test
    public void configEntryPointsAcquireConfigBeforeEstimatorLock()
            throws Exception {
        File directory = temporaryFolder.newFolder("config-lock-order");
        File destination = new File(directory, "soh.properties");
        AtomicReference<Object> estimatorLock = new AtomicReference<>();
        AtomicReference<Double> configuredNominal =
            new AtomicReference<>(0.0);
        AtomicInteger configDepth = new AtomicInteger();
        AtomicInteger transactions = new AtomicInteger();
        SohEstimator.UserNominalConfig config =
                new SohEstimator.UserNominalConfig() {
                    @Override
                    public boolean write(Object value) {
                        assertTrue(configDepth.get() > 0);
                        configuredNominal.set(
                            value == JSONObject.NULL
                                ? 0.0 : ((Number) value).doubleValue());
                        return true;
                    }

                    @Override
                    public double read() {
                        assertTrue(configDepth.get() > 0);
                        return configuredNominal.get();
                    }

                    @Override
                    public void runUnderConfigLock(Runnable work) {
                        Object lock = estimatorLock.get();
                        if (lock != null) {
                            assertFalse(Thread.holdsLock(lock));
                        }
                        transactions.incrementAndGet();
                        configDepth.incrementAndGet();
                        try {
                            work.run();
                        } finally {
                            configDepth.decrementAndGet();
                        }
                    }
                };
        SohEstimator estimator = new SohEstimator(
                destination,
                (file, properties) -> {
                    store(file, properties);
                    return SohEstimator.PersistenceOutcome.DURABLE;
                },
                config);
        estimatorLock.set(getField(estimator, "autoDetectLock"));

        estimator.init();
        estimator.setNominalCapacityKwhFromUser(30.0);
        estimator.reset();
        estimator.clearUserNominal();

        assertTrue(transactions.get() >= 4);
        assertEquals(0, configDepth.get());
    }

    @Test
    public void statusSnapshotIsCoherentAndImmutable() throws Exception {
        File directory = temporaryFolder.newFolder("status-snapshot");
        SohEstimator estimator = estimatorWithNominal(
                new File(directory, "soh.properties"), 20.0);
        setField(estimator, "currentSoh", 88.0);
        setField(estimator, "calibrationSoh", 82.0);
        setField(estimator, "peakRemainKwhAtFull", 18.0);
        setField(estimator, "peakRemainKwhSamples", 3);

        Object snapshot = captureStatusSnapshot(estimator, false, -1.0);

        setField(estimator, "nominalCapacityKwh", 30.0);
        setField(estimator, "currentSoh", 75.0);
        assertEquals(20.0,
                (Double) getField(snapshot, "nominalCapacityKwh"), 0.0);
        assertEquals(88.0,
                (Double) getField(snapshot, "currentSoh"), 0.0);
        assertEquals(17.6,
                (Double) getField(snapshot, "estimatedCapacityKwh"), 0.0001);
        assertEquals("live", getField(snapshot, "displaySource"));

        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/abrp/SohEstimator.java");
        String getStatus = between(
                source,
                "public org.json.JSONObject getStatus()",
                "private StatusSnapshot captureStatusSnapshot");
        String capture = between(
                source,
                "private StatusSnapshot captureStatusSnapshot",
                "private long readPersistedLastUpdatedLocked");
        assertTrue(getStatus.contains("captureStatusSnapshot(phev, oemSoh)"));
        assertFalse(getStatus.contains("getEstimatedCapacityKwh()"));
        assertTrue(capture.contains("synchronized (autoDetectLock)"));
    }

    @Test
    public void clearResetAndMigrationHaveNoDirectRewriteOrDelete()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/abrp/SohEstimator.java");
        String clear = between(
                source,
                "public void clearUserNominal()",
                "public double getNominalCapacityKwh()");
        String setUserNominal = between(
                source,
                "public boolean setNominalCapacityKwhFromUser",
                "public void clearUserNominal()");
        String reset = between(
                source,
                "public void reset()",
                "// ==================== STATUS");
        String schema = between(
                source,
                "private void writeSchemaStamp()",
                "private void persistEstimate()");
        String snapshot = between(
                source,
                "public CapacitySohSnapshot getCapacitySohSnapshot()",
                "private double getDisplaySohLocked");

        assertOrdered(
                clear,
                "configCleared = userNominalConfig.write",
                "if (!configCleared)",
                "this.nominalCapacityKwh = 0");
        assertOrdered(
                setUserNominal,
                "configSaved = userNominalConfig.write",
                "if (!configSaved)",
                "this.nominalCapacityKwh = capacityKwh");
        assertOrdered(
                reset,
                "persistClearedStateTombstoneWithDurabilityRetries",
                "if (!tombstoneOutcome.wasCommitted())",
                "clearEstimateStateLocked(true)",
                "estimatorGeneration++",
                "if (tombstoneOutcome != PersistenceOutcome.DURABLE)");
        assertTrue(schema.contains(
                "nominalOnlyProperties(replacement, true)"));
        assertTrue(schema.contains(
                "publishPropertiesWithDurabilityRetries("));
        assertFalse(schema.contains("FileInputStream"));
        assertFalse(schema.contains("FileOutputStream"));
        assertFalse(source.contains("sohFile.delete()"));
        assertFalse(source.contains("new FileOutputStream(sohFile"));
        assertTrue(source.contains(
                "public static final class CapacitySohSnapshot"));
        assertTrue(source.contains(
                "private final double nominalCapacityKwh;"));
        assertTrue(source.contains("private final double displaySoh;"));
        assertTrue(source.contains(
                "public long getEstimatorGeneration()"));
        assertTrue(source.contains(
                "public long getResetModelEpoch()"));

        int externalRead = snapshot.indexOf(
                "BydDataCollector.getInstance()");
        int lock = snapshot.indexOf("synchronized (autoDetectLock)");
        int capture = snapshot.indexOf(
                "return new CapacitySohSnapshot", lock);
        assertTrue(externalRead >= 0);
        assertTrue(lock > externalRead);
        assertTrue(capture > lock);
    }

    private static SohEstimator estimatorWithNominal(
            File destination, double nominalKwh) throws Exception {
        SohEstimator estimator = new SohEstimator(destination);
        setField(estimator, "nominalCapacityKwh", nominalKwh);
        setField(estimator, "nominalSource", "auto");
        setField(estimator, "resetModelEpoch", 100L);
        return estimator;
    }

    private static SohEstimator estimatorWithAdapters(
            File destination, double configuredNominal, boolean writesSucceed)
            throws Exception {
        SohEstimator.PersistenceWriter writer = (file, properties) -> {
            store(file, properties);
            return SohEstimator.PersistenceOutcome.DURABLE;
        };
        SohEstimator estimator = new SohEstimator(
                destination,
                writer,
                fixedConfig(configuredNominal, writesSucceed));
        setField(estimator, "nominalCapacityKwh", configuredNominal);
        setField(estimator, "nominalSource", "user");
        setField(estimator, "resetModelEpoch", 100L);
        return estimator;
    }

    private static SohEstimator.UserNominalConfig fixedConfig(
            double configuredNominal, boolean writesSucceed) {
        return new SohEstimator.UserNominalConfig() {
            @Override
            public boolean write(Object value) {
                return writesSucceed;
            }

            @Override
            public double read() {
                return configuredNominal;
            }
        };
    }

    private static void store(File file, Properties properties)
            throws IOException {
        try (FileOutputStream output =
                new FileOutputStream(file, false)) {
            properties.store(output, "test");
            output.getFD().sync();
        }
    }

    private static void storeOldUserEstimate(File destination)
            throws IOException {
        Properties properties = new Properties();
        properties.setProperty("schema_version", "3");
        properties.setProperty("last_updated", "1000");
        properties.setProperty("soh_percent", "88.0");
        properties.setProperty("nominal_capacity_kwh", "20.0");
        properties.setProperty("nominal_source", "user");
        properties.setProperty("nominal_identity",
                "user:" + Long.toHexString(
                    Double.doubleToLongBits(20.0)));
        properties.setProperty("calibration_soh", "82.0");
        properties.setProperty("calibration_timestamp_ms", "1000");
        store(destination, properties);
    }

    private static Properties load(File file) throws Exception {
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            properties.load(input);
        }
        return properties;
    }

    private static void setField(Object target, String name, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void invoke(Object target, String methodName)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static boolean tryCommitInitialSeed(
            SohEstimator estimator,
            long generation,
            double nominalKwh,
            double candidateSoh) throws Exception {
        Method method = SohEstimator.class.getDeclaredMethod(
                "tryCommitInitialSeed",
                long.class,
                double.class,
                double.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(
                estimator, generation, nominalKwh, candidateSoh);
    }

    private static void assertIllegalState(ThrowingRunnable runnable)
            throws Exception {
        try {
            runnable.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("Expected IllegalStateException");
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static SohEstimator.CapacitySohSnapshot captureSnapshot(
            SohEstimator estimator) throws Exception {
        Method method = SohEstimator.class.getDeclaredMethod(
                "captureCapacitySohSnapshot",
                boolean.class,
                double.class);
        method.setAccessible(true);
        return (SohEstimator.CapacitySohSnapshot) method.invoke(
                estimator, false, -1.0);
    }

    private static Object captureStatusSnapshot(
            SohEstimator estimator, boolean phev, double oemSoh)
            throws Exception {
        Method method = SohEstimator.class.getDeclaredMethod(
                "captureStatusSnapshot",
                boolean.class,
                double.class);
        method.setAccessible(true);
        return method.invoke(estimator, phev, oemSoh);
    }

    private static void assertOrdered(String source, String... needles) {
        int previous = -1;
        for (String needle : needles) {
            int position = source.indexOf(needle, previous + 1);
            assertTrue("Missing or out of order: " + needle,
                    position > previous);
            previous = position;
        }
    }

    private static String between(
            String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        if (start < 0 || end < 0) {
            throw new AssertionError("Could not locate source markers");
        }
        return source.substring(start, end);
    }

    private static String readRepositoryFile(String relativePath)
            throws Exception {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule =
                    current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
