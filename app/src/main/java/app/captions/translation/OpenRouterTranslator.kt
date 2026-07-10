package app.captions.translation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class TranslationRequest(
    val text: String,
    val targetLanguage: String,
    val priorPairs: List<Pair<String, String>> = emptyList(),
)

data class TranslationResult(
    val translatedText: String,
)

@Singleton
class OpenRouterTranslator @Inject constructor(
    private val client: OkHttpClient,
    @Named("openRouterBaseUrl") private val baseUrl: HttpUrl,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun translate(apiKey: String, request: TranslationRequest): TranslationResult {
        val system = buildString {
            appendLine("You are a precise subtitle translator.")
            appendLine("Translate the user text into ${request.targetLanguage}.")
            appendLine("Return ONLY the translation text — no quotes, no labels, no explanation.")
            appendLine("Keep speaker tone; preserve names and numbers.")
            if (request.priorPairs.isNotEmpty()) {
                appendLine("Recent bilingual context (for consistency):")
                request.priorPairs.takeLast(6).forEach { (src, tgt) ->
                    appendLine("- $src => $tgt")
                }
            }
        }
        val payload = buildJsonObject {
            put("model", DEFAULT_MODEL)
            put("temperature", 0.0)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", system)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", request.text)
                        },
                    )
                },
            )
        }
        val httpRequest = Request.Builder()
            .url(baseUrl.resolve("api/v1/chat/completions")!!)
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://github.com/wsy02322/Captions")
            .header("X-Title", "Captions")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Translation failed (${response.code}): ${body.take(200)}")
            }
            return parse(body)
        }
    }

    fun parse(body: String): TranslationResult {
        val chat = json.decodeFromString<ChatResponse>(body)
        val content = chat.choices?.firstOrNull()?.message?.content?.trim().orEmpty()
        require(content.isNotBlank()) { "Empty translation" }
        return TranslationResult(translatedText = content.trim().trim('"'))
    }

    companion object {
        const val DEFAULT_MODEL = "google/gemini-3.1-pro-preview"
        const val DEFAULT_TARGET_LANGUAGE = "Simplified Chinese"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
private data class ChatResponse(
    val choices: List<ChatChoice>? = null,
)

@Serializable
private data class ChatChoice(
    val message: ChatResponseMessage? = null,
)

@Serializable
private data class ChatResponseMessage(
    val content: String? = null,
)
