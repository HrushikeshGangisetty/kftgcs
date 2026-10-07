package com.example.kftgcs.telemetry

/**
 * Hobbywing DroneCAN ESC setup, tunnelled through the FC with MAVLink CAN forwarding
 * (MAV_CMD_CAN_FORWARD + CAN_FRAME) - what the DroneCAN GUI Tool's "Hobbywing ESC Panel" does.
 *
 * Everything sent here fits in a single CAN frame (<= 7 payload bytes), and of a longer answer
 * only the first frame is used, so there is no multi-frame reassembly / CRC handling:
 *  - com.hobbywing.esc.GetEscID (message 20013): broadcast payload [0]; every ESC answers with
 *    [node_id, throttle_channel].
 *  - Services, request payloads: SetID (210) [node_id, throttle_channel], SetBaud (211) [baud],
 *    SetDirection (213) [direction], SetReportingFrequency (214) [option, MSG_ID lo, hi, rate],
 *    SetThrottleSource (215) [source].
 *  - GetMajorConfig (242): request [0]; the answer (7 bytes in the DSDL, but ESC firmware may
 *    send more) starts with the ESC's current direction, throttle source and message 1 / 2
 *    rates. The responses to 213 / 214 / 215 echo the value the ESC now holds too (see
 *    [hwMergeConfig]). Baud rate is never reported.
 *
 * Transfer IDs count per (data type, destination): an ESC drops a request whose ID is exactly
 * one ahead of the one it expects, which a counter shared between data types produces.
 */

/** DroneCAN node ID the GCS talks as (the GUI tool's default; the FC is normally 10). */
internal const val HW_GCS_NODE_ID = 127

internal const val HW_GET_ESC_ID = 20013
internal const val HW_SET_ID = 210
internal const val HW_SET_BAUD = 211
internal const val HW_SET_DIRECTION = 213
internal const val HW_SET_REPORTING_FREQUENCY = 214
internal const val HW_SET_THROTTLE_SOURCE = 215
internal const val HW_GET_MAJOR_CONFIG = 242

/** SetReportingFrequency rate codes "Reset to defaults" writes for messages 1-3: 50, 10, 10 Hz. */
internal val HW_DEFAULT_MSG_RATE_CODES = listOf(5, 7, 7)

/** Data type IDs the FC is asked to forward (CAN_FILTER_MODIFY), ascending. */
internal val HW_FORWARDED_IDS = listOf(
    HW_SET_ID, HW_SET_BAUD, HW_SET_DIRECTION, HW_SET_REPORTING_FREQUENCY, HW_SET_THROTTLE_SOURCE,
    HW_GET_MAJOR_CONFIG, HW_GET_ESC_ID
)

private const val CAN_EFF_FLAG = 0x80000000u   // CAN_FRAME.id: extended-frame flag
private const val PRIORITY = 24u               // CANARD_TRANSFER_PRIORITY_LOW
private const val TAIL_SINGLE_FRAME = 0xC0     // start-of-transfer | end-of-transfer, toggle 0
private const val TAIL_START = 0x80            // start-of-transfer
private const val TAIL_END = 0x40              // end-of-transfer
private const val TAIL_TOGGLE = 0x20

/** A CAN frame as CAN_FRAME carries it: [id] with the extended flag, [data] incl. tail byte. */
internal data class HwCanFrame(val id: UInt, val data: List<UByte>)

/**
 * The first (normally only) frame of a transfer from an ESC: a GetEscID answer ([typeId] =
 * [HW_GET_ESC_ID]) or a response to one of our service requests ([typeId] = the service ID).
 * [payload] of a multi-frame transfer is just the part its first frame carries.
 */
internal data class HwEscReply(val srcNode: Int, val typeId: Int, val payload: List<Int>)

/**
 * What an ESC has itself reported; a field is null / absent until the ESC reports it.
 * [msgRates]: message number (1-3) to SetReportingFrequency code (1 = 500 Hz … 9 = off).
 */
data class HwEscConfig(
    val ccw: Boolean? = null,
    val pwm: Boolean? = null,
    val msgRates: Map<Int, Int> = emptyMap(),
    /** SetBaud code the ESC last acknowledged. The ESC never reports its baud rate itself. */
    val ackedBaud: Int? = null
)

