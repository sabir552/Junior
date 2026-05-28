package com.junior.assistant.ai

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiLiveClient(
    private val apiKey: String,
    private val voiceSettingsProvider: () -> String,
    private val systemPromptProvider: () -> String,
    private val listener: LiveClientListener
) {
    interface LiveClientListener {
        fun onConnected()
        fun onDisconnected()
        fun onAudioChunkReceived(audioData: ByteArray)
        fun onOutputTranscription(text: String)
        fun onInputTranscription(text: String)
        fun onTurnComplete()
        fun onError(error: String)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isConnected = false
    private var isClosedManually = false

    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            sendKeepAlivePulse()
            handler.postDelayed(this, 8000)
        }
    }

    // FIX: Session renewal 9 minutes (540s) pe — Gemini Live API ki limit ke andar
    private val sessionRenewalRunnable = object : Runnable {
        override fun run() {
            Log.d("GeminiLiveClient", "Renewing session after 540 seconds...")
            reconnect()
        }
    }

    fun start() {
        isClosedManually = false
        connect()
    }

    fun stop() {
        isClosedManually = true
        disconnect()
    }

    private fun connect() {
        if (isConnected) return
        // FIX: Stable Gemini 2.0 Flash Live model use kiya — preview model deprecated ho sakta tha
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                Log.d("GeminiLiveClient", "WebSocket Connected")
                handler.post { listener.onConnected() }
                sendSetupConfig()
                startTimers()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseServerMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("GeminiLiveClient", "WebSocket Closing: $code / $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.d("GeminiLiveClient", "WebSocket Closed: $code")
                stopTimers()
                handler.post { listener.onDisconnected() }
                if (!isClosedManually) {
                    retryConnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Log.e("GeminiLiveClient", "WebSocket Failure: ${t.message}", t)
                stopTimers()
                handler.post { listener.onError(t.message ?: "Connection error") }
                if (!isClosedManually) {
                    retryConnect()
                }
            }
        })
    }

    private fun disconnect() {
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        isConnected = false
        stopTimers()
    }

    private fun reconnect() {
        disconnect()
        handler.postDelayed({
            if (!isClosedManually) connect()
        }, 3000)
    }

    private fun retryConnect() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!isClosedManually) connect()
        }, 3000)
    }

    private fun startTimers() {
        handler.removeCallbacks(keepAliveRunnable)
        handler.removeCallbacks(sessionRenewalRunnable)
        handler.postDelayed(keepAliveRunnable, 8000)
        handler.postDelayed(sessionRenewalRunnable, 540000)
    }

    private fun stopTimers() {
        handler.removeCallbacks(keepAliveRunnable)
        handler.removeCallbacks(sessionRenewalRunnable)
    }

    private fun sendSetupConfig() {
        try {
            val setupParams = JSONObject().apply {
                // FIX: Stable model use kiya — preview wala silently fail karta tha
                put("model", "models/gemini-2.0-flash-live-001")
                put("generation_config", JSONObject().apply {
                    put("response_modalities", JSONArray().put("AUDIO"))
                    put("speech_config", JSONObject().apply {
                        put("voice_config", JSONObject().apply {
                            put("prebuilt_voice_config", JSONObject().apply {
                                put("voice_name", voiceSettingsProvider())
                            })
                        })
                    })
                    put("temperature", 0.9)
                })
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", systemPromptProvider())
                    }))
                })
            }
            val envelope = JSONObject().apply {
                put("setup", setupParams)
            }
            webSocket?.send(envelope.toString())
            Log.d("GeminiLiveClient", "Setup Config Sent: model=gemini-2.0-flash-live-001, voice=${voiceSettingsProvider()}")
        } catch (e: Exception) {
            Log.e("GeminiLiveClient", "Failed to send setup config", e)
        }
    }

    fun sendAudioChunk(pcmData: ByteArray) {
        if (!isConnected) return
        try {
            val base64Data = Base64.encodeToString(pcmData, Base64.NO_WRAP)
            val chunk = JSONObject().apply {
                put("mime_type", "audio/pcm;rate=16000")
                put("data", base64Data)
            }
            val mediaChunks = JSONArray().put(chunk)
            val realtimeInput = JSONObject().apply {
                put("media_chunks", mediaChunks)
            }
            val envelope = JSONObject().apply {
                put("realtime_input", realtimeInput)
            }
            webSocket?.send(envelope.toString())
        } catch (e: Exception) {
            Log.e("GeminiLiveClient", "Failed to send audio chunk", e)
        }
    }

    fun sendTextMessage(text: String) {
        if (!isConnected) return
        try {
            val part = JSONObject().apply { put("text", text) }
            val turn = JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(part))
            }
            val clientContent = JSONObject().apply {
                put("turns", JSONArray().put(turn))
                put("turn_complete", true)
            }
            val envelope = JSONObject().apply {
                put("client_content", clientContent)
            }
            webSocket?.send(envelope.toString())
            Log.d("GeminiLiveClient", "Text message sent: $text")
        } catch (e: Exception) {
            Log.e("GeminiLiveClient", "Failed to send text message", e)
        }
    }

    // FIX: Proper interrupt signal — empty turns ki jagah activity_end use kiya
    fun sendInterruptSignal() {
        if (!isConnected) return
        try {
            // Proper way: realtime_input ke saath activity end signal
            val activityEnd = JSONObject().apply {
                put("activity_end", JSONObject())
            }
            val realtimeInput = JSONObject().apply {
                put("realtime_input", activityEnd)
            }
            webSocket?.send(realtimeInput.toString())
            Log.d("GeminiLiveClient", "Interrupt signal sent (activity_end)")
        } catch (e: Exception) {
            Log.e("GeminiLiveClient", "Failed to send interrupt signal", e)
        }
    }

    private fun sendKeepAlivePulse() {
        if (!isConnected) return
        val silentChunk = ByteArray(1024)
        sendAudioChunk(silentChunk)
    }

    private fun parseServerMessage(text: String) {
        try {
            val root = JSONObject(text)

            // FIX: setupComplete message handle kiya — confirm karta hai session ready hai
            if (root.has("setupComplete")) {
                Log.d("GeminiLiveClient", "Setup complete confirmed by server.")
                return
            }

            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    if (modelTurn.has("parts")) {
                        val parts = modelTurn.getJSONArray("parts")
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val base64Data = inlineData.optString("data")
                                if (!base64Data.isNullOrEmpty()) {
                                    val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    handler.post { listener.onAudioChunkReceived(audioBytes) }
                                }
                            }
                        }
                    }
                }

                if (serverContent.has("outputTranscription")) {
                    val textStr = serverContent.getJSONObject("outputTranscription").optString("text")
                    if (!textStr.isNullOrEmpty()) {
                        handler.post { listener.onOutputTranscription(textStr) }
                    }
                }

                if (serverContent.has("inputTranscription")) {
                    val textStr = serverContent.getJSONObject("inputTranscription").optString("text")
                    if (!textStr.isNullOrEmpty()) {
                        handler.post { listener.onInputTranscription(textStr) }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    handler.post { listener.onTurnComplete() }
                }
            }

            // FIX: Error messages properly handle kiye
            if (root.has("error")) {
                val errorMsg = root.getJSONObject("error").optString("message", "Unknown server error")
                Log.e("GeminiLiveClient", "Server error received: $errorMsg")
                handler.post { listener.onError(errorMsg) }
            }

        } catch (e: Exception) {
            Log.e("GeminiLiveClient", "Error parsing server message: ${e.message}", e)
        }
    }
}
