package com.overdrive.app.remoteaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.overdrive.app.byd.DiLink5LockRail;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 1.6a: the optional gate-evidence fields of {@link DiLink5ProximityCommandGuard.Scope}. Absence is
 * today's wire shape (older daemon, manual command) and must keep working; presence must be all four
 * fields, well-formed; anything in between is rejected before it can select a write path.
 */
public class DiLink5ProximityCommandGuardScopeTest {
    private static final String TOKEN = "00000000-0000-0000-0000-000000000001";

    private static DiLink5ProximityCommandGuard.Scope plain() {
        return new DiLink5ProximityCommandGuard.Scope(TOKEN, "unlock", "di5-generation", 7L, 5000L);
    }

    private static DiLink5ProximityCommandGuard.Scope gated(String rail, int state, long observed, int raw) {
        return new DiLink5ProximityCommandGuard.Scope(TOKEN, "unlock", "di5-generation", 7L, 5000L,
                rail, state, observed, raw);
    }

    @Test
    public void gateFieldsRoundTripThroughJson() throws Exception {
        DiLink5ProximityCommandGuard.Scope original = gated("carsvc", 2, 4_321L, 2);
        JSONObject json = original.toJson();
        assertEquals("carsvc", json.getString("gateRail"));
        assertEquals(2, json.getInt("gateState"));
        assertEquals(4_321L, json.getLong("gateObservedElapsedMs"));
        assertEquals(2, json.getInt("gateRaw"));
        DiLink5ProximityCommandGuard.Scope copy =
                DiLink5ProximityCommandGuard.Scope.parse(json.toString());
        assertEquals(DiLink5LockRail.CARSVC, copy.gateRailOrNull());
        assertEquals("carsvc", copy.gateRail);
        assertEquals(2, copy.gateState);
        assertEquals(4_321L, copy.gateObservedElapsedMs);
        assertEquals(2, copy.gateRaw);
        // The five original fields are untouched by the extension.
        assertEquals(TOKEN, copy.token);
        assertEquals("unlock", copy.action);
        assertEquals("di5-generation", copy.modeGeneration);
        assertEquals(7L, copy.revision);
        assertEquals(5000L, copy.expiresAt);
        // Every rail tag round-trips; a negative / MIN_VALUE raw (legacy pair) fits the int field.
        for (DiLink5LockRail rail : DiLink5LockRail.values()) {
            DiLink5ProximityCommandGuard.Scope scope = DiLink5ProximityCommandGuard.Scope.parse(
                    gated(rail.tag(), 1, 1L, Integer.MIN_VALUE).toJson().toString());
            assertEquals(rail, scope.gateRailOrNull());
            assertEquals(Integer.MIN_VALUE, scope.gateRaw);
        }
    }

    @Test
    public void absenceIsToleratedAndIsTodaysWireShape() throws Exception {
        DiLink5ProximityCommandGuard.Scope scope = plain();
        assertNull(scope.gateRail);
        assertNull(scope.gateRailOrNull());
        assertEquals(0, scope.gateState);
        assertEquals(0L, scope.gateObservedElapsedMs);
        JSONObject json = scope.toJson();
        assertEquals(5, json.length());
        for (String key : new String[]{"gateRail", "gateState", "gateObservedElapsedMs", "gateRaw"}) {
            assertFalse(key, json.has(key));
        }
        // A 5-key JSON (older daemon) parses to an absent gate.
        DiLink5ProximityCommandGuard.Scope copy = DiLink5ProximityCommandGuard.Scope.parse(json.toString());
        assertNull(copy.gateRailOrNull());
        assertEquals(0, copy.gateState);
        assertEquals(0L, copy.gateObservedElapsedMs);
        // Unknown extra keys (relay request fields) are still ignored.
        copy = DiLink5ProximityCommandGuard.Scope.parse(
                json.put("command", "remoteAccessActuationCheck").put("requestId", "r1").toString());
        assertNull(copy.gateRailOrNull());
        // The 5-arg constructor delegates to the 9-arg one with absent values.
        assertEquals(plain().toJson().toString(), gated(null, 0, 0L, 0).toJson().toString());
    }

