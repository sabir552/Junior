package com.junior.assistant.service

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationHelperService : NotificationListenerService() {

    companion object {
        const val ACTION_WHATSAPP_NOTIFICATION = "com.junior.WHATSAPP_NOTIFICATION"
        const val EXTRA_SENDER = "extra_sender"
        const val EXTRA_MESSAGE = "extra_message"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        if (packageName == "com.whatsapp") {
            try {
                val extras = sbn.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Someone"
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

                if (text.isNotEmpty() && !text.contains("new messages") && !title.contains("WhatsApp")) {
                    Log.d("NotificationService", "WhatsApp notification captured: $title -> $text")

                    val intent = Intent(ACTION_WHATSAPP_NOTIFICATION).apply {
                        setPackage(this@NotificationHelperService.packageName)
                        putExtra(EXTRA_SENDER, title)
                        putExtra(EXTRA_MESSAGE, text)
                    }
                    sendBroadcast(intent)
                }
            } catch (e: Exception) {
                Log.e("NotificationHelper", "Error parsing notification", e)
            }
        }
    }
}
