package app.captions.pipeline

import android.media.projection.MediaProjection
import app.captions.audio.CaptureSource
import app.captions.audio.EnergyVadSegmenter
import app.captions.audio.MicrophoneCapture
import app.captions.audio.PlaybackCapture
import app.captions.audio.WavEncoder
import app.captions.data.keys.ApiKeyRepository
import app.captions.data.keys.ApiProvider
import app.captions.transcription.BatchTranscriptionProvider
import app.captions.transcription.CaptionLine
import app.captions.transcription.SelectedProviderKind
import app.captions.transcription.StreamingTranscriptionSession
import app.captions.transcription.TranscriptionContext
import app.captions.transcription.TranscriptionEvent
import app.captions.transcription.TranscriptionResult
import app.captions.transcription.deepgram.DeepgramTranscriptionProvider
import app.captions.transcription.elevenlabs.ElevenLabsTranscriptionProvider
import app.captions.transcription.openrouter.OpenRouterTranscriptionProvider
import app.captions.translation.OpenRouterTranslator
import app.captions.translation.TranslationRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val providerHint: String? = null,
    val captureSource: CaptureSource = CaptureSource.MICROPHONE,
    val targetLanguage: String = OpenRouterTranslator.DEFAULT_TARGET_LANGUAGE,
)

