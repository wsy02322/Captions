package app.captions.providers.openrouter

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterModelCatalogTest {
  private val catalog = OpenRouterModelCatalog(
      client = OkHttpClient(),
      baseUrl = "https://openrouter.ai/".toHttpUrl(),
  )

  @Test
  fun `parseModels filters audio and text capabilities`() {
      val body = """
          {
            "data": [
              {
                "id": "google/gemini-3.1-pro-preview",
                "architecture": { "input_modalities": ["text", "audio", "image"] }
              },
              {
                "id": "openai/gpt-4o-mini",
                "architecture": { "input_modalities": ["text", "image"] }
              },
              {
                "id": "legacy/no-architecture"
              }
            ]
          }
      """.trimIndent()

      val parsed = catalog.parseModels(body)
      val gemini = parsed.first { it.id == "google/gemini-3.1-pro-preview" }
      val mini = parsed.first { it.id == "openai/gpt-4o-mini" }
      val legacy = parsed.first { it.id == "legacy/no-architecture" }

      assertTrue(gemini.supportsAudio)
      assertTrue(gemini.supportsText)
      assertFalse(mini.supportsAudio)
      assertTrue(mini.supportsText)
      assertFalse(legacy.supportsAudio)
      assertTrue(legacy.supportsText)
  }

  @Test
  fun `mergeOptions keeps recommended order and appends extras`() {
      val merged = OpenRouterModels.mergeOptions(
          recommended = OpenRouterModels.RECOMMENDED_TRANSLATION,
          fetchedIds = listOf(
              "google/gemini-3.1-pro-preview",
              "vendor/extra-model",
          ),
      )
      assertEquals("google/gemini-3.1-pro-preview", merged.first().id)
      assertEquals("vendor/extra-model", merged.last().id)
      assertEquals(5, merged.size)
  }
}
