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
            prefs[Keys.TRANSLATION_MODEL] ?: OpenRouterModels.DEFAULT_TRANSLATION
        }

    val openRouterSttModel: Flow<String> =
        dataStore.data.map { prefs ->
            prefs[Keys.OPENROUTER_STT_MODEL] ?: OpenRouterModels.DEFAULT_STT
        }

    suspend fun setTranslationModel(model: String) {
        dataStore.edit { prefs ->
            prefs[Keys.TRANSLATION_MODEL] = model.trim()
        }
    }

    suspend fun setOpenRouterSttModel(model: String) {
        dataStore.edit { prefs ->
            prefs[Keys.OPENROUTER_STT_MODEL] = model.trim()
        }
    }

    private object Keys {
        val TRANSLATION_MODEL = stringPreferencesKey("translation_model")
        val OPENROUTER_STT_MODEL = stringPreferencesKey("openrouter_stt_model")
    }
}
