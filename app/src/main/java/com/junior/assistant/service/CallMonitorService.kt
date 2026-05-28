package com.junior.assistant.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log

class CallMonitorService : Service() {

    companion object {
        const val ACTION_INCOMING_CALL = "com.junior.INCOMING_CALL"
        const val ACTION_CALL_ENDED = "com.junior.CALL_ENDED"
        const val EXTRA_CALLER_NAME = "extra_caller_name"
    }

    private var telephonyManager: TelephonyManager? = null
    private var callback: Any? = null

    override fun onCreate() {
        super.onCreate()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        registerCallListener()
        Log.d("CallMonitorService", "Service started listening for incoming calls")
    }

    override fun onDestroy() {
        unregisterCallListener()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerCallListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val telephonyCallback = @SuppressLint("NewApi")
                object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state, null)
                    }
                }
                telephonyManager?.registerTelephonyCallback(mainExecutor, telephonyCallback)
                callback = telephonyCallback
            } else {
                @Suppress("DEPRECATION")
                val phoneStateListener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(state, phoneNumber)
                    }
                }
                telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
                callback = phoneStateListener
            }
        } catch (e: Exception) {
            Log.e("CallMonitorService", "Error registering listener", e)
        }
    }

    private fun unregisterCallListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (callback as? TelephonyCallback)?.let {
                    telephonyManager?.unregisterTelephonyCallback(it)
                }
            } else {
                (callback as? PhoneStateListener)?.let {
                    telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (e: Exception) {
            Log.e("CallMonitorService", "Error unregistering listener", e)
        }
    }

    private fun handleCallState(state: Int, phoneNumber: String?) {
        Log.d("CallMonitorService", "Call state changed: $state, number: $phoneNumber")
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val name = resolveCallerName(phoneNumber)
                Log.d("CallMonitorService", "Ringing caller: $name")
                val intent = Intent(ACTION_INCOMING_CALL).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_CALLER_NAME, name)
                }
                sendBroadcast(intent)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.d("CallMonitorService", "Phone returned to idle state")
                val intent = Intent(ACTION_CALL_ENDED).apply {
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }
    }

    private fun resolveCallerName(phoneNumber: String?): String {
        if (phoneNumber.isNullOrEmpty()) return "Unknown Caller"
        var resolvedName = "Unknown Caller"

        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )

            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            val resolver = contentResolver
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (columnIndex != -1) {
                        resolvedName = cursor.getString(columnIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CallMonitor", "Error resolving caller name", e)
        }

        if (resolvedName == "Unknown Caller" && phoneNumber.length >= 7) {
            resolvedName = "Number ending in ${phoneNumber.takeLast(4)}"
        }

        return resolvedName
    }
}