    @Test
    public void malformedGateEvidenceIsRejected() throws Exception {
        JSONObject good = gated("carsvc", 2, 4_321L, 2).toJson();
        expectRejected(new JSONObject(good.toString()).put("gateRail", "cloud"));
        expectRejected(new JSONObject(good.toString()).put("gateRail", "TYPED-CABIN"));
        expectRejected(new JSONObject(good.toString()).put("gateRail", ""));
        expectRejected(new JSONObject(good.toString()).put("gateState", 3));
        expectRejected(new JSONObject(good.toString()).put("gateState", 0));
        expectRejected(new JSONObject(good.toString()).put("gateState", -1));
        expectRejected(new JSONObject(good.toString()).put("gateState", "2"));
        expectRejected(new JSONObject(good.toString()).put("gateState", 2.5));
        expectRejected(new JSONObject(good.toString()).put("gateObservedElapsedMs", 0L));
        expectRejected(new JSONObject(good.toString()).put("gateObservedElapsedMs", -1L));
        expectRejected(new JSONObject(good.toString()).put("gateObservedElapsedMs", "4321"));
        expectRejected(new JSONObject(good.toString()).put("gateRaw", "2"));
        expectRejected(new JSONObject(good.toString()).put("gateRaw", 1.5));
        expectRejected(new JSONObject(good.toString()).put("gateRaw", 1L + Integer.MAX_VALUE));
        // All present or all absent: any partial set is malformed.
        for (String key : new String[]{"gateRail", "gateState", "gateObservedElapsedMs", "gateRaw"}) {
            JSONObject partial = new JSONObject(good.toString());
            partial.remove(key);
            expectRejected(partial);
            JSONObject only = plain().toJson().put(key, good.get(key));
            expectRejected(only);
        }
        // Constructor-level: a rail with absent state/time, or state/time without a rail.
        expectRejected(() -> gated("ota", 0, 0L, 0));
        expectRejected(() -> gated("ota", 2, 0L, 0));
        expectRejected(() -> gated("ota", 0, 10L, 0));
        expectRejected(() -> gated(null, 2, 10L, 0));
        expectRejected(() -> gated(null, 0, 10L, 0));
        expectRejected(() -> gated(null, 2, 0L, 0));
        expectRejected(() -> gated("cloud", 2, 10L, 0));
    }

    @Test
    public void gateFieldsDoNotChangeTheLocalAuthorisationRules() throws Exception {
        JSONObject settings = new JSONObject().put("enabled", true).put("unlockEnabled", true)
                .put("lockEnabled", true).put("dryRun", false).put("revision", 7L);
        DiLink5ProximityCommandGuard.Scope scope = gated("ota", 2, 4_321L, 2);
        assertTrue(DiLink5ProximityCommandGuard.locallyAllowed(scope, settings, 1200L, true, true, false));
        assertFalse(DiLink5ProximityCommandGuard.locallyAllowed(scope, settings, 5000L, true, true, false));
        assertFalse(DiLink5ProximityCommandGuard.locallyAllowed(
                scope, settings.put("revision", 8L), 1200L, true, true, false));
        // The authenticated reply check matches the five original fields exactly as before.
        JSONObject reply = scope.toJson().put("command", "remoteAccessActuationResponse")
                .put("allowed", true).put("requestId", "request-1").put("observedAtElapsedMs", 1100L);
        assertTrue(DiLink5ProximityCommandGuard.validReply(scope, reply, "request-1", 1000L, 1200L));
        assertFalse(DiLink5ProximityCommandGuard.validReply(
                scope, reply.put("token", "wrong"), "request-1", 1000L, 1200L));
    }

    private static void expectRejected(JSONObject json) {
        try {
            DiLink5ProximityCommandGuard.Scope.parse(json.toString());
            fail("accepted " + json);
        } catch (IllegalArgumentException expected) {
            // rejected
        } catch (ArithmeticException fractional) {
            // rejected: the shared exact() helper refuses fractional numbers this way, exactly as it
            // already does for a fractional revision or expiry
        } catch (Exception other) {
            fail("unexpected " + other + " for " + json);
        }
    }

    private static void expectRejected(Runnable construction) {
        try {
            construction.run();
            fail("accepted malformed scope");
        } catch (IllegalArgumentException expected) {
            // rejected
        }
    }
}
