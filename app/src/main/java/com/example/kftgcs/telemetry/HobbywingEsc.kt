package com.example.kftgcs.telemetry

/**
 * Hobbywing DroneCAN ESC setup, tunnelled through the FC with MAVLink CAN forwarding
 * (MAV_CMD_CAN_FORWARD + CAN_FRAME) - what the DroneCAN GUI Tool's "Hobbywing ESC Panel" does.
 *
 * Everything used here fits in a single CAN frame (<= 7 payload bytes), so there is no
 * multi-frame transfer / CRC handling:
 *  - com.hobbywing.esc.GetEscID (message 20013): broadcast payload [0]; every ESC answers with
 *    [node_id, throttle_channel].
 *  - Services, request payloads: SetID (210) [node_id, throttle_channel], SetBaud (211) [baud],
 *    SetDirection (213) [direction], SetReportingFrequency (214) [option, MSG_ID lo, hi, rate],
 *    SetThrottleSource (215) [source].
 * GetMajorConfig (242, current direction / source / rates) has a multi-frame response and is
 * deliberately not read.
 */

/** DroneCAN node ID the GCS talks as (the GUI tool's default; the FC is normally 10). */
internal const val HW_GCS_NODE_ID = 127

internal const val HW_GET_ESC_ID = 20013
internal const val HW_SET_ID = 210
internal const val HW_SET_BAUD = 211
internal const val HW_SET_DIRECTION = 213
internal const val HW_SET_REPORTING_FREQUENCY = 214
internal const val HW_SET_THROTTLE_SOURCE = 215

/** Data type IDs the FC is asked to forward (CAN_FILTER_MODIFY), ascending. */
internal val HW_FORWARDED_IDS = listOf(
    HW_SET_ID, HW_SET_BAUD, HW_SET_DIRECTION, HW_SET_REPORTING_FREQUENCY, HW_SET_THROTTLE_SOURCE, HW_GET_ESC_ID
)

private const val CAN_EFF_FLAG = 0x80000000u   // CAN_FRAME.id: extended-frame flag
private const val PRIORITY = 24u               // CANARD_TRANSFER_PRIORITY_LOW
private const val TAIL_SINGLE_FRAME = 0xC0     // start-of-transfer | end-of-transfer, toggle 0

/** A CAN frame as CAN_FRAME carries it: [id] with the extended flag, [data] incl. tail byte. */
internal data class HwCanFrame(val id: UInt, val data: List<UByte>)

/**
 * A single-frame transfer from an ESC: a GetEscID answer ([typeId] = [HW_GET_ESC_ID]) or a
 * response to one of our service requests ([typeId] = the service ID).
 */
internal data class HwEscReply(val srcNode: Int, val typeId: Int, val payload: List<Int>)

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

/** Decodes a forwarded CAN frame; null unless it is a GetEscID answer or a service response to us. */
internal fun hwParseReply(id: UInt, data: List<UByte>, len: Int): HwEscReply? {
    if (len !in 1..8 || data.size < len || (data[len - 1].toInt() and 0xE0) != TAIL_SINGLE_FRAME) return null
    val canId = id and 0x1FFFFFFFu
    val src = (canId and 0x7Fu).toInt()
    if (src == 0) return null // anonymous
    val payload = data.take(len - 1).map { it.toInt() }
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
