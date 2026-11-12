package ch.lkmc.combray.download

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Private staging, then an Android Downloads entry which stays hidden until the copy is complete. */
internal class VideoFiles(context: Context) : VideoDownloadFiles {
    private val resolver = context.applicationContext.contentResolver
    private val packageName = context.packageName
    private val directory = File(context.noBackupFilesDir, "video-downloads")
    private val initialization = Mutex()
    private var initialized = false

    override suspend fun prepare(): File = withContext(Dispatchers.IO) {
        initialization.withLock {
            if (!initialized) {
                // Only our unfinished exports, never completed downloads. This also recovers a
                // process killed during the final copy. The same lock fences the first new export.
                val selection = "${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ? AND " +
                    "${MediaStore.Downloads.IS_PENDING} = 1 AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
                val unfinished = resolver.query(COLLECTION, arrayOf(MediaStore.Downloads._ID), selection, arrayOf(packageName, "$PREFIX%.mp4"), null)
                    ?: throw IOException("Downloads unavailable")
                unfinished.use { cursor ->
                    while (cursor.moveToNext()) resolver.delete(ContentUris.withAppendedId(COLLECTION, cursor.getLong(0)), null, null)
                }
                directory.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.startsWith(PREFIX) && !file.delete()) throw IOException("Video staging cleanup failed")
                }
                initialized = true
            }
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Video staging unavailable")
            File(directory, "$PREFIX${UUID.randomUUID()}.mp4")
        }
    }

    override suspend fun publish(file: File): String = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() == 0L) throw IOException("Empty video")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(COLLECTION, values) ?: throw IOException("Downloads unavailable")
        try {
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Downloads unavailable")
            output.use { destination -> file.inputStream().use { it.copyTo(destination) } }
            val published = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            if (resolver.update(uri, published, null, null) != 1) throw IOException("Video publication failed")
            file.name
        } catch (error: Exception) {
            // Never leave a partial public download or mistake a provider failure for success.
            resolver.delete(uri, null, null)
            throw error
        }
    }

    override suspend fun discard(file: File) = withContext(Dispatchers.IO) {
        // A failed deletion remains private and is retried before the next process's download.
        file.delete()
        Unit
    }

    private companion object {
        const val PREFIX = "combray-video-"
        val COLLECTION = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }
}
