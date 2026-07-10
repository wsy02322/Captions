package app.captions.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WavEncoderTest {
    @Test
    fun `encodes pcm with valid wav header`() {
        val pcm = ByteArray(320) { 1 }
        val wav = WavEncoder.encodePcm16Mono(pcm, sampleRate = 16_000)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("fmt ", wav.copyOfRange(12, 16).toString(Charsets.US_ASCII))
        assertEquals("data", wav.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        assertTrue(wav.copyOfRange(44, wav.size).contentEquals(pcm))
    }
}
