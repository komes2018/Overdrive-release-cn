package com.overdrive.app.ui.vehicle

import androidx.annotation.DrawableRes
import com.overdrive.app.R
import java.util.Locale

/**
 * Maps a selected vehicle model id to its dashboard render.
 *
 * Ids arrive from /api/models/selected, but the id space is wider than
 * models/manifest.json: a manually selected or HAL-reported model can be
 * anything DashboardFragment.modelDisplayName knows about.
 */
object VehicleArt {

    /** @return the render for [modelId], or the generic fallback. */
    @DrawableRes
    fun drawableFor(modelId: String?): Int = when (normalize(modelId)) {
        "seal" -> R.drawable.vehicle_seal
        "sealion7" -> R.drawable.vehicle_sealion7
        // Same body as the Seal U; only the drivetrain differs.
        "sealu", "sealudmi" -> R.drawable.vehicle_seal_u
        "dolphin" -> R.drawable.vehicle_dolphin
        "atto3" -> R.drawable.vehicle_atto3
        "atto3evo" -> R.drawable.vehicle_atto3_evo
        "atto2" -> R.drawable.vehicle_atto2
        "han" -> R.drawable.vehicle_han
        "tang" -> R.drawable.vehicle_tang
        "m6" -> R.drawable.vehicle_m6
        "seagull" -> R.drawable.vehicle_seagull
        "destroyer", "destroyer05" -> R.drawable.vehicle_destroyer05
        // Shark currently reuses seal.glb in the manifest; keep its still in
        // lockstep with the actual 3D asset until a dedicated model ships.
        "shark" -> R.drawable.vehicle_seal
        else -> R.drawable.vehicle_fallback
    }

    /** Folds separator styles, so "seal-u", "seal_u" and "Seal U" all match. */
    private fun normalize(modelId: String?): String =
        modelId?.lowercase(Locale.US)?.filter(Char::isLetterOrDigit) ?: ""
}
