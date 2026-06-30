package com.example.kftgcs.loganalysis.model

/**
 * Domain models produced by the offline log analysis engine.
 *
 * These are deliberately separate from the Room `TelemetryEntity` (which holds sparse, nullable
 * 5-second DB snapshots). Here every value is a concrete decoded sample synchronised by `TimeUS`.
 */

/** Severity of a [DiagnosticFlag]. INFO is for surfaced ERR/MSG records; WARNING/CRITICAL trip rules. */
enum class DiagnosticSeverity { INFO, WARNING, CRITICAL }

/**
 * A single anomaly detected by the [com.example.kftgcs.loganalysis.diagnostic.CrashAnalyzer].
 *
 * @param timeUs time since boot (microseconds) at which the condition tripped.
 * @param title short headline, e.g. "Motor / ESC failure".
 * @param description human-readable explanation of the deduced cause.
 * @param metrics the specific values that tripped the rule, for display (e.g. "Roll error" -> "27.4°").
 * @param severity see [DiagnosticSeverity].
 */
data class DiagnosticFlag(
    val timeUs: Long,
    val title: String,
    val description: String,
    val metrics: Map<String, String>,
    val severity: DiagnosticSeverity
)

/**
 * One decimated, synchronised snapshot of the flight for visual replay (target ~2–5 Hz).
 *
 * Fields hold the latest value of each source message as of [timeUs]; a value may be [Double.NaN]
 * (or null for [modeName]) if that message had not yet appeared in the log.
 */
data class ReplayFrame(
    val timeUs: Long,
    val lat: Double,        // POS.Lat (degrees)
    val lng: Double,        // POS.Lng (degrees)
    val altMsl: Double,     // POS.Alt (m, MSL)
    val relAlt: Double,     // POS.RelHomeAlt (m, relative to home)
    val roll: Double,       // ATT.Roll (deg)
    val pitch: Double,      // ATT.Pitch (deg)
    val yaw: Double,        // ATT.Yaw (deg)
    val groundSpeed: Double,// GPS.Spd (m/s)
    val volt: Double,       // BAT.Volt (V)
    val curr: Double,       // BAT.Curr (A)
    val nSats: Int,         // GPS.NSats (satellite count, -1 if unknown)
    val hdop: Double,       // GPS.HDop (horizontal dilution of precision)
    val escOutputs: List<Int>, // RCOU C1..Cn motor/ESC PWM outputs (µs), empty if unknown
    val modeNum: Int,       // MODE.ModeNum (-1 if unknown)
    val modeName: String?   // MODE.Mode resolved to a name, null if unknown
)
