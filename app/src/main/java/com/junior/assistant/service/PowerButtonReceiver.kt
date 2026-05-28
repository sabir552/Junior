package com.junior.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class PowerButtonReceiver : BroadcastReceiver() {

    companion object {
        private var lastPressTime: Long = 0
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == Intent.ACTION_SCREEN_OFF || intent.action == Intent.ACTION_SCREEN_ON) {
            val currentTime = System.currentTimeMillis()
            val diff = currentTime - lastPressTime
            Log.d("PowerButtonReceiver", "Power button state action: ${intent.action}, diff=$diff ms")

            if (diff in 50..600) {
                Log.d("PowerButtonReceiver", "Double press recognized. Activating Overlay.")
                val overlayIntent = Intent(context, JuniorOverlayService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(overlayIntent)
                    } else {
                        context.startService(overlayIntent)
                    }
                } catch (e: Exception) {
                    Log.e("PowerButtonReceiver", "Failed to start overlay foreground service", e)
                }
            }
            lastPressTime = currentTime
        }
    }
}
