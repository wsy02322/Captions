package app.captions.transcription.deepgram

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepgramListenUrlTest {

    @Test
    fun `listen url enables nova-3 multi diarize without diarize_model`() {
        val url = DeepgramTranscriptionProvider.buildListenUrl("https://api.deepgram.com/".toHttpUrl())

        assertEquals("/v1/listen", url.encodedPath)
        assertEquals("nova-3", url.queryParameter("model"))
        assertEquals("multi", url.queryParameter("language"))
        assertEquals("true", url.queryParameter("diarize"))
        assertEquals("16000", url.queryParameter("sample_rate"))
        assertEquals("linear16", url.queryParameter("encoding"))
        assertFalse(url.queryParameterNames.contains("diarize_model"))
        assertTrue(url.queryParameterNames.contains("interim_results"))
        assertTrue(url.queryParameterNames.contains("vad_events"))
    }
}
