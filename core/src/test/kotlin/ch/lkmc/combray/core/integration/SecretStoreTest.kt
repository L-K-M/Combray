package ch.lkmc.combray.core.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecretStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aTruncatedSecretFileReadsAsMissingInsteadOfCrashing() {
        val store = KeystoreSecretStore(context)
        // A write cut short by power loss: fewer bytes than the IV alone.
        File(context.noBackupFilesDir, "secrets/${SecretName.KARAKEEP_API_KEY.name}.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store.read(SecretName.KARAKEEP_API_KEY))
    }
}
