package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;

/**
 * Publishes AC power, seat-heat / seat-cool (per seat), steering-wheel heat, high/low beam,
 * and the AC setpoint into the automation state at a FAST cadence so climate/light changes
 * fire promptly.
 *
 * <p><b>Why a dedicated fast poll.</b> These signals otherwise ride the telemetry snapshot
 * ({@link BydEvent#bydEvent} → collectSettings / collectLight), built every ~5s. The settings
 * HAL callback (onSettingsCallback) only refreshes seat climate when the HAL happens to push a
 * {@code SET_*_SEAT_*_STATE} event, and the light callback refreshes ONLY the DRL — so a seat
 * climate or beam change made from the car's own UI lagged up to ~5s. This poll reads each
 * signal via its dedicated collector getter ({@link
 * com.overdrive.app.byd.BydDataCollector#readSeatClimateNow(boolean, int)} /
 * {@link com.overdrive.app.byd.BydDataCollector#readBeamNow(boolean)}) and publishes through the
 * SAME {@link BydEvent} event keys, so edge/dedup semantics match the snapshot path exactly —
 * just sampled faster.
 *
 * <p><b>Zero cost unless one of these automations exists.</b> The task is scheduled only while
 * at least one owned signal is referenced. Each individual SDK getter remains separately gated.
 */
public final class ClimateEvent {
    static final long POLL_MS = 500L;
    private static final ConditionalPoller poller = new ConditionalPoller(
            "climate and light modes",
            POLL_MS,
            ClimateEvent::referenced,
            BydEvent::pollClimate);

    private ClimateEvent() {}

    private static boolean referenced() {
        return Automations.isEventReferenced(BydEvent.AC)
                || Automations.isEventReferenced(BydEvent.SEAT_COOL_DRIVER)
                || Automations.isEventReferenced(BydEvent.SEAT_COOL_PASSENGER)
                || Automations.isEventReferenced(BydEvent.SEAT_HEAT_DRIVER)
                || Automations.isEventReferenced(BydEvent.SEAT_HEAT_PASSENGER)
                || Automations.isEventReferenced(BydEvent.STEERING_HEAT)
                || Automations.isEventReferenced(BydEvent.LIGHTS_HIGH_BEAM)
                || Automations.isEventReferenced(BydEvent.LIGHTS_LOW_BEAM)
                || Automations.isEventReferenced(BydEvent.LIGHTS_DRL)
                || Automations.isEventReferenced(BydEvent.AUTO_LIGHTS)
                || Automations.isEventReferenced(BydEvent.AUTO_WIPER)
                || Automations.isEventReferenced(BydEvent.WIPER_ACTIVE)
                || Automations.isEventReferenced(BydEvent.AC_SETPOINT);
    }

    public static void refresh() {
        poller.refresh();
    }

    static boolean isScheduledForTest() {
        return poller.isScheduledForTest();
    }
}
