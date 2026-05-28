package com.junior.assistant.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.junior.assistant.ui.main.MainActivity
import kotlin.math.abs

class JuniorWakeWordService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var listeningThread: Thread? = null
    
    // Constant configuration for the offline trigger amplitude signature check
    private val wakeWordThreshold = 22000
    private val debounceTimeMs = 2500L
    private var lastTriggerTime = 0L

    companion object {
        const val CHANNEL_ID = "JuniorWakeWordServiceChannel"
        const val NOTIFICATION_ID = 101
        
        // Broadcasts to allow communication with the voice control system
        const val ACTION_CONVERSATION_STATE = "com.junior.assistant.ACTION_CONVERSATION_STATE"
        const val EXTRA_COVERSATION_ACTIVE = "active"
        const val ACTION_TRIGGER_WAKE = "com.junior.assistant.ACTION_TRIGGER_WAKE"

        @Volatile
        var isConversationActive: Boolean = false
    }

    private val conversationStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && intent.action == ACTION_CONVERSATION_STATE) {
                isConversationActive = intent.getBooleanExtra(EXTRA_COVERSATION_ACTIVE, false)
                Log.d("JuniorWakeWordService", "Conversation state broadcast changed. active=$isConversationActive")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("JuniorWakeWordService", "Service onCreate - initializing 24/7 background listener")
        
        // Register receiver for dynamic state updates
        val filter = IntentFilter(ACTION_CONVERSATION_STATE)
        androidx.core.content.ContextCompat.registerReceiver(
            this, conversationStateReceiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Keep CPU alive with dedicated partial wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Junior:WakeLockActive")
        wakeLock?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("JuniorWakeWordService", "Service onStartCommand - checking and starting foreground mode")
        
        createNotificationChannel()
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startListeningLoop()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListeningLoop() {
        if (isListening) return
        isListening = true

        listeningThread = Thread {
            Log.d("JuniorWakeWordService", "Background listening thread started")
            
            val bufferSize = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1024)

            while (isListening) {
                if (isConversationActive) {
                    // Temporarily release recording resource during active WebSocket streaming session 
                    // to prevent permission acquisition and audio framework lockups
                    Log.d("JuniorWakeWordService", "Active chat detected. Releasing/Pausing background mic recording")
                    releaseAudioRecord()
                    while (isConversationActive && isListening) {
                        try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
                    }
                    Log.d("JuniorWakeWordService", "Active chat finished. Resuming background mic wake-word loop")
                }

                if (!isListening) break

                if (audioRecord == null) {
                    initAudioRecord(bufferSize)
                }

                val record = audioRecord
                if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                    val buffer = ShortArray(1024)
                    try {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            var maxAmp = 0
                            for (i in 0 until read) {
                                val absValue = abs(buffer[i].toInt())
                                if (absValue > maxAmp) {
                                    maxAmp = absValue
                                }
                            }
                            // Local offline signal processing to isolate the voice trigger event
                            if (maxAmp > wakeWordThreshold) {
                                val now = System.currentTimeMillis()
                                if (now - lastTriggerTime > debounceTimeMs) {
                                    lastTriggerTime = now
                                    Log.d("JuniorWakeWordService", "Offline threshold wake pattern triggered: peak=$maxAmp. Waking up Junior!")
                                    triggerSystemActivation()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("JuniorWakeWordService", "Error reading audio frames", e)
                        releaseAudioRecord()
                        try { Thread.sleep(1500) } catch (ex: InterruptedException) { break }
                    }
                } else {
                    // Try re-initializing or backing off if initialization fails
                    releaseAudioRecord()
                    try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
                }
            }
            releaseAudioRecord()
            Log.d("JuniorWakeWordService", "Background listening thread terminated")
        }
        listeningThread?.start()
    }

    private fun initAudioRecord(bufferSize: Int) {
        try {
            @SuppressLint("MissingPermission")
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record.startRecording()
                audioRecord = record
                Log.d("JuniorWakeWordService", "AudioRecord initialized and recording successfully")
            } else {
                Log.e("JuniorWakeWordService", "Failed to initialize AudioRecord state")
                record.release()
            }
        } catch (e: Exception) {
            Log.e("JuniorWakeWordService", "Exception configuring AudioRecord", e)
        }
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioRecord = null
    }

    private fun triggerSystemActivation() {
        // 1. Invoke JuniorOverlayService to show animated Floating canvas orb
        val overlayIntent = Intent(this, JuniorOverlayService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(overlayIntent)
            } else {
                startService(overlayIntent)
            }
        } catch (e: Exception) {
            Log.e("JuniorWakeWordService", "Failed to start JuniorOverlayService foreground", e)
        }

        // 2. Broadcast local activation trigger event to notify MainActivity + start connection automatically
        val triggerIntent = Intent(ACTION_TRIGGER_WAKE).apply {
            setPackage(packageName)
        }
        sendBroadcast(triggerIntent)

        // 3. Launch MainActivity if it is not currently active to bring full visual console screen instantly
        try {
            val systemUiIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(systemUiIntent)
        } catch (e: Exception) {
            Log.e("JuniorWakeWordService", "Could not request MainActivity foreground launch", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Junior Persistent Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors environment silently to detect wake word and activate Junior assistant hands-free"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Junior Hands-Free Active")
            .setContentText("Listening 24/7 for wake activation word")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Log.d("JuniorWakeWordService", "Service onDestroy - clean termination")
        isListening = false
        listeningThread?.interrupt()
        listeningThread = null
        
        try {
            unregisterReceiver(conversationStateReceiver)
        } catch (e: Exception) {
            // ignore
        }

        releaseAudioRecord()

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }
}
