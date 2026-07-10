package app.captions.transcription.openrouter

import app.captions.transcription.BatchTranscriptionProvider
import app.captions.transcription.TranscriptionContext
import app.captions.transcription.TranscriptionResult
import app.captions.transcription.TranscriptionSegment
import app.captions.transcription.TranscriptionWord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class OpenRouterTranscriptionProvider @Inject constructor(
    private val client: OkHttpClient,
    @Named("openRouterBaseUrl") private val baseUrl: HttpUrl,
) : BatchTranscriptionProvider {

    override val displayName: String = "OpenRouter multimodal"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun transcribe(
        apiKey: String,
        wav: ByteArray,
        context: TranscriptionContext,
    ): TranscriptionResult {
        val audioB64 = Base64.getEncoder().encodeToString(wav)
        val payload = buildRequestJson(audioB64, context)
        val request = Request.Builder()
            .url(baseUrl.resolve("api/v1/chat/completions")!!)
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://github.com/wsy02322/Captions")
            .header("X-Title", "Captions")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("OpenRouter STT failed (${response.code}): ${body.take(200)}")
            }
            return OpenRouterAudioResponseParser.parse(body, json)
        }
    }

    companion object {
        const val DEFAULT_MODEL = "google/gemini-3.1-pro-preview"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun buildSystemPrompt(context: TranscriptionContext): String = buildString {
            appendLine("You are a speech-to-text engine with speaker diarization.")
            appendLine("Return ONLY valid JSON matching:")
            appendLine(
                """{"segments":[{"speaker":0,"lang":"en","text":"...","words":[{"text":"...","speaker":0}]}],"speaker_descriptions":{"0":"brief voice note"}}""",
            )
            appendLine("Rules: keep speaker ints stable within the chunk; do not invent text;")
            appendLine("empty audio → {\"segments\":[]}.")
            if (context.priorText.isNotBlank()) {
                appendLine("Previous transcript context:")
                appendLine(context.priorText.takeLast(800))
            }
            if (context.glossary.isNotEmpty()) {
                appendLine("Glossary / keyterms: ${context.glossary.joinToString(", ")}")
            }
            if (context.speakerDescriptions.isNotEmpty()) {
                appendLine("Known speakers from earlier chunks:")
                context.speakerDescriptions.forEach { (id, desc) ->
                    appendLine("- speaker $id: $desc")
                }
            }
        }

        fun buildRequestJson(audioB64: String, context: TranscriptionContext): JsonObject =
            buildJsonObject {
                put("model", DEFAULT_MODEL)
                put("temperature", 0.0)
                put(
                    "messages",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "system")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "text")
                                                put("text", buildSystemPrompt(context))
                                            },
                                        )
                                    },
                                )
                            },
                        )
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "text")
                                                put("text", "Transcribe this audio chunk to the required JSON.")
                                            },
                                        )
                                        add(
                                            buildJsonObject {
                                                put("type", "input_audio")
                                                put(
                                                    "input_audio",
                                                    buildJsonObject {
                                                        put("data", audioB64)
                                                        put("format", "wav")
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            }
    }
}

object OpenRouterAudioResponseParser {
    fun parse(body: String, json: Json = Json { ignoreUnknownKeys = true }): TranscriptionResult {
        val chat = json.decodeFromString<ChatResponse>(body)
        val content = chat.choices?.firstOrNull()?.message?.content?.trim().orEmpty()
        val jsonText = extractJsonObject(content) ?: content
        val parsed = json.decodeFromString<OpenRouterSttPayload>(jsonText)
        val segments = parsed.segments.orEmpty().map { seg ->
            val words = seg.words.orEmpty().map {
                TranscriptionWord(text = it.text.orEmpty(), speaker = it.speaker ?: seg.speaker ?: 0)
            }.filter { it.text.isNotBlank() }
            TranscriptionSegment(
                speaker = seg.speaker ?: 0,
                lang = seg.lang,
                text = seg.text?.trim().orEmpty().ifEmpty {
                    words.joinToString(" ") { it.text }
                },
                words = words,
            )
        }.filter { it.text.isNotBlank() }
        val descriptions = parsed.speakerDescriptions.orEmpty()
            .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }
            .toMap()
        return TranscriptionResult(segments = segments, speakerDescriptions = descriptions)
    }

    fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
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

@Serializable
internal data class OpenRouterSttPayload(
    val segments: List<OpenRouterSegment>? = null,
    @SerialName("speaker_descriptions") val speakerDescriptions: Map<String, String>? = null,
)

@Serializable
internal data class OpenRouterSegment(
    val speaker: Int? = null,
    val lang: String? = null,
    val text: String? = null,
    val words: List<OpenRouterWord>? = null,
)

@Serializable
internal data class OpenRouterWord(
    val text: String? = null,
    val speaker: Int? = null,
)
