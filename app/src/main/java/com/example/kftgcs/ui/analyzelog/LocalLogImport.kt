package com.example.kftgcs.ui.analyzelog

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Copies a user-picked DataFlash log (via the Storage Access Framework) into the app's cache so the
 * analysis pipeline can treat it exactly like a USB-downloaded `.bin`.
 *
 * Runs the stream copy on [Dispatchers.IO] so the UI thread is never blocked. Throws [IOException] if
 * the source can't be opened or ends up empty (e.g. a corrupt pick or the SD card disconnecting
 * mid-copy) — callers should surface that as an error state.
 *
 * @param uri the content URI returned by the SAF picker.
 * @param fileName destination name within `cacheDir`; defaults to a fixed name so repeated imports
 *   reuse the same cache slot.
 * @return the cached [File].
 */
suspend fun importLogUriToCache(
    context: Context,
    uri: Uri,
    fileName: String = "imported_local_log.bin"
): File = withContext(Dispatchers.IO) {
    val out = File(context.cacheDir, fileName)
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { output ->
            input.copyTo(output)
        }
    } ?: throw IOException("Couldn't open the selected file.")

    if (out.length() == 0L) {
        out.delete()
        throw IOException("The selected file is empty or unreadable.")
    }
    out
}
