package com.overdrive.app.genai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.Automation;
import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.AutomationCondition;
import com.overdrive.app.automation.condition.BydEvent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class GenAiRoutineLearnerTest {

    @Rule
    public final TemporaryFolder temporaryFolder =
            new TemporaryFolder();

    private String previousHome;
    private MutableClock clock;
    private final boolean[] enabled = {true};
    private final List<String> savedIds = new ArrayList<>();
    private final List<Automation> savedAutomations =
            new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        previousHome = System.getProperty(
                GenAiInsights.HOME_PROPERTY);
        File home = temporaryFolder.newFolder("genai");
        System.setProperty(
                GenAiInsights.HOME_PROPERTY,
                home.getAbsolutePath());
        clock = new MutableClock(
                ZoneId.of("Asia/Kolkata"),
                "2026-08-01T08:00:00");
        enabled[0] = true;
        savedIds.clear();
        savedAutomations.clear();
        GenAiRoutineLearner.installTestDependencies(
                () -> enabled[0],
                clock,
                (id, automation) -> {
                    savedIds.add(id);
                    savedAutomations.add(automation);
                    return true;
                });
    }

    @After
    public void tearDown() {
        GenAiRoutineLearner.restoreProductionDependenciesForTest();
        if (previousHome == null) {
            System.clearProperty(GenAiInsights.HOME_PROPERTY);
        } else {
            System.setProperty(
                    GenAiInsights.HOME_PROPERTY, previousHome);
        }
    }

    @Test
    public void requiresFourObservationsAcrossThreeDatesAndGroupsBySlot()
            throws Exception {
        recordClimate("2026-08-01T08:01:00", 0, 22);
        recordClimate("2026-08-01T08:29:00", 0, 22);
        recordClimate("2026-08-02T08:10:00", 0, 22);

        assertEquals(0, suggestions().length());

        recordClimate("2026-08-03T08:25:00", 0, 22);
        JSONArray suggestions = suggestions();
        assertEquals(1, suggestions.length());
        JSONObject suggestion = suggestions.getJSONObject(0);
        assertEquals("climate", suggestion.optString("kind"));
        assertEquals("08:00", suggestion.optString("time"));
        assertEquals(4, suggestion.optInt("observationCount"));
        assertEquals(3, suggestion.optInt("distinctDates"));

        recordClimate("2026-08-04T08:31:00", 0, 22);
        assertEquals(1, suggestions().length());
    }

    @Test
    public void statusReturnsAtMostTwoDeterministicSuggestions()
            throws Exception {
        for (int target = 20; target <= 22; target++) {
            recordClimate("2026-08-01T09:01:00", 0, target);
            recordClimate("2026-08-01T09:10:00", 0, target);
            recordClimate("2026-08-02T09:15:00", 0, target);
            recordClimate("2026-08-03T09:20:00", 0, target);
        }

        JSONArray first = suggestions();
        JSONArray second = suggestions();
        assertEquals(2, first.length());
        assertEquals(
                first.getJSONObject(0).optString("id"),
                second.getJSONObject(0).optString("id"));
        assertEquals(
                first.getJSONObject(1).optString("id"),
                second.getJSONObject(1).optString("id"));
    }

    @Test
    public void saveBuildsValidatedManualAutomationWithStableUuid()
            throws Exception {
        recordClimateCandidate();
        JSONObject suggestion = suggestions().getJSONObject(0);
        String suggestionId = suggestion.optString("id");

        JSONObject result = GenAiRoutineLearner.decision(
                suggestionId, "save");
        assertTrue(result.optBoolean("success"));
        assertEquals(1, savedAutomations.size());
        UUID.fromString(result.optString("automationId"));

        Automation automation = savedAutomations.get(0);
        assertEquals(Automation.MODE_MANUAL, automation.getMode());
        assertTrue(automation.toJson().optBoolean("disabled"));
        assertTrue(automation.toJson().optBoolean("manualOnly"));
        assertEquals(BydEvent.TIME,
                automation.getTriggers().iterator().next());

        AutomationCondition time =
                automation.getConditions().get(0);
        assertEquals(BydEvent.TIME, time.getEventData());
        assertEquals("eq", time.getComparator());
        assertEquals(8 * 60, time.getValue());

        AutomationAction action = automation.getActions().get(0);
        assertEquals("setAcTemp", action.getType());
        assertEquals(22, action.getVariables().get("temperature"));
        assertEquals("0", action.getVariables().get("zone"));
        String firstId = savedIds.get(0);

        assertTrue(GenAiRoutineLearner.decision(
                "", "reset").optBoolean("success"));
        recordClimateCandidate();
        String secondSuggestionId =
                suggestions().getJSONObject(0).optString("id");
        assertEquals(suggestionId, secondSuggestionId);
        assertTrue(GenAiRoutineLearner.decision(
                secondSuggestionId, "save").optBoolean("success"));
        assertEquals(firstId, savedIds.get(1));
    }

    @Test
    public void sunshadeSnoozeDismissAndResetAreLocal()
            throws Exception {
        recordSunshadeCandidate();
        JSONObject suggestion = suggestions().getJSONObject(0);
        String id = suggestion.optString("id");
        assertEquals("sunshade", suggestion.optString("kind"));
        assertEquals("close", suggestion
                .optJSONObject("action")
                .optJSONObject("variables")
                .optString("payload"));
        assertTrue(suggestion.optJSONObject("automation")
                .optBoolean("manualOnly"));

        assertTrue(GenAiRoutineLearner.decision(
                id, "snooze").optBoolean("success"));
        assertEquals(0, suggestions().length());

        clock.set("2026-08-11T08:00:00");
        assertEquals(1, suggestions().length());
        assertTrue(GenAiRoutineLearner.decision(
                id, "dismiss").optBoolean("success"));
        assertEquals(0, suggestions().length());

        assertTrue(GenAiRoutineLearner.decision(
                null, "reset").optBoolean("success"));
        JSONObject status = GenAiRoutineLearner.statusJson();
        assertEquals(0, status.optInt("observationCount"));
        assertEquals(0,
                status.optJSONArray("suggestions").length());
        assertTrue(savedAutomations.isEmpty());
    }

    @Test
    public void disabledAndIneligibleActionsAreNotRetained()
            throws Exception {
        assertFalse(GenAiRoutineLearner.recordClimate(
                0, 22, false, true));
        assertFalse(GenAiRoutineLearner.setSunshade(
                "close", true, false));
        assertFalse(GenAiRoutineLearner.setSunshade("stop"));

        assertTrue(GenAiRoutineLearner.recordClimate(0, 22));
        assertEquals(1, GenAiRoutineLearner.statusJson()
                .optInt("observationCount"));

        enabled[0] = false;
        JSONObject disabledStatus =
                GenAiRoutineLearner.statusJson();
        assertFalse(disabledStatus.optBoolean("enabled"));
        assertEquals(0,
                disabledStatus.optInt("observationCount"));

        GenAiRoutineLearner.reloadForTest();
        assertEquals(0, GenAiRoutineLearner.statusJson()
                .optInt("observationCount"));
        assertFalse(GenAiRoutineLearner.recordClimate(0, 22));
    }

    @Test
    public void storeIsPersistentBoundedAndOwnerOnly()
            throws Exception {
        for (int i = 0;
             i < GenAiRoutineLearner.MAX_OBSERVATIONS + 5;
             i++) {
            clock.set("2026-08-"
                    + String.format("%02d", 1 + (i % 20))
                    + "T10:00:00");
            assertTrue(GenAiRoutineLearner.recordClimate(
                    i % 3, 17 + (i % 17)));
        }
        assertEquals(GenAiRoutineLearner.MAX_OBSERVATIONS,
                GenAiRoutineLearner.statusJson()
                        .optInt("observationCount"));

        File store = GenAiRoutineLearner.storeFileForTest();
        assertTrue(store.isFile());
        assertTrue(store.length() > 0);
        try {
            Set<PosixFilePermission> permissions =
                    Files.getPosixFilePermissions(store.toPath());
            assertEquals(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE), permissions);
        } catch (UnsupportedOperationException ignored) {
            assertTrue(store.canRead());
            assertTrue(store.canWrite());
        }

        GenAiRoutineLearner.reloadForTest();
        assertEquals(GenAiRoutineLearner.MAX_OBSERVATIONS,
                GenAiRoutineLearner.statusJson()
                        .optInt("observationCount"));
    }

    private JSONArray suggestions() {
        JSONObject status = GenAiRoutineLearner.statusJson();
        assertTrue(status.optBoolean("success"));
        assertTrue(status.optBoolean("enabled"));
        JSONArray suggestions = status.optJSONArray("suggestions");
        assertNotNull(suggestions);
        return suggestions;
    }

    private void recordClimateCandidate() {
        recordClimate("2026-08-01T08:01:00", 0, 22);
        recordClimate("2026-08-01T08:20:00", 0, 22);
        recordClimate("2026-08-02T08:10:00", 0, 22);
        recordClimate("2026-08-03T08:25:00", 0, 22);
    }

    private void recordSunshadeCandidate() {
        recordSunshade("2026-08-01T08:01:00");
        recordSunshade("2026-08-01T08:20:00");
        recordSunshade("2026-08-02T08:10:00");
        recordSunshade("2026-08-03T08:25:00");
    }

    private void recordClimate(
            String localDateTime, int zone, int temperature) {
        clock.set(localDateTime);
        assertTrue(GenAiRoutineLearner.recordClimate(
                zone, temperature));
    }

    private void recordSunshade(String localDateTime) {
        clock.set(localDateTime);
        assertTrue(GenAiRoutineLearner.setSunshade("close"));
    }

    private static final class MutableClock extends Clock {
        private final ZoneId zone;
        private Instant instant;

        MutableClock(ZoneId zone, String localDateTime) {
            this.zone = zone;
            set(localDateTime);
        }

        void set(String localDateTime) {
            instant = LocalDateTime.parse(localDateTime)
                    .atZone(zone).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new MutableClock(
                    requestedZone,
                    LocalDateTime.ofInstant(
                            instant, requestedZone).toString());
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
