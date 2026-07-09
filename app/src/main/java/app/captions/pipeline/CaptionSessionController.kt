package app.captions.pipeline

import app.captions.audio.MicrophoneCapture
import app.captions.data.keys.ApiKeyRepository
import app.captions.data.keys.ApiProvider
import app.captions.transcription.CaptionLine
import app.captions.transcription.StreamingTranscriptionSession
import app.captions.transcription.TranscriptionEvent
import app.captions.transcription.deepgram.DeepgramTranscriptionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

enum class CaptureStatus { Idle, Connecting, Listening, Error }

data class LiveCaptionState(
    val status: CaptureStatus = CaptureStatus.Idle,
    val lines: List<CaptionLine> = emptyList(),
    val partial: CaptionLine? = null,
    val errorMessage: String? = null,
    val level: Float = 0f,
)

@Singleton
class CaptionSessionController @Inject constructor(
    private val microphoneCapture: MicrophoneCapture,
    private val deepgram: DeepgramTranscriptionProvider,
    private val apiKeyRepository: ApiKeyRepository,
) {
    private val _state = MutableStateFlow(LiveCaptionState())
    val state: StateFlow<LiveCaptionState> = _state.asStateFlow()

    private val sessionRef = AtomicReference<StreamingTranscriptionSession?>(null)
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.update {
                it.copy(status = CaptureStatus.Connecting, errorMessage = null, lines = emptyList(), partial = null)
            }
            val apiKey = apiKeyRepository.key(ApiProvider.DEEPGRAM).first()
            if (apiKey.isNullOrBlank()) {
                _state.update {
                    it.copy(
                        status = CaptureStatus.Error,
                        errorMessage = "Add a Deepgram API key in Settings",
                    )
                }
                return@launch
            }

            try {
                val session = deepgram.openStreamingSession(apiKey) { event ->
                    handleEvent(event)
                }
                sessionRef.set(session)
                _state.update { it.copy(status = CaptureStatus.Listening) }

                microphoneCapture.pcm16Frames().collect { frame ->
                    if (!isActive) return@collect
                    _state.update { it.copy(level = pcmLevel(frame)) }
                    session.sendPcm16(frame)
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        status = CaptureStatus.Error,
                        errorMessage = t.message ?: "Failed to start transcription",
                    )
                }
            } finally {
                sessionRef.getAndSet(null)?.close()
                if (_state.value.status != CaptureStatus.Error) {
                    _state.update { it.copy(status = CaptureStatus.Idle, level = 0f, partial = null) }
                } else {
                    _state.update { it.copy(level = 0f, partial = null) }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        // Session close happens in finally of the start coroutine.
        if (_state.value.status != CaptureStatus.Error) {
            _state.update { it.copy(status = CaptureStatus.Idle, level = 0f, partial = null) }
        }
    }

    private fun handleEvent(event: TranscriptionEvent) {
        when (event) {
            TranscriptionEvent.Connected ->
                _state.update { it.copy(status = CaptureStatus.Listening, errorMessage = null) }

            TranscriptionEvent.Disconnected -> Unit

            is TranscriptionEvent.Error ->
                _state.update {
                    it.copy(status = CaptureStatus.Error, errorMessage = event.message)
                }

            is TranscriptionEvent.Partial -> {
                val now = System.currentTimeMillis()
                _state.update { state ->
                    val existing = state.partial
                    state.copy(
                        partial = CaptionLine(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            speaker = event.speaker,
                            text = event.text,
                            isFinal = false,
                            startedAtMs = existing?.startedAtMs ?: now,
                            updatedAtMs = now,
                        ),
                    )
                }
            }

            is TranscriptionEvent.Final -> {
                val now = System.currentTimeMillis()
                _state.update { state ->
                    val line = CaptionLine(
                        id = state.partial?.id ?: UUID.randomUUID().toString(),
                        speaker = event.speaker,
                        text = event.text,
                        isFinal = true,
                        startedAtMs = state.partial?.startedAtMs ?: now,
                        updatedAtMs = now,
                    )
                    state.copy(
                        partial = null,
                        lines = (state.lines + line).takeLast(200),
                    )
                }
            }
        }
    }

    private fun pcmLevel(frame: ByteArray): Float {
        if (frame.size < 2) return 0f
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < frame.size) {
            val sample = (frame[i].toInt() and 0xFF) or (frame[i + 1].toInt() shl 8)
            val signed = sample.toShort().toInt()
            sum += signed * signed
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = kotlin.math.sqrt(sum / count)
        return (rms / 4000.0).toFloat().coerceIn(0f, 1f)
    }
}
