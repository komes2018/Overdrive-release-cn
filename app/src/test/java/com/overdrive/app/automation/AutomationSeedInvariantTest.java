package com.overdrive.app.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.action.SetVariableAction;
import com.overdrive.app.automation.condition.BydEvent;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.value.Value;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.UUID;

/**
 * Regression test for the SEED INVARIANT in {@link Automations#update}: the FIRST value ever
 * observed for a signal is a startup seed and must NOT run trigger evaluation; only a genuine
 * transition (a second, different value) may fire automations.
 *
 * <p>Breaking this fired a burst of unrelated automations (WiFi/Bluetooth/gear/…) on every
 * daemon start — i.e. on every car power-on for on-only installs (v37 field reports). The
 * gate is stateChanged's null-oldValue guard, which only stays live if update() passes the
 * true previous value through unsubstituted when it is null.
 */
public class AutomationSeedInvariantTest {

    /** Route DaemonLogger to stdout: android.util.Log is not mocked on the JVM. */
    @BeforeClass
    public static void muteAndroidLog() {
        com.overdrive.app.logging.DaemonLogger.Config cfg =
                new com.overdrive.app.logging.DaemonLogger.Config();
        cfg.enableConsoleLog = false;
        cfg.enableFileLog = false;
        cfg.enableStdoutLog = true;
        com.overdrive.app.logging.DaemonLogger.configure(cfg);
    }

    /** Unique per-run so state left in the static maps by other tests can't collide. */
    private final String variableName =
            "seedProbe_" + UUID.randomUUID().toString().substring(0, 8);
    private String automationId;

    @After
    public void cleanup() {
        if (automationId != null) {
            Automations.deleteAutomation(automationId);
        }
    }

