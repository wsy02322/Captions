package app.captions.transcription.deepgram

import app.captions.transcription.TranscriptionEvent
import app.captions.transcription.TranscriptionWord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object DeepgramMessageParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(text: String): TranscriptionEvent? {
        val message = runCatching { json.decodeFromString<DeepgramMessage>(text) }.getOrNull()
            ?: return null
        if (message.type != null && message.type != "Results") return null
        val alt = message.channel?.alternatives?.firstOrNull() ?: return null
        val transcript = alt.transcript?.trim().orEmpty()
        if (transcript.isEmpty()) return null
        val words = alt.words.orEmpty().map {
            TranscriptionWord(
                text = it.punctuatedWord ?: it.word.orEmpty(),
                speaker = it.speaker ?: 0,
                startSec = it.start,
                endSec = it.end,
            )
        }
        val speaker = words.groupingBy { it.speaker }.eachCount().maxByOrNull { it.value }?.key
            ?: words.firstOrNull()?.speaker
            ?: 0
        return if (message.isFinal == true || message.speechFinal == true) {
            TranscriptionEvent.Final(transcript, speaker, words)
        } else {
            TranscriptionEvent.Partial(transcript, speaker, words)
        }
    }
}

@Serializable
internal data class DeepgramMessage(
    val type: String? = null,
    @SerialName("is_final") val isFinal: Boolean? = null,
    @SerialName("speech_final") val speechFinal: Boolean? = null,
    val channel: DeepgramChannel? = null,
)

@Serializable
internal data class DeepgramChannel(
    val alternatives: List<DeepgramAlternative>? = null,
)

@Serializable
internal data class DeepgramAlternative(
    val transcript: String? = null,
    val words: List<DeepgramWord>? = null,
)

@Serializable
internal data class DeepgramWord(
    val word: String? = null,
    @SerialName("punctuated_word") val punctuatedWord: String? = null,
    val speaker: Int? = null,
    val start: Double? = null,
    val end: Double? = null,
)
