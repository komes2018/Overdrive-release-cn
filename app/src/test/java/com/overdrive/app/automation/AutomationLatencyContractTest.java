package com.overdrive.app.automation;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class AutomationLatencyContractTest {

    @org.junit.BeforeClass
    public static void configureHarness() throws Exception {
        System.setProperty("overdrive.automation.home",
                Files.createTempDirectory("overdrive-automation-latency").toString());
        com.overdrive.app.logging.DaemonLogger.Config config =
                new com.overdrive.app.logging.DaemonLogger.Config();
        config.enableConsoleLog = false;
        config.enableFileLog = false;
        config.enableStdoutLog = true;
        com.overdrive.app.logging.DaemonLogger.configure(config);
    }

    @Test
    public void topLevelWaitsYieldTheSharedWorker() throws Exception {
        String automations = source("automation/Automations.java");
        String queue = source("automation/AutomationQueue.java");
        String pause = source("automation/action/PauseAction.java");
        String wait = source("automation/action/WaitUntilAction.java");
        String waitState = source("automation/action/WaitUntilStateAction.java");

        assertTrue(automations.contains("RUN_DEPTH.get() != 1"));
        assertTrue(automations.contains("cursor.beginAttempt()"));
        assertTrue(queue.contains("item.startTime = Math.max(System.nanoTime(), resumeAtNanos)"));
        assertTrue(pause.indexOf("if (Automations.deferQueuedAction")
                < pause.indexOf("Thread.sleep(ms)"));
        assertTrue(wait.contains("queuedWaitDeadlineNanos"));
        assertTrue(wait.contains("deferQueuedAction(automationAction, pollMs, false)"));
        assertTrue(waitState.contains("deferQueuedAction(automationAction, pollMs, false)"));
    }

    @Test
    public void playAndStopCommandsUseOneOrderedDispatcher() throws Exception {
        String audio = source("byd/AudioPlaybackController.java");

        assertTrue(audio.contains("Executors.newSingleThreadExecutor"));
        assertTrue(audio.contains("MEDIA_COMMANDS.execute(() -> runQuietCommand(cmd))"));
        assertTrue(audio.contains("p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)"));
        assertTrue(audio.contains("; am broadcast -a "));
    }

    @Test
    public void queuedPauseLetsAnotherAutomationRunBeforeItResumes() throws Exception {
        String token = Long.toHexString(System.nanoTime());
        String slowId = "latency-slow-" + token;
        String fastId = "latency-fast-" + token;
        String startedVar = "latency_started_" + token;
        String slowDoneVar = "latency_slow_" + token;
        String fastDoneVar = "latency_fast_" + token;

        org.json.JSONArray slowActions = new org.json.JSONArray()
                .put(setVariable(startedVar, "yes"))
                .put(new org.json.JSONObject()
                        .put("type", "pause")
                        .put("variables", new org.json.JSONObject()
                                .put("milliseconds", 6000)))
                .put(setVariable(slowDoneVar, "done"));
        org.json.JSONArray fastActions = new org.json.JSONArray()
                .put(setVariable(fastDoneVar, "done"));

        try {
            assertTrue(Automations.updateAutomation(slowId, automation(slowActions)));
            assertTrue(Automations.updateAutomation(fastId, automation(fastActions)));

            AutomationQueue.addToQueue(slowId, 0);
            assertTrue("yes".equals(awaitVariable(startedVar, 5000)));
            Thread.sleep(100L);

            AutomationQueue.addToQueue(fastId, 0);
            // Keep a wide separation between the fast deadline and the deferred resume so a
            // heavily loaded full-suite worker cannot turn scheduler latency into a false failure.
            assertTrue("done".equals(awaitVariable(fastDoneVar, 4000)));
            assertTrue("done".equals(awaitVariable(slowDoneVar, 8000)));
        } finally {
            Automations.deleteAutomation(slowId);
            Automations.deleteAutomation(fastId);
        }
    }

    @Test
    public void queuedWaitSurvivesPendingRemovalAndLetsAnotherAutomationRun() throws Exception {
        String token = Long.toHexString(System.nanoTime());
        String slowId = "wait-slow-" + token;
        String fastId = "wait-fast-" + token;
        String releaseVar = "wait_release_" + token;
        String startedVar = "wait_started_" + token;
        String slowDoneVar = "wait_slow_" + token;
        String fastDoneVar = "wait_fast_" + token;

        org.json.JSONArray slowActions = new org.json.JSONArray()
                .put(setVariable(startedVar, "yes"))
                .put(new org.json.JSONObject()
                        .put("type", "waitUntilState")
                        .put("variables", new org.json.JSONObject()
                                .put("event", "variable:name=" + releaseVar)
                                .put("state", "yes")
                                .put("timeout", 5)))
                .put(setVariable(slowDoneVar, "done"));

        try {
            Automations.update(
                    com.overdrive.app.automation.action.SetVariableAction.variableEvent(releaseVar),
                    "no", true);
            assertTrue(Automations.updateAutomation(slowId, automation(slowActions)));
            assertTrue(Automations.updateAutomation(
                    fastId, automation(new org.json.JSONArray()
                            .put(setVariable(fastDoneVar, "done")))));

            AutomationQueue.addToQueue(slowId, 0);
            assertTrue("yes".equals(awaitVariable(startedVar, 5000)));
            Thread.sleep(100L);
            AutomationQueue.removeFromQueue(slowId);
            AutomationQueue.addToQueue(fastId, 0);
            assertTrue("done".equals(awaitVariable(fastDoneVar, 1500)));

            Automations.update(
                    com.overdrive.app.automation.action.SetVariableAction.variableEvent(releaseVar),
                    "yes", true);
            assertTrue("done".equals(awaitVariable(slowDoneVar, 2000)));
        } finally {
            Automations.deleteAutomation(slowId);
            Automations.deleteAutomation(fastId);
        }
    }

    private static org.json.JSONObject automation(org.json.JSONArray actions) throws Exception {
        return new org.json.JSONObject()
                .put("triggers", new org.json.JSONArray()
                        .put(new org.json.JSONObject()
                                .put("type", "callState")
                                .put("variables", new org.json.JSONObject())))
                .put("conditions", new org.json.JSONArray())
                .put("delay", 0)
                .put("actions", actions)
                .put("name", "latency regression probe")
                .put("disabled", false);
    }

    private static org.json.JSONObject setVariable(String name, String value) throws Exception {
        return new org.json.JSONObject()
                .put("type", "setVariable")
                .put("variables", new org.json.JSONObject()
                        .put("name", name)
                        .put("value", value));
    }

    private static String awaitVariable(String name, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String value = variableText(name);
            if (value != null && !value.isEmpty()) return value;
            Thread.sleep(25L);
        }
        return variableText(name);
    }

    private static String variableText(String name) {
        com.overdrive.app.automation.value.Value value = Automations.getStateValue(
                com.overdrive.app.automation.action.SetVariableAction.variableEvent(name));
        return value == null ? null : value.toString();
    }

    private static String source(String relative) throws Exception {
        Path path = Path.of("src/main/java/com/overdrive/app", relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