    @Test
    public void firstValueSeedsSilently_onlyARealTransitionFires() throws Exception {
        automationId = UUID.randomUUID().toString();

        // "When callState changes → set <variableName> = fired". No conditions, no delay.
        // callState is used because no scheduled poller publishes it in a JVM test run,
        // so this test is the only writer of that key.
        JSONObject json = new JSONObject()
                .put("triggers", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "callState")
                                .put("variables", new JSONObject())))
                .put("conditions", new JSONArray())
                .put("delay", 0)
                .put("actions", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "setVariable")
                                .put("variables", new JSONObject()
                                        .put("name", variableName)
                                        .put("value", "fired"))))
                .put("name", "seed invariant probe")
                .put("disabled", false);
        assertTrue("test automation must parse and register",
                Automations.updateAutomation(automationId, json));

        // Registration pre-seeds action-defined variables with "" (seedVariablesDefinedByActions),
        // so "did the automation run?" is observed as the variable BECOMING "fired", not non-null.
        assertTrue("probe variable must not read 'fired' before any event",
                !"fired".equals(probeVariableText()));

        // 1. FIRST value for the key — a seed. Must not fire the automation.
        Automations.update(BydEvent.CALL_STATE, "idle");
        // The queue worker is asynchronous; give a spurious fire ample time to show up.
        Thread.sleep(1500);
        assertTrue("startup seed must not run trigger evaluation (v37 power-on burst regression)",
                !"fired".equals(probeVariableText()));

        // 2. A real transition — must fire.
        Automations.update(BydEvent.CALL_STATE, "ringing");
        String fired = awaitProbeVariable("fired", 5000);
        assertEquals("a genuine value transition must fire the automation", "fired", fired);
    }

    /**
     * Observed-edge semantics ({@link Automations#updateObservedEdge}): an edge handler that
     * WITNESSED a transition must fire deterministically, in both boot orderings from the
     * 2026-08-09 field log — (a) a racing snapshot seeded the same value first, (b) the edge
     * is the first value for the key. Sampled publishes keep full seed/dedup semantics
     * throughout.
     */
    @Test
    public void observedEdgeFiresDeterministically_regardlessOfSeedRace() throws Exception {
        automationId = UUID.randomUUID().toString();
        JSONObject json = new JSONObject()
                .put("triggers", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "power")
                                .put("variables", new JSONObject())))
                .put("conditions", new JSONArray())
                .put("delay", 0)
                .put("actions", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "setVariable")
                                .put("variables", new JSONObject()
                                        .put("name", variableName)
                                        .put("value", "fired"))))
                .put("name", "observed edge probe")
                .put("disabled", false);
        assertTrue("test automation must parse and register",
                Automations.updateAutomation(automationId, json));

        // Ordering (a): a sampled snapshot seeds power silently…
        Automations.update(BydEvent.POWER, "on");
        Thread.sleep(1500);
        assertTrue("sampled seed must stay silent even for power",
                !"fired".equals(probeVariableText()));

        // …then the OBSERVED edge lands with the SAME value: the transition still happened,
        // so it must fire (the snapshot merely won the race to the state map).
        Automations.updateObservedEdge(BydEvent.POWER, "on");
        assertEquals("observed edge must fire after a same-value racing seed",
                "fired", awaitProbeVariable("fired", 5000));

        // A later sampled repeat is still a dedup'd no-op.
        resetProbe();
        Automations.update(BydEvent.POWER, "on");
        Thread.sleep(1500);
        assertTrue("sampled repeat must not re-fire", !"fired".equals(probeVariableText()));

        // And a genuine observed transition fires exactly like a sampled one.
        Automations.updateObservedEdge(BydEvent.POWER, "off");
        assertEquals("observed transition must fire",
                "fired", awaitProbeVariable("fired", 5000));

        // Exactly-once: a DUPLICATE observed edge of an already-delivered value must not
        // re-fire (heartbeat that slipped the caller's dedup).
        resetProbe();
        Automations.updateObservedEdge(BydEvent.POWER, "off");
        Thread.sleep(1500);
        assertTrue("duplicate observed edge must not re-fire an already-delivered value",
                !"fired".equals(probeVariableText()));

        // Exactly-once across publishers: a sampled transition that DELIVERS the new value
        // first suppresses the observed edge's re-fire of the same value.
        Automations.update(BydEvent.POWER, "on");     // off→on, sampled, delivers
        assertEquals("sampled transition delivers", "fired", awaitProbeVariable("fired", 5000));
        resetProbe();
        Automations.updateObservedEdge(BydEvent.POWER, "on");
        Thread.sleep(1500);
        assertTrue("observed edge must not re-deliver a transition a sampled publish already fired",
                !"fired".equals(probeVariableText()));
    }

    /**
     * Ordering (b) from the field log: the observed edge is the FIRST value ever seen for the
     * key (the ACC probe won the boot race — 16:19 boot in log_NQ26GBEA). It must fire.
     */
    @Test
    public void observedEdgeFiresAsFirstValueForUnseededKey() throws Exception {
        automationId = UUID.randomUUID().toString();
        JSONObject json = new JSONObject()
                .put("triggers", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "lock")
                                .put("variables", new JSONObject())))
                .put("conditions", new JSONArray())
                .put("delay", 0)
                .put("actions", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "setVariable")
                                .put("variables", new JSONObject()
                                        .put("name", variableName)
                                        .put("value", "fired"))))
                .put("name", "unseeded observed edge probe")
                .put("disabled", false);
        assertTrue("test automation must parse and register",
                Automations.updateAutomation(automationId, json));

        // No prior publish of "lock" anywhere in this JVM: the edge is the first value.
        Automations.updateObservedEdge(BydEvent.LOCK, "locked");
        assertEquals("observed edge must fire even as the first value for the key",
                "fired", awaitProbeVariable("fired", 5000));
    }

    @Test
    public void scopedExpiryHidesRelayedStateWithoutChangingOrdinaryUpdates() {
        EventData key = new EventData(
                "expiryProbe_" + UUID.randomUUID().toString().substring(0, 8));
        Automations.withStateExpiry(
                System.currentTimeMillis() - 1L,
                () -> {
                    Automations.update(key, "fresh", true);
                    return null;
                });
        assertNull(Automations.getStateValue(key));

        Automations.update(key, "fresh", true);
        assertEquals("fresh", Automations.getStateValue(key).toString());
    }

    private void resetProbe() {
        Automations.update(SetVariableAction.variableEvent(variableName), "reset");
    }

    private String probeVariableText() {
        Value v = Automations.getStateValue(SetVariableAction.variableEvent(variableName));
        return v == null ? null : v.toString();
    }

    private String awaitProbeVariable(String expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String v = probeVariableText();
            if (expected.equals(v)) return v;
            Thread.sleep(50);
        }
        return probeVariableText();
    }
}
