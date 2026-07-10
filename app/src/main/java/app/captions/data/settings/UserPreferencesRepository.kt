package app.captions.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class UserPreferences(
    val transcriptionProvider: TranscriptionProviderPreference =
        TranscriptionProviderPreference.AUTO,
    val translationModel: String = ModelCatalog.DEFAULT_TRANSLATION_MODEL,
    val openRouterTranscriptionModel: String = ModelCatalog.DEFAULT_OPENROUTER_STT_MODEL,
)

@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val preferences: Flow<UserPreferences> =
        dataStore.data.map { prefs ->
            UserPreferences(
                transcriptionProvider = prefs[KEY_TRANSCRIPTION_PROVIDER]
                    ?.let { runCatching { TranscriptionProviderPreference.valueOf(it) }.getOrNull() }
                    ?: TranscriptionProviderPreference.AUTO,
                translationModel = prefs[KEY_TRANSLATION_MODEL]
                    ?.takeIf { it.isNotBlank() }
                    ?: ModelCatalog.DEFAULT_TRANSLATION_MODEL,
                openRouterTranscriptionModel = prefs[KEY_OPENROUTER_STT_MODEL]
                    ?.takeIf { it.isNotBlank() }
                    ?: ModelCatalog.DEFAULT_OPENROUTER_STT_MODEL,
            )
        }

    suspend fun setTranscriptionProvider(preference: TranscriptionProviderPreference) {
        dataStore.edit { it[KEY_TRANSCRIPTION_PROVIDER] = preference.name }
    }

    suspend fun setTranslationModel(modelId: String) {
        val trimmed = modelId.trim()
        dataStore.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(KEY_TRANSLATION_MODEL)
            } else {
                prefs[KEY_TRANSLATION_MODEL] = trimmed
            }
        }
    }

    suspend fun setOpenRouterTranscriptionModel(modelId: String) {
        val trimmed = modelId.trim()
        dataStore.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(KEY_OPENROUTER_STT_MODEL)
            } else {
                prefs[KEY_OPENROUTER_STT_MODEL] = trimmed
            }
        }
    }

    companion object {
        private val KEY_TRANSCRIPTION_PROVIDER = stringPreferencesKey("transcription_provider_pref")
        private val KEY_TRANSLATION_MODEL = stringPreferencesKey("translation_model")
        private val KEY_OPENROUTER_STT_MODEL = stringPreferencesKey("openrouter_stt_model")
    }
}
