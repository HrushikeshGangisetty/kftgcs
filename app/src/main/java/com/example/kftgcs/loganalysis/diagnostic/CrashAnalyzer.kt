package com.example.kftgcs.loganalysis.diagnostic

import com.example.kftgcs.loganalysis.model.DiagnosticFlag
import com.example.kftgcs.loganalysis.model.DiagnosticSeverity
import com.example.kftgcs.loganalysis.parser.LogMessage
import com.example.kftgcs.loganalysis.parser.ParseSummary
import kotlin.math.abs

/**
 * Streaming crash diagnostic rule engine.
 *
 * Fed one [LogMessage] at a time via [onMessage] (in roughly chronological order), it maintains only
 * a small rolling window of the quantities each rule needs — never the full history — and emits a
 * [DiagnosticFlag] when a rule trips. Call [finish] once the stream ends to run end-of-log rules
 * (e.g. brownout) and obtain the time-sorted result.
 *
 * Rules implemented (ArduPilot 4.6.3 / EKF3):
 *  1. Motor / ESC failure   — attitude error sustained while a motor output is pegged at max.
 *  2. Battery cell collapse — large voltage drop without a matching current spike.
 *  3. FC brownout           — 5 V rail collapses just before the log abruptly ends.
 *  4. Severe vibration      — sustained high VIBE, or continuously incrementing IMU clipping.
 *  5. Compass / EKF failure — magnetic variance spike paired with a position-error spike.
 * Plus informational surfacing of ERR and MSG records.
 */
class CrashAnalyzer {

    private val flags = mutableListOf<DiagnosticFlag>()
    private val lastFiredUs = HashMap<String, Long>()
    private var infoCount = 0

    // --- Rule 1 (motor/ESC) state ---
    private var attErrStartUs = -1L
    private var lastRcouMax = 0.0
    private var lastRcouChannel = 0
    private var lastRcouTimeUs = -1L

    // --- Rule 2 (battery) state: rolling 500 ms window of (t, volt, curr) ---
    private val batWindow = ArrayDeque<Triple<Long, Double, Double>>()

    // --- Rule 3 (brownout) state ---
    private var lastVcc = Double.NaN
    private var lastVccTimeUs = 0L
    private var minVcc = Double.NaN

    // --- Rule 4 (vibration) state ---
    private var vibeStartUs = -1L
    private var prevClipTotal = -1L
    private var consecClipInc = 0

    // --- Rule 5 (compass/EKF) state ---
    private val peWindow = ArrayDeque<Pair<Long, Double>>() // (t, XKF1.PE)
    private var lastSv = Double.NaN

    fun onMessage(msg: LogMessage) {
        when (msg.name) {
            "ATT" -> onAtt(msg)
            "RCOU" -> onRcou(msg)
            "BAT", "BATT" -> onBat(msg)
            "POWR" -> onPowr(msg)
            "VIBE" -> onVibe(msg)
            "XKF1" -> onXkf1(msg)
            "XKF4" -> onXkf4(msg)
            "ERR" -> onErr(msg)
            "MSG" -> onMsg(msg)
        }
    }

    /** Run end-of-log rules and return all flags sorted by time. */
    fun finish(summary: ParseSummary): List<DiagnosticFlag> {
        // Rule 3: FC brownout — Vcc below threshold right as the log abruptly ends.
        if (summary.truncated && !lastVcc.isNaN() && lastVcc < POWR_VCC_MIN) {
            add(
                DiagnosticFlag(
                    timeUs = lastVccTimeUs,
                    title = "Flight controller brownout",
                    description = "The 5 V rail (POWR.Vcc) fell below ${POWR_VCC_MIN} V and the log " +
                        "ends abruptly immediately afterwards — consistent with the autopilot " +
                        "browning out / resetting mid-flight (power supply or BEC failure).",
                    metrics = mapOf(
                        "Last Vcc" to fmtV(lastVcc),
                        "Min Vcc" to fmtV(minVcc),
                        "Log ended" to "truncated (mid-message)"
                    ),
                    severity = DiagnosticSeverity.CRITICAL
                )
            )
        }
        return flags.sortedBy { it.timeUs }
    }

