package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;

/**
 * Publishes the per-seat seatbelt buckled/unbuckled state — and the per-seat OCCUPANCY state,
 * which comes off the same safety-belt device — into the automation state at a FAST cadence so
 * a "when the driver buckles" / "when the passenger seat becomes occupied" automation fires
 * promptly. The two signals are gated independently, so a rule on one never pays for the other.
 *
 * <p><b>Why a dedicated fast poll.</b> The seatbelt state otherwise rides only the main
 * telemetry poll ({@link BydEvent#bydEvent}), which builds every ~5s while stationary —
 * and buckling happens while parked/stopped. So the trigger lagged the buckle by up to
 * ~5s (the reported "takes 2-3 seconds"). This poll prefers the dedicated belt device and
 * falls back to the getter used by the telemetry-recording overlay, directly on the daemon at
 * a fast cadence, and publishes on change. Exact mirror of {@link TurnSignalEvent}.
 *
 * <p><b>Zero cost unless a related automation exists.</b> The scheduled task is cancelled
 * when no belt or occupancy signal is referenced.
 */
public final class SeatbeltEvent {
    private static final long POLL_MS = 500L;
    private static final ConditionalPoller poller = new ConditionalPoller(
            "seatbelt and occupancy",
            POLL_MS,
            SeatbeltEvent::referenced,
            SeatbeltEvent::poll);

    private SeatbeltEvent() {}

    private static boolean referenced() {
        return Automations.isEventReferenced(BydEvent.SEATBELT_DRIVER)
                || Automations.isEventReferenced(BydEvent.SEATBELT_PASSENGER)
                || Automations.isEventReferenced(BydEvent.OCCUPANT_DRIVER)
                || Automations.isEventReferenced(BydEvent.OCCUPANT_PASSENGER);
    }

    public static void refresh() {
        poller.refresh();
    }

    private static void poll() {
        boolean passengerBelt =
                Automations.isEventReferenced(BydEvent.SEATBELT_PASSENGER);
        boolean passengerOccupant =
                Automations.isEventReferenced(BydEvent.OCCUPANT_PASSENGER);
        if (passengerBelt || passengerOccupant) {
            // The passenger getter uses the same raw value for a real buckle and an empty seat.
            // Sample its door first so an exit edge ends the current belt session before the
            // getter can rebound to the empty-seat value.
            com.overdrive.app.byd.BydDataCollector.getInstance()
                    .pollPassengerDoorStateForSeatbeltNow();
        }
        if (Automations.isEventReferenced(BydEvent.SEATBELT_DRIVER) || passengerBelt) {
            BydEvent.pollSeatbelts();
        }
        if (passengerOccupant) {
            BydEvent.pollOccupants();
        }
        if (Automations.isEventReferenced(BydEvent.OCCUPANT_DRIVER)) {
            BydEvent.pollDriverOccupant();
        }
    }
}
