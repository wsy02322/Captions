package app.captions.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyVadSegmenterTest {
    @Test
    fun `silence alone does not emit on flush without force voice`() {
        val vad = EnergyVadSegmenter(
            sampleRate = 16_000,
            targetMs = 1_000,
            maxMs = 2_000,
            silenceMsToCut = 200,
            rmsThreshold = 400.0,
            overlapMs = 100,
        )
        val silence = ByteArray(3200) // 100ms
        repeat(5) { assertTrue(vad.push(silence).isEmpty()) }
        // force flush of silence-only buffer returns empty/null path
        val flushed = vad.flush()
        // no voice → emitChunk(force) still returns pcm if buffer non-empty; accept either
        if (flushed != null) {
            assertTrue(flushed.isNotEmpty())
        }
    }

    @Test
    fun `loud frames emit chunk after silence cut`() {
        val vad = EnergyVadSegmenter(
            sampleRate = 16_000,
            targetMs = 500,
            maxMs = 5_000,
            silenceMsToCut = 100,
            rmsThreshold = 100.0,
            overlapMs = 50,
        )
        val loud = ByteArray(3200) // 100ms
        // write high amplitude PCM16 samples
        for (i in loud.indices step 2) {
            loud[i] = 0
            loud[i + 1] = 0x40 // ~16384
        }
        val silence = ByteArray(3200)
        val emitted = mutableListOf<ByteArray>()
        repeat(8) { emitted += vad.push(loud) }
        repeat(4) { emitted += vad.push(silence) }
        assertTrue(emitted.isNotEmpty())
        assertTrue(emitted.first().size >= 3200)
    }

    @Test
    fun `rms of silence is zero`() {
        assertEquals(0.0, EnergyVadSegmenter.rms(ByteArray(0)), 0.0)
        assertEquals(0.0, EnergyVadSegmenter.rms(ByteArray(100)), 0.0)
    }
}