    // ---------------------------------------------------------------------------------------------

    private fun onAtt(msg: LogMessage) {
        val roll = msg.getDouble("Roll")
        val desRoll = msg.getDouble("DesRoll")
        if (roll.isNaN() || desRoll.isNaN()) return
        val t = msg.timeUs()
        val err = abs(roll - desRoll)

        if (err > ROLL_ERR_DEG) {
            if (attErrStartUs < 0) attErrStartUs = t
            val sustainedUs = t - attErrStartUs
            val rcouRecent = lastRcouTimeUs >= 0 && (t - lastRcouTimeUs) <= RCOU_RECENT_US
            if (sustainedUs > ROLL_ERR_SUSTAIN_US && rcouRecent && lastRcouMax >= RCOU_MAX_PWM) {
                fire(
                    ruleId = "rule1_motor",
                    timeUs = t,
                    title = "Motor / ESC failure",
                    description = "The aircraft could not hold attitude: roll deviated from the " +
                        "demanded angle by more than ${ROLL_ERR_DEG.toInt()}° for over " +
                        "${ROLL_ERR_SUSTAIN_US / 1000} ms while a motor output was pegged at maximum " +
                        "— the classic signature of a failed motor, ESC or propeller.",
                    metrics = mapOf(
                        "Roll error" to fmtDeg(err),
                        "Roll / DesRoll" to "${fmtDeg(roll)} / ${fmtDeg(desRoll)}",
                        "Sustained" to "${sustainedUs / 1000} ms",
                        "Pegged output" to "C$lastRcouChannel = ${lastRcouMax.toInt()} µs"
                    ),
                    severity = DiagnosticSeverity.CRITICAL
                )
            }
        } else {
            attErrStartUs = -1L
        }
    }

    private fun onRcou(msg: LogMessage) {
        var max = 0.0
        var ch = 0
        for (i in 1..14) {
            val col = "C$i"
            if (!msg.has(col)) continue
            val v = msg.getDouble(col)
            if (!v.isNaN() && v > max) {
                max = v
                ch = i
            }
        }
        lastRcouMax = max
        lastRcouChannel = ch
        lastRcouTimeUs = msg.timeUs()
    }

    private fun onBat(msg: LogMessage) {
        // Only consider the main battery (instance 0) if an instance column is present.
        if (msg.has("Inst") && msg.getLong("Inst") != 0L) return
        if (msg.has("Instance") && msg.getLong("Instance") != 0L) return
        val volt = msg.getDouble("Volt")
        val curr = msg.getDouble("Curr")
        if (volt.isNaN()) return
        val t = msg.timeUs()

        batWindow.addLast(Triple(t, volt, if (curr.isNaN()) 0.0 else curr))
        while (batWindow.isNotEmpty() && t - batWindow.first().first > BAT_WINDOW_US) {
            batWindow.removeFirst()
        }
        val oldest = batWindow.first()
        val voltDrop = oldest.second - volt
        val currDelta = (if (curr.isNaN()) 0.0 else curr) - oldest.third
        // Voltage collapsed but current did NOT spike to match → cell/connection failure, not sag.
        if (voltDrop > BAT_VOLT_DROP && currDelta <= BAT_CURR_TOL_A) {
            fire(
                ruleId = "rule2_battery",
                timeUs = t,
                title = "Battery cell collapse",
                description = "Pack voltage dropped ${fmtV(voltDrop)} within ${BAT_WINDOW_US / 1000} ms " +
                    "without a corresponding current spike (${fmtA(currDelta)} change). A voltage " +
                    "collapse under stable load points to a failed cell or a high-resistance / loose " +
                    "battery connection rather than ordinary sag.",
                metrics = mapOf(
                    "Voltage drop" to fmtV(voltDrop),
                    "Volt now" to fmtV(volt),
                    "Current change" to fmtA(currDelta)
                ),
                severity = DiagnosticSeverity.CRITICAL
            )
        }
    }

