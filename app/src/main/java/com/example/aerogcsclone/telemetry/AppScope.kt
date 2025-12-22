package com.example.aerogcsclone.Telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-level coroutine scope for background operations
 * Used by TelemetryRepository and other long-running operations
 *
 * Using Dispatchers.Default for telemetry processing - optimized for CPU-bound work
 * Network operations (connection management) should explicitly use Dispatchers.IO
 */
object AppScope : CoroutineScope {
    override val coroutineContext = SupervisorJob() + Dispatchers.Default
}
