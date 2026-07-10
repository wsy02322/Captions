package app.captions.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionLineCoalescerTest {
    @Test
    fun `merges short same-speaker fragments within gap`() {
        assertTrue(
            CaptionLineCoalescer.shouldMerge(
                previousSpeaker = 0,
                previousText = "don't know if",
                previousUpdatedAtMs = 1_000,
                nextSpeaker = 0,
                nextText = "this",
                nextAtMs = 1_500,
            ),
        )
    }

    @Test
    fun `does not merge different speakers`() {
        assertFalse(
            CaptionLineCoalescer.shouldMerge(
                previousSpeaker = 0,
                previousText = "hello",
                previousUpdatedAtMs = 1_000,
                nextSpeaker = 1,
                nextText = "hi",
                nextAtMs = 1_200,
            ),
        )
    }

    @Test
    fun `join inserts space between words`() {
        assertEquals("hello world", CaptionLineCoalescer.join("hello", "world"))
        assertEquals("hello,", CaptionLineCoalescer.join("hello", ","))
    }
}
