package br.com.movingdots.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug-only entry point that simulates an IN_VEHICLE ENTER/EXIT without driving. */
class DebugTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VehicleTransitionReceiver.handle(context, enter = intent.getBooleanExtra("enter", true))
    }
}
