package com.overdrive.app.ui.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.ui.util.RecordingLibraryFilterState;
import com.overdrive.app.ui.util.RecordingsApiClient;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;

public class RecordingLibraryFilterStateTest {

    @Test
    public void exactPlaceIsNormalizedAndSingleValued() {
        Set<String> places = new LinkedHashSet<>(Arrays.asList(
                "  Cheras  ", "Kuala Lumpur"));
        RecordingLibraryFilterState state = state(
                Collections.emptySet(), Collections.emptySet(), places,
                "", Collections.emptySet(), false);

        assertEquals("cheras", state.getExactPlace());
    }

    @Test
    public void searchAndStorageCountAsActiveNarrowing() {
        assertTrue(state(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                "bay", Collections.emptySet(), false).getHasActiveNarrowing());
        assertTrue(state(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                "", Collections.singleton("sd_card"), false).getHasActiveNarrowing());
        assertFalse(state(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                "", Collections.emptySet(), false).getHasActiveNarrowing());
    }

    @Test
    public void fallbackSearchMatchesEveryPlaceLabel() {
        RecordingLibraryFilterState state = state(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                "lumpur", Collections.emptySet(), false);

        assertTrue(state.matchesFallback(
                "SD_CARD", "Cheras", "Cheras, Kuala Lumpur", null,
                Collections.emptyList(), null, false));
        assertFalse(state.matchesFallback(
                "SD_CARD", "Penang", null, null,
                Collections.emptyList(), null, false));
    }

    @Test
    public void metadataFiltersDoNotLeakThroughClipsWithoutSidecars() {
        RecordingLibraryFilterState state = state(
                Collections.singleton("person"), Collections.singleton("alert"),
                Collections.emptySet(), "", Collections.emptySet(), false);

        assertFalse(state.matchesFallback(
                "INTERNAL", null, null, null,
                Collections.emptyList(), null, false));
    }

    @Test
    public void apiQueryCarriesExactAndSubstringPlaceFilters() {
        RecordingsApiClient.Filter filter = new RecordingsApiClient.Filter(
                null,
                Collections.emptySet(),
                null,
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                "cheras",
                "kuala lumpur",
                null,
                Collections.singleton("SD_CARD"));
        String query = filter.toQuery(1, 30);

        assertTrue(query.contains("place=cheras"));
        assertTrue(query.contains("placeContains=kuala%20lumpur"));
        assertTrue(query.contains("storage=SD_CARD"));
        assertFalse("no parking narrowing unless asked", query.contains("parkingSessionId"));
    }

    @Test
    public void parkingSessionNarrowsTheApiQueryAndCountsAsActive() {
        RecordingsApiClient.Filter filter = new RecordingsApiClient.Filter(
                "sentry",
                Collections.emptySet(),
                null,
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                null,
                null,
                null,
                Collections.emptySet(),
                "park_20260919_143200");
        assertTrue(filter.toQuery(1, 30).contains("&parkingSessionId=park_20260919_143200"));
        assertTrue(filter.toContextQuery().contains("parkingSessionId=park_20260919_143200"));

        RecordingLibraryFilterState state = new RecordingLibraryFilterState(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                "", Collections.emptySet(), false, " park_20260919_143200 ");
        assertTrue(state.getHasActiveNarrowing());
        assertTrue("trimmed, same token rule as the daemon",
                "park_20260919_143200".equals(state.getNormalizedParkingSessionId()));

        RecordingLibraryFilterState bad = new RecordingLibraryFilterState(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                "", Collections.emptySet(), false, "../etc");
        assertFalse("a non-token id is dropped, not sent", bad.getHasActiveNarrowing());
    }

    private static RecordingLibraryFilterState state(
            Set<String> actors,
            Set<String> severities,
            Set<String> places,
            String placeContains,
            Set<String> storages,
            boolean dateNarrowed) {
        return new RecordingLibraryFilterState(
                actors, severities, places, placeContains, storages, dateNarrowed);
    }
}
