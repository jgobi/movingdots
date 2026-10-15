@file:Suppress("UNUSED_PARAMETER")

package br.com.movingdots

import android.content.Context

/**
 * The "foss" build has no Google Play services, so it has no Automatic mode: same API as the
 * "full" build's AutoDetection, with the feature reported as unsupported.
 */
object AutoDetection {
    fun isSupported(context: Context) = false

    fun hasPermission(context: Context) = false

    fun targetName(context: Context) = "IN_VEHICLE"

    fun onModeChanged(context: Context, mode: Int) = Unit

    /** An Automatic mode can't be stored by this build, but never keep one if it is. */
    fun onProcessStart(context: Context) {
        val prefs = Prefs(context)
        if (prefs.mode == CueModes.AUTO) prefs.mode = CueModes.OFF
    }

    fun onAppUpdated(context: Context) = Unit

    fun onSessionChanged(context: Context) = Unit

    fun onVehicleLeft(context: Context) = Unit
}