    private fun onPowr(msg: LogMessage) {
        val vcc = msg.getDouble("Vcc")
        if (vcc.isNaN()) return
        lastVcc = vcc
        lastVccTimeUs = msg.timeUs()
        minVcc = if (minVcc.isNaN()) vcc else minOf(minVcc, vcc)
    }

    private fun onVibe(msg: LogMessage) {
        val t = msg.timeUs()
        val vx = msg.getDouble("VibeX")
        val vy = msg.getDouble("VibeY")
        val vz = msg.getDouble("VibeZ")
        val maxVibe = listOf(vx, vy, vz).filter { !it.isNaN() }.maxOrNull()
        if (maxVibe != null) {
            if (maxVibe > VIBE_MAX) {
                if (vibeStartUs < 0) vibeStartUs = t
                if (t - vibeStartUs > VIBE_SUSTAIN_US) {
                    fire(
                        ruleId = "rule4_vibe",
                        timeUs = t,
                        title = "Severe vibration",
                        description = "Vibration exceeded ${VIBE_MAX.toInt()} m/s² on at least one axis " +
                            "for over ${VIBE_SUSTAIN_US / 1000} ms. Sustained vibration this high blinds " +
                            "the accelerometers and corrupts the EKF's attitude/position estimate.",
                        metrics = mapOf(
                            "VibeX" to fmtVibe(vx),
                            "VibeY" to fmtVibe(vy),
                            "VibeZ" to fmtVibe(vz)
                        ),
                        severity = DiagnosticSeverity.CRITICAL
                    )
                }
            } else {
                vibeStartUs = -1L
            }
        }

        // IMU clipping: cumulative counters that keep climbing mean the accelerometers are
        // physically bottoming out.
        val c0 = msg.getLong("Clip0")
        val c1 = msg.getLong("Clip1")
        val c2 = msg.getLong("Clip2")
        val total = c0 + c1 + c2
        if (prevClipTotal >= 0) {
            if (total > prevClipTotal) consecClipInc++ else consecClipInc = 0
            if (consecClipInc >= CLIP_CONSEC_SAMPLES) {
                fire(
                    ruleId = "rule4_clip",
                    timeUs = t,
                    title = "IMU clipping (severe vibration)",
                    description = "Accelerometer clipping counters (VIBE.Clip0/1/2) incremented on " +
                        "$consecClipInc consecutive samples — the IMUs are saturating against their " +
                        "measurement range, a sign of destructive physical vibration.",
                    metrics = mapOf(
                        "Clip0" to "$c0",
                        "Clip1" to "$c1",
                        "Clip2" to "$c2"
                    ),
                    severity = DiagnosticSeverity.WARNING
                )
            }
        }
        prevClipTotal = total
    }

    private fun onXkf1(msg: LogMessage) {
        // Use core 0 only when a core column is present, to avoid mixing EKF instances.
        if (msg.has("C") && msg.getLong("C") != 0L) return
        if (msg.has("Core") && msg.getLong("Core") != 0L) return
        val pe = msg.getDouble("PE")
        if (pe.isNaN()) return
        val t = msg.timeUs()
        peWindow.addLast(t to pe)
        while (peWindow.isNotEmpty() && t - peWindow.first().first > XKF_WINDOW_US) {
            peWindow.removeFirst()
        }
        evaluateEkf(t)
    }

    private fun onXkf4(msg: LogMessage) {
        if (msg.has("C") && msg.getLong("C") != 0L) return
        if (msg.has("Core") && msg.getLong("Core") != 0L) return
        val sv = msg.getDouble("SV")
        if (!sv.isNaN()) lastSv = sv
        evaluateEkf(msg.timeUs())
    }

