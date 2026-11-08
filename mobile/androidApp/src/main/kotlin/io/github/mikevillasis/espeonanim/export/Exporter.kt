package io.github.mikevillasis.espeonanim.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Saving and sharing a finished `.espeon` package. */
object Exporter {
    const val MIME = "application/octet-stream"

    fun exportDir(context: Context): File = File(context.filesDir, "exports").apply { mkdirs() }

    /** Copies [file] to Downloads/EspeonAnim via MediaStore; returns the visible location. */
    fun saveToDownloads(context: Context, file: File): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EspeonAnim")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create a file in Downloads.")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) } }
                ?: throw IllegalStateException("Could not open the Downloads file for writing.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return "Downloads/EspeonAnim/${file.name}"
    }

    /** Chooser intent that shares the package file with any app (Nearby Share, Drive, mail ...). */
    fun shareIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Send EspeonAnim package").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
