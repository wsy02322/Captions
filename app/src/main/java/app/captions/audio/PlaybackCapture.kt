package app.captions.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures other apps' audio via [MediaProjection] + [AudioPlaybackCaptureConfiguration].
 * Matches USAGE_MEDIA / USAGE_GAME / USAGE_UNKNOWN; voice-call audio is excluded by the platform.
 */
@Singleton
class PlaybackCapture @Inject constructor() {

    fun pcm16Frames(projection: MediaProjection, frameMs: Int = 100): Flow<ByteArray> = flow {
        val sampleRate = AudioCapture.SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        require(minBuffer > 0) { "AudioRecord unavailable on this device" }

        val config = PlaybackCaptureConfig.build(projection)
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        val frameBytes = (sampleRate * frameMs / 1000) * 2
        val bufferSize = maxOf(minBuffer, frameBytes * 2)
        val recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "Failed to initialize playback AudioRecord"
        }

        val buffer = ByteArray(frameBytes.coerceAtLeast(320))
        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    emit(buffer.copyOf(read))
                } else if (read < 0) {
                    Log.w(TAG, "Playback AudioRecord read error: $read")
                    break
                }
            }
        } finally {
            runCatching {
                recorder.stop()
                recorder.release()
            }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val TAG = "PlaybackCapture"
    }
}

/** Pure helpers for playback-capture configuration (unit-testable). */
object PlaybackCaptureConfig {
    val MATCHED_USAGES = intArrayOf(
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_UNKNOWN,
    )

    fun build(projection: MediaProjection): AudioPlaybackCaptureConfiguration {
        val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
        for (usage in MATCHED_USAGES) {
            builder.addMatchingUsage(usage)
        }
        return builder.build()
    }
}
