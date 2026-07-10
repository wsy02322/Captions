package app.captions.transcription

/**
 * A single caption bubble. Speaker is an opaque int used only for color mapping —
 * never shown as a text label in the UI.
 */
data class CaptionLine(
    val id: String,
    val speaker: Int,
    val text: String,
    val isFinal: Boolean,
    val startedAtMs: Long,
    val updatedAtMs: Long = startedAtMs,
)

data class TranscriptionWord(
    val text: String,
    val speaker: Int,
    val startSec: Double? = null,
    val endSec: Double? = null,
)

sealed class TranscriptionEvent {
    data class Partial(val text: String, val speaker: Int, val words: List<TranscriptionWord>) :
        TranscriptionEvent()

    data class Final(val text: String, val speaker: Int, val words: List<TranscriptionWord>) :
        TranscriptionEvent()

    data class Error(val message: String, val cause: Throwable? = null) : TranscriptionEvent()

    data object Connected : TranscriptionEvent()
    data object Disconnected : TranscriptionEvent()
}

interface StreamingTranscriptionSession {
    suspend fun sendPcm16(frame: ByteArray)
    suspend fun close()
}

interface TranscriptionProvider {
    val displayName: String
    suspend fun openStreamingSession(
        apiKey: String,
        onEvent: (TranscriptionEvent) -> Unit,
    ): StreamingTranscriptionSession
}

/** Context carried across batch STT chunks for glossary + speaker continuity. */
data class TranscriptionContext(
    val priorText: String = "",
    val glossary: List<String> = emptyList(),
    val speakerDescriptions: Map<Int, String> = emptyMap(),
)

data class TranscriptionSegment(
    val speaker: Int,
    val lang: String? = null,
    val text: String,
    val words: List<TranscriptionWord> = emptyList(),
)

data class TranscriptionResult(
    val segments: List<TranscriptionSegment>,
    val speakerDescriptions: Map<Int, String> = emptyMap(),
)

/** Batch (chunked WAV) transcription — used by ElevenLabs Scribe and OpenRouter multimodal. */
interface BatchTranscriptionProvider {
    val displayName: String
    suspend fun transcribe(
        apiKey: String,
        wav: ByteArray,
        context: TranscriptionContext = TranscriptionContext(),
    ): TranscriptionResult
}

enum class SelectedProviderKind {
    DEEPGRAM_STREAMING,
    ELEVENLABS_BATCH,
    OPENROUTER_BATCH,
}
