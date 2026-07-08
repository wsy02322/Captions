package app.captions.data.keys

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/** Reversible fake so tests can assert what is persisted without Android Keystore. */
private class FakeKeyCrypto : KeyCrypto {
    override fun encrypt(plainText: String): String =
        "enc:" + Base64.getEncoder().encodeToString(plainText.toByteArray())

    override fun decrypt(encoded: String): String =
        String(Base64.getDecoder().decode(encoded.removePrefix("enc:")))
}

@OptIn(ExperimentalCoroutinesApi::class)
class ApiKeyRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())

    private fun createRepository(): Pair<ApiKeyRepository, androidx.datastore.core.DataStore<Preferences>> {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile("settings.preferences_pb")
        }
        return ApiKeyRepository(dataStore, FakeKeyCrypto()) to dataStore
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `set and read back key round-trips`() = runTest {
        val (repository, _) = createRepository()

        repository.setKey(ApiProvider.OPENROUTER, "sk-or-v1-secret")

        assertEquals("sk-or-v1-secret", repository.key(ApiProvider.OPENROUTER).first())
    }

    @Test
    fun `keys are stored encrypted not in plain text`() = runTest {
        val (repository, dataStore) = createRepository()

        repository.setKey(ApiProvider.ELEVENLABS, "xi-secret")

        val rawStored = dataStore.data.first().asMap().values.map { it.toString() }
        assertTrue(rawStored.isNotEmpty())
        rawStored.forEach { assertNotEquals("xi-secret", it) }
        rawStored.forEach { assertTrue(it.startsWith("enc:")) }
    }

    @Test
    fun `providers are stored independently`() = runTest {
        val (repository, _) = createRepository()

        repository.setKey(ApiProvider.OPENROUTER, "key-a")
        repository.setKey(ApiProvider.ELEVENLABS, "key-b")

        assertEquals("key-a", repository.key(ApiProvider.OPENROUTER).first())
        assertEquals("key-b", repository.key(ApiProvider.ELEVENLABS).first())
    }

    @Test
    fun `blank key clears stored value`() = runTest {
        val (repository, _) = createRepository()

        repository.setKey(ApiProvider.OPENROUTER, "key-a")
        repository.setKey(ApiProvider.OPENROUTER, "   ")

        assertNull(repository.key(ApiProvider.OPENROUTER).first())
    }

    @Test
    fun `key value is trimmed before storage`() = runTest {
        val (repository, _) = createRepository()

        repository.setKey(ApiProvider.OPENROUTER, "  sk-or-v1-secret \n")

        assertEquals("sk-or-v1-secret", repository.key(ApiProvider.OPENROUTER).first())
    }

    @Test
    fun `unset key returns null`() = runTest {
        val (repository, _) = createRepository()

        assertNull(repository.key(ApiProvider.ELEVENLABS).first())
    }
}
