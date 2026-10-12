package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

class AudioRecorder(private val sampleRate: Int = 16000) {

    private var audioRecord: AudioRecord? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var recordingJob: Job? = null

    @Volatile
    var isRecording: Boolean = false
        private set

    var onAudioChunk: ((pcm: ByteArray, base64: String, amplitude: Float) -> Unit)? = null
    var onAmplitudeUpdated: ((Float) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording) return true

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = max(minBufferSize * 2, 2048)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                return false
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingJob = scope.launch {
                val buffer = ByteArray(2048)
                while (isActive && isRecording) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readBytes > 0) {
                        val chunk = buffer.copyOf(readBytes)
                        val base64Data = Base64.encodeToString(chunk, Base64.NO_WRAP)

                        // Calculate input amplitude
                        var maxAmp = 0
                        var i = 0
                        while (i < readBytes - 1) {
                            val sample = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xFF)).toShort()
                            maxAmp = max(maxAmp, abs(sample.toInt()))
                            i += 2
                        }
                        val normalizedAmp = (maxAmp / 32767f).coerceIn(0f, 1f)
                        onAmplitudeUpdated?.invoke(normalizedAmp)
                        onAudioChunk?.invoke(chunk, base64Data, normalizedAmp)
                    }
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            isRecording = false
            return false
        }
    }

    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioRecord = null
        onAmplitudeUpdated?.invoke(0f)
    }
}
