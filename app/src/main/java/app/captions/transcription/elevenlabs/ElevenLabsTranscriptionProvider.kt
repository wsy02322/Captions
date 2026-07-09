package app.captions.transcription.elevenlabs

import app.captions.transcription.BatchTranscriptionProvider
import app.captions.transcription.TranscriptionContext
import app.captions.transcription.TranscriptionResult
import app.captions.transcription.TranscriptionSegment
import app.captions.transcription.TranscriptionWord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ElevenLabsTranscriptionProvider @Inject constructor(
    private val client: OkHttpClient,
    @Named("elevenLabsBaseUrl") private val baseUrl: HttpUrl,
) : BatchTranscriptionProvider {

    override val displayName: String = "ElevenLabs Scribe v2"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun transcribe(
        apiKey: String,
        wav: ByteArray,
        context: TranscriptionContext,
    ): TranscriptionResult {
        val url = baseUrl.resolve("v1/speech-to-text")!!
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "chunk.wav",
                wav.toRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model_id", "scribe_v2")
            .addFormDataPart("diarize", "true")
            .addFormDataPart("timestamps_granularity", "word")
        if (context.glossary.isNotEmpty()) {
            // ElevenLabs keyterms: comma-separated or repeated; send joined for simplicity.
            bodyBuilder.addFormDataPart("keyterms", context.glossary.joinToString(","))
        }
        val request = Request.Builder()
            .url(url)
            .header("xi-api-key", apiKey)
            .post(bodyBuilder.build())
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("ElevenLabs STT failed (${response.code}): ${body.take(200)}")
            }
            return ElevenLabsResponseParser.parse(body, json)
        }
    }
}

object ElevenLabsResponseParser {
    fun parse(body: String, json: Json = Json { ignoreUnknownKeys = true }): TranscriptionResult {
        val parsed = json.decodeFromString<ElevenLabsSttResponse>(body)
        val words = parsed.words.orEmpty().mapNotNull { w ->
            val text = w.text?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            TranscriptionWord(
                text = text,
                speaker = w.speakerId ?: 0,
                startSec = w.start,
                endSec = w.end,
            )
        }
        if (words.isNotEmpty()) {
            // Group consecutive same-speaker runs into segments.
            val segments = ArrayList<TranscriptionSegment>()
            var currentSpeaker = words.first().speaker
            val bucket = ArrayList<TranscriptionWord>()
            fun flush() {
                if (bucket.isEmpty()) return
                segments += TranscriptionSegment(
                    speaker = currentSpeaker,
                    lang = parsed.languageCode,
                    text = bucket.joinToString(" ") { it.text },
                    words = bucket.toList(),
                )
                bucket.clear()
            }
            for (w in words) {
                if (w.speaker != currentSpeaker) {
                    flush()
                    currentSpeaker = w.speaker
                }
                bucket += w
            }
            flush()
            return TranscriptionResult(segments = segments)
        }
        val text = parsed.text?.trim().orEmpty()
        if (text.isEmpty()) return TranscriptionResult(emptyList())
        return TranscriptionResult(
            listOf(
                TranscriptionSegment(
                    speaker = 0,
                    lang = parsed.languageCode,
                    text = text,
                ),
            ),
        )
    }
}

@Serializable
internal data class ElevenLabsSttResponse(
    val text: String? = null,
    @SerialName("language_code") val languageCode: String? = null,
    val words: List<ElevenLabsWord>? = null,
)

@Serializable
internal data class ElevenLabsWord(
    val text: String? = null,
    val start: Double? = null,
    val end: Double? = null,
    @SerialName("speaker_id") val speakerId: Int? = null,
    val type: String? = null,
)
