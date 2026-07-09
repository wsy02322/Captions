package app.captions.providers

import app.captions.data.keys.ApiProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class KeyValidatorTest {

    private lateinit var server: MockWebServer
    private lateinit var validator: KeyValidator

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        validator = KeyValidator(
            client = client,
            openRouterBaseUrl = server.url("/"),
            deepgramBaseUrl = server.url("/"),
            elevenLabsBaseUrl = server.url("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `openrouter valid key hits key endpoint with bearer header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{}}"""))

        val result = validator.validate(ApiProvider.OPENROUTER, "sk-or-v1-test")

        assertEquals(KeyValidationResult.VALID, result)
        val request = server.takeRequest()
        assertEquals("/api/v1/key", request.path)
        assertEquals("Bearer sk-or-v1-test", request.getHeader("Authorization"))
    }

    @Test
    fun `deepgram valid key hits projects endpoint with token header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"projects":[]}"""))

        val result = validator.validate(ApiProvider.DEEPGRAM, "dg-test")

        assertEquals(KeyValidationResult.VALID, result)
        val request = server.takeRequest()
        assertEquals("/v1/projects", request.path)
        assertEquals("Token dg-test", request.getHeader("Authorization"))
    }

    @Test
    fun `elevenlabs valid key hits user endpoint with xi-api-key header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"user_id":"u"}"""))

        val result = validator.validate(ApiProvider.ELEVENLABS, "xi-test")

        assertEquals(KeyValidationResult.VALID, result)
        val request = server.takeRequest()
        assertEquals("/v1/user", request.path)
        assertEquals("xi-test", request.getHeader("xi-api-key"))
    }

    @Test
    fun `401 maps to invalid`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad"}"""))

        assertEquals(
            KeyValidationResult.INVALID,
            validator.validate(ApiProvider.OPENROUTER, "sk-or-v1-bad"),
        )
    }

    @Test
    fun `403 maps to invalid`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        assertEquals(
            KeyValidationResult.INVALID,
            validator.validate(ApiProvider.ELEVENLABS, "xi-bad"),
        )
    }

    @Test
    fun `server error maps to network error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(
            KeyValidationResult.NETWORK_ERROR,
            validator.validate(ApiProvider.OPENROUTER, "sk-or-v1-test"),
        )
    }

    @Test
    fun `connection failure maps to network error`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(
            KeyValidationResult.NETWORK_ERROR,
            validator.validate(ApiProvider.OPENROUTER, "sk-or-v1-test"),
        )
    }
}
