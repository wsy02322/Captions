package app.captions.transcription.deepgram

import app.captions.transcription.TranscriptionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepgramMessageParserTest {

    @Test
    fun `parses interim partial with speaker`() {
        val json = """
            {
              "type":"Results",
              "is_final":false,
              "speech_final":false,
              "channel":{
                "alternatives":[{
                  "transcript":"hello world",
                  "words":[
                    {"word":"hello","punctuated_word":"hello","speaker":0,"start":0.0,"end":0.3},
                    {"word":"world","punctuated_word":"world","speaker":0,"start":0.3,"end":0.6}
                  ]
                }]
              }
            }
        """.trimIndent()

        val event = DeepgramMessageParser.parse(json) as TranscriptionEvent.Partial
        assertEquals("hello world", event.text)
        assertEquals(0, event.speaker)
        assertEquals(2, event.words.size)
    }

    @Test
    fun `parses final and picks majority speaker`() {
        val json = """
            {
              "type":"Results",
              "is_final":true,
              "channel":{
                "alternatives":[{
                  "transcript":"hi there friend",
                  "words":[
                    {"word":"hi","speaker":1},
                    {"word":"there","speaker":1},
                    {"word":"friend","speaker":0}
                  ]
                }]
              }
            }
        """.trimIndent()

        val event = DeepgramMessageParser.parse(json) as TranscriptionEvent.Final
        assertEquals("hi there friend", event.text)
        assertEquals(1, event.speaker)
    }

    @Test
    fun `ignores empty transcript`() {
        val json = """{"type":"Results","is_final":true,"channel":{"alternatives":[{"transcript":"  "}]}}"""
        assertNull(DeepgramMessageParser.parse(json))
    }

    @Test
    fun `ignores non results messages`() {
        assertNull(DeepgramMessageParser.parse("""{"type":"Metadata"}"""))
    }

    @Test
    fun `speaker colors are stable and distinct for first speakers`() {
        val a = app.captions.ui.caption.SpeakerColors.colorFor(0)
        val b = app.captions.ui.caption.SpeakerColors.colorFor(1)
        val aAgain = app.captions.ui.caption.SpeakerColors.colorFor(0)
        assertEquals(a, aAgain)
        assertTrue(a != b)
    }
}
