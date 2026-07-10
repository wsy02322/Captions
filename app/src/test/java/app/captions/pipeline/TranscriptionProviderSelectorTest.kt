package app.captions.pipeline

import app.captions.transcription.SelectedProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionProviderSelectorTest {
    @Test
    fun `canStart when any provider key present`() {
        assertTrue(TranscriptionProviderSelector.canStart(true, false, false))
        assertTrue(TranscriptionProviderSelector.canStart(false, true, false))
        assertTrue(TranscriptionProviderSelector.canStart(false, false, true))
        assertFalse(TranscriptionProviderSelector.canStart(false, false, false))
    }

    @Test
    fun `priority constants exist for streaming and batch`() {
        assertEquals("DEEPGRAM_STREAMING", SelectedProviderKind.DEEPGRAM_STREAMING.name)
        assertEquals("ELEVENLABS_BATCH", SelectedProviderKind.ELEVENLABS_BATCH.name)
        assertEquals("OPENROUTER_BATCH", SelectedProviderKind.OPENROUTER_BATCH.name)
    }
}
