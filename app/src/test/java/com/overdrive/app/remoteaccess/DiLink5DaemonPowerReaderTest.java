package com.overdrive.app.remoteaccess;

import com.overdrive.app.byd.BydVehicleData;

import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Scoped power responses must fail closed before native Bluetooth can reconnect. */
public class DiLink5DaemonPowerReaderTest {
    private static final int UNKNOWN = BydVehicleData.UNAVAILABLE;
    private static final String GENERATION = "00000000-0000-0000-0000-000000000001";

    private static final class Fixture {
        long now = 10_000L;
        DiLink5DaemonPowerReader.Scope scope =
                new DiLink5DaemonPowerReader.Scope(GENERATION, 7L);
        int power;
        int calls;
        int verified;
        String lastId;
        JSONObject lastRequest;
        Change change = payload -> {};
        boolean transportFailure;
        boolean verifierFailure;
        boolean interruptDuringVerification;
        boolean changeScopeDuringResponse;
        final DiLink5DaemonPowerReader.Reader reader = new DiLink5DaemonPowerReader.Reader(
                () -> now, () -> scope, signed -> {
                    calls++;
                    if (transportFailure) throw new IllegalStateException("Daemon is unavailable");
                    lastRequest = new JSONObject(signed);
                    lastId = lastRequest.getString("requestId");
                    JSONObject reply = reply(lastId, scope, power, now);
                    change.apply(reply);
                    if (changeScopeDuringResponse)
                        scope = new DiLink5DaemonPowerReader.Scope(GENERATION, 8L);
                    return reply.toString();
                }, JSONObject::toString, signed -> {
                    verified++;
                    if (verifierFailure) throw new SecurityException("Signature rejected");
                    if (interruptDuringVerification) Thread.currentThread().interrupt();
                    return new JSONObject(signed);
                }, () -> "request-" + calls);

        void expire() { now += DiLink5DaemonPowerReader.CACHE_TTL_MS; }
    }

    private interface Change { void apply(JSONObject payload) throws Exception; }

    private static JSONObject reply(String id, DiLink5DaemonPowerReader.Scope scope,
                                    int power, long observed) throws Exception {
        return new JSONObject().put("command", scope.responseCommand())
                .put("success", true).put("requestId", id)
                .put("modeGeneration", scope.modeGeneration).put("revision", scope.revision)
                .put("power", power).put("observedAtElapsedMs", observed);
    }

    @Test public void sendsCurrentGenerationRevisionAndUniqueRequestCorrelation() {
        Fixture f = new Fixture();
        assertEquals(0, f.reader.readNow());
        assertEquals("remoteAccessPowerRequest", f.lastRequest.optString("command"));
        assertEquals(GENERATION, f.lastRequest.optString("modeGeneration"));
        assertEquals(7L, f.lastRequest.optLong("revision"));
        assertEquals("request-0", f.lastId);
        f.expire();
        assertEquals(0, f.reader.readNow());
        assertEquals("request-1", f.lastId);
        assertEquals(2, f.verified);
    }

    @Test public void bodyworkRequestPreservesAllFourNativePowerLevels() {
        for (int power = 0; power <= 3; power++) {
            Fixture f = new Fixture();
            f.scope = new DiLink5DaemonPowerReader.Scope(GENERATION, 7L, true);
            f.power = power;
            assertEquals(power, f.reader.readNow());
            assertEquals("remoteAccessBodyworkPowerRequest", f.lastRequest.optString("command"));
        }
    }

    @Test public void bodyworkCannotAcceptDiLink5ResponseOrInvalidLevels() {
        for (Object power : new Object[]{"0", true, -1, 4, 0.5, 65535}) {
            Fixture f = new Fixture();
            f.scope = new DiLink5DaemonPowerReader.Scope(GENERATION, 7L, true);
            f.change = payload -> payload.put("power", power);
            assertEquals(UNKNOWN, f.reader.readNow());
        }
        Fixture f = new Fixture();
        f.scope = new DiLink5DaemonPowerReader.Scope(GENERATION, 7L, true);
        f.change = payload -> payload.put("command", "remoteAccessPowerResponse");
        assertEquals(UNKNOWN, f.reader.readNow());
    }

