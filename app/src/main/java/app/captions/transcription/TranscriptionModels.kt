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
