package com.example.kftgcs.loganalysis

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.kftgcs.utils.LogUtils
import java.io.File

/**
 * Shares a file via Android's share sheet, exposing it through the app's [FileProvider] (authority
 * `${packageName}.fileprovider`). Cached files (e.g. downloaded `.bin` logs, screen recordings) are
 * shareable thanks to the `cache-path` entry in `res/xml/file_provider_paths.xml`.
 *
 * Mirrors the FileProvider pattern already used by `FlightLogExporter.shareFile`.
 */
private fun shareFile(context: Context, file: File, mimeType: String, title: String) {
    if (!file.exists() || file.length() == 0L) {
        Toast.makeText(context, "File is no longer available", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        LogUtils.e("LogSharing", "Failed to share ${file.name}", e)
        Toast.makeText(context, "Couldn't share file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/** Share a downloaded DataFlash `.bin` log. */
fun shareLogFile(context: Context, file: File) =
    shareFile(context, file, "application/octet-stream", "Share flight log")

/** Share a recorded `.mp4` replay video. */
fun shareRecording(context: Context, file: File) =
    shareFile(context, file, "video/mp4", "Share replay recording")
