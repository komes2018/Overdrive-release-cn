package com.overdrive.app.automation.condition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.overdrive.app.automation.Automations;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Starts a child JVM with saved driving rules so Automations receives a genuine cold start.
 */
public class AutomationColdStartIntegrationTest {

    @Test
    public void savedDrivingRulesScheduleFastPollersWithoutRetoggle() throws Exception {
        Path home = Files.createTempDirectory("overdrive-automation-cold-start-");
        Path output = home.resolve("probe.log");
        try {
            Files.write(
                    home.resolve("config.json"),
                    savedAutomations().toString().getBytes(StandardCharsets.UTF_8));

            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    childClasspath(),
                    AutomationColdStartIntegrationTest.class.getName(),
                    home.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();

            boolean exited = process.waitFor(30, TimeUnit.SECONDS);
            String probeOutput = Files.exists(output)
                    ? new String(Files.readAllBytes(output), StandardCharsets.UTF_8)
                    : "";
            if (!exited) {
                process.destroyForcibly();
                fail("cold-start probe timed out:\n" + probeOutput);
            }
            assertEquals("cold-start probe failed:\n" + probeOutput, 0, process.exitValue());
            assertTrue("probe did not reach its success assertion:\n" + probeOutput,
                    probeOutput.contains("COLD_START_POLLERS_OK"));
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(home)) {
                paths.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("automation home is required");
        System.setProperty("overdrive.automation.home", args[0]);

        com.overdrive.app.logging.DaemonLogger.Config config =
                new com.overdrive.app.logging.DaemonLogger.Config();
        config.enableConsoleLog = false;
        config.enableFileLog = false;
        config.enableStdoutLog = true;
        com.overdrive.app.logging.DaemonLogger.configure(config);

        require(Automations.isEventReferenced(BydEvent.TURN_LEFT),
                "left turn reference not loaded");
        require(Automations.isEventReferenced(BydEvent.TURN_RIGHT),
                "right turn reference not loaded");
        require(Automations.isEventReferenced(BydEvent.GEAR),
                "gear reference not loaded");
        require(Automations.isEventReferenced(BydEvent.SPEED_KMPH),
                "speed reference not loaded");
        require(Automations.isEventReferenced(BydEvent.ACCELERATOR),
                "accelerator reference not loaded");
        require(Automations.isEventReferenced(BydEvent.BRAKE),
                "brake reference not loaded");
        require(Automations.isEventReferenced(BydEvent.STEERING_ANGLE),
                "steering reference not loaded");
        require(Automations.isEventReferenced(BydEvent.STEERING_HEAT),
                "steering heat reference not loaded");
        require(TurnSignalEvent.isScheduledForTest(), "turn poller not scheduled");
        require(GearEvent.isScheduledForTest(), "gear poller not scheduled");
        require(DynamicsEvent.isScheduledForTest(), "dynamics poller not scheduled");
        require(ClimateEvent.isScheduledForTest(), "climate poller not scheduled");
        System.out.println("COLD_START_POLLERS_OK");
    }

    private static JSONObject savedAutomations() throws Exception {
        return new JSONObject()
                .put("right-camera", cameraAutomation("right"))
                .put("left-camera", cameraAutomation("left"));
    }

    private static JSONObject cameraAutomation(String side) throws Exception {
        return new JSONObject()
                .put("triggers", new JSONArray()
                        .put(event("turnSignal").put("variables",
                                new JSONObject().put("side", side)))
                        .put(event("gear"))
                        .put(event("speed").put("variables",
                                new JSONObject().put("units", "kmph")))
                        .put(event("accelerator"))
                        .put(event("brake"))
                        .put(event("steeringAngle"))
                        .put(event("steeringHeat")))
                .put("conditions", new JSONArray()
                        .put(event("turnSignal")
                                .put("variables", new JSONObject().put("side", side))
                                .put("comparator", "eq")
                                .put("value", "on"))
                        .put(event("gear")
                                .put("comparator", "eq")
                                .put("value", "d")))
                .put("conditionLogic", "AND")
                .put("delay", 0)
                .put("actions", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "setVariable")
                                .put("variables", new JSONObject()
                                        .put("name", "cold_start_" + side)
                                        .put("value", side))))
                .put("elseActions", new JSONArray())
                .put("disabled", false)
                .put("name", "Driving - " + side + " front cam");
    }

    private static JSONObject event(String type) throws Exception {
        return new JSONObject()
                .put("type", type)
                .put("variables", new JSONObject());
    }

    private static String childClasspath() throws Exception {
        Set<String> entries = new LinkedHashSet<>();
        String inherited = System.getProperty("java.class.path", "");
        if (!inherited.isEmpty()) {
            for (String entry : inherited.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!entry.isEmpty()) entries.add(entry);
            }
        }

        for (ClassLoader loader = AutomationColdStartIntegrationTest.class.getClassLoader();
             loader != null;
             loader = loader.getParent()) {
            if (!(loader instanceof URLClassLoader)) continue;
            for (URL url : ((URLClassLoader) loader).getURLs()) {
                if ("file".equals(url.getProtocol())) {
                    entries.add(Path.of(url.toURI()).toString());
                }
            }
        }
        assertTrue("unable to build child JVM classpath", entries.size() > 1);
        return String.join(File.pathSeparator, entries);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
