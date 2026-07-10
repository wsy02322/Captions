package app.captions.audio

import kotlinx.coroutines.flow.Flow

/** Emits PCM16 mono frames at [SAMPLE_RATE] Hz. */
interface AudioCapture {
    fun pcm16Frames(frameMs: Int = 100): Flow<ByteArray>

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}

enum class CaptureSource {
    MICROPHONE,
    PLAYBACK,
}
