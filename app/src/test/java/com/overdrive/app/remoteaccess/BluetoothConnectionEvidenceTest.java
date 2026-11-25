package com.overdrive.app.remoteaccess;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BluetoothConnectionEvidenceTest {
    private static final String MODE = "00000000-0000-0000-0000-000000000001";

    static JSONObject packet(String state, long at) throws Exception {
        boolean known = !"unknown".equals(state);
        return new JSONObject().put("connectionReadKnown", known).put("connectionState", state)
                .put("radioReadKnown", true).put("radioOn", true)
                .put("connectionObservedAtElapsedMs", at)
                .put("connectionModeGeneration", known ? MODE : "");
    }

    @Test public void knownProofKeepsOriginalTimestampAndExactFreshnessBoundary() throws Exception {
        BluetoothConnectionEvidence proof = BluetoothConnectionEvidence.parse(packet("disconnected", 100));
        assertEquals(BluetoothConnectionEvidence.State.DISCONNECTED, proof.state);
        assertEquals(100, proof.observedAtElapsedMs);
        assertTrue(proof.healthyAt(5_100, 5_000));
        assertFalse(proof.healthyAt(5_101, 5_000));
        assertFalse(proof.healthyAt(99, 5_000));
    }

    @Test public void unknownCanCarryMissingModeAndImmediatelyHasNoAuthority() throws Exception {
        JSONObject packet = packet("unknown", -1).put("radioReadKnown", false).put("radioOn", false);
        packet.remove("connectionModeGeneration");
        BluetoothConnectionEvidence proof = BluetoothConnectionEvidence.parse(packet);
        assertEquals("", proof.modeGeneration);
        assertFalse(proof.healthyAt(100, 5_000));
    }

    @Test public void oldMetadataAbsentIsDistinguishedFromPartialProof() throws Exception {
        assertFalse(BluetoothConnectionEvidence.hasAny(new JSONObject().put("rawRssi", -10)));
        JSONObject partial = new JSONObject().put("connectionReadKnown", true);
        assertTrue(BluetoothConnectionEvidence.hasAny(partial));
        try { BluetoothConnectionEvidence.parse(partial); fail("Partial proof accepted"); }
        catch (IllegalArgumentException expected) {}
    }

    @Test public void malformedAndContradictoryEvidenceIsRejected() throws Exception {
        JSONObject[] invalid = {
                packet("disconnected", 100).put("connectionReadKnown", "true"),
                packet("disconnected", 100).put("radioReadKnown", false),
                packet("disconnected", 100).put("connectionReadKnown", false),
                packet("unknown", -1).put("connectionReadKnown", true),
                packet("disconnected", 100).put("connectionObservedAtElapsedMs", 100.5),
                packet("disconnected", -1),
                packet("disconnected", 100).put("connectionModeGeneration", "1-1-1-1-1"),
                packet("disconnected", 100).put("connectionState", "lost")
        };
        for (JSONObject packet : invalid) {
            try { BluetoothConnectionEvidence.parse(packet); fail("Malformed proof accepted: " + packet); }
            catch (IllegalArgumentException expected) {}
        }
    }
}
