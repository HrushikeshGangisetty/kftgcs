package com.example.kftgcs.logging

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists uncaught-exception details to an app-private file.
 *
 * Why this exists: in RELEASE builds Timber plants no tree and ProGuard strips
 * all android.util.Log calls, so the global crash handler in [com.example.kftgcs.GCSApplication]
 * would otherwise leave no trace of a production crash on the device. This writes
 * a plain-text record to filesDir/crash_logs/ that survives the crash and can be
 * surfaced/exported later (e.g. from settings) for field debugging.
 *
 * This is only a LOCAL safety-net. Aggregate production crash reporting comes
 * from Google Play Console → Android vitals (which relies on the R8 mapping file;
 * see app/proguard-rules.pro) and/or Firebase Crashlytics if it is ever enabled.
 *
 * Every method is defensive: it must never throw, because it runs while the app
 * is already crashing.
 */
object CrashLogger {

    private const val DIR = "crash_logs"
    private const val FILE_PREFIX = "crash_"
    private const val MAX_FILES = 10

    @Volatile
    private var appContext: Context? = null

    /** Call once, early in Application.onCreate(), before the crash handler is armed. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Write a crash record to disk. Fast, synchronous and swallows all errors so a
     * logging failure can never become a secondary crash.
     */
    fun logCrash(thread: Thread, throwable: Throwable, extra: Map<String, Any?> = emptyMap()) {
        try {
            val dir = crashDir() ?: return
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

            val stack = StringWriter().also { sw ->
                throwable.printStackTrace(PrintWriter(sw))
            }.toString()

            val report = buildString {
                appendLine("==== KFT GCS CRASH ====")
                appendLine("Time:        $timestamp")
                appendLine("Thread:      ${thread.name}")
                appendLine("App version: ${appVersion()}")
                appendLine("Android:     ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Device:      ${Build.MANUFACTURER} ${Build.MODEL}")
                extra.forEach { (key, value) -> appendLine("$key: $value") }
                appendLine("---- Stack trace ----")
                append(stack)
            }

            File(dir, "$FILE_PREFIX$timestamp.txt").writeText(report)
            prune(dir)
        } catch (_: Throwable) {
            // Never let crash logging cause a secondary crash.
        }
    }

    /** Most-recent-first list of stored crash files. */
    fun getCrashFiles(): List<File> =
        crashDir()
            ?.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Full text of the most recent crash, or null if there are none. */
    fun getLastCrash(): String? =
        try {
            getCrashFiles().firstOrNull()?.readText()
        } catch (_: Throwable) {
            null
        }

    /** Delete all stored crash logs (e.g. after the user exports/acknowledges them). */
    fun clear() {
        try {
            getCrashFiles().forEach { it.delete() }
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun crashDir(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(MAX_FILES).forEach { it.delete() }
    }

    private fun appVersion(): String {
        val ctx = appContext ?: return "unknown"
        return try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${info.versionName} (${info.longVersionCodeCompat()})"
        } catch (_: Throwable) {
            "unknown"
        }
    }

    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
        else @Suppress("DEPRECATION") versionCode.toLong()
}
