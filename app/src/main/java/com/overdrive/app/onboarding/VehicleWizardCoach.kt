package com.overdrive.app.onboarding

import android.app.Activity
import android.util.Log
import com.overdrive.app.R

/**
 * Vehicle-profile chapter: introduces the battery capacity + model setting, then opens
 * the REAL showVehicleCapacityDialog (model preset vs custom kWh, visible 5–120 toast
 * validation). Skippable — the profile is not safety-critical and is reachable later via
 * the Dashboard Vehicle tile.
 */
class VehicleWizardCoach(
    private val activity: Activity,
    private val overlay: OnboardingOverlayView,
    private val state: OnboardingState,
    private val onFinished: () -> Unit,
) {
    fun begin() {
        if (state.vehicleStepDone) { onFinished(); return }
        overlay.consumeCutoutTouch = false
        overlay.showCentered()
        overlay.bindStep(
            title = activity.getString(R.string.onboarding_vehicle_title),
            body = activity.getString(R.string.onboarding_vehicle_body),
            primaryText = activity.getString(R.string.onboarding_vehicle_primary),
            onPrimary = {
                try {
                    val opened = (activity as? com.overdrive.app.ui.MainActivity)
                        ?.openVehicleProfileForOnboarding {
                            state.vehicleStepDone = true
                            onFinished()
                        } == true
                    if (!opened) {
                        state.vehicleStepDone = true
                        onFinished()
                    }
                } catch (t: Throwable) {
                    Log.w("VehicleWizardCoach", "open vehicle dialog failed: ${t.message}")
                    state.vehicleStepDone = true
                    onFinished()
                }
            },
            secondaryText = activity.getString(R.string.onboarding_skip),
            onSecondary = { state.vehicleStepDone = true; onFinished() },
        )
    }
}
