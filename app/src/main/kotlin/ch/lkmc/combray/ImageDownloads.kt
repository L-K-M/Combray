package ch.lkmc.combray

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import ch.lkmc.combray.core.policy.AddressDecision
import ch.lkmc.combray.core.policy.AddressPolicy
import ch.lkmc.combray.core.policy.HostNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** Whether Android accepted an explicit image download, not whether the file has arrived. */
enum class ImageDownloadResult { QUEUED, UNSUPPORTED_ADDRESS, UNAVAILABLE }

/**
 * Hands a user-chosen web image to Android's download service. Android owns progress, retries
 * and completion after the app closes. The user finds the file in Downloads.
 *
 * No browser cookies, integration credentials or page referrer leave the app. Consequently,
 * protected images may fail; data/blob images need engine support and are not offered here.
 * This is a new request for the address, not an export of Servo's cached image pixels.
 */
class ImageDownloads internal constructor(
    private val queue: (DownloadManager.Request) -> Long,
    private val description: String,
) {
    constructor(context: Context) : this(
        queue = context.applicationContext.getSystemService(DownloadManager::class.java)?.let { it::enqueue }
            ?: { throw IllegalStateException("Android downloads are unavailable") },
        description = context.getString(R.string.app_name),
    )

    suspend fun enqueue(address: String): ImageDownloadResult = withContext(Dispatchers.IO) {
        val url = webAddress(address) ?: return@withContext ImageDownloadResult.UNSUPPORTED_ADDRESS
        try {
            val name = fileName(url)
            val request = DownloadManager.Request(Uri.parse(url.toString()))
                .setTitle(name)
                .setDescription(description)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            // Do not supply a guessed MIME type: the server's response determines it.
            if (queue(request) < 0) ImageDownloadResult.UNAVAILABLE else ImageDownloadResult.QUEUED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Providers can be disabled or reject a request. Their errors can contain the full
            // address, so never log the exception or turn a rejected enqueue into "queued".
            ImageDownloadResult.UNAVAILABLE
        }
    }

    private fun webAddress(address: String): HttpUrl? {
        if (address.length > MAX_ADDRESS_CHARS || !HostNames.isWeb(address)) return null
        val navigation = AddressPolicy.interpret(address).primary as? AddressDecision.Navigate ?: return null
        val url = navigation.url.toHttpUrlOrNull() ?: return null
        if (url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty()) return null
        // Pass the canonical spelling to Android, never a second parser's reading of raw input.
        return url.newBuilder().fragment(null).build()
    }

    private fun fileName(url: HttpUrl): String {
        val original = url.pathSegments.last().substringAfterLast('/').substringAfterLast('\\')
        val extension = original.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            .takeIf { it in IMAGE_EXTENSIONS }
        val stem = original.substringBeforeLast('.', missingDelimiterValue = original)
            .filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
            .trim('.', '-', '_')
            .take(MAX_STEM_CHARS)
            .ifEmpty { "image" }
        // Never overwrite an earlier download. No query, fragment, path or server-supplied
        // Content-Disposition can choose the destination. Unknown formats get no fake suffix.
        return "$stem-${UUID.randomUUID()}" + (extension?.let { ".$it" } ?: "")
    }

    private companion object {
        const val MAX_ADDRESS_CHARS = 8 * 1024
        // A retained UTF-16 character uses at most three UTF-8 bytes; the suffix still fits 255.
        const val MAX_STEM_CHARS = 64
        val IMAGE_EXTENSIONS = setOf("avif", "bmp", "gif", "heic", "heif", "ico", "jpeg", "jpg", "png", "svg", "tif", "tiff", "webp")
    }
}
