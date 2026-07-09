package app.captions.pipeline

import app.captions.transcription.TranscriptionWord
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerIdMapperTest {
    @Test
    fun `maps overlapping words to stable session speakers`() {
        val mapper = SpeakerIdMapper()
        val first = mapper.mapWords(
            listOf(
                TranscriptionWord("hello", speaker = 0),
                TranscriptionWord("there", speaker = 0),
                TranscriptionWord("friend", speaker = 1),
            ),
        )
        assertEquals(listOf(0, 0, 1), first.map { it.speaker })

        // Next chunk uses different local IDs but overlapping "friend" from speaker 7
        // should align to session speaker 1; "okay" from 9 becomes new or mapped.
        val second = mapper.mapWords(
            listOf(
                TranscriptionWord("friend", speaker = 7),
                TranscriptionWord("okay", speaker = 9),
            ),
        )
        assertEquals(1, second[0].speaker)
        assertEquals(9.let { /* newly allocated after 0,1 */ second[1].speaker }, second[1].speaker)
        // speaker 9 should get a new session id (2)
        assertEquals(2, second[1].speaker)
    }

    @Test
    fun `reset clears mapping`() {
        val mapper = SpeakerIdMapper()
        mapper.mapSpeaker(5)
        mapper.reset()
        assertEquals(0, mapper.mapSpeaker(5))
    }
}
