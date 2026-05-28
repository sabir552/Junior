package com.junior.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PowerConnectionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_POWER_STATUS_CHANGED = "com.junior.POWER_STATUS_CHANGED"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val message = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> "I just connected the charger, reacts dynamically"
            Intent.ACTION_POWER_DISCONNECTED -> "I just disconnected the charger"
            Intent.ACTION_BATTERY_LOW -> "Battery level is critically low"
            else -> null
        }

        if (message != null) {
            Log.d("PowerConnectionReceiver", "Power connection status toggled: $message")
            val statusIntent = Intent(ACTION_POWER_STATUS_CHANGED).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_STATUS_MESSAGE, message)
            }
            context.sendBroadcast(statusIntent)
        }
    }
}
