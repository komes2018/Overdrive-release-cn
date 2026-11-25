package com.overdrive.app.remoteaccess;

import com.overdrive.app.byd.BydVehicleData;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class VehiclePowerReaderTest {
    @Test public void existingFreshPowerAvoidsAcquiringAnotherDevice() {
        for (int level = 0; level <= 3; level++) {
            final int expected = level;
            assertEquals(expected, VehiclePowerReader.readFrom(() -> expected,
                    () -> { throw new AssertionError("Fallback must not run"); }));
        }
    }

    @Test public void coldProcessCanReadPowerWithoutCollectorInitialization() {
        AtomicInteger reads = new AtomicInteger();
        assertEquals(0, VehiclePowerReader.readFrom(() -> BydVehicleData.UNAVAILABLE,
                () -> { reads.incrementAndGet(); return 0; }));
        assertEquals(1, reads.get());
    }

    @Test public void failedExistingHandleCanUseAFreshGetter() {
        assertEquals(2, VehiclePowerReader.readFrom(
                () -> { throw new IllegalStateException("Unavailable handle"); }, () -> 2));
    }

    @Test public void daemonFallbackRunsOnlyWhenBothAppGettersAreUnavailable() {
        AtomicInteger daemonReads = new AtomicInteger();
        for (int level = 0; level <= 3; level++) {
            final int power = level;
            assertEquals(power, VehiclePowerReader.readFrom(() -> power, () -> null,
                    () -> { daemonReads.incrementAndGet(); return 0; }));
            assertEquals(power, VehiclePowerReader.readFrom(() -> null, () -> power,
                    () -> { daemonReads.incrementAndGet(); return 0; }));
        }
        assertEquals(0, daemonReads.get());
        assertEquals(0, VehiclePowerReader.readFrom(
                () -> { throw new SecurityException("App getter denied"); },
                () -> BydVehicleData.UNAVAILABLE,
                () -> { daemonReads.incrementAndGet(); return 0; }));
        assertEquals(1, daemonReads.get());
    }

    @Test public void missingOrInvalidDaemonReplyCannotProveOff() {
        assertEquals(BydVehicleData.UNAVAILABLE, VehiclePowerReader.readFrom(
                () -> null, () -> null, () -> { throw new IllegalStateException("No daemon"); }));
        for (Object invalid : new Object[]{null, true, "0", -1, 4, 0.5})
            assertEquals(BydVehicleData.UNAVAILABLE,
                    VehiclePowerReader.readFrom(() -> null, () -> null, () -> invalid));
    }

    @Test public void eachReadUsesTheCurrentPowerInsteadOfCachingOff() {
        AtomicInteger power = new AtomicInteger(0);
        assertEquals(0, VehiclePowerReader.readFrom(() -> null, power::get));
        power.set(3);
        assertEquals(3, VehiclePowerReader.readFrom(() -> null, power::get));
    }

    @Test public void invalidOrFailedReadsNeverBecomeVehicleOff() {
        Object[] invalid = {null, "0", true, -1, 4, 65535, Double.NaN,
                Double.POSITIVE_INFINITY, 0.5};
        for (Object raw : invalid)
            assertEquals(BydVehicleData.UNAVAILABLE,
                    VehiclePowerReader.readFrom(() -> raw, () -> raw));
        assertEquals(BydVehicleData.UNAVAILABLE, VehiclePowerReader.readFrom(
                () -> null, () -> { throw new SecurityException("Unavailable"); }));
    }

    @Test public void diLink5ReadsFreshConfirmedIgnitionForEveryDecision() {
        java.util.concurrent.atomic.AtomicReference<Boolean> on =
                new java.util.concurrent.atomic.AtomicReference<>(false);
        assertEquals(0, VehiclePowerReader.readDiLink5From(on::get));
        on.set(true);
        assertEquals(1, VehiclePowerReader.readDiLink5From(on::get));
        on.set(null);
        assertEquals(BydVehicleData.UNAVAILABLE, VehiclePowerReader.readDiLink5From(on::get));
    }

    @Test public void diLink5UnavailableOrConflictingIgnitionNeverArmsOff() {
        assertEquals(BydVehicleData.UNAVAILABLE, VehiclePowerReader.readDiLink5From(() -> null));
        assertEquals(BydVehicleData.UNAVAILABLE, VehiclePowerReader.readDiLink5From(
                () -> { throw new SecurityException("Local power unavailable"); }));
    }
}
