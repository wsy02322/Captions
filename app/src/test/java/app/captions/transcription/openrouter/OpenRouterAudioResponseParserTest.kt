package app.captions.transcription.openrouter

import app.captions.transcription.TranscriptionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterAudioResponseParserTest {
    @Test
    fun `parses chat completion content json`() {
        val body = """
            {
              "choices":[{
                "message":{
                  "content":"```json\n{\"segments\":[{\"speaker\":0,\"lang\":\"en\",\"text\":\"hi there\",\"words\":[{\"text\":\"hi\",\"speaker\":0},{\"text\":\"there\",\"speaker\":0}]}],\"speaker_descriptions\":{\"0\":\"soft voice\"}}\n```"
                }
              }]
            }
        """.trimIndent()
        val result = OpenRouterAudioResponseParser.parse(body)
        assertEquals(1, result.segments.size)
        assertEquals("hi there", result.segments[0].text)
        assertEquals(0, result.segments[0].speaker)
        assertEquals(2, result.segments[0].words.size)
        assertEquals("soft voice", result.speakerDescriptions[0])
    }

    @Test
    fun `extractJsonObject strips fences`() {
        val extracted = OpenRouterAudioResponseParser.extractJsonObject("prefix {\"a\":1} suffix")
        assertEquals("""{"a":1}""", extracted)
    }

    @Test
    fun `system prompt includes prior context and speakers`() {
        val prompt = OpenRouterTranscriptionProvider.buildSystemPrompt(
            TranscriptionContext(
                priorText = "earlier line",
                glossary = listOf("Captions"),
                speakerDescriptions = mapOf(0 to "deep voice"),
            ),
        )
        assertTrue(prompt.contains("earlier line"))
        assertTrue(prompt.contains("Captions"))
        assertTrue(prompt.contains("deep voice"))
        assertNotNull(OpenRouterTranscriptionProvider.buildRequestJson("abc", TranscriptionContext()))
    }
}
