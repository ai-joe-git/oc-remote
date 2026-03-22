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
import android.widget.Toast
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

    private var audioRecord: android.media.AudioRecord? = null
    private var outputFile: File? = null
    private var isRecording = false
    private var recordingStartTime: Long = 0

    private var speechRecognizer: SpeechRecognizer? = null
    private var recordingJob: Job? = null

    var maxDurationMs: Int = 60000
    var language: String = "en-US"
    var currentMode: SttMode = SttMode.NATIVE
    var whisperUrl: String = "http://localhost:7372"

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
        Log.d(TAG, "startRecording called, isRecording=$isRecording")
        if (isRecording) {
            Log.d(TAG, "Already recording, returning false")
            return false
        }

        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        Log.d(TAG, "RECORD_AUDIO permission status: $permission")
        if (permission != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Permission not granted, showing toast")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
            }
            onError("Microphone permission not granted")
            return false
        }

        try {
            outputFile = File.createTempFile("recording_", ".pcm", context.cacheDir)
            Log.d(TAG, "Temp file for PCM: ${outputFile?.absolutePath}")

            val bufferSize = android.media.AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.media.AudioRecord.Builder()
                    .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }

            if (audioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord failed to initialize")
            }

            audioRecord?.startRecording()
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            onRecordingStarted()

            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(bufferSize)
                val outputStream = FileOutputStream(outputFile)
                try {
                    while (isActive && isRecording) {
                        val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (bytesRead > 0) {
                            outputStream.write(buffer, 0, bytesRead)
                        }
                    }
                } finally {
                    outputStream.close()
                }
            }

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
        isRecording = false

        recordingJob?.cancelAndJoin()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }

        audioRecord = null

        val pcmBytes = outputFile?.let { file ->
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

        val wavBytes = encodePcmToWav(pcmBytes)
        onRecordingStopped(wavBytes)
        return wavBytes
    }

    fun cancelRecording() {
        maxDurationHandler.removeCallbacks(maxDurationRunnable)
        isRecording = false
        recordingJob?.cancel()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error canceling AudioRecord", e)
        }

        audioRecord = null
        outputFile?.delete()
        outputFile = null
    }

    fun startNativeRecognition() {
        Log.d(TAG, "startNativeRecognition called")
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.d(TAG, "Speech recognition not available, showing toast")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Speech recognition not available on this device", Toast.LENGTH_SHORT).show()
            }
            onError("Speech recognition not available")
            return
        }

        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        Log.d(TAG, "RECORD_AUDIO permission status: $permission")
        if (permission != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Permission not granted, showing toast")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
            }
            onError("Microphone permission not granted")
            return
        }

        try {
            Log.d(TAG, "Creating SpeechRecognizer")
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            Log.d(TAG, "Setting recognition listener")
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

                override fun onRmsChanged(rmsdB: Float) {
                    // Not used
                }

                override fun onBufferReceived(buffer: ByteArray?) {
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

    suspend fun recognizeServer(audioBytes: ByteArray, whisperUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val wavBytes = encodeM4aToWav(audioBytes)

            val client = HttpClient {
                install(HttpTimeout) {
                    requestTimeoutMillis = 60000
                    connectTimeoutMillis = 10000
                }
            }

            val responseText: String = client.submitFormWithBinaryData(
                url = "$whisperUrl/v1/audio/transcriptions",
                formData = formData {
                    append("file", wavBytes, io.ktor.http.Headers.build {
                        append(HttpHeaders.ContentType, "audio/wav")
                        append(HttpHeaders.ContentDisposition, "filename=\"audio.wav\"")
                    })
                    append("model", "whisper-large-v3-turbo")
                    append("response_format", "text")
                    if (language.isNotBlank()) {
                        append("language", language.substring(0, 2))
                    }
                }
            ).body()

            client.close()
            responseText.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Server transcription failed", e)
            withContext(Dispatchers.Main) {
                onError("Server transcription failed: ${e.message}")
            }
            throw e
        }
    }

    private fun encodePcmToWav(pcmBytes: ByteArray): ByteArray {
        val byteRate = SAMPLE_RATE * 1 * 16 / 8
        val blockAlign = 1 * 16 / 8
        val dataSize = pcmBytes.size
        val fileSize = 36 + dataSize

        val wavOutput = ByteArrayOutputStream()
        wavOutput.write("RIFF".toByteArray())
        wavOutput.write(intToByteArrayLE(fileSize))
        wavOutput.write("WAVE".toByteArray())
        wavOutput.write("fmt ".toByteArray())
        wavOutput.write(intToByteArrayLE(16))
        wavOutput.write(shortToByteArrayLE(1))
        wavOutput.write(shortToByteArrayLE(1))
        wavOutput.write(intToByteArrayLE(SAMPLE_RATE))
        wavOutput.write(intToByteArrayLE(byteRate))
        wavOutput.write(shortToByteArrayLE(blockAlign.toShort()))
        wavOutput.write(shortToByteArrayLE(16))
        wavOutput.write("data".toByteArray())
        wavOutput.write(intToByteArrayLE(dataSize))
        wavOutput.write(pcmBytes)

        return wavOutput.toByteArray()
    }

    private fun intToByteArrayLE(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToByteArrayLE(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            ((value.toInt() shr 8) and 0xFF).toByte()
        )
    }

    fun release() {
        cancelRecording()
        maxDurationHandler.removeCallbacks(maxDurationRunnable)
        speechRecognizer?.destroy()
        speechRecognizer = null
        scope.cancel()
    }

    private fun cleanupRecording() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null
        recordingJob?.cancel()
        outputFile?.delete()
        outputFile = null
        isRecording = false
    }
}