    @Test public void bodyworkUnavailableRetiresPreviousOff() {
        Fixture f = new Fixture();
        f.scope = new DiLink5DaemonPowerReader.Scope(GENERATION, 7L, true);
        assertEquals(0, f.reader.readNow());
        f.expire();
        f.power = UNKNOWN;
        assertEquals(UNKNOWN, f.reader.readNow());
    }

    @Test public void changingPowerSourceDiscardsCacheAndRevokesInflightReply() {
        Fixture f = new Fixture();
        assertEquals(0, f.reader.readNow());
        f.scope = new DiLink5DaemonPowerReader.Scope(GENERATION, 7L, true);
        f.power = 3;
        assertEquals(3, f.reader.readNow());
        assertEquals(2, f.calls);
        f.expire();
        f.change = payload -> f.scope = new DiLink5DaemonPowerReader.Scope(GENERATION, 7L);
        assertEquals(UNKNOWN, f.reader.readNow());
    }

    @Test public void callersWithinOneSecondShareAnOnDemandResult() {
        Fixture f = new Fixture();
        assertEquals(0, f.reader.readNow());
        f.now += 999L;
        assertEquals(0, f.reader.readNow());
        assertEquals(1, f.calls);
        assertEquals(1, f.verified);
    }

    @Test public void expiryPerformsFreshReadAndRetiresPreviousOff() {
        Fixture f = new Fixture();
        assertEquals(0, f.reader.readNow());
        f.power = 1;
        f.expire();
        assertEquals(1, f.reader.readNow());
        assertEquals(2, f.calls);
    }

    @Test public void freshUnknownRetiresOffAndNeverReusesIt() {
        Fixture f = new Fixture();
        assertEquals(0, f.reader.readNow());
        f.power = UNKNOWN;
        f.expire();
        assertEquals(UNKNOWN, f.reader.readNow());
        f.now++;
        assertEquals(UNKNOWN, f.reader.readNow());
        assertEquals(2, f.calls);
    }

    @Test public void changedRevisionDiscardsCacheImmediately() {
        Fixture f = new Fixture();
        assertEquals(0, f.reader.readNow());
        f.scope = new DiLink5DaemonPowerReader.Scope(GENERATION, 8L);
        f.power = 1;
        assertEquals(1, f.reader.readNow());
        assertEquals(2, f.calls);
    }

    @Test public void changedModeGenerationDiscardsCacheImmediately() {
        Fixture f = new Fixture();
        assertEquals(0, f.reader.readNow());
        f.scope = new DiLink5DaemonPowerReader.Scope("replacement-generation", 7L);
        f.power = 1;
        assertEquals(1, f.reader.readNow());
        assertEquals(2, f.calls);
    }

    @Test public void removedScopeInvalidatesCacheWithoutTransport() {
        Fixture f = new Fixture();
        assertEquals(0, f.reader.readNow());
        f.scope = null;
        assertEquals(UNKNOWN, f.reader.readNow());
        assertEquals(1, f.calls);
        f.scope = new DiLink5DaemonPowerReader.Scope(GENERATION, 7L);
        f.power = UNKNOWN;
        assertEquals(UNKNOWN, f.reader.readNow());
        assertEquals(2, f.calls);
    }

    @Test public void coldInvalidScopeDoesNotStartAnyTransport() {
        for (DiLink5DaemonPowerReader.Scope scope : new DiLink5DaemonPowerReader.Scope[]{
                null, new DiLink5DaemonPowerReader.Scope(null, 7L),
                new DiLink5DaemonPowerReader.Scope("", 7L),
                new DiLink5DaemonPowerReader.Scope(GENERATION, -1L)}) {
            Fixture f = new Fixture();
            f.scope = scope;
            assertEquals(UNKNOWN, f.reader.readNow());
            assertEquals(0, f.calls);
            assertEquals(0, f.verified);
        }
    }