@Singleton
class CaptionSessionController @Inject constructor(
    private val microphoneCapture: MicrophoneCapture,
    private val playbackCapture: PlaybackCapture,
    private val deepgram: DeepgramTranscriptionProvider,
    private val elevenLabs: ElevenLabsTranscriptionProvider,
    private val openRouter: OpenRouterTranscriptionProvider,
    private val selector: TranscriptionProviderSelector,
    private val apiKeyRepository: ApiKeyRepository,
    private val translator: OpenRouterTranslator,
) {
    private val _state = MutableStateFlow(LiveCaptionState())
    val state: StateFlow<LiveCaptionState> = _state.asStateFlow()

    private val sessionRef = AtomicReference<StreamingTranscriptionSession?>(null)
    private val speakerMapper = SpeakerIdMapper()
    private var job: Job? = null
    private val translationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val translationMutex = Mutex()
    private val recentPairs = ArrayDeque<Pair<String, String>>()

    fun start(
        scope: CoroutineScope,
        source: CaptureSource = CaptureSource.MICROPHONE,
        mediaProjection: MediaProjection? = null,
    ) {
        if (job?.isActive == true) return
        job = scope.launch {
            speakerMapper.reset()
            recentPairs.clear()
            _state.update {
                it.copy(
                    status = CaptureStatus.Connecting,
                    errorMessage = null,
                    lines = emptyList(),
                    partial = null,
                    captureSource = source,
                    providerHint = null,
                )
            }

            val resolved = selector.resolve()
            if (resolved == null) {
                _state.update {
                    it.copy(
                        status = CaptureStatus.Error,
                        errorMessage = "Add a Deepgram, ElevenLabs, or OpenRouter API key in Settings",
                    )
                }
                return@launch
            }

            if (source == CaptureSource.PLAYBACK && mediaProjection == null) {
                _state.update {
                    it.copy(
                        status = CaptureStatus.Error,
                        errorMessage = "Screen/audio capture permission is required for app audio",
                    )
                }
                return@launch
            }

            _state.update { it.copy(providerHint = resolved.displayHint) }

            try {
                val frames: Flow<ByteArray> = when (source) {
                    CaptureSource.MICROPHONE -> microphoneCapture.pcm16Frames()
                    CaptureSource.PLAYBACK -> playbackCapture.pcm16Frames(mediaProjection!!)
                }

                when (resolved.kind) {
                    SelectedProviderKind.DEEPGRAM_STREAMING ->
                        runStreaming(resolved.apiKey, frames)

                    SelectedProviderKind.ELEVENLABS_BATCH ->
                        runBatch(elevenLabs, resolved.apiKey, frames)

                    SelectedProviderKind.OPENROUTER_BATCH ->
                        runBatch(openRouter, resolved.apiKey, frames)
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
                runCatching { mediaProjection?.stop() }
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
        if (_state.value.status != CaptureStatus.Error) {
            _state.update { it.copy(status = CaptureStatus.Idle, level = 0f, partial = null) }
        }
    }

    private suspend fun runStreaming(apiKey: String, frames: Flow<ByteArray>) {
        val session = deepgram.openStreamingSession(apiKey) { event ->
            when (event) {
                is TranscriptionEvent.Partial -> {
                    val mapped = speakerMapper.mapWords(event.words)
                    val speaker = mapped.firstOrNull()?.speaker ?: speakerMapper.mapSpeaker(event.speaker)
                    handleEvent(event.copy(speaker = speaker, words = mapped))
                }
                is TranscriptionEvent.Final -> {
                    val mapped = speakerMapper.mapWords(event.words)
                    val speaker = mapped.firstOrNull()?.speaker ?: speakerMapper.mapSpeaker(event.speaker)
                    handleEvent(event.copy(speaker = speaker, words = mapped))
                }
                else -> handleEvent(event)
            }
        }
        sessionRef.set(session)
        _state.update { it.copy(status = CaptureStatus.Listening) }
        frames.collect { frame ->
            _state.update { it.copy(level = pcmLevel(frame)) }
            session.sendPcm16(frame)
        }
    }

    private suspend fun CoroutineScope.runBatch(
        provider: BatchTranscriptionProvider,
        apiKey: String,
        frames: Flow<ByteArray>,
    ) {
        _state.update { it.copy(status = CaptureStatus.Listening) }
        handleEvent(TranscriptionEvent.Connected)
        val vad = EnergyVadSegmenter()
        var priorText = ""
        var speakerDescriptions = emptyMap<Int, String>()

        suspend fun processPcm(pcm: ByteArray) {
            if (pcm.isEmpty()) return
            val wav = WavEncoder.encodePcm16Mono(pcm)
            val result = provider.transcribe(
                apiKey = apiKey,
                wav = wav,
                context = TranscriptionContext(
                    priorText = priorText,
                    speakerDescriptions = speakerDescriptions,
                ),
            )
            emitBatchResult(result)
            if (result.speakerDescriptions.isNotEmpty()) {
                speakerDescriptions = speakerDescriptions + result.speakerDescriptions
            }
            val chunkText = result.segments.joinToString(" ") { it.text }.trim()
            if (chunkText.isNotEmpty()) {
                priorText = (priorText + " " + chunkText).takeLast(1200)
            }
        }

        frames.collect { frame ->
            if (!isActive) return@collect
            _state.update { it.copy(level = pcmLevel(frame)) }
            for (chunk in vad.push(frame)) {
                processPcm(chunk)
            }
        }
        vad.flush()?.let { processPcm(it) }
    }

    private fun emitBatchResult(result: TranscriptionResult) {
        for (segment in result.segments) {
            val mappedWords = if (segment.words.isNotEmpty()) {
                speakerMapper.mapWords(segment.words)
            } else {
                emptyList()
            }
            val speaker = mappedWords.firstOrNull()?.speaker
                ?: speakerMapper.mapSpeaker(segment.speaker)
            val text = mappedWords.joinToString(" ") { it.text }.ifBlank { segment.text }
            if (text.isBlank()) continue
            handleEvent(
                TranscriptionEvent.Final(
                    text = text,
                    speaker = speaker,
                    words = mappedWords,
                ),
            )
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
                            translation = existing?.translation,
                        ),
                    )
                }
            }

            is TranscriptionEvent.Final -> {
                val now = System.currentTimeMillis()
                var translatedLineId: String? = null
                var textForTranslation = event.text
                _state.update { state ->
                    val last = state.lines.lastOrNull()
                    val merge = last != null &&
                        last.isFinal &&
                        CaptionLineCoalescer.shouldMerge(
                            previousSpeaker = last.speaker,
                            previousText = last.text,
                            previousUpdatedAtMs = last.updatedAtMs,
                            nextSpeaker = event.speaker,
                            nextText = event.text,
                            nextAtMs = now,
                        )
                    if (merge) {
                        val prev = checkNotNull(last)
                        val joined = CaptionLineCoalescer.join(prev.text, event.text)
                        val updated = prev.copy(
                            text = joined,
                            updatedAtMs = now,
                            translation = null, // refresh translation for merged text
                        )
                        translatedLineId = updated.id
                        textForTranslation = joined
                        state.copy(
                            partial = null,
                            lines = state.lines.dropLast(1) + updated,
                        )
                    } else {
                        val line = CaptionLine(
                            id = state.partial?.id ?: UUID.randomUUID().toString(),
                            speaker = event.speaker,
                            text = event.text,
                            isFinal = true,
                            startedAtMs = state.partial?.startedAtMs ?: now,
                            updatedAtMs = now,
                        )
                        translatedLineId = line.id
                        textForTranslation = line.text
                        state.copy(
                            partial = null,
                            lines = (state.lines + line).takeLast(200),
                        )
                    }
                }
                translatedLineId?.let { enqueueTranslation(it, textForTranslation) }
            }
        }
    }

    private fun enqueueTranslation(lineId: String, text: String) {
        if (text.isBlank()) return
        translationScope.launch {
            translationMutex.withLock {
                val openRouterKey = apiKeyRepository.key(ApiProvider.OPENROUTER).first()
                if (openRouterKey.isNullOrBlank()) return@withLock
                val target = _state.value.targetLanguage
                runCatching {
                    translator.translate(
                        apiKey = openRouterKey,
                        request = TranslationRequest(
                            text = text,
                            targetLanguage = target,
                            priorPairs = recentPairs.toList(),
                        ),
                    )
                }.onSuccess { result ->
                    recentPairs.addLast(text to result.translatedText)
                    while (recentPairs.size > 8) recentPairs.removeFirst()
                    _state.update { state ->
                        state.copy(
                            lines = state.lines.map { line ->
                                if (line.id == lineId) {
                                    line.copy(translation = result.translatedText)
                                } else {
                                    line
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun pcmLevel(frame: ByteArray): Float {
        val rms = EnergyVadSegmenter.rms(frame)
        return (rms / 4000.0).toFloat().coerceIn(0f, 1f)
    }
}
