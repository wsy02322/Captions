package app.captions.pipeline

import app.captions.data.keys.ApiKeyRepository
import app.captions.data.keys.ApiProvider
import app.captions.data.keys.KeyCrypto
import app.captions.data.settings.TranscriptionProviderPreference
import app.captions.data.settings.UserPreferencesRepository
import app.captions.transcription.SelectedProviderKind
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

private class PlainKeyCrypto : KeyCrypto {
    override fun encrypt(plainText: String): String =
        "enc:" + Base64.getEncoder().encodeToString(plainText.toByteArray())

    override fun decrypt(encoded: String): String =
        String(Base64.getDecoder().decode(encoded.removePrefix("enc:")))
}

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionProviderSelectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())

    private lateinit var apiKeys: ApiKeyRepository
    private lateinit var prefs: UserPreferencesRepository
    private lateinit var selector: TranscriptionProviderSelector

    private fun setup() {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile("sel.preferences_pb")
        }
        apiKeys = ApiKeyRepository(dataStore, PlainKeyCrypto())
        prefs = UserPreferencesRepository(dataStore)
        selector = TranscriptionProviderSelector(apiKeys, prefs)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `canStart when any provider key present`() {
        assertTrue(TranscriptionProviderSelector.canStart(true, false, false))
        assertTrue(TranscriptionProviderSelector.canStart(false, true, false))
        assertTrue(TranscriptionProviderSelector.canStart(false, false, true))
        assertFalse(TranscriptionProviderSelector.canStart(false, false, false))
    }

    @Test
    fun `priority constants exist for streaming and batch`() {
        assertEquals("DEEPGRAM_STREAMING", SelectedProviderKind.DEEPGRAM_STREAMING.name)
        assertEquals("ELEVENLABS_BATCH", SelectedProviderKind.ELEVENLABS_BATCH.name)
        assertEquals("OPENROUTER_BATCH", SelectedProviderKind.OPENROUTER_BATCH.name)
    }

    @Test
    fun `auto prefers deepgram when all keys present`() = runTest {
        setup()
        apiKeys.setKey(ApiProvider.DEEPGRAM, "dg")
        apiKeys.setKey(ApiProvider.ELEVENLABS, "xi")
        apiKeys.setKey(ApiProvider.OPENROUTER, "or")
        prefs.setTranscriptionProvider(TranscriptionProviderPreference.AUTO)

        val resolved = selector.resolve()
        assertEquals(SelectedProviderKind.DEEPGRAM_STREAMING, resolved?.kind)
    }

    @Test
    fun `user preference pins elevenlabs over deepgram`() = runTest {
        setup()
        apiKeys.setKey(ApiProvider.DEEPGRAM, "dg")
        apiKeys.setKey(ApiProvider.ELEVENLABS, "xi")
        prefs.setTranscriptionProvider(TranscriptionProviderPreference.ELEVENLABS)

        val resolved = selector.resolve()
        assertEquals(SelectedProviderKind.ELEVENLABS_BATCH, resolved?.kind)
    }

    @Test
    fun `missing preferred key falls back to auto order`() = runTest {
        setup()
        apiKeys.setKey(ApiProvider.OPENROUTER, "or")
        prefs.setTranscriptionProvider(TranscriptionProviderPreference.DEEPGRAM)

        val resolved = selector.resolve()
        assertEquals(SelectedProviderKind.OPENROUTER_BATCH, resolved?.kind)
    }

    @Test
    fun `no keys returns null`() = runTest {
        setup()
        assertNull(selector.resolve())
    }
}