    @Test public void settingsChangeDuringTransportRevokesResponse() {
        Fixture f = new Fixture();
        f.changeScopeDuringResponse = true;
        assertEquals(UNKNOWN, f.reader.readNow());
        f.changeScopeDuringResponse = false;
        f.power = 1;
        assertEquals(1, f.reader.readNow());
        assertEquals(2, f.calls);
    }

    @Test public void transportFailureRetiresPreviousOff() {
        Fixture f = new Fixture();
        assertEquals(0, f.reader.readNow());
        f.expire();
        f.transportFailure = true;
        assertEquals(UNKNOWN, f.reader.readNow());
        f.transportFailure = false;
        f.power = UNKNOWN;
        assertEquals(UNKNOWN, f.reader.readNow());
    }

    @Test public void signatureFailureRetiresPreviousOffAndCannotCacheResponse() {
        Fixture f = new Fixture();
        assertEquals(0, f.reader.readNow());
        f.expire();
        f.verifierFailure = true;
        assertEquals(UNKNOWN, f.reader.readNow());
        f.verifierFailure = false;
        f.power = 1;
        f.expire();
        assertEquals(1, f.reader.readNow());
        assertEquals(3, f.calls);
    }

    @Test public void responseForAnotherRequestIsRejected() {
        rejected(payload -> payload.put("requestId", "another-request"));
    }

    @Test public void responseForAnotherGenerationOrRevisionIsRejected() {
        rejected(payload -> payload.put("modeGeneration", "another-generation"));
        rejected(payload -> payload.put("revision", 8L));
        rejected(payload -> payload.put("revision", "7"));
        rejected(payload -> payload.put("revision", 7.5));
    }

    @Test public void wrongCommandOrUnsuccessfulResponseCannotProveOff() {
        rejected(payload -> payload.put("command", "remoteAccessObservation"));
        rejected(payload -> payload.put("success", false));
        rejected(payload -> payload.put("success", "true"));
        rejected(payload -> payload.remove("success"));
    }

    @Test public void weakUnknownAndUnsupportedPowerValuesNeverBecomeOff() {
        Object[] invalid = {"0", true, JSONObject.NULL, -1, 2, 3, 0.5,
                65535, Long.MAX_VALUE, "weak"};
        for (Object power : invalid) rejected(payload -> payload.put("power", power));
    }

    @Test public void missingPowerTimestampOrScopeIsRejected() {
        for (String field : new String[]{"power", "observedAtElapsedMs", "modeGeneration",
                "revision", "requestId", "command"})
            rejected(payload -> payload.remove(field));
    }

    @Test public void futureAndPreRequestTimestampsCannotProveOff() {
        rejected(payload -> payload.put("observedAtElapsedMs", 10_001L));
        rejected(payload -> payload.put("observedAtElapsedMs", 9_999L));
        rejected(payload -> payload.put("observedAtElapsedMs", "10000"));
        rejected(payload -> payload.put("observedAtElapsedMs", 10_000.5));
    }

    @Test public void slowResponseCannotExtendItsSourceFreshness() {
        Fixture f = new Fixture();
        f.change = payload -> f.now += DiLink5DaemonPowerReader.MAX_SAMPLE_AGE_MS + 1L;
        assertEquals(UNKNOWN, f.reader.readNow());
    }

    @Test public void exactMaximumSampleAgeIsAcceptedButExpiresOnNextRead() {
        Fixture f = new Fixture();
        f.change = payload -> f.now += DiLink5DaemonPowerReader.MAX_SAMPLE_AGE_MS;
        assertEquals(0, f.reader.readNow());
        f.now++;
        f.change = payload -> {};
        f.power = UNKNOWN;
        assertEquals(UNKNOWN, f.reader.readNow());
        assertEquals(2, f.calls);
    }

