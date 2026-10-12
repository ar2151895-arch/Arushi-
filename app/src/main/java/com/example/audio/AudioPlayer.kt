package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max

class AudioPlayer(private val sampleRate: Int = 24000) {

    private var audioTrack: AudioTrack? = null
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var playbackJob: Job? = null

    @Volatile
    var isPlaying: Boolean = false
        private set

    var onPlayingStateChanged: ((Boolean) -> Unit)? = null
    var onAmplitudeUpdated: ((Float) -> Unit)? = null

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = max(minBufferSize * 2, 4096)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun playChunk(pcmData: ByteArray) {
        if (pcmData.isEmpty()) return
        audioQueue.add(pcmData)

        if (playbackJob?.isActive != true) {
            startPlaybackLoop()
        }
    }

    private fun startPlaybackLoop() {
        playbackJob = scope.launch {
            if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                initAudioTrack()
            }

            try {
                if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack?.play()
                }
            } catch (e: Exception) {
                // Ignore
            }

            updatePlayingState(true)

            while (isActive) {
                val chunk = audioQueue.poll()
                if (chunk != null) {
                    // Calculate amplitude for speech visualization
                    var maxAmp = 0
                    var i = 0
                    while (i < chunk.size - 1) {
                        val sample = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xFF)).toShort()
                        maxAmp = max(maxAmp, abs(sample.toInt()))
                        i += 2
                    }
                    val normalizedAmp = (maxAmp / 32767f).coerceIn(0f, 1f)
                    onAmplitudeUpdated?.invoke(normalizedAmp)

                    audioTrack?.write(chunk, 0, chunk.size)
                } else {
                    // Check if queue has been empty for a short moment
                    kotlinx.coroutines.delay(60)
                    if (audioQueue.isEmpty()) {
                        break
                    }
                }
            }

            onAmplitudeUpdated?.invoke(0f)
            updatePlayingState(false)
        }
    }

    /**
     * Immediately stops audio playback, flushes buffers, and clears pending chunks.
     * Crucial for conversational turn-taking and interruption handling.
     */
    fun stopAndFlush() {
        audioQueue.clear()
        playbackJob?.cancel()
        playbackJob = null

        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {
            // Ignore
        }
        onAmplitudeUpdated?.invoke(0f)
        updatePlayingState(false)
    }

    private fun updatePlayingState(playing: Boolean) {
        if (isPlaying != playing) {
            isPlaying = playing
            onPlayingStateChanged?.invoke(playing)
        }
    }

    fun release() {
        stopAndFlush()
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioTrack = null
    }
}
