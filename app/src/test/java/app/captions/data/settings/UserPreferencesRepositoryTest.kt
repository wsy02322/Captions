package app.captions.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UserPreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())

    private fun createRepository(): UserPreferencesRepository {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile("user_prefs.preferences_pb")
        }
        return UserPreferencesRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `defaults match factory catalog`() = runTest {
        val repository = createRepository()
        val prefs = repository.preferences.first()
        assertEquals(TranscriptionProviderPreference.AUTO, prefs.transcriptionProvider)
        assertEquals(ModelCatalog.DEFAULT_TRANSLATION_MODEL, prefs.translationModel)
        assertEquals(ModelCatalog.DEFAULT_OPENROUTER_STT_MODEL, prefs.openRouterTranscriptionModel)
    }

    @Test
    fun `transcription provider preference persists`() = runTest {
        val repository = createRepository()
        repository.setTranscriptionProvider(TranscriptionProviderPreference.ELEVENLABS)
        assertEquals(
            TranscriptionProviderPreference.ELEVENLABS,
            repository.preferences.first().transcriptionProvider,
        )
    }

    @Test
    fun `translation model can be changed by user`() = runTest {
        val repository = createRepository()
        repository.setTranslationModel("anthropic/claude-sonnet-4.6")
        assertEquals(
            "anthropic/claude-sonnet-4.6",
            repository.preferences.first().translationModel,
        )
    }

    @Test
    fun `openrouter stt model accepts custom id`() = runTest {
        val repository = createRepository()
        repository.setOpenRouterTranscriptionModel("google/gemini-3.5-flash")
        assertEquals(
            "google/gemini-3.5-flash",
            repository.preferences.first().openRouterTranscriptionModel,
        )
    }

    @Test
    fun `blank model clears back to default`() = runTest {
        val repository = createRepository()
        repository.setTranslationModel("anthropic/claude-opus-4.7")
        repository.setTranslationModel("   ")
        assertEquals(
            ModelCatalog.DEFAULT_TRANSLATION_MODEL,
            repository.preferences.first().translationModel,
        )
    }
}
