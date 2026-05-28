package com.junior.assistant.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telecom.TelecomManager
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.BuildConfig
import com.example.R
import com.junior.assistant.ai.AudioEngine
import com.junior.assistant.model.CommandActionType
import com.junior.assistant.model.SenderType
import com.junior.assistant.service.AccessibilityHelperService
import com.junior.assistant.service.CallMonitorService
import com.junior.assistant.service.JuniorOverlayService
import com.junior.assistant.service.NotificationHelperService
import com.junior.assistant.service.PowerConnectionReceiver
import com.junior.assistant.ui.settings.SettingsActivity
import com.junior.assistant.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    lateinit var viewModel: MainViewModel

    private lateinit var rootLayout: View
    private lateinit var orbView: OrbAnimationView
    private lateinit var waveformView: WaveformView
    private lateinit var chatRecycler: RecyclerView
    private lateinit var btnSettings: ImageView
    private lateinit var btnMic: ImageButton
    private lateinit var btnKeyboard: ImageView
    private lateinit var redGlowOverlay: View

    private lateinit var tickerBattery: TextView
    private lateinit var tickerRam: TextView
    private lateinit var tickerClock: TextView

    private lateinit var adapter: ChatAdapter
    private var messagesList = mutableListOf<com.junior.assistant.model.ChatMessage>()

    private var audioEngine: AudioEngine? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private val tickerHandler = Handler(Looper.getMainLooper())
    private var isInCallMode = false

    private val incomingCallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val name = intent.getStringExtra(CallMonitorService.EXTRA_CALLER_NAME) ?: "Unknown Caller"
            handleIncomingCallCallout(name)
        }
    }

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val sender = intent.getStringExtra(NotificationHelperService.EXTRA_SENDER) ?: "Someone"
            val msgBody = intent.getStringExtra(NotificationHelperService.EXTRA_MESSAGE) ?: ""
            handleWhatsappAlertFlow(sender, msgBody)
        }
    }

    private val powerConnectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val statusMsg = intent.getStringExtra(PowerConnectionReceiver.EXTRA_STATUS_MESSAGE) ?: ""
            if (statusMsg.isNotEmpty()) {
                viewModel.sendTextDirectly(statusMsg)
            }
        }
    }

    private val wakeTriggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == com.junior.assistant.service.JuniorWakeWordService.ACTION_TRIGGER_WAKE) {
                Log.d("MainActivity", "Hands-free wake trigger received!")
                runOnUiThread {
                    viewModel.triggerInterrupt()
                    audioEngine?.flushPlayback()
                    // FIX: Wake trigger pe client restart karne ki zaroorat nahi — woh already connected hai
                    Toast.makeText(this@MainActivity, "Junior Activated Hands-Free!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // FIX: Permission result mein startClient() call kiya — ab AudioEngine ready hone ke BAAD connect hoga
    private val multiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (recordGranted) {
            startAudioEngine()
            startWakeWordService()
            // FIX: startClient yahan call hota hai — AudioEngine null nahi hoga ab
            viewModel.startClient()
        } else {
            Toast.makeText(this, "Microphone permission is required, Sir.", Toast.LENGTH_LONG).show()
            // FIX: Permission nahi mili toh bhi text-only mode ke liye client start karo
            viewModel.startClient()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        val sharedPrefs = getSharedPreferences("JuniorPrefs", MODE_PRIVATE)
        val customKey = sharedPrefs.getString("custom_api_key", "")
        val activeApiKey = if (!customKey.isNullOrEmpty()) customKey else BuildConfig.GEMINI_API_KEY

        viewModel.initClient(activeApiKey)

        rootLayout = findViewById(R.id.main_root_layout)
        orbView = findViewById(R.id.orb_animation_view)
        waveformView = findViewById(R.id.waveform_view)
        chatRecycler = findViewById(R.id.chat_recycler)
        btnSettings = findViewById(R.id.btn_settings)
        btnMic = findViewById(R.id.btn_mic)
        btnKeyboard = findViewById(R.id.btn_text_keyboard)
        redGlowOverlay = findViewById(R.id.red_glow_overlay)

        tickerBattery = findViewById(R.id.ticker_battery)
        tickerRam = findViewById(R.id.ticker_ram)
        tickerClock = findViewById(R.id.ticker_clock)

        chatRecycler.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }

        adapter = ChatAdapter(messagesList)
        chatRecycler.adapter = adapter

        val density = resources.displayMetrics.density
        val controlsBar = findViewById<LinearLayout>(R.id.controls_bar)
        val r = 28f * density
        val topRoundedBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(0xFF0A0A0A.toInt())
            setStroke((1 * density).toInt(), 0x15FFFFFF.toInt())
        }
        controlsBar.background = topRoundedBg

        val sideBtnBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFF18181B.toInt())
            setStroke((1.5f * density).toInt(), 0x22FFFFFF.toInt())
        }
        val p = (12 * density).toInt()
        btnSettings.apply {
            background = sideBtnBg
            setPadding(p, p, p, p)
        }
        btnKeyboard.apply {
            background = sideBtnBg
            setPadding(p, p, p, p)
        }

        val micBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFFFF1744.toInt())
            setStroke((2.5f * density).toInt(), 0xFFFFA2B5.toInt())
        }
        val micPadding = (18 * density).toInt()
        btnMic.apply {
            background = micBg
            setPadding(micPadding, micPadding, micPadding, micPadding)
        }

        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        btnMic.setOnClickListener {
            viewModel.triggerInterrupt()
            audioEngine?.flushPlayback()
        }

        btnMic.setOnLongClickListener {
            viewModel.triggerInterrupt()
            audioEngine?.flushPlayback()
            viewModel.addMessage("[Interrupted playback manually]", SenderType.USER)
            true
        }

        btnKeyboard.setOnClickListener {
            showKeyboardInputDialog()
        }

        startDiagnosticsTickers()
        checkOverlayAndAccessibility()

        val filterCall = IntentFilter(CallMonitorService.ACTION_INCOMING_CALL)
        androidx.core.content.ContextCompat.registerReceiver(
            this, incomingCallReceiver, filterCall,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val filterNotif = IntentFilter(NotificationHelperService.ACTION_WHATSAPP_NOTIFICATION)
        androidx.core.content.ContextCompat.registerReceiver(
            this, notificationReceiver, filterNotif,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val filterPower = IntentFilter(PowerConnectionReceiver.ACTION_POWER_STATUS_CHANGED)
        androidx.core.content.ContextCompat.registerReceiver(
            this, powerConnectionReceiver, filterPower,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val filterWake = IntentFilter(com.junior.assistant.service.JuniorWakeWordService.ACTION_TRIGGER_WAKE)
        androidx.core.content.ContextCompat.registerReceiver(
            this, wakeTriggerReceiver, filterWake,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        requestBatteryOptimizationBypass()
        checkAndStartWakeWordService()

        com.junior.assistant.utils.HardwareFeatureManager.registerActivity(this)

        lifecycleScope.launch {
            viewModel.messages.collectLatest { list ->
                messagesList.clear()
                messagesList.addAll(list)
                adapter.notifyDataSetChanged()
                if (messagesList.isNotEmpty()) {
                    chatRecycler.smoothScrollToPosition(messagesList.size - 1)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.orbState.collectLatest { state ->
                orbView.setState(state)
                if (state == OrbState.LISTENING) {
                    redGlowOverlay.visibility = View.VISIBLE
                    redGlowOverlay.animate().alpha(0.08f).setDuration(200).start()
                } else {
                    redGlowOverlay.animate().alpha(0.0f).setDuration(200).withEndAction {
                        redGlowOverlay.visibility = View.GONE
                    }.start()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.rmsValue.collectLatest { rms ->
                orbView.setRms(rms)
                waveformView.setAmplitude(rms)
            }
        }

        lifecycleScope.launch {
            viewModel.commandTriggerFlow.collectLatest { actionResult ->
                if (actionResult != null) {
                    handleCustomLayoutCommand(actionResult.actionType, actionResult.targetData)
                    viewModel.clearCommandTrigger()
                }
            }
        }

        viewModel.setPlaybackCallback { audioBytes ->
            audioEngine?.queuePlaybackAudio(audioBytes)
        }

        viewModel.setTurnCompleteCallback {
            audioEngine?.startPlayback()
        }

        // FIX: startClient() yahan se HATA diya — ab permission callback ke andar call hota hai
        // Pehle yahan call hota tha jab audioEngine abhi null tha
        requestAppPermissions()
    }

    override fun onDestroy() {
        tickerHandler.removeCallbacksAndMessages(null)
        sendConversationStateBroadcast(false)
        try {
            unregisterReceiver(incomingCallReceiver)
            unregisterReceiver(notificationReceiver)
            unregisterReceiver(powerConnectionReceiver)
            unregisterReceiver(wakeTriggerReceiver)
        } catch (e: Exception) {
            // ignore
        }
        audioEngine?.stopRecording()
        audioEngine?.stopPlayback()
        viewModel.stopClient()
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.CAMERA,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }

        // FIX: Pehle check karo — agar already granted hai toh launcher call mat karo
        val recordAlreadyGranted = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (recordAlreadyGranted) {
            // Already granted — seedha audio aur client start karo
            startAudioEngine()
            startWakeWordService()
            viewModel.startClient()
        } else {
            multiplePermissionsLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startAudioEngine() {
        sendConversationStateBroadcast(true)
        audioEngine = AudioEngine(
            onMicChunkRecorded = { data ->
                if (!isInCallMode) {
                    viewModel.inputAudioChunk(data)
                }
            },
            onRmsUpdated = { rms ->
                viewModel.updateRms(rms)
            },
            onWakeWordDetected = {
                Log.d("MainActivity", "Junior matched Wake Peak silently.")
            }
        ).apply {
            startRecording()
            startPlayback()
        }
    }

    private fun sendConversationStateBroadcast(active: Boolean) {
        val stateIntent = Intent(com.junior.assistant.service.JuniorWakeWordService.ACTION_CONVERSATION_STATE).apply {
            putExtra(com.junior.assistant.service.JuniorWakeWordService.EXTRA_COVERSATION_ACTIVE, active)
            setPackage(packageName)
        }
        sendBroadcast(stateIntent)
        com.junior.assistant.service.JuniorWakeWordService.isConversationActive = active
    }

    private fun requestBatteryOptimizationBypass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to present battery optimizations settings bypass request", e)
                }
            }
        }
    }

    private fun checkAndStartWakeWordService() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startWakeWordService()
        } else {
            Log.d("MainActivity", "Delaying wake-word service startup until RECORD_AUDIO is granted.")
        }
    }

    private fun startWakeWordService() {
        val wakeWordIntent = Intent(this, com.junior.assistant.service.JuniorWakeWordService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(wakeWordIntent)
            } else {
                startService(wakeWordIntent)
            }
            Log.d("MainActivity", "Junior persistent hands-free service started successfully.")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting persistent hands-free wake-word listener service", e)
        }
    }

    private fun checkOverlayAndAccessibility() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
        if (!AccessibilityHelperService.isServiceRunning()) {
            Toast.makeText(this, "Please enable Junior Accessibility, Sir.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    private fun showKeyboardInputDialog() {
        val input = EditText(this).apply {
            hint = "Instruct Junior..."
            setBackgroundColor(0xFF222222.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(32, 32, 32, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Chat with Junior")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val txt = input.text.toString().trim()
                if (txt.isNotEmpty()) {
                    viewModel.sendTextDirectly(txt)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startDiagnosticsTickers() {
        val runnable = object : Runnable {
            override fun run() {
                val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
                val batLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                tickerBattery.text = "BAT: $batLevel%"

                val mi = ActivityManager.MemoryInfo()
                val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                activityManager.getMemoryInfo(mi)
                val availableMegs = mi.availMem / 1048576L
                tickerRam.text = "RAM: $availableMegs MB"

                val clockStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                tickerClock.text = clockStr

                tickerHandler.postDelayed(this, 1000)
            }
        }
        tickerHandler.post(runnable)
    }

    private fun handleIncomingCallCallout(callerName: String) {
        isInCallMode = true
        audioEngine?.setMuted(true)

        viewModel.addMessage("Incoming call from: $callerName", SenderType.JUNIOR)
        viewModel.sendTextDirectly("Sir, $callerName ka call aa raha hai. Uthau ya reject karu?")

        lifecycleScope.launch {
            kotlinx.coroutines.delay(4500)
            triggerLocallSpeechRecognizerSTT { userSpeech ->
                val speechLower = userSpeech.lowercase()
                if (speechLower.contains("uthao") || speechLower.contains("haan") || speechLower.contains("accept")) {
                    acceptIncomingCall()
                } else if (speechLower.contains("reject") || speechLower.contains("nahi") || speechLower.contains("mat")) {
                    rejectIncomingCall()
                } else {
                    viewModel.addMessage("Call standing by.", SenderType.JUNIOR)
                }
                isInCallMode = false
                audioEngine?.setMuted(false)
            }
        }
    }

    private fun handleWhatsappAlertFlow(sender: String, messageBody: String) {
        isInCallMode = true
        audioEngine?.setMuted(true)

        viewModel.addMessage("WhatsApp: $sender -> $messageBody", SenderType.JUNIOR)
        viewModel.sendTextDirectly("Sir, $sender ne WhatsApp par pucha hai: $messageBody. Reply karna hai?")

        lifecycleScope.launch {
            kotlinx.coroutines.delay(4500)
            triggerLocallSpeechRecognizerSTT { replyText ->
                if (replyText.isNotEmpty() && !replyText.equals("no", ignoreCase = true) && !replyText.contains("nahi")) {
                    viewModel.addMessage("Replying: $replyText", SenderType.USER)
                    val service = AccessibilityHelperService.instance
                    if (service != null) {
                        service.clickOnText("Type a message")
                        service.typeText(replyText)
                        service.clickOnText("Send")
                    } else {
                        val whatsappIntent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(replyText)}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(whatsappIntent)
                    }
                }
                isInCallMode = false
                audioEngine?.setMuted(false)
            }
        }
    }

    private fun acceptIncomingCall() {
        val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    telecomManager.acceptRingingCall()
                    viewModel.addMessage("Answering call, Sir.", SenderType.JUNIOR)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Telecom answer call failed", e)
        }
    }

    private fun rejectIncomingCall() {
        val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    telecomManager.endCall()
                    viewModel.addMessage("Ending call, Sir.", SenderType.JUNIOR)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Telecom end call failed", e)
        }
    }

    private fun triggerLocallSpeechRecognizerSTT(callback: (String) -> Unit) {
        runOnUiThread {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    callback("")
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val out = matches?.firstOrNull() ?: ""
                    callback(out)
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer?.startListening(intent)
        }
    }

    private fun handleCustomLayoutCommand(type: CommandActionType, target: String?) {
        val service = AccessibilityHelperService.instance
        when (type) {
            CommandActionType.OPEN_APP -> {
                if (target != null) {
                    val launchIntent = packageManager.getLaunchIntentForPackage(target)
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                    } else {
                        Toast.makeText(this, "Application package '$target' is missing, Sir.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            CommandActionType.GLOBAL_HOME -> {
                service?.goHome()
            }
            CommandActionType.SYSTEM_VOLUME_UP -> {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            }
            CommandActionType.SYSTEM_VOLUME_DOWN -> {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            }
            CommandActionType.TOGGLE_FLASHLIGHT -> {
                val value = target == "ON"
                val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
                try {
                    val id = cameraManager.cameraIdList[0]
                    cameraManager.setTorchMode(id, value)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed toggle torch", e)
                }
            }
            CommandActionType.CALL_CONTACT -> {
                if (target != null) {
                    val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$target"))
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                        startActivity(callIntent)
                    } else {
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$target"))
                        startActivity(dialIntent)
                    }
                }
            }
            CommandActionType.WHATSAPP_MESSAGE -> {
                if (target != null) {
                    val url = "https://api.whatsapp.com/send?phone=$target"
                    val i = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse(url)
                    }
                    startActivity(i)
                }
            }
            CommandActionType.SCROLL_DOWN -> {
                service?.scrollDown()
            }
            CommandActionType.SCROLL_UP -> {
                service?.scrollUp()
            }
            CommandActionType.LOOK_SCREEN -> {
                val text = service?.scrapeScreenText() ?: "Accessibility service disabled"
                viewModel.sendTextDirectly("Discuss what is active on my screen: $text")
            }
            CommandActionType.SOS_MODE -> {
                com.junior.assistant.utils.HardwareFeatureManager.triggerSOS(this)
            }
            CommandActionType.EXPENSE_LOG -> {
                if (target != null) {
                    val parts = target.split("|")
                    if (parts.size == 2) {
                        val amt = parts[0].toDoubleOrNull() ?: 0.0
                        val cat = parts[1]
                        com.junior.assistant.utils.HardwareFeatureManager.logVoiceExpense(this, amt, cat)
                    }
                }
            }
            CommandActionType.GEOFENCE_REMINDER -> {
                if (target != null) {
                    val parts = target.split("|")
                    if (parts.size == 2) {
                        val task = parts[0]
                        val loc = parts[1]
                        com.junior.assistant.utils.HardwareFeatureManager.registerVoiceGeofence(this, task, loc)
                    }
                }
            }
            CommandActionType.ANTI_THEFT_ARM -> {
                com.junior.assistant.utils.HardwareFeatureManager.armAntiTheftMode(this)
            }
            CommandActionType.ANTI_THEFT_DISARM -> {
                com.junior.assistant.utils.HardwareFeatureManager.disarmAntiTheft(this)
            }
            CommandActionType.CAMERA_CLICK -> {
                val delay = target?.toIntOrNull() ?: 0
                com.junior.assistant.utils.HardwareFeatureManager.captureVoicePhoto(this, delay)
            }
            else -> {}
        }
    }
}
