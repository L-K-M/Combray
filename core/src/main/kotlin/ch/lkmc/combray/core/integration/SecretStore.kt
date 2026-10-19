package ch.lkmc.combray.core.integration

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Integration credentials. Never shown again after saving, never in logs or page scripts. */
enum class SecretName { KARAKEEP_API_KEY, HISTER_TOKEN }

interface SecretStore {
    /** The secret, or null when unset or unreadable (for example after a Keystore key loss). */
    fun read(name: SecretName): String?

    fun write(name: SecretName, value: String)

    fun delete(name: SecretName)
}

/**
 * Credentials bound to the server they were entered for, stored as "<fingerprint>\n<secret>".
 * Configuration writes the credential and the service row in two steps; if the process dies in
 * between, a credential read back for another server counts as missing and is never sent there.
 */
internal class ServiceCredentials(private val secrets: SecretStore) {
    suspend fun read(name: SecretName, fingerprint: String): String? = withContext(Dispatchers.IO) {
        val stored = secrets.read(name) ?: return@withContext null
        if (stored.substringBefore(SEPARATOR) != fingerprint) return@withContext null
        stored.substringAfter(SEPARATOR, missingDelimiterValue = "").ifEmpty { null }
    }

    /** False when the device could not store it (Keystore or storage failure). */
    suspend fun write(name: SecretName, fingerprint: String, secret: String): Boolean = withContext(Dispatchers.IO) {
        try {
            secrets.write(name, fingerprint + SEPARATOR + secret)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "storing ${name.name} failed: ${failure.javaClass.name}")
            false
        }
    }

    suspend fun delete(name: SecretName) = withContext(Dispatchers.IO) { secrets.delete(name) }

    private companion object {
        const val SEPARATOR = '\n'
        const val LOG_TAG = "CombraySecrets"
    }
}

/**
 * AES-GCM with a non-exportable Android Keystore key; ciphertext in no-backup app storage. A lost
 * key (device reset of the Keystore) makes secrets unreadable: callers then ask for them again,
 * without touching history or other data.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val directory = File(context.noBackupFilesDir, "secrets").apply { mkdirs() }

    override fun read(name: SecretName): String? {
        val file = file(name)
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            // A write cut short (power loss) leaves less than an IV and a tag: unreadable.
            if (bytes.size < IV_BYTES + TAG_BITS / Byte.SIZE_BITS) throw GeneralSecurityException("truncated")
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (unreadable: GeneralSecurityException) {
            unreadable(name)
        } catch (unreadable: ProviderException) {
            // Android Keystore reports some failures (a lost key being recreated) as runtime errors.
            unreadable(name)
        } catch (unreadable: IOException) {
            unreadable(name)
        }
    }

    private fun unreadable(name: SecretName): String? {
        Log.w(LOG_TAG, "stored ${name.name} is unreadable; it must be entered again")
        return null
    }

    override fun write(name: SecretName, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val temporary = File(directory, "${name.name}.tmp")
        temporary.writeBytes(sealed)
        if (!temporary.renameTo(file(name))) throw IllegalStateException("could not store ${name.name}")
    }

    override fun delete(name: SecretName) {
        file(name).delete()
    }

    private fun file(name: SecretName) = File(directory, "${name.name}.bin")

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val LOG_TAG = "CombraySecrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "combray-integration-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
    }
}
