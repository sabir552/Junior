package com.junior.assistant.utils

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.junior.assistant.data.ExpenseEntity
import com.junior.assistant.data.MemoryDatabase
import com.junior.assistant.service.GeofenceBroadcastReceiver
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.*

object HardwareFeatureManager {

    private const val TAG = "HardwareFeatureManager"
    private var activeActivityRef: WeakReference<Activity>? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false

    // Anti-theft Variables
    private var isAntiTheftArmed = false
    private var sensorManager: SensorManager? = null
    private var gyroListener: SensorEventListener? = null
    private var proxListener: SensorEventListener? = null
    private var antiTheftJob: Job? = null

    fun registerActivity(activity: Activity) {
        activeActivityRef = WeakReference(activity)
        initTtsIfNeeded(activity.applicationContext)
    }

    fun getActiveActivity(): Activity? = activeActivityRef?.get()

    private fun initTtsIfNeeded(context: Context) {
        if (textToSpeech == null) {
            textToSpeech = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech?.language = Locale("hi", "IN") // Casual Indian localization
                    isTtsInitialized = true
                }
            }
        }
    }

    fun speak(text: String) {
        if (isTtsInitialized) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JuniorSpeak")
        }
    }

    // 1. EMERGENCY PANIC WORD & SOS MODE
    fun triggerSOS(context: Context) {
        Log.d(TAG, "Triggering SOS Backup System...")
        Toast.makeText(context, "🚨 Junior: Sending SOS alert!", Toast.LENGTH_LONG).show()
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    val lat = location?.latitude ?: 19.0760
                    val lng = location?.longitude ?: 72.8777
                    sendSilentSms(context, lat, lng)
                }.addOnFailureListener {
                    sendSilentSms(context, 19.0760, 72.8777)
                }
            } else {
                sendSilentSms(context, 19.0760, 72.8777)
            }
        } catch (e: Exception) {
            sendSilentSms(context, 19.0760, 72.8777)
        }
    }

    private fun sendSilentSms(context: Context, lat: Double, lng: Double) {
        val message = "🚨 EMERGENCY SOS! Aadil is in critical danger. Live Location coordinates: https://www.google.com/maps/search/?api=1&query=$lat,$lng"
        val primeContacts = getPrimeContactsDirectly(context)
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        if (primeContacts.isEmpty()) {
            Log.e(TAG, "No Prime Contacts configured to send SOS text.")
            return
        }

        for (contact in primeContacts) {
            try {
                smsManager.sendTextMessage(contact.second, null, message, null, null)
                Log.d(TAG, "Silently sent SMS to ${contact.first} at ${contact.second}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending SMS to ${contact.second}", e)
            }
        }
    }

    private fun getPrimeContactsDirectly(context: Context): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val sharedPrefs = context.getSharedPreferences("JuniorPrefs", Context.MODE_PRIVATE)
        val jsonStr = sharedPrefs.getString("prime_contacts_json", null)
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.optString("name", "")
                    val phone = obj.optString("phone", "")
                    if (name.isNotEmpty() && phone.isNotEmpty()) {
                        result.add(Pair(name, phone))
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        if (result.isEmpty()) {
            val legacyName = sharedPrefs.getString("prime_name", null)
            val legacyNum = sharedPrefs.getString("prime_number", null)
            if (!legacyName.isNullOrEmpty() && !legacyNum.isNullOrEmpty()) {
                result.add(Pair(legacyName, legacyNum))
            }
        }
        return result
    }

    // 2. SMART EXPENSE VOICE LOGGER
    fun logVoiceExpense(context: Context, amount: Double, category: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = MemoryDatabase.getDatabase(context)
            db.expenseDao().insertExpense(
                ExpenseEntity(amount = amount, category = category)
            )
            withContext(Dispatchers.Main) {
                val txt = "Log kar liya: ${amount.toInt()} rupey ka $category save ho gaya hai database mein, Sir!"
                speak(txt)
                Toast.makeText(context, txt, Toast.LENGTH_SHORT).show()
                // Force a message insert into Chat ui
                getActiveMainViewModel(context)?.addMessage("Log registered: $category ($amount Rs)", com.junior.assistant.model.SenderType.JUNIOR)
            }
        }
    }

    // 3. GEO-FENCING TRIGGERS
    fun registerVoiceGeofence(context: Context, task: String, locationKey: String) {
        Log.d(TAG, "Registering voice geofence for '$locationKey', task: '$task'")
        val geofencingClient = LocationServices.getGeofencingClient(context)
        
        val (lat, lng) = when (locationKey.lowercase().trim()) {
            "office", "work", "office office" -> Pair(19.1234, 72.8567)
            "home", "ghar", "ghar par" -> Pair(19.0760, 72.8777)
            else -> Pair(19.0800, 72.8900) // general area
        }

        val sharedPrefs = context.getSharedPreferences("JuniorPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("geofence_task_$locationKey", task).apply()

        // Create standard geofence
        val geofence = Geofence.Builder()
            .setRequestId(locationKey)
            .setCircularRegion(lat, lng, 120f)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_DWELL)
            .setLoiteringDelay(5000)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            putExtra("location_key", locationKey)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, 1001, intent, flags)

        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                geofencingClient.addGeofences(request, pendingIntent).addOnSuccessListener {
                    val res = "Ho gaya remind! Jab aap $locationKey pahunchenge toh main bol dunga: '$task'"
                    speak(res)
                    Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
                }.addOnFailureListener {
                    Log.e(TAG, "Failed adding geofences", it)
                }
            } else {
                speak("Location permission access required to set geofence limit, Sir.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Geofencing registration crash", e)
        }
    }

    fun handleGeofenceFired(context: Context, locationKey: String) {
        val sharedPrefs = context.getSharedPreferences("JuniorPrefs", Context.MODE_PRIVATE)
        val task = sharedPrefs.getString("geofence_task_$locationKey", "Work task") ?: "task"
        
        Log.d(TAG, "GEOFENCE FIRED: Arrived at '$locationKey' with task '$task'")
        
        // Trigger alerts
        GlobalScope.launch(Dispatchers.Main) {
            // Display alert Toast
            Toast.makeText(context, "📍 ARRIVED ALERT: $task at $locationKey!", Toast.LENGTH_LONG).show()
            
            // Highlight of UI or red flash screen triggering can be done on the active activity
            val activity = getActiveActivity()
            if (activity != null) {
                // If the app is active, draw alert or flash red screen or play reminder
                Toast.makeText(activity, "ALERT: $task", Toast.LENGTH_LONG).show()
            }
            
            // Max volume and play loop vocalizing the reminder in high volume caring tone
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI)
            
            speak("Aadil, aap $locationKey pahunch gaye hain! Suniye, aapko $task bolna tha! please yaad rakhiyega!")
        }
    }

    // 4. ANTI-THEFT AUDIO TRAP
    fun armAntiTheftMode(context: Context) {
        if (isAntiTheftArmed) {
            speak("Anti-Theft System pehle se hi active hai, Sir!")
            return
        }
        isAntiTheftArmed = true
        initTtsIfNeeded(context)
        speak("Anti-theft system armed! Agar ab kisi ne phone ko touch kiya toh main shor macha dunga!")
        Toast.makeText(context, "🛡️ Anti-Theft Arm Checked", Toast.LENGTH_SHORT).show()

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        
        val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyro != null) {
            gyroListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    if (event != null && isAntiTheftArmed) {
                        val limit = kotlin.math.abs(event.values[0]) + kotlin.math.abs(event.values[1]) + kotlin.math.abs(event.values[2])
                        if (limit > 1.5f) {
                            triggerTheftAlarm(context)
                        }
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager?.registerListener(gyroListener, gyro, SensorManager.SENSOR_DELAY_NORMAL)
        }

        val prox = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (prox != null) {
            proxListener = object : SensorEventListener {
                private var baseVal = -1f
                override fun onSensorChanged(event: SensorEvent?) {
                    if (event != null && isAntiTheftArmed) {
                        val current = event.values[0]
                        if (baseVal == -1f) {
                            baseVal = current
                        } else if (current != baseVal) {
                            triggerTheftAlarm(context)
                        }
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager?.registerListener(proxListener, prox, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun disarmAntiTheft(context: Context) {
        if (!isAntiTheftArmed) {
            speak("System already disarmed hai, Sir.")
            return
        }
        isAntiTheftArmed = false
        antiTheftJob?.cancel()
        sensorManager?.let { sm ->
            gyroListener?.let { sm.unregisterListener(it) }
            proxListener?.let { sm.unregisterListener(it) }
        }
        speak("Anti-Theft system disarmed successfully. Safe mood, Sir.")
        Toast.makeText(context, "🛡️ Disarmed", Toast.LENGTH_SHORT).show()
    }

    private fun triggerTheftAlarm(context: Context) {
        // Disarm to prevent repeated loops on same trigger
        isAntiTheftArmed = false
        sensorManager?.let { sm ->
            gyroListener?.let { sm.unregisterListener(it) }
            proxListener?.let { sm.unregisterListener(it) }
        }

        antiTheftJob = CoroutineScope(Dispatchers.Main).launch {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // Loop Vocal alerts in high volume
            for (i in 1..4) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI)
                speak("Aapka phone koi touch kar raha hai! Chor haath hatao!")
                delay(3000)
            }
        }

        // Snapshot background photo
        capturePictureBackground(context)
    }

    // 5. HANDS-FREE VOICE CAMERA CONTROLS
    fun captureVoicePhoto(context: Context, countdownSec: Int = 0) {
        if (countdownSec > 0) {
            val alert = "$countdownSec seconds mein photo khinchne wali hai!"
            speak(alert)
            Toast.makeText(context, alert, Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.Main).launch {
                delay(countdownSec * 1000L)
                capturePictureBackground(context)
            }
        } else {
            speak("Abhi photo capture kar raha hoon!")
            capturePictureBackground(context)
        }
    }

    private fun capturePictureBackground(context: Context) {
        val appActivity = getActiveActivity()
        if (appActivity == null) {
            Log.e(TAG, "No active activity context found to tie CameraX lifecycle.")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA // Front Snap for security/anti-theft

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    appActivity as LifecycleOwner,
                    cameraSelector,
                    imageCapture
                )

                val filename = "Junior_Capture_${System.currentTimeMillis()}.jpg"
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/JuniorAssistant")
                    }
                }
                val outputOptions = ImageCapture.OutputFileOptions.Builder(
                    context.contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ).build()

                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            val savedUri = outputFileResults.savedUri
                            val txt = "Image frame captured successfully and written to gallery!"
                            Log.d(TAG, "$txt : $savedUri")
                            Toast.makeText(context, "📸 Captured: $filename", Toast.LENGTH_LONG).show()
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "CameraX snapshot capture failed", exception)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error initiating background capture", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun getActiveMainViewModel(context: Context): com.junior.assistant.viewmodel.MainViewModel? {
        val activity = getActiveActivity() as? com.junior.assistant.ui.main.MainActivity ?: return null
        return activity.viewModel
    }
}
