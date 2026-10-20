@file:Suppress("UNUSED_PARAMETER")

package io.github.jgobi.movingdots

import android.content.Context

/**
 * The "foss" build has no Google Play services, so it can't turn the dots off when the user leaves
 * the vehicle: same API as the "full" build's AutoDetection, with the feature reported as unsupported.
 */
object AutoDetection {
    fun isSupported(context: Context) = false

    fun hasPermission(context: Context) = false

    fun isEnabled(context: Context) = false

    fun startWatching(context: Context) = Unit

    fun stopWatching(context: Context) = Unit

    fun onProcessStart(context: Context) = Unit
}
