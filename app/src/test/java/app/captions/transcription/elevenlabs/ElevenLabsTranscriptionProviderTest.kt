package app.captions.transcription.elevenlabs

import app.captions.transcription.TranscriptionContext
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ElevenLabsTranscriptionProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: ElevenLabsTranscriptionProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = ElevenLabsTranscriptionProvider(
            client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
            baseUrl = server.url("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `posts multipart scribe_v2 with diarize and xi-api-key`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"hi","words":[{"text":"hi","speaker_id":0}]}"""),
        )
        val result = provider.transcribe(
            apiKey = "xi-test",
            wav = ByteArray(100) { 1 },
            context = TranscriptionContext(glossary = listOf("Captions")),
        )
        assertEquals(1, result.segments.size)
        val request = server.takeRequest()
        assertEquals("/v1/speech-to-text", request.path)
        assertEquals("xi-test", request.getHeader("xi-api-key"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("scribe_v2"))
        assertTrue(body.contains("diarize"))
        assertTrue(body.contains("Captions"))
    }
}
