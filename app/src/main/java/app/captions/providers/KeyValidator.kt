package app.captions.providers

import app.captions.data.keys.ApiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

enum class KeyValidationResult { VALID, INVALID, NETWORK_ERROR }

/**
 * Verifies API keys against cheap authenticated endpoints:
 * OpenRouter `GET /api/v1/key`, Deepgram `GET /v1/projects`, ElevenLabs `GET /v1/user`.
 */
@Singleton
class KeyValidator @Inject constructor(
    private val client: OkHttpClient,
    @Named("openRouterBaseUrl") private val openRouterBaseUrl: HttpUrl,
    @Named("deepgramBaseUrl") private val deepgramBaseUrl: HttpUrl,
    @Named("elevenLabsBaseUrl") private val elevenLabsBaseUrl: HttpUrl,
) {

    suspend fun validate(provider: ApiProvider, key: String): KeyValidationResult {
        val request = when (provider) {
            ApiProvider.OPENROUTER ->
                Request.Builder()
                    .url(openRouterBaseUrl.resolve("api/v1/key")!!)
                    .header("Authorization", "Bearer $key")
                    .build()

            ApiProvider.DEEPGRAM ->
                Request.Builder()
                    .url(deepgramBaseUrl.resolve("v1/projects")!!)
                    .header("Authorization", "Token $key")
                    .build()

            ApiProvider.ELEVENLABS ->
                Request.Builder()
                    .url(elevenLabsBaseUrl.resolve("v1/user")!!)
                    .header("xi-api-key", key)
                    .build()
        }
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> KeyValidationResult.VALID
                        response.code in setOf(401, 403) -> KeyValidationResult.INVALID
                        else -> KeyValidationResult.NETWORK_ERROR
                    }
                }
            } catch (_: IOException) {
                KeyValidationResult.NETWORK_ERROR
            }
        }
    }
}
