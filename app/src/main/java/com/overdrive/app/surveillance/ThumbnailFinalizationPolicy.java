package com.overdrive.app.surveillance;

import java.util.List;

/**
 * Reconciles an earlier thumbnail capture with the actor's final temporal
 * verdict.
 *
 * <p>A different moving object's pixels can overlap a parked vehicle's box,
 * making the slot look live when it is captured. By event finalization,
 * ActorTracker may have enough history to prove that the boxed non-person
 * never moved. Presentation should honor that final verdict without deleting
 * or delaying the recording.
 *
 * <p>Missing actor state fails open, and a stationary PERSON remains eligible
 * because a standing person is still a valid surveillance subject.
 */
final class ThumbnailFinalizationPolicy {
    private ThumbnailFinalizationPolicy() {}

    static boolean shouldExcludeSlot(long actorId, List<Actor> finalActors) {
        if (finalActors == null || finalActors.isEmpty()) return false;

        boolean matched = false;
        boolean exclude = false;
        for (Actor actor : finalActors) {
            if (actor == null || actor.actorId != actorId) continue;

            // Last matching snapshot wins. The normal caller provides one
            // actor per id (live state wins the event-peak merge), but this
            // also behaves correctly if a future caller supplies duplicates.
            matched = true;
            exclude = actor.classGroup != Actor.ClassGroup.PERSON
                    && actor.isStaticForTimeline;
        }
        return matched && exclude;
    }
}
