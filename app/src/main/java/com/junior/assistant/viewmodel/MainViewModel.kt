package com.junior.assistant.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.junior.assistant.ai.CommandParser
import com.junior.assistant.ai.GeminiLiveClient
import com.junior.assistant.ui.main.OrbState
import com.junior.assistant.data.MemoryDatabase
import com.junior.assistant.data.MemoryEntity
import com.junior.assistant.model.ChatMessage
import com.junior.assistant.model.SenderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = MemoryDatabase.getDatabase(application)
    private val dao = db.memoryDao()
    private val sharedPrefs = application.getSharedPreferences("JuniorPrefs", Context.MODE_PRIVATE)
    private val commandParser = CommandParser(application)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _orbState = MutableStateFlow(OrbState.IDLE)
    val orbState: StateFlow<OrbState> = _orbState.asStateFlow()

    private val _rmsValue = MutableStateFlow(0f)
    val rmsValue: StateFlow<Float> = _rmsValue.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var liveClient: GeminiLiveClient? = null
    var textInputBuffer = StringBuilder()

    private val _commandTriggerFlow = MutableStateFlow<CommandParser.CommandResult?>(null)
    val commandTriggerFlow = _commandTriggerFlow.asStateFlow()

    fun initClient(apiKey: String) {
        liveClient = GeminiLiveClient(
            apiKey = apiKey,
            voiceSettingsProvider = { getVoiceChoice() },
            systemPromptProvider = { buildSysPrompt() },
            listener = object : GeminiLiveClient.LiveClientListener {
                override fun onConnected() {
                    _isConnected.value = true
                    _orbState.value = OrbState.IDLE
                    addMessage("Junior online. Main sun raha hoon.", SenderType.JUNIOR)
                }

                override fun onDisconnected() {
                    _isConnected.value = false
                    _orbState.value = OrbState.IDLE
                }

                override fun onAudioChunkReceived(audioData: ByteArray) {
                    _orbState.value = OrbState.SPEAKING
                    playbackAudioCallback?.invoke(audioData)
                }

                override fun onOutputTranscription(text: String) {
                    textInputBuffer.append(text)
                    _orbState.value = OrbState.SPEAKING
                }

                override fun onInputTranscription(text: String) {
                    addMessage(text, SenderType.USER)
                }

                override fun onTurnComplete() {
                    val fullWords = textInputBuffer.toString().trim()
                    if (fullWords.isNotEmpty()) {
                        addMessage(fullWords, SenderType.JUNIOR)
                        textInputBuffer.clear()
                        checkExternalCommands(fullWords)
                    }
                    _orbState.value = OrbState.IDLE
                    turnCompleteCallback?.invoke()
                }

                override fun onError(error: String) {
                    _orbState.value = OrbState.IDLE
                    Log.e("MainViewModel", "GeminiLiveClient error: $error")
                }
            }
        )
    }

    private var playbackAudioCallback: ((ByteArray) -> Unit)? = null
    private var turnCompleteCallback: (() -> Unit)? = null

    fun setPlaybackCallback(callback: (ByteArray) -> Unit) {
        playbackAudioCallback = callback
    }

    fun setTurnCompleteCallback(callback: () -> Unit) {
        turnCompleteCallback = callback
    }

    fun startClient() {
        liveClient?.start()
    }

    fun stopClient() {
        liveClient?.stop()
    }

    fun inputAudioChunk(data: ByteArray) {
        liveClient?.sendAudioChunk(data)
    }

    fun triggerInterrupt() {
        liveClient?.sendInterruptSignal()
        _orbState.value = OrbState.IDLE
        textInputBuffer.clear()
    }

    fun sendTextDirectly(text: String) {
        addMessage(text, SenderType.USER)
        liveClient?.sendTextMessage(text)
        _orbState.value = OrbState.THINKING
    }

    fun addMessage(text: String, sender: SenderType) {
        val message = ChatMessage(UUID.randomUUID().toString(), text, sender)
        _messages.value = _messages.value + message
    }

    fun updateRms(rms: Float) {
        _rmsValue.value = rms
    }

    fun setOrbState(state: OrbState) {
        _orbState.value = state
    }

    private fun getVoiceChoice(): String {
        return sharedPrefs.getString("voice_preset", "Charon") ?: "Charon"
    }

    private fun getPersonaMode(): String {
        return sharedPrefs.getString("persona_mode", "Companion") ?: "Companion"
    }

    private fun getUserName(): String {
        return sharedPrefs.getString("user_custom_name", "Sir") ?: "Sir"
    }

    // FIX: Spin-wait hata diya — ab properly coroutine ke andar async load hota hai
    private fun buildSysPrompt(): String {
        val userName = getUserName()
        val mode = getPersonaMode()
        val formattedDate = SimpleDateFormat("dd MMMM yyyy, hh:mm a", Locale.getDefault()).format(Date())

        val modeBlock = when (mode) {
            "Professional" -> """
                Personality: You are Junior, a formal, professional assistant. 
                Keep answers strictly in clear English. Under 2 sentences maximum. No slang, absolutely no emojis.
            """.trimIndent()
            "Assistant" -> """
                Personality: You are Junior, a helpful personal companion assistant.
                You can speak English or casual Hinglish (Hindi/Urdu + English). Response length must match 2-3 sentences max.
            """.trimIndent()
            else -> """
                Personality: You are Junior, the user's best male companion and boyfriend. 
                Speak in Hinglish (Hindi/Urdu + English mixed) with an extremely warm, protective, and emotionally expressively caring tone.
                Use expressive phrases like "haan", "acha", "tumne yaad kiya? 😊", "main yahan hoon yaar ❤️", "tumhara", "bilkul".
                Keep spoken sentences extremely short, under 2-3 spoken sentences. Speak like you are talking aloud in a real phone call.
            """.trimIndent()
        }

        return """
            $modeBlock
            Context: The current localized time and date is $formattedDate.
            User Profile: You are in a secure session addressing your client as $userName.
            Action: Deliver smooth realistic male generated speech sentences based strictly on the specified persona parameters. Do not speak using markdown rules.
        """.trimIndent()
    }

    // FIX: Memories async load hoti hain — system prompt mein inject karne ke liye separate suspend function
    suspend fun loadMemoriesForPrompt(): String = withContext(Dispatchers.IO) {
        return@withContext try {
            val list = dao.getUnresolvedMemories()
            if (list.isNotEmpty()) {
                list.joinToString("\n") {
                    "- Recalled memory: ${it.text} (logged on ${
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it.timestamp))
                    })"
                }
            } else {
                "No recent memories recorded."
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to load memories", e)
            "No recent memories recorded."
        }
    }

    fun recordMemory(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertMemory(MemoryEntity(text = text))
        }
        addMessage("Logged to local memory DB: $text", SenderType.JUNIOR)
    }

    fun checkExternalCommands(text: String) {
        val result = commandParser.parse(text)
        if (result.isHandled) {
            _commandTriggerFlow.value = result
        } else {
            val sanitized = text.lowercase()
            if (sanitized.contains("remember that") ||
                sanitized.contains("yaad rakhna ki") ||
                sanitized.contains("yaad rakho")
            ) {
                val index = when {
                    sanitized.indexOf("remember that") != -1 ->
                        sanitized.indexOf("remember that") + "remember that".length
                    sanitized.indexOf("yaad rakhna ki") != -1 ->
                        sanitized.indexOf("yaad rakhna ki") + "yaad rakhna ki".length
                    else ->
                        sanitized.indexOf("yaad rakho") + "yaad rakho".length
                }
                val memoryText = text.substring(index).trim()
                if (memoryText.isNotEmpty()) {
                    recordMemory(memoryText)
                }
            }
        }
    }

    fun clearCommandTrigger() {
        _commandTriggerFlow.value = null
    }
}