/**
 * [old] updated with what [reply] says the ESC holds, or null when the reply carries no setting.
 *  - GetMajorConfig: bool direction, bool throttle_source, uint6 throttle_channel, uint5
 *    led_status, uint3 led_color, uint4 MSG2_rate, uint4 MSG1_rate, uint16 angle, 2 reserved.
 *  - SetDirection [direction], SetThrottleSource [source],
 *    SetReportingFrequency [option, MSG_ID lo, hi, rate].
 */
internal fun hwMergeConfig(old: HwEscConfig, reply: HwEscReply): HwEscConfig? {
    val p = reply.payload
    return when {
        reply.typeId == HW_GET_MAJOR_CONFIG && p.size >= 3 -> old.copy(
            ccw = p[0] and 0x80 != 0,
            pwm = p[0] and 0x40 != 0,
            msgRates = old.msgRates + mapOf(1 to (p[2] and 0x0F), 2 to (p[2] shr 4 and 0x0F))
        )
        reply.typeId == HW_SET_DIRECTION && p.size == 1 -> old.copy(ccw = p[0] == 1)
        reply.typeId == HW_SET_THROTTLE_SOURCE && p.size == 1 -> old.copy(pwm = p[0] == 1)
        reply.typeId == HW_SET_REPORTING_FREQUENCY && p.size == 4 ->
            old.copy(msgRates = old.msgRates + ((p[1] or (p[2] shl 8)) - 20049 to p[3]))
        else -> null
    }
}

private fun withTail(payload: List<Int>, transferId: Int): List<UByte> =
    (payload + (TAIL_SINGLE_FRAME or (transferId and 0x1F))).map { it.toUByte() }

/** Broadcast asking every Hobbywing ESC for its node ID and throttle ID. */
internal fun hwGetEscIdFrame(transferId: Int) = HwCanFrame(
    id = CAN_EFF_FLAG or (PRIORITY shl 24) or (HW_GET_ESC_ID.toUInt() shl 8) or HW_GCS_NODE_ID.toUInt(),
    data = withTail(listOf(0), transferId)
)

/** Service request [serviceId] to the ESC at [destNode]; [payload] is at most 7 bytes. */
internal fun hwServiceFrame(serviceId: Int, destNode: Int, payload: List<Int>, transferId: Int) = HwCanFrame(
    id = CAN_EFF_FLAG or (PRIORITY shl 24) or (serviceId.toUInt() shl 16) or 0x8000u or
        (destNode.toUInt() shl 8) or 0x80u or HW_GCS_NODE_ID.toUInt(),
    data = withTail(payload, transferId)
)

/**
 * Decodes a forwarded CAN frame; null unless it is the first frame of a GetEscID answer or of a
 * service response to us.
 */
internal fun hwParseReply(id: UInt, data: List<UByte>, len: Int): HwEscReply? {
    if (len !in 1..8 || data.size < len) return null
    val tail = data[len - 1].toInt()
    if (tail and (TAIL_START or TAIL_TOGGLE) != TAIL_START) return null
    val canId = id and 0x1FFFFFFFu
    val src = (canId and 0x7Fu).toInt()
    if (src == 0) return null // anonymous
    // A multi-frame transfer starts with its 2-byte CRC.
    val payload = data.take(len - 1).drop(if (tail and TAIL_END == 0) 2 else 0).map { it.toInt() }
    return if ((canId and 0x80u) != 0u) {
        // Service frame: type | request flag | destination node
        val isResponseToUs = (canId and 0x8000u) == 0u && ((canId shr 8) and 0x7Fu).toInt() == HW_GCS_NODE_ID
        if (isResponseToUs) HwEscReply(src, ((canId shr 16) and 0xFFu).toInt(), payload) else null
    } else {
        // An ESC's answer is [its own node ID, throttle ID]; the 1-byte request is not one.
        if (((canId shr 8) and 0xFFFFu).toInt() == HW_GET_ESC_ID && payload.size == 2 && payload[0] == src)
            HwEscReply(src, HW_GET_ESC_ID, payload)
        else null
    }
}
