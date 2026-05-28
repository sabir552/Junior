package com.junior.assistant.ai

import android.annotation.SuppressLint
import android.media.*
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.sqrt

class AudioEngine(
    private val onMicChunkRecorded: (ByteArray) -> Unit,
    private val onRmsUpdated: (Float) -> Unit,
    private val onWakeWordDetected: () -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var isRecording = false
    private var isPlaying = false

    private var recordingThread: Thread? = null
    private var playbackThread: Thread? = null

    private val playbackQueue = LinkedBlockingQueue<ByteArray>()

    private var wakeWordThreshold = 22000
    private var isMuted = false

    // FIX: Feedback loop guard — queue-based check ki jagah simple flag use kiya
    // Pehle playbackQueue.isNotEmpty() check ki wajah se mic stream unnecessarily ruk jaata tha
    @Volatile private var isOutputActive = false

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) return
        isRecording = true

        val bufferSize = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(1024)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioEngine", "AudioRecord initialization failed!")
                isRecording = false
                return
            }

            audioRecord?.startRecording()
            recordingThread = Thread {
                val buffer = ByteArray(1024)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val rms = calculateRms(buffer, read)
                        onRmsUpdated(rms)

                        checkWakeWordThreshold(buffer, read)

                        // FIX: Sirf isMuted check — isOutputActive flag se feedback loop rokna kafi hai
                        // Queue-based guard remove kiya jo valid audio stream block kar raha tha
                        if (!isMuted) {
                            val chunk = ByteArray(read)
                            System.arraycopy(buffer, 0, chunk, 0, read)
                            onMicChunkRecorded(chunk)
                        }
                    }
                }
            }
            recordingThread?.start()
            Log.d("AudioEngine", "Recording started")
        } catch (e: Exception) {
            Log.e("AudioEngine", "Error starting recording", e)
            isRecording = false
        }
    }

    fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("AudioEngine", "Error stopping recording", e)
        }
        audioRecord = null
        recordingThread = null
    }

    fun startPlayback() {
        if (isPlaying) return
        isPlaying = true

        val bufferSize = AudioTrack.getMinBufferSize(
            24000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(24000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        audioTrack = AudioTrack(
            attributes,
            format,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        audioTrack?.play()

        playbackThread = Thread {
            while (isPlaying) {
                try {
                    val pcmData = playbackQueue.take()
                    isOutputActive = true
                    audioTrack?.write(pcmData, 0, pcmData.size)
                    // Queue khaali hone par flag reset karo
                    if (playbackQueue.isEmpty()) {
                        isOutputActive = false
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e("AudioEngine", "Playback error", e)
                    isOutputActive = false
                }
            }
        }
        playbackThread?.start()
        Log.d("AudioEngine", "Playback started")
    }

    fun queuePlaybackAudio(data: ByteArray) {
        playbackQueue.offer(data)
    }

    fun flushPlayback() {
        playbackQueue.clear()
        isOutputActive = false
        try {
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.e("AudioEngine", "AudioTrack flush failed", e)
        }
    }

    fun stopPlayback() {
        isPlaying = false
        isOutputActive = false
        playbackThread?.interrupt()
        playbackThread = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e("AudioEngine", "Error stopping playback", e)
        }
        audioTrack = null
        playbackQueue.clear()
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
    }

    fun isPlayingActive(): Boolean {
        return isOutputActive
    }

    private fun calculateRms(buffer: ByteArray, size: Int): Float {
        var sum = 0.0
        val shortBufferCount = size / 2
        for (i in 0 until shortBufferCount) {
            val sample = ((buffer[2 * i + 1].toInt() shl 8) or (buffer[2 * i].toInt() and 0xFF)).toShort()
            sum += sample * sample
        }
        val avg = sum / shortBufferCount.coerceAtLeast(1)
        return sqrt(avg).toFloat()
    }

    private fun checkWakeWordThreshold(buffer: ByteArray, size: Int) {
        val shortBufferCount = size / 2
        var maxVal = 0
        for (i in 0 until shortBufferCount) {
            val sample = ((buffer[2 * i + 1].toInt() shl 8) or (buffer[2 * i].toInt() and 0xFF))
            val absVal = kotlin.math.abs(sample)
            if (absVal > maxVal) {
                maxVal = absVal
            }
        }
        if (maxVal > wakeWordThreshold) {
            Log.d("AudioEngine", "Wake threshold pattern matched! peak=$maxVal")
            onWakeWordDetected()
        }
    }
}
