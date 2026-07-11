package app.captions.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.captions.providers.openrouter.OpenRouterModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class ModelPreferencesRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var tempDir: File
    private lateinit var repository: ModelPreferencesRepository

    @Before
    fun setUp() {
        tempDir = File.createTempFile("model_prefs", null).apply {
            delete()
            mkdirs()
        }
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempDir, "prefs.preferences_pb")
        }
        repository = ModelPreferencesRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun `defaults to recommended models when unset`() = runTest {
        assertEquals(OpenRouterModels.DEFAULT_TRANSLATION, repository.translationModel.first())
        assertEquals(OpenRouterModels.DEFAULT_STT, repository.openRouterSttModel.first())
    }

    @Test
    fun `persists user selected models`() = runTest {
        repository.setTranslationModel("anthropic/claude-sonnet-4.6")
        repository.setOpenRouterSttModel("google/gemini-3.5-flash")

        assertEquals("anthropic/claude-sonnet-4.6", repository.translationModel.first())
        assertEquals("google/gemini-3.5-flash", repository.openRouterSttModel.first())
    }
}
