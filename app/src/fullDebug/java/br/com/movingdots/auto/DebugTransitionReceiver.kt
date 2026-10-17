package br.com.movingdots.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import br.com.movingdots.DetectionLog

/**
 * Debug-only entry point that simulates a vehicle ENTER without driving. (An EXIT needs no
 * simulation: sessions end when samples stop showing a vehicle.)
 */
class DebugTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DetectionLog(context).add("Debug: simulated ENTER")
        VehicleTransitionReceiver.startSession(context)
    }
}
