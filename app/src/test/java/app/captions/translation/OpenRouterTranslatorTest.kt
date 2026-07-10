package app.captions.translation

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenRouterTranslatorTest {
    @Test
    fun `parses chat completion content as translation`() {
        val translator = OpenRouterTranslator(
            client = OkHttpClient(),
            baseUrl = "https://openrouter.ai/".toHttpUrl(),
        )
        val result = translator.parse(
            """
            {"choices":[{"message":{"content":"  你好，世界  "}}]}
            """.trimIndent(),
        )
        assertEquals("你好，世界", result.translatedText)
    }
}
