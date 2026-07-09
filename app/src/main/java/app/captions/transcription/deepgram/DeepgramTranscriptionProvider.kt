package app.captions.transcription.deepgram

import app.captions.transcription.StreamingTranscriptionSession
import app.captions.transcription.TranscriptionEvent
import app.captions.transcription.TranscriptionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class DeepgramTranscriptionProvider @Inject constructor(
    private val client: OkHttpClient,
    @Named("deepgramBaseUrl") private val baseUrl: HttpUrl,
) : TranscriptionProvider {

    override val displayName: String = "Deepgram Nova-3"

    override suspend fun openStreamingSession(
        apiKey: String,
        onEvent: (TranscriptionEvent) -> Unit,
    ): StreamingTranscriptionSession {
        val url = baseUrl.newBuilder()
            .addPathSegment("v1")
            .addPathSegment("listen")
            .addQueryParameter("model", "nova-3")
            .addQueryParameter("language", "multi")
            .addQueryParameter("encoding", "linear16")
            .addQueryParameter("sample_rate", "16000")
            .addQueryParameter("channels", "1")
            .addQueryParameter("interim_results", "true")
            .addQueryParameter("punctuate", "true")
            .addQueryParameter("smart_format", "true")
            .addQueryParameter("diarize", "true")
            .addQueryParameter("diarize_model", "latest")
            .addQueryParameter("utterance_end_ms", "1200")
            .addQueryParameter("vad_events", "true")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $apiKey")
            .build()

        return suspendCancellableCoroutine { cont ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val outbound = Channel<ByteArray>(Channel.UNLIMITED)
            val opened = AtomicBoolean(false)

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (opened.compareAndSet(false, true)) {
                        onEvent(TranscriptionEvent.Connected)
                        scope.launch {
                            for (frame in outbound) {
                                if (!webSocket.send(frame.toByteString())) break
                            }
                        }
                        cont.resume(
                            DeepgramStreamingSession(
                                webSocket = webSocket,
                                outbound = outbound,
                                scope = scope,
                            ),
                        )
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    DeepgramMessageParser.parse(text)?.let(onEvent)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onEvent(TranscriptionEvent.Error(t.message ?: "Deepgram connection failed", t))
                    if (opened.compareAndSet(false, true)) {
                        cont.resumeWithException(t)
                    }
                    scope.cancel()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onEvent(TranscriptionEvent.Disconnected)
                    scope.cancel()
                }
            }

            val ws = client.newWebSocket(request, listener)
            cont.invokeOnCancellation {
                outbound.close()
                ws.close(1000, "cancelled")
                scope.cancel()
            }
        }
    }
}

private class DeepgramStreamingSession(
    private val webSocket: WebSocket,
    private val outbound: Channel<ByteArray>,
    private val scope: CoroutineScope,
) : StreamingTranscriptionSession {
    private val closed = AtomicBoolean(false)

    override suspend fun sendPcm16(frame: ByteArray) {
        if (closed.get() || frame.isEmpty()) return
        outbound.send(frame)
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        outbound.close()
        runCatching {
            webSocket.send("""{"type":"CloseStream"}""")
            webSocket.close(1000, "done")
        }
        scope.cancel()
    }
}
