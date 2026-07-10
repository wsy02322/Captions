package app.captions.providers.openrouter

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class OpenRouterModelCatalog @Inject constructor(
    private val client: OkHttpClient,
    @Named("openRouterBaseUrl") private val baseUrl: HttpUrl,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchAudioModels(apiKey: String): List<String> =
        fetchModels(apiKey).filter { it.supportsAudio }.map { it.id }

    suspend fun fetchTextModels(apiKey: String): List<String> =
        fetchModels(apiKey).filter { it.supportsText }.map { it.id }

    internal fun parseModels(body: String): List<ParsedModel> {
        val response = json.decodeFromString<ModelsResponse>(body)
        return response.data.orEmpty().mapNotNull { model ->
            val id = model.id?.trim().orEmpty()
            if (id.isEmpty()) return@mapNotNull null
            val modalities = model.architecture?.inputModalities.orEmpty()
                .map { it.lowercase() }
                .toSet()
            ParsedModel(
                id = id,
                supportsAudio = "audio" in modalities,
                supportsText = modalities.isEmpty() || "text" in modalities,
            )
        }
    }

    private suspend fun fetchModels(apiKey: String): List<ParsedModel> {
        val request = Request.Builder()
            .url(baseUrl.resolve("api/v1/models")!!)
            .header("Authorization", "Bearer $apiKey")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("OpenRouter models failed (${response.code}): ${body.take(200)}")
            }
            return parseModels(body)
        }
    }

    internal data class ParsedModel(
        val id: String,
        val supportsAudio: Boolean,
        val supportsText: Boolean,
    )
}

@Serializable
private data class ModelsResponse(
    val data: List<ModelEntry>? = null,
)

@Serializable
private data class ModelEntry(
    val id: String? = null,
    val architecture: ModelArchitecture? = null,
)

@Serializable
private data class ModelArchitecture(
    @kotlinx.serialization.SerialName("input_modalities")
    val inputModalities: List<String>? = null,
)
