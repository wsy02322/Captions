package app.captions.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MicrophoneCapture @Inject constructor() {

    fun pcm16Frames(frameMs: Int = 100): Flow<ByteArray> = flow {
        val sampleRate = SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        require(minBuffer > 0) { "AudioRecord unavailable on this device" }

        val frameBytes = (sampleRate * frameMs / 1000) * 2
        val bufferSize = maxOf(minBuffer, frameBytes * 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            encoding,
            bufferSize,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "Failed to initialize AudioRecord"
        }

        val buffer = ByteArray(frameBytes.coerceAtLeast(320))
        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    emit(buffer.copyOf(read))
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord read error: $read")
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

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val TAG = "MicrophoneCapture"
    }
}
