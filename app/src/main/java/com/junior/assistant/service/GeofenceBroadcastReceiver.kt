package com.junior.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.junior.assistant.utils.HardwareFeatureManager

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val event = GeofencingEvent.fromIntent(intent)
        if (event == null) {
            Log.e("GeofenceBCR", "GeofencingEvent is null")
            return
        }
        if (event.hasError()) {
            Log.e("GeofenceBCR", "GeofencingEvent has error: ${event.errorCode}")
            return
        }
        val transition = event.geofenceTransition
        if (transition == Geofence.GEOFENCE_TRANSITION_ENTER || transition == Geofence.GEOFENCE_TRANSITION_DWELL) {
            val locationKey = intent.getStringExtra("location_key") ?: "home"
            HardwareFeatureManager.handleGeofenceFired(context.applicationContext, locationKey)
        }
    }
}
