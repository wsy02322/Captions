package app.captions.transcription.elevenlabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevenLabsResponseParserTest {
    @Test
    fun `parses diarized words into speaker segments`() {
        val json = """
            {
              "text":"hello world goodbye",
              "language_code":"en",
              "words":[
                {"text":"hello","speaker_id":0,"start":0.0,"end":0.2},
                {"text":"world","speaker_id":0,"start":0.2,"end":0.4},
                {"text":"goodbye","speaker_id":1,"start":0.5,"end":0.8}
              ]
            }
        """.trimIndent()
        val result = ElevenLabsResponseParser.parse(json)
        assertEquals(2, result.segments.size)
        assertEquals(0, result.segments[0].speaker)
        assertEquals("hello world", result.segments[0].text)
        assertEquals(1, result.segments[1].speaker)
        assertEquals("goodbye", result.segments[1].text)
        assertEquals("en", result.segments[0].lang)
    }

    @Test
    fun `falls back to top-level text when words missing`() {
        val result = ElevenLabsResponseParser.parse("""{"text":"only text","language_code":"zh"}""")
        assertEquals(1, result.segments.size)
        assertEquals("only text", result.segments[0].text)
        assertTrue(result.segments[0].words.isEmpty())
    }
}
