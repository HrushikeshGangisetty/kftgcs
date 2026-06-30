package com.example.kftgcs.loganalysis

import com.example.kftgcs.loganalysis.model.ReplayFrame
import com.example.kftgcs.loganalysis.parser.LogMessage

/**
 * Builds the decimated visual-replay timeline from the streaming parser.
 *
 * Strategy: hold the latest value of each source message (POS / ATT / GPS / BAT / MODE) and emit a
 * merged [ReplayFrame] onto a fixed time grid ([GRID_US]) — i.e. downsample to ~[1f / (GRID_US/1e6)] Hz.
 * This keeps memory bounded regardless of the source log rate (a 10-minute flight → ~2.4k frames at
 * 4 Hz) and gives the UI an evenly spaced, synchronised series to scrub through later.
 *
 * Feed every message via [onMessage]; call [build] once to get the finished list.
 */
class ReplayTimelineBuilder {

    private val frames = ArrayList<ReplayFrame>()

    // Latest held values, updated as messages stream in.
    private var lat = Double.NaN
    private var lng = Double.NaN
    private var altMsl = Double.NaN
    private var relAlt = Double.NaN
    private var roll = Double.NaN
    private var pitch = Double.NaN
    private var yaw = Double.NaN
    private var groundSpeed = Double.NaN
    private var volt = Double.NaN
    private var curr = Double.NaN
    private var nSats = -1
    private var hdop = Double.NaN
    private var escOutputs: List<Int> = emptyList()
    private var modeNum = -1
    private var modeName: String? = null

    private var nextGridUs = -1L
    private var seenPosition = false

    fun onMessage(msg: LogMessage) {
        when (msg.name) {
            "POS" -> {
                lat = msg.getDouble("Lat")
                lng = msg.getDouble("Lng")
                altMsl = msg.getDouble("Alt")
                relAlt = msg.getDouble("RelHomeAlt")
                seenPosition = true
            }
            "ATT" -> {
                roll = msg.getDouble("Roll")
                pitch = msg.getDouble("Pitch")
                yaw = msg.getDouble("Yaw")
            }
            "GPS" -> {
                // Primary GPS only (instance 0) when an instance column is present.
                if (!(msg.has("I") && msg.getLong("I") != 0L)) {
                    groundSpeed = msg.getDouble("Spd")
                    if (msg.has("NSats")) nSats = msg.getLong("NSats").toInt()
                    if (msg.has("HDop")) hdop = msg.getDouble("HDop")
                }
            }
            "BAT", "BATT" -> {
                if (!(msg.has("Inst") && msg.getLong("Inst") != 0L) &&
                    !(msg.has("Instance") && msg.getLong("Instance") != 0L)
                ) {
                    volt = msg.getDouble("Volt")
                    curr = msg.getDouble("Curr")
                }
            }
            "RCOU" -> {
                // Motor/ESC PWM outputs C1..C8 (covers quad through octo frames).
                val outs = ArrayList<Int>(8)
                for (i in 1..8) {
                    val col = "C$i"
                    if (msg.has(col)) outs.add(msg.getDouble(col).toInt())
                }
                if (outs.isNotEmpty()) escOutputs = outs
            }
            "MODE" -> {
                modeNum = msg.getLong("ModeNum").toInt()
                modeName = FlightMode.name(modeNum)
            }
            else -> return
        }

        val t = msg.timeUs()
        if (t <= 0L) return
        if (nextGridUs < 0L) nextGridUs = t + GRID_US

        // Emit a frame each time we cross a grid boundary (only once we have a position fix).
        if (t >= nextGridUs && seenPosition) {
            frames.add(snapshot(t))
            // Advance the grid past the current time (handles gaps without emitting empties).
            do {
                nextGridUs += GRID_US
            } while (t >= nextGridUs)
        }
    }

    fun build(): List<ReplayFrame> = frames

    private fun snapshot(t: Long) = ReplayFrame(
        timeUs = t,
        lat = lat,
        lng = lng,
        altMsl = altMsl,
        relAlt = relAlt,
        roll = roll,
        pitch = pitch,
        yaw = yaw,
        groundSpeed = groundSpeed,
        volt = volt,
        curr = curr,
        nSats = nSats,
        hdop = hdop,
        escOutputs = escOutputs,
        modeNum = modeNum,
        modeName = modeName
    )

    companion object {
        /** Grid spacing in microseconds. 250 ms → 4 Hz, within the 2–5 Hz target. */
        private const val GRID_US = 250_000L
    }
}

/**
 * ArduCopter flight-mode number → name mapping (AP 4.6.3). Used to resolve [ReplayFrame.modeName].
 * Mirrors the subset in `MavMode` but covers the full mode table for replay display.
 */
object FlightMode {
    private val NAMES: Map<Int, String> = mapOf(
        0 to "Stabilize",
        1 to "Acro",
        2 to "AltHold",
        3 to "Auto",
        4 to "Guided",
        5 to "Loiter",
        6 to "RTL",
        7 to "Circle",
        9 to "Land",
        11 to "Drift",
        13 to "Sport",
        14 to "Flip",
        15 to "AutoTune",
        16 to "PosHold",
        17 to "Brake",
        18 to "Throw",
        19 to "Avoid ADSB",
        20 to "Guided NoGPS",
        21 to "Smart RTL",
        22 to "FlowHold",
        23 to "Follow",
        24 to "ZigZag",
        25 to "SystemID",
        26 to "Heli Autorotate",
        27 to "Auto RTL"
    )

    fun name(modeNum: Int): String? = NAMES[modeNum] ?: if (modeNum >= 0) "Mode $modeNum" else null
}
