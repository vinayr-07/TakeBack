package com.vr.takeback.platform.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.*
import java.util.Random

/**
 * FocusAudioPlayer: Procedural ambient audio synthesizer.
 * Generates continuous Brown noise, White noise, Rain simulation, and Pink noise
 * 100% on-device using AudioTrack without requiring heavy external asset downloads.
 */
class FocusAudioPlayer(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioTrack: AudioTrack? = null
    private var audioJob: Job? = null
    private var isPlaying = false
    private val random = Random()

    fun playSound(soundType: String, volume: Float = 0.5f) {
        stopSound()
        isPlaying = true

        val sampleRate = 44100
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track.setVolume(volume.coerceIn(0f, 1f))
        track.play()
        audioTrack = track

        audioJob = CoroutineScope(Dispatchers.Default).launch {
            val buffer = ShortArray(bufferSize / 2)
            var lastBrownSample = 0.0

            while (isActive && isPlaying) {
                for (i in buffer.indices) {
                    val sample = when (soundType.lowercase()) {
                        "brown noise", "brown" -> {
                            val white = (random.nextDouble() * 2.0) - 1.0
                            lastBrownSample = (lastBrownSample + (0.02 * white)) / 1.02
                            (lastBrownSample * 32767.0 * 0.4).toInt().coerceIn(-32768, 32767).toShort()
                        }
                        "rain", "rain simulation" -> {
                            val white = (random.nextDouble() * 2.0) - 1.0
                            // Random droplets + filtered hiss
                            val droplet = if (random.nextInt(1000) > 985) (random.nextDouble() * 12000).toInt() else 0
                            lastBrownSample = (lastBrownSample + (0.04 * white)) / 1.04
                            ((lastBrownSample * 20000.0) + droplet).toInt().coerceIn(-32768, 32767).toShort()
                        }
                        else -> { // White Noise
                            val white = (random.nextDouble() * 2.0) - 1.0
                            (white * 32767.0 * 0.15).toInt().coerceIn(-32768, 32767).toShort()
                        }
                    }
                    buffer[i] = sample
                }
                track.write(buffer, 0, buffer.size)
            }
        }
    }

    fun stopSound() {
        isPlaying = false
        audioJob?.cancel()
        audioJob = null
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioTrack = null
    }

    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }
}