    @Test public void monotonicClockRegressionCannotReuseOff() {
        Fixture f = new Fixture();
        assertEquals(0, f.reader.readNow());
        f.now--;
        f.transportFailure = true;
        assertEquals(UNKNOWN, f.reader.readNow());
        assertEquals(2, f.calls);
    }

    @Test public void oversizedResponseIsRejectedBeforeSignatureVerification() {
        Fixture f = new Fixture();
        f.change = payload -> payload.put("padding", new String(
                new char[DiLink5DaemonPowerReader.MAX_RESPONSE_BYTES]).replace('\0', 'x'));
        assertEquals(UNKNOWN, f.reader.readNow());
        assertEquals(0, f.verified);
    }

    @Test public void replayedResponseFromPreviousRequestIsRejected() {
        Fixture f = new Fixture();
        assertEquals(0, f.reader.readNow());
        String previousId = f.lastId;
        f.expire();
        f.change = payload -> payload.put("requestId", previousId);
        assertEquals(UNKNOWN, f.reader.readNow());
    }

    @Test public void concurrentCallersShareOneBoundedTransport() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        DiLink5DaemonPowerReader.Scope scope = new DiLink5DaemonPowerReader.Scope(GENERATION, 7L);
        DiLink5DaemonPowerReader.Reader reader = new DiLink5DaemonPowerReader.Reader(
                () -> 10_000L, () -> scope, signed -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    if (!release.await(2L, TimeUnit.SECONDS))
                        throw new IllegalStateException("Test transport was not released");
                    JSONObject request = new JSONObject(signed);
                    return reply(request.getString("requestId"), scope, 0, 10_000L).toString();
                }, JSONObject::toString, JSONObject::new, () -> "concurrent-request");
        Runnable read = () -> {
            try { assertEquals(0, reader.readNow()); }
            catch (Throwable failed) { failure.compareAndSet(null, failed); }
        };
        Thread first = new Thread(read);
        Thread second = new Thread(read);
        try {
            first.start();
            assertTrue(entered.await(2L, TimeUnit.SECONDS));
            second.start();
        } finally {
            release.countDown();
            first.join(2_000L);
            second.join(2_000L);
        }
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(1, calls.get());
    }

    @Test public void recreatedReaderCannotInheritOffFromPreviousProcess() {
        Fixture old = new Fixture();
        assertEquals(0, old.reader.readNow());
        Fixture replacement = new Fixture();
        replacement.transportFailure = true;
        assertEquals(UNKNOWN, replacement.reader.readNow());
        assertEquals(1, replacement.calls);
    }

    @Test public void failedQuerySharesUnknownWithoutRetryingForEveryPhone() {
        Fixture f = new Fixture();
        f.transportFailure = true;
        assertEquals(UNKNOWN, f.reader.readNow());
        f.now += 999L;
        assertEquals(UNKNOWN, f.reader.readNow());
        assertEquals(1, f.calls);
        f.now++;
        f.transportFailure = false;
        f.power = 1;
        assertEquals(1, f.reader.readNow());
        assertEquals(2, f.calls);
    }

    @Test public void interruptedCallerCannotUseCachedOffOrStartTransport() {
        for (boolean cached : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            if (cached) assertEquals(0, f.reader.readNow());
            try {
                Thread.currentThread().interrupt();
                assertEquals(UNKNOWN, f.reader.readNow());
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(cached ? 1 : 0, f.calls);
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test public void responseCompletedAfterCancellationCannotPublishOrCacheOff() {
        Fixture f = new Fixture();
        f.interruptDuringVerification = true;
        try {
            assertEquals(UNKNOWN, f.reader.readNow());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        f.interruptDuringVerification = false;
        f.power = UNKNOWN;
        assertEquals(UNKNOWN, f.reader.readNow());
        assertEquals(2, f.calls);
    }

    private static void rejected(Change change) {
        Fixture f = new Fixture();
        f.change = change;
        assertEquals(UNKNOWN, f.reader.readNow());
    }
}
