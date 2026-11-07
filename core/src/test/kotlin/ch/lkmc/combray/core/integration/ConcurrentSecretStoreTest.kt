package ch.lkmc.combray.core.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** A synthetic provider exercises the store's actual lookup, encryption and file paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConcurrentSecretStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var previousProvider: Provider? = null

    @Before
    fun installSyntheticKeystore() {
        currentKey.set(null)
        creations.set(0)
        lookups = CountDownLatch(2)
        raceLookups = false
        previousProvider = Security.getProvider(PROVIDER)
        Security.removeProvider(PROVIDER)
        Security.addProvider(object : Provider(PROVIDER, 1.0, "Synthetic test keys only") {
            init {
                put("KeyStore.$PROVIDER", TestKeyStore::class.java.name)
                put("KeyGenerator.AES", TestKeyGenerator::class.java.name)
            }
        })
    }

    @After
    fun restoreProvider() {
        Security.removeProvider(PROVIDER)
        previousProvider?.let(Security::addProvider)
    }

    @Test
    fun simultaneousFirstWritesUseOneKey() {
        val store = KeystoreSecretStore(context)
        assertConcurrentWrites(store, store)
    }

    @Test
    fun theAliasIsSharedAcrossStoreInstances() {
        assertConcurrentWrites(KeystoreSecretStore(context), KeystoreSecretStore(context))
    }

    @Test
    fun keyLossStillMakesOldSecretsMissingAndAllowsReplacement() {
        val store = KeystoreSecretStore(context)
        store.write(SecretName.KARAKEEP_API_KEY, "old value")
        currentKey.set(null)

        assertNull(store.read(SecretName.KARAKEEP_API_KEY))
        store.write(SecretName.HISTER_TOKEN, "replacement")

        assertEquals("replacement", store.read(SecretName.HISTER_TOKEN))
        assertEquals(2, creations.get())
    }

    private fun assertConcurrentWrites(first: KeystoreSecretStore, second: KeystoreSecretStore) {
        raceLookups = true
        val start = CyclicBarrier(3)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val one = workers.submit {
                start.await(5, TimeUnit.SECONDS)
                first.write(SecretName.KARAKEEP_API_KEY, "first value")
            }
            val two = workers.submit {
                start.await(5, TimeUnit.SECONDS)
                second.write(SecretName.HISTER_TOKEN, "second value")
            }
            start.await(5, TimeUnit.SECONDS)
            one.get(10, TimeUnit.SECONDS)
            two.get(10, TimeUnit.SECONDS)

            assertEquals("The shared alias must be created once", 1, creations.get())
            assertEquals("first value", first.read(SecretName.KARAKEEP_API_KEY))
            assertEquals("second value", second.read(SecretName.HISTER_TOKEN))
        } finally {
            workers.shutdownNow()
        }
    }

    // Public no-argument SPI classes are required for Provider's reflective construction.
    class TestKeyStore : KeyStoreSpi() {
        override fun engineGetKey(alias: String?, password: CharArray?): Key? {
            val found = currentKey.get()
            if (found == null && raceLookups) {
                lookups.countDown()
                // Without serialization both readers observe absence before either creates.
                // With serialization the first reader proceeds after this bounded wait.
                lookups.await(2, TimeUnit.SECONDS)
            }
            return found
        }

        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date? = null
        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) = unsupported()
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = unsupported()
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = unsupported()
        override fun engineDeleteEntry(alias: String?) = unsupported()
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(emptyList())
        override fun engineContainsAlias(alias: String?): Boolean = currentKey.get() != null
        override fun engineSize(): Int = if (currentKey.get() == null) 0 else 1
        override fun engineIsKeyEntry(alias: String?): Boolean = currentKey.get() != null
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }

    class TestKeyGenerator : KeyGeneratorSpi() {
        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) = Unit
        override fun engineGenerateKey(): SecretKey {
            val serial = creations.incrementAndGet().toByte()
            val key = SecretKeySpec(ByteArray(32) { serial }, "AES")
            currentKey.set(key)
            return key
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        val currentKey = AtomicReference<SecretKey?>()
        val creations = AtomicInteger()
        @Volatile var lookups = CountDownLatch(2)
        @Volatile var raceLookups = false
    }
}
