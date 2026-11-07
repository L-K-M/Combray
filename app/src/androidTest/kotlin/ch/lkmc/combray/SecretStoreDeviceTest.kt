package ch.lkmc.combray

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.integration.KeystoreSecretStore
import ch.lkmc.combray.core.integration.SecretName
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fixture-only, on the debug app's cleared data: exercise Android's real Keystore. */
@RunWith(AndroidJUnit4::class)
class SecretStoreDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun startWithoutAnAlias() {
        clearFixtureSecrets()
        // Simulated key loss, scoped by Android to this test app's UID.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("combray-integration-secrets")
    }

    @After
    fun clearFixtureSecrets() {
        val store = KeystoreSecretStore(context)
        store.delete(SecretName.KARAKEEP_API_KEY)
        store.delete(SecretName.HISTER_TOKEN)
    }

    @Test
    fun simultaneousFirstWritesRemainReadable() {
        val store = KeystoreSecretStore(context)
        writeTogether(store, store)
    }

    @Test
    fun separateStoreInstancesUseTheSameAliasSafely() {
        writeTogether(KeystoreSecretStore(context), KeystoreSecretStore(context))
    }

    private fun writeTogether(first: KeystoreSecretStore, second: KeystoreSecretStore) {
        val start = CyclicBarrier(3)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val one = workers.submit {
                start.await(10, TimeUnit.SECONDS)
                first.write(SecretName.KARAKEEP_API_KEY, "synthetic first credential")
            }
            val two = workers.submit {
                start.await(10, TimeUnit.SECONDS)
                second.write(SecretName.HISTER_TOKEN, "synthetic second credential")
            }
            start.await(10, TimeUnit.SECONDS)
            one.get(15, TimeUnit.SECONDS)
            two.get(15, TimeUnit.SECONDS)

            assertEquals("synthetic first credential", first.read(SecretName.KARAKEEP_API_KEY))
            assertEquals("synthetic second credential", second.read(SecretName.HISTER_TOKEN))
        } finally {
            workers.shutdownNow()
        }
    }
}