    /** Rule 5: magnetic variance high AND position error spiking within the rolling window. */
    private fun evaluateEkf(t: Long) {
        if (lastSv.isNaN() || lastSv <= XKF4_SV_MAX) return
        if (peWindow.size < 2) return
        val peValues = peWindow.map { it.second }
        val peSpike = (peValues.max() - peValues.min())
        if (peSpike > XKF1_PE_SPIKE) {
            fire(
                ruleId = "rule5_ekf",
                timeUs = t,
                title = "Compass / EKF failure",
                description = "Magnetic field variance (XKF4.SV = ${fmt2(lastSv)}) exceeded " +
                    "$XKF4_SV_MAX while EKF position error (XKF1.PE) spiked by ${fmt2(peSpike)} m. " +
                    "This pattern indicates magnetic interference (e.g. a high-tension wire) or a bad " +
                    "compass calibration corrupting the navigation solution.",
                metrics = mapOf(
                    "Mag variance SV" to fmt2(lastSv),
                    "PE spike" to "${fmt2(peSpike)} m"
                ),
                severity = DiagnosticSeverity.CRITICAL
            )
        }
    }

    private fun onErr(msg: LogMessage) {
        val subsys = msg.getLong("Subsys")
        val ecode = msg.getLong("ECode")
        // ECode 0 is typically a "cleared/resolved" marker; surface only active error codes.
        if (ecode == 0L) return
        addInfo(
            DiagnosticFlag(
                timeUs = msg.timeUs(),
                title = "Subsystem error",
                description = "ArduPilot logged a subsystem error (ERR). Subsys $subsys, ECode $ecode.",
                metrics = mapOf("Subsys" to "$subsys", "ECode" to "$ecode"),
                severity = DiagnosticSeverity.WARNING
            )
        )
    }

    private fun onMsg(msg: LogMessage) {
        val text = msg.getString("Message").trim()
        if (text.isEmpty()) return
        addInfo(
            DiagnosticFlag(
                timeUs = msg.timeUs(),
                title = "Log message",
                description = text,
                metrics = emptyMap(),
                severity = DiagnosticSeverity.INFO
            )
        )
    }

    // ---------------------------------------------------------------------------------------------

    private fun fire(
        ruleId: String,
        timeUs: Long,
        title: String,
        description: String,
        metrics: Map<String, String>,
        severity: DiagnosticSeverity
    ) {
        val last = lastFiredUs[ruleId]
        if (last != null && timeUs - last < COOLDOWN_US) return // suppress duplicate within episode
        lastFiredUs[ruleId] = timeUs
        add(DiagnosticFlag(timeUs, title, description, metrics, severity))
    }

    private fun add(flag: DiagnosticFlag) {
        flags.add(flag)
    }

    private fun addInfo(flag: DiagnosticFlag) {
        if (infoCount >= MAX_INFO_FLAGS) return
        infoCount++
        flags.add(flag)
    }

    private fun fmtDeg(v: Double) = String.format("%.1f°", v)
    private fun fmtV(v: Double) = String.format("%.2f V", v)
    private fun fmtA(v: Double) = String.format("%.1f A", v)
    private fun fmtVibe(v: Double) = if (v.isNaN()) "—" else String.format("%.1f m/s²", v)
    private fun fmt2(v: Double) = String.format("%.2f", v)

    companion object {
        // Rule 1
        private const val ROLL_ERR_DEG = 20.0
        private const val ROLL_ERR_SUSTAIN_US = 1_000_000L
        private const val RCOU_MAX_PWM = 1950.0
        private const val RCOU_RECENT_US = 500_000L
        // Rule 2
        private const val BAT_WINDOW_US = 500_000L
        private const val BAT_VOLT_DROP = 3.0
        private const val BAT_CURR_TOL_A = 5.0 // current may rise this much and still count as "stable"
        // Rule 3
        private const val POWR_VCC_MIN = 4.5
        // Rule 4
        private const val VIBE_MAX = 30.0
        private const val VIBE_SUSTAIN_US = 1_000_000L
        private const val CLIP_CONSEC_SAMPLES = 3
        // Rule 5
        private const val XKF4_SV_MAX = 0.5
        private const val XKF1_PE_SPIKE = 1.0 // metres of position-error swing within the window
        private const val XKF_WINDOW_US = 1_000_000L
        // General
        private const val COOLDOWN_US = 5_000_000L
        private const val MAX_INFO_FLAGS = 50
    }
}
