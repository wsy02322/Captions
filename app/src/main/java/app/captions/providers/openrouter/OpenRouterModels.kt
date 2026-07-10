package app.captions.providers.openrouter

data class ModelOption(
    val id: String,
    val label: String,
    val description: String,
)

object OpenRouterModels {
    const val DEFAULT_TRANSLATION = "google/gemini-3.1-pro-preview"
    const val DEFAULT_STT = "google/gemini-3.1-pro-preview"

    val RECOMMENDED_TRANSLATION: List<ModelOption> = listOf(
        ModelOption(
            id = "google/gemini-3.1-pro-preview",
            label = "Gemini 3.1 Pro",
            description = "Highest accuracy (default)",
        ),
        ModelOption(
            id = "anthropic/claude-opus-4.7",
            label = "Claude Opus 4.7",
            description = "Tone-sensitive / literary",
        ),
        ModelOption(
            id = "anthropic/claude-sonnet-4.6",
            label = "Claude Sonnet 4.6",
            description = "Balanced quality and cost",
        ),
        ModelOption(
            id = "google/gemini-3-flash-preview",
            label = "Gemini 3 Flash",
            description = "Low cost, fast",
        ),
    )

    val RECOMMENDED_STT: List<ModelOption> = listOf(
        ModelOption(
            id = "google/gemini-3.1-pro-preview",
            label = "Gemini 3.1 Pro",
            description = "Highest accuracy fallback (default)",
        ),
        ModelOption(
            id = "google/gemini-3.5-flash",
            label = "Gemini 3.5 Flash",
            description = "Balanced latency and cost",
        ),
        ModelOption(
            id = "google/gemini-3-flash-preview",
            label = "Gemini 3 Flash",
            description = "Low cost, fastest",
        ),
    )

    fun mergeOptions(recommended: List<ModelOption>, fetchedIds: List<String>): List<ModelOption> {
        val known = recommended.associateBy { it.id }.toMutableMap()
        for (id in fetchedIds) {
            if (id !in known) {
                known[id] = ModelOption(id = id, label = id, description = "From OpenRouter catalog")
            }
        }
        val ordered = recommended.mapNotNull { known.remove(it.id) }
        val extras = known.values.sortedBy { it.id }
        return ordered + extras
    }
}
