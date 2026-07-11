package app.captions.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.captions.providers.openrouter.OpenRouterModels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val translationModel: Flow<String> =
        dataStore.data.map { prefs ->
            prefs[Keys.TRANSLATION_MODEL]?.takeIf { it.isNotBlank() }
                ?: OpenRouterModels.DEFAULT_TRANSLATION
        }

    val openRouterSttModel: Flow<String> =
        dataStore.data.map { prefs ->
            prefs[Keys.OPENROUTER_STT_MODEL]?.takeIf { it.isNotBlank() }
                ?: OpenRouterModels.DEFAULT_STT
        }

    suspend fun setTranslationModel(model: String) {
        val trimmed = model.trim()
        dataStore.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(Keys.TRANSLATION_MODEL)
            } else {
                prefs[Keys.TRANSLATION_MODEL] = trimmed
            }
        }
    }

    suspend fun setOpenRouterSttModel(model: String) {
        val trimmed = model.trim()
        dataStore.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(Keys.OPENROUTER_STT_MODEL)
            } else {
                prefs[Keys.OPENROUTER_STT_MODEL] = trimmed
            }
        }
    }

    private object Keys {
        val TRANSLATION_MODEL = stringPreferencesKey("translation_model")
        val OPENROUTER_STT_MODEL = stringPreferencesKey("openrouter_stt_model")
    }
}
