package app.captions.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Encodes raw PCM16 little-endian mono samples as a WAV byte array. */
object WavEncoder {
    fun encodePcm16Mono(pcm: ByteArray, sampleRate: Int = AudioCapture.SAMPLE_RATE): ByteArray {
        val dataSize = pcm.size
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1) // audio format = PCM
        header.putShort(1) // channels
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // byte rate
        header.putShort(2) // block align
        header.putShort(16) // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        return header.array() + pcm
    }
}
