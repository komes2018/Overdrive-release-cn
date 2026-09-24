package com.overdrive.app.genai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class GenAiVehicleHistoryTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void plannerSchemasAreClosedAndBounded() throws Exception {
        JSONObject plan = GenAiVehicleHistory.plannerSchema();
        assertFalse(plan.getBoolean("additionalProperties"));
        assertEquals(10, plan.getJSONObject("properties")
                .getJSONObject("limit").getInt("maximum"));
        assertEquals("newest", plan.getJSONObject("properties")
                .getJSONObject("sort").getJSONArray("enum")
                .getString(0));

        JSONObject answer = GenAiVehicleHistory.answerSchema();
        assertFalse(answer.getBoolean("additionalProperties"));
        assertEquals(3, answer.getJSONObject("properties")
                .getJSONObject("caveats").getInt("maxItems"));
    }

    @Test
    public void validSingleSourcePlanParsesAndFiltersTrips()
            throws Exception {
        JSONObject json = basePlan("trips")
                .put("fromMs", NOW - 7L * 86_400_000L)
                .put("toMs", NOW)
                .put("trip", tripFilters()
                        .put("minDistanceKm", 20)
                        .put("maxDurationMinutes", 60));

        GenAiVehicleHistory.Plan plan =
                GenAiVehicleHistory.parsePlan(json.toString(), NOW);

        assertEquals("trips", plan.source);
        assertTrue(GenAiVehicleHistory.matchesTrip(
                plan, new JSONObject()
                        .put("distanceKm", 25)
                        .put("durationSeconds", 3_000)));
        assertFalse(GenAiVehicleHistory.matchesTrip(
                plan, new JSONObject()
                        .put("distanceKm", 10)
                        .put("durationSeconds", 3_000)));
    }

    @Test
    public void mixedSourceAndOversizedPlansAreRejected()
            throws Exception {
        JSONObject mixed = basePlan("trips")
                .put("fromMs", NOW - 86_400_000L)
                .put("toMs", NOW)
                .put("event", eventFilters()
                        .put("types",
                                new JSONArray().put("sentry")));
        assertInvalid(mixed);

        JSONObject tooWide = basePlan("charging")
                .put("fromMs", NOW - 367L * 86_400_000L)
                .put("toMs", NOW);
        assertInvalid(tooWide);

        JSONObject tooMany = basePlan("events")
                .put("fromMs", NOW - 86_400_000L)
                .put("toMs", NOW)
                .put("limit", 11);
        assertInvalid(tooMany);
    }

    @Test
    public void clarificationCannotCarryAHiddenQuery()
            throws Exception {
        JSONObject clarification = basePlan("none")
                .put("fromMs", 0)
                .put("toMs", 0)
                .put("needsInput", true)
                .put("reply", "Which history source should I search?");
        GenAiVehicleHistory.Plan parsed =
                GenAiVehicleHistory.parsePlan(
                        clarification.toString(), NOW);
        assertTrue(parsed.needsInput);

        clarification.put("charging", chargingFilters()
                .put("type", "dc"));
        assertInvalid(clarification);
    }

    @Test
    public void cardsAndProviderEvidenceUsePrivacyAllowlists()
            throws Exception {
        JSONObject trip = GenAiVehicleHistory.safeTripCard(
                new JSONObject()
                        .put("id", 7)
                        .put("distanceKm", 12.5)
                        .put("startLat", 12.34)
                        .put("startLon", 56.78)
                        .put("telemetryFilePath", "/private/trip.jsonl"));
        JSONObject event = GenAiVehicleHistory.safeEventCard(
                new JSONObject()
                        .put("id", "0123456789abcdef0123456789abcdef")
                        .put("type", "sentry")
                        .put("timestamp", NOW)
                        .put("filename", "secret.mp4")
                        .put("path", "/private/secret.mp4")
                        .put("videoUrl", "/video/id/secret")
                        .put("startLat", 12.34)
                        .put("place", new JSONObject()
                                .put("short", "Home")
                                .put("countryCode", "IN")
                                .put("displayName", "Exact private place")));
        JSONObject charge = GenAiVehicleHistory.safeChargingCard(
                new JSONObject()
                        .put("id", 9)
                        .put("energyAdded", 42.0)
                        .put("placeLabel", "Office")
                        .put("lat", 12.34)
                        .put("lng", 56.78)
                        .put("tariffId", "private-tariff"));

        assertFalse(trip.has("startLat"));
        assertFalse(trip.has("telemetryFilePath"));
        assertFalse(event.has("filename"));
        assertFalse(event.has("videoUrl"));
        assertFalse(event.has("startLat"));
        assertFalse(event.getJSONObject("place")
                .has("displayName"));
        assertFalse(charge.has("lat"));
        assertFalse(charge.has("tariffId"));

        JSONArray evidence =
                GenAiVehicleHistory.evidenceForCards(
                        new JSONArray()
                                .put(trip)
                                .put(event)
                                .put(charge));
        for (int i = 0; i < evidence.length(); i++) {
            JSONObject item = evidence.getJSONObject(i);
            assertFalse(item.has("id"));
            assertTrue(item.has("ref"));
        }
    }

    @Test
    public void chargingFiltersRequireKnownEvidence()
            throws Exception {
        JSONObject json = basePlan("charging")
                .put("fromMs", NOW - 30L * 86_400_000L)
                .put("toMs", NOW)
                .put("charging", chargingFilters()
                        .put("type", "dc")
                        .put("state", "completed")
                        .put("placeContains", "home")
                        .put("minEnergyKwh", 20));
        GenAiVehicleHistory.Plan plan =
                GenAiVehicleHistory.parsePlan(json.toString(), NOW);

        assertTrue(GenAiVehicleHistory.matchesCharging(
                plan, new JSONObject()
                        .put("isDc", true)
                        .put("inProgress", false)
                        .put("placeLabel", "Home charger")
                        .put("energyAdded", 30)));
        assertFalse(GenAiVehicleHistory.matchesCharging(
                plan, new JSONObject()
                        .put("inProgress", false)
                        .put("placeLabel", "Home charger")
                        .put("energyAdded", 30)));
    }

    @Test
    public void deterministicFallbackStatesCompleteness()
            throws Exception {
        GenAiVehicleHistory.Execution exact =
                new GenAiVehicleHistory.Execution(
                        "trips",
                        new JSONArray().put(
                                new JSONObject().put("kind", "trip")),
                        4, 4, true, false);
        assertEquals(
                "Found 4 matching trips; showing 1.",
                GenAiVehicleHistory.fallbackText(exact));

        GenAiVehicleHistory.Execution truncated =
                new GenAiVehicleHistory.Execution(
                        "charging", new JSONArray(),
                        2_000, 12, false, true);
        assertTrue(GenAiVehicleHistory.fallbackText(truncated)
                .contains("at least 12"));
    }

    private static JSONObject basePlan(String source)
            throws Exception {
        return new JSONObject()
                .put("source", source)
                .put("fromMs", NOW - 30L * 86_400_000L)
                .put("toMs", NOW)
                .put("sort", "newest")
                .put("limit", 10)
                .put("trip", tripFilters())
                .put("event", eventFilters())
                .put("charging", chargingFilters())
                .put("needsInput", false)
                .put("reply", "");
    }

    private static JSONObject tripFilters() throws Exception {
        return new JSONObject()
                .put("minDistanceKm", -1)
                .put("maxDistanceKm", -1)
                .put("minDurationMinutes", -1)
                .put("maxDurationMinutes", -1)
                .put("minEnergyKwh", -1)
                .put("maxEnergyKwh", -1)
                .put("minOverallScore", -1)
                .put("maxOverallScore", -1);
    }

    private static JSONObject eventFilters() throws Exception {
        return new JSONObject()
                .put("types", new JSONArray())
                .put("actorClasses", new JSONArray())
                .put("severities", new JSONArray())
                .put("proximities", new JSONArray())
                .put("placeContains", "")
                .put("country", "");
    }

    private static JSONObject chargingFilters() throws Exception {
        return new JSONObject()
                .put("type", "any")
                .put("state", "any")
                .put("placeContains", "")
                .put("minEnergyKwh", -1)
                .put("maxEnergyKwh", -1)
                .put("minPeakPowerKw", -1)
                .put("maxPeakPowerKw", -1);
    }

    private static void assertInvalid(JSONObject plan)
            throws Exception {
        try {
            GenAiVehicleHistory.parsePlan(
                    plan.toString(), NOW);
            fail("Expected invalid history plan");
        } catch (GenAiRuntime.GenAiException expected) {
            assertEquals("invalid_history_plan", expected.code);
        }
    }
}
