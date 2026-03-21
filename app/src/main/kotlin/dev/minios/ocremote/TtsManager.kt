package dev.minios.ocremote

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dev.minios.ocremote.data.repository.SettingsRepository.AudioOutput
import dev.minios.ocremote.data.repository.SettingsRepository.TtsMode
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream

class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsManager"
    }

    private var nativeTts: TextToSpeech? = null
    private var isNativeInitialized = false
    private var audioManager: AudioManager? = null
    private var mediaPlayer: MediaPlayer? = null

    var currentMode: TtsMode = TtsMode.NATIVE
    var currentVoice: String = "default"
    var currentSpeed: Float = 1.0f
    var autoPlay: Boolean = true
    var audioOutput: AudioOutput = AudioOutput.SPEAKER

    var onStart: () -> Unit = {}
    var onDone: () -> Unit = {}
    var onError: (String) -> Unit = {}

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    fun initNative(onComplete: (TextToSpeech?) -> Unit) {
        if (nativeTts != null && isNativeInitialized) {
            onComplete(nativeTts)
            return
        }

        nativeTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isNativeInitialized = true
                Log.d(TAG, "Native TTS initialized successfully")
                onComplete(nativeTts)
            } else {
                Log.e(TAG, "Native TTS initialization failed with status: $status")
                onComplete(null)
            }
        }
    }

    fun getNativeVoices(): List<String> {
        if (!isNativeInitialized) return emptyList()
        return nativeTts?.voices?.map { it.name } ?: emptyList()
    }

    fun speakNative(text: String, voice: String, speed: Float) {
        if (!isNativeInitialized) {
            onError("TTS not initialized")
            return
        }

        val tts = nativeTts ?: return

        routeAudioOutput(audioOutput)

        tts.setSpeechRate(speed)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post { onStart() }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    onDone()
                    abandonAudioFocus()
                }
            }

            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    onError("TTS playback error")
                    abandonAudioFocus()
                }
            }
        })

        val params = android.os.Bundle()
        val result = if (voice != "default" && getNativeVoices().contains(voice)) {
            tts.setVoice(tts.voices?.find { it.name == voice })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "tts_utterance")
        } else {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "tts_utterance")
        }

        if (result == TextToSpeech.ERROR) {
            onError("TTS speak failed")
            abandonAudioFocus()
        } else {
            requestAudioFocus()
        }
    }

    suspend fun speakServer(text: String, voice: String, serverUrl: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            val client = HttpClient {
                install(HttpTimeout) {
                    requestTimeoutMillis = 30000
                    connectTimeoutMillis = 10000
                }
            }

            val response: dev.minios.ocremote.data.api.VoiceSynthesizeResponse = client.post("$serverUrl/voice/synthesize") {
                contentType(ContentType.Application.Json)
                setBody(SynthesizeRequest(text = text, voice = voice))
            }.body()

            client.close()

            val audioBytes = response.getAudioBytes()
            withContext(Dispatchers.Main) {
                playAudioBytes(audioBytes)
            }
            audioBytes
        } catch (e: Exception) {
            Log.e(TAG, "Server TTS failed", e)
            withContext(Dispatchers.Main) {
                onError("Server TTS failed: ${e.message}")
            }
            throw e
        }
    }

    private fun playAudioBytes(audioBytes: ByteArray) {
        try {
            stopCurrentPlayback()
            routeAudioOutput(audioOutput)

            val tempFile = File.createTempFile("tts_", ".wav", context.cacheDir)
            FileOutputStream(tempFile).use { it.write(audioBytes) }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(if (audioOutput == AudioOutput.EARPIECE) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(tempFile.absolutePath)
                setOnPreparedListener {
                    start()
                    onStart()
                    requestAudioFocus()
                }
                setOnCompletionListener {
                    it.release()
                    tempFile.delete()
                    onDone()
                    abandonAudioFocus()
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    mp.release()
                    tempFile.delete()
                    onError("Audio playback error")
                    abandonAudioFocus()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio", e)
            onError("Failed to play audio: ${e.message}")
            abandonAudioFocus()
        }
    }

    fun speak(text: String, mode: TtsMode, voice: String, speed: Float, serverUrl: String) {
        currentMode = mode
        currentVoice = voice
        currentSpeed = speed

        when (mode) {
            TtsMode.NATIVE -> {
                speakNative(text, voice, speed)
            }
            TtsMode.SERVER -> {
                scope.launch {
                    try {
                        speakServer(text, voice, serverUrl)
                    } catch (e: Exception) {
                        // Error already handled in speakServer
                    }
                }
            }
            TtsMode.OFF -> {
                // Do nothing
            }
        }
    }

    fun stop() {
        nativeTts?.stop()
        stopCurrentPlayback()
        abandonAudioFocus()
    }

    private fun stopCurrentPlayback() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
    }

    private fun requestAudioFocus() {
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(if (audioOutput == AudioOutput.EARPIECE) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { }
            .build()
        audioManager?.requestAudioFocus(focusRequest)
    }

    private fun abandonAudioFocus() {
        audioManager?.abandonAudioFocusRequest(
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(if (audioOutput == AudioOutput.EARPIECE) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .build()
        )
    }

    private fun routeAudioOutput(output: AudioOutput) {
        when (output) {
            AudioOutput.EARPIECE -> {
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager?.isSpeakerphoneOn = false
            }
            AudioOutput.SPEAKER -> {
                audioManager?.mode = AudioManager.MODE_NORMAL
                audioManager?.isSpeakerphoneOn = true
            }
        }
    }

    fun release() {
        nativeTts?.stop()
        nativeTts?.shutdown()
        nativeTts = null
        isNativeInitialized = false
        stopCurrentPlayback()
        abandonAudioFocus()
        scope.cancel()
    }

    @Serializable
    data class SynthesizeRequest(val text: String, val voice: String)
}
