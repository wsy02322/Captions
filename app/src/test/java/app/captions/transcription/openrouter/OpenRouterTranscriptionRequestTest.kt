package app.captions.transcription.openrouter

import app.captions.transcription.TranscriptionContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterTranscriptionRequestTest {
    @Test
    fun `buildRequestJson uses provided model id`() {
        val json = OpenRouterTranscriptionProvider.buildRequestJson(
            audioB64 = "AAAA",
            context = TranscriptionContext(),
            model = "google/gemini-3.5-flash",
        )
        assertEquals(
            "google/gemini-3.5-flash",
            (json["model"] as JsonPrimitive).contentOrNull,
        )
    }

    @Test
    fun `blank model falls back to default`() {
        val json = OpenRouterTranscriptionProvider.buildRequestJson(
            audioB64 = "AAAA",
            context = TranscriptionContext(),
            model = "  ",
        )
        assertEquals(
            OpenRouterTranscriptionProvider.DEFAULT_MODEL,
            (json["model"] as JsonPrimitive).contentOrNull,
        )
    }

    @Test
    fun `system prompt includes prior context`() {
        val prompt = OpenRouterTranscriptionProvider.buildSystemPrompt(
            TranscriptionContext(priorText = "hello world"),
        )
        assertTrue(prompt.contains("hello world"))
    }
}
