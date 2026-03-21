package dev.minios.ocremote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.util.Log
import androidx.core.content.ContextCompat
import dev.minios.ocremote.data.repository.SettingsRepository.MaxRecordingDuration
import dev.minios.ocremote.data.repository.SettingsRepository.SttMode
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

class SttManager(private val context: Context) {

    companion object {
        private const val TAG = "SttManager"
        private const val SAMPLE_RATE = 16000
        private const val BIT_RATE = 128000
    }

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false
    private var recordingStartTime: Long = 0

    private var speechRecognizer: SpeechRecognizer? = null

    var maxDurationMs: Int = 60000
    var language: String = "en-US"
    var currentMode: SttMode = SttMode.NATIVE

    var onRecordingStarted: () -> Unit = {}
    var onRecordingStopped: (ByteArray) -> Unit = {}
    var onPartialResult: (String) -> Unit = {}
    var onFinalResult: (String) -> Unit = {}
    var onError: (String) -> Unit = {}
    var onMaxDurationReached: () -> Unit = {}

    private val maxDurationHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val maxDurationRunnable = Runnable {
        if (isRecording) {
            stopRecording()
            onMaxDurationReached()
        }
    }

    fun startRecording(): Boolean {
        if (isRecording) return false

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Microphone permission not granted")
            return false
        }

        try {
            outputFile = File.createTempFile("recording_", ".m4a", context.cacheDir)

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            @Suppress("DEPRECATION")
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(outputFile?.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            onRecordingStarted()

            if (maxDurationMs > 0) {
                maxDurationHandler.postDelayed(maxDurationRunnable, maxDurationMs.toLong())
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            onError("Failed to start recording: ${e.message}")
            cleanupRecording()
            return false
        }
    }

    fun stopRecording(): ByteArray {
        if (!isRecording) return ByteArray(0)

        maxDurationHandler.removeCallbacks(maxDurationRunnable)

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder", e)
        }

        mediaRecorder = null
        isRecording = false

        val audioBytes = outputFile?.let { file ->
            try {
                FileInputStream(file).use { fis ->
                    ByteArrayOutputStream().use { bos ->
                        fis.copyTo(bos)
                        bos.toByteArray()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading recording file", e)
                ByteArray(0)
            } finally {
                file.delete()
            }
        } ?: ByteArray(0)

        onRecordingStopped(audioBytes)
        return audioBytes
    }

    fun cancelRecording() {
        maxDurationHandler.removeCallbacks(maxDurationRunnable)

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error canceling MediaRecorder", e)
        }

        mediaRecorder = null
        isRecording = false
        outputFile?.delete()
        outputFile = null
    }

    fun startNativeRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition not available")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Microphone permission not granted")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    Log.d(TAG, "Speech recognizer ready")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Speech recognition beginning")
                }

                override fun onEndOfSpeech() {
                    Log.d(TAG, "Speech recognition ended")
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.let { result ->
                        onPartialResult(result)
                    }
                }

                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        onFinalResult(text)
                    } else {
                        onError("No speech recognized")
                    }
                }

                override fun onError(error: Int) {
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                        else -> "Unknown error"
                    }
                    Log.e(TAG, "Speech recognition error: $errorMessage (code: $error)")
                    if (error != SpeechRecognizer.ERROR_NO_MATCH) {
                        onError(errorMessage)
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })

            val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognition", e)
            onError("Failed to start speech recognition: ${e.message}")
        }
    }

    fun stopNativeRecognition() {
        speechRecognizer?.stopListening()
    }

    suspend fun recognizeServer(audioBytes: ByteArray, serverUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val client = HttpClient {
                install(HttpTimeout) {
                    requestTimeoutMillis = 60000
                    connectTimeoutMillis = 10000
                }
            }

            val response: TranscribeResponse = client.submitFormWithBinaryData(
                url = "$serverUrl/voice/transcribe",
                formData = formData {
                    append("audio", audioBytes, io.ktor.http.Headers.build {
                        append(HttpHeaders.ContentType, "audio/m4a")
                        append(HttpHeaders.ContentDisposition, "filename=\"recording.m4a\"")
                    })
                }
            ).body()

            client.close()
            response.text
        } catch (e: Exception) {
            Log.e(TAG, "Server transcription failed", e)
            withContext(Dispatchers.Main) {
                onError("Server transcription failed: ${e.message}")
            }
            throw e
        }
    }

    fun release() {
        cancelRecording()
        maxDurationHandler.removeCallbacks(maxDurationRunnable)
        speechRecognizer?.destroy()
        speechRecognizer = null
        scope.cancel()
    }

    private fun cleanupRecording() {
        mediaRecorder?.release()
        mediaRecorder = null
        outputFile?.delete()
        outputFile = null
        isRecording = false
    }

    @Serializable
    data class TranscribeResponse(val text: String)
}
