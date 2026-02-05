package com.example.aerogcsclone.utils

import android.util.Log
import com.example.aerogcsclone.BuildConfig

/**
 * Utility class for logging that can be disabled in production builds.
 * All debug log statements should use this class instead of Log directly.
 *
 * In release builds (BuildConfig.DEBUG = false), all logging is disabled.
 */
object LogUtils {

    // Set to false for production deployment to disable all logs
    private val LOGGING_ENABLED = BuildConfig.DEBUG

    fun d(tag: String, message: String) {
        if (LOGGING_ENABLED) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (LOGGING_ENABLED) Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        if (LOGGING_ENABLED) Log.w(tag, message)
    }

    fun e(tag: String, message: String) {
        if (LOGGING_ENABLED) Log.e(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        if (LOGGING_ENABLED) Log.e(tag, message, throwable)
    }

    fun v(tag: String, message: String) {
        if (LOGGING_ENABLED) Log.v(tag, message)
    }
}

