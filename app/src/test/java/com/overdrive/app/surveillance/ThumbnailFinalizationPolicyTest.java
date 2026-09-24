package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class ThumbnailFinalizationPolicyTest {

    @Test
    public void stationaryVehicleIsExcludedFromFinalBoxedThumbnail() {
        Actor parkedCar = actor(11L, Actor.ClassGroup.VEHICLE, true);

        assertTrue(ThumbnailFinalizationPolicy.shouldExcludeSlot(
                parkedCar.actorId, Collections.singletonList(parkedCar)));
    }

    @Test
    public void movingVehicleRemainsEligible() {
        Actor movingCar = actor(12L, Actor.ClassGroup.VEHICLE, false);

        assertFalse(ThumbnailFinalizationPolicy.shouldExcludeSlot(
                movingCar.actorId, Collections.singletonList(movingCar)));
    }

    @Test
    public void stationaryPersonRemainsEligible() {
        Actor standingPerson = actor(13L, Actor.ClassGroup.PERSON, true);

        assertFalse(ThumbnailFinalizationPolicy.shouldExcludeSlot(
                standingPerson.actorId, Collections.singletonList(standingPerson)));
    }

    @Test
    public void unrelatedOrMissingFinalActorStateFailsOpen() {
        Actor otherCar = actor(22L, Actor.ClassGroup.VEHICLE, true);

        assertFalse(ThumbnailFinalizationPolicy.shouldExcludeSlot(
                21L, Collections.singletonList(otherCar)));
        assertFalse(ThumbnailFinalizationPolicy.shouldExcludeSlot(21L, null));
        assertFalse(ThumbnailFinalizationPolicy.shouldExcludeSlot(
                21L, Collections.<Actor>emptyList()));
    }

    @Test
    public void latestMatchingSnapshotWinsIfCallerSuppliesDuplicates() {
        Actor earlierStatic = actor(31L, Actor.ClassGroup.VEHICLE, true);
        Actor latestMoving = actor(31L, Actor.ClassGroup.VEHICLE, false);

        assertFalse(ThumbnailFinalizationPolicy.shouldExcludeSlot(
                31L, Arrays.asList(earlierStatic, latestMoving)));
        assertTrue(ThumbnailFinalizationPolicy.shouldExcludeSlot(
                31L, Arrays.asList(latestMoving, earlierStatic)));
    }

    private static Actor actor(long id, Actor.ClassGroup group, boolean staticForTimeline) {
        return new Actor(
                id, group,
                1_000L, 2_000L,
                0L, 1_000L,
                1,
                Actor.Proximity.FAR, Actor.Proximity.FAR,
                Actor.Trend.STABLE,
                staticForTimeline, staticForTimeline,
                !staticForTimeline, true, true,
                Actor.Severity.NOTICE,
                1_500L, 500L,
                0.80f,
                10, 10, 40, 20,
                320, 240, 0,
                10, 10, 40, 20,
                0);
    }
}
