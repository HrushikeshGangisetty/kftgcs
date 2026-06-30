package com.example.kftgcs.loganalysis.parser

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Constants and type machinery for decoding ArduPilot DataFlash (`.bin`) logs (targeting AP 4.6.3).
 *
 * A DataFlash log is a stream of self-describing messages. Every message on disk is:
 * `HEAD1 (0xA3)` + `HEAD2 (0x95)` + `type (uint8)` + payload. The payload layout for each `type`
 * is described by a leading `FMT` message (type 128). All multi-byte values are little-endian.
 *
 * See [DataFlashParser] for the streaming reader that uses these definitions.
 */
object DataFlash {
    const val HEAD1: Int = 0xA3
    const val HEAD2: Int = 0x95

    /** Message type id of the `FMT` (format) message — the self-describing dictionary entry. */
    const val FMT_TYPE: Int = 128

    /** 3-byte message header: HEAD1, HEAD2, type. Payload length = FMT.length - HEADER_LEN. */
    const val HEADER_LEN: Int = 3

    /**
     * Fixed on-disk layout of an `FMT` message payload (the dictionary entry describing one type):
     * `type:uint8(1) + length:uint8(1) + name:char[4] + format:char[16] + columns:char[64]` = 86 B.
     * This is parsed by hand in [DataFlashParser] to bootstrap the dictionary (chicken-and-egg).
     */
    const val FMT_NAME_LEN: Int = 4
    const val FMT_FORMAT_LEN: Int = 16
    const val FMT_COLUMNS_LEN: Int = 64
    const val FMT_PAYLOAD_LEN: Int = 2 + FMT_NAME_LEN + FMT_FORMAT_LEN + FMT_COLUMNS_LEN // 86
}

/**
 * The binary field types used in ArduPilot DataFlash format strings, with the on-disk [size] in
 * bytes and the decoding semantics. The numeric "scaled" types (`c/C/e/E`) store a fixed-point
 * integer that must be divided by 100; `L` stores a 1e7-scaled lat/lng integer.
 *
 * `decodeDouble` returns the physical (scaled) value; `decodeLong` returns the raw integer
 * (no scaling); `decodeString` reads NUL-trimmed ASCII for the char types.
 */
enum class FieldType(val code: Char, val size: Int) {
    INT8('b', 1),
    UINT8('B', 1),
    INT16('h', 2),
    UINT16('H', 2),
    INT32('i', 4),
    UINT32('I', 4),
    FLOAT('f', 4),
    DOUBLE('d', 8),
    INT64('q', 8),
    UINT64('Q', 8),
    CHAR4('n', 4),
    CHAR16('N', 16),
    CHAR64('Z', 64),
    MODE('M', 1),
    INT16_SCALED('c', 2),   // value = raw / 100
    UINT16_SCALED('C', 2),  // value = raw / 100
    INT32_SCALED('e', 4),   // value = raw / 100
    UINT32_SCALED('E', 4),  // value = raw / 100
    LATLON('L', 4),         // value = raw * 1e-7 (degrees)
    INT16_ARRAY('a', 64);   // int16_t[32]

    private fun buffer(raw: ByteArray): ByteBuffer =
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

    /** Decode this field as a physical (scaled) double. Char/array types return 0.0. */
    fun decodeDouble(raw: ByteArray, offset: Int): Double {
        val bb = buffer(raw)
        return when (this) {
            INT8 -> raw[offset].toDouble()
            UINT8, MODE -> (raw[offset].toInt() and 0xFF).toDouble()
            INT16 -> bb.getShort(offset).toDouble()
            UINT16 -> (bb.getShort(offset).toInt() and 0xFFFF).toDouble()
            INT32 -> bb.getInt(offset).toDouble()
            UINT32 -> (bb.getInt(offset).toLong() and 0xFFFFFFFFL).toDouble()
            FLOAT -> bb.getFloat(offset).toDouble()
            DOUBLE -> bb.getDouble(offset)
            INT64 -> bb.getLong(offset).toDouble()
            UINT64 -> bb.getLong(offset).toULong().toDouble()
            INT16_SCALED -> bb.getShort(offset).toDouble() / 100.0
            UINT16_SCALED -> (bb.getShort(offset).toInt() and 0xFFFF).toDouble() / 100.0
            INT32_SCALED -> bb.getInt(offset).toDouble() / 100.0
            UINT32_SCALED -> (bb.getInt(offset).toLong() and 0xFFFFFFFFL).toDouble() / 100.0
            LATLON -> bb.getInt(offset).toDouble() * 1e-7
            CHAR4, CHAR16, CHAR64, INT16_ARRAY -> 0.0
        }
    }

    /** Decode this field as a raw integer (no scaling applied). Char/array types return 0. */
    fun decodeLong(raw: ByteArray, offset: Int): Long {
        val bb = buffer(raw)
        return when (this) {
            INT8 -> raw[offset].toLong()
            UINT8, MODE -> (raw[offset].toInt() and 0xFF).toLong()
            INT16, INT16_SCALED -> bb.getShort(offset).toLong()
            UINT16, UINT16_SCALED -> (bb.getShort(offset).toInt() and 0xFFFF).toLong()
            INT32, INT32_SCALED, LATLON -> bb.getInt(offset).toLong()
            UINT32, UINT32_SCALED -> bb.getInt(offset).toLong() and 0xFFFFFFFFL
            FLOAT -> bb.getFloat(offset).toLong()
            DOUBLE -> bb.getDouble(offset).toLong()
            INT64, UINT64 -> bb.getLong(offset)
            CHAR4, CHAR16, CHAR64, INT16_ARRAY -> 0L
        }
    }

    /** Decode this field as a NUL-trimmed ASCII string (intended for the char[] types). */
    fun decodeString(raw: ByteArray, offset: Int): String {
        val end = (offset + size).coerceAtMost(raw.size)
        val sb = StringBuilder()
        var i = offset
        while (i < end) {
            val c = raw[i].toInt() and 0xFF
            if (c == 0) break
            sb.append(c.toChar())
            i++
        }
        return sb.toString()
    }

    companion object {
        private val byCode: Map<Char, FieldType> = entries.associateBy { it.code }

        /** Returns the [FieldType] for a format char, or null if unrecognised. */
        fun fromCode(c: Char): FieldType? = byCode[c]
    }
}

/**
 * A parsed `FMT` dictionary entry: the on-disk layout of one message [type].
 *
 * @param type message type id (the byte following the 0xA3 0x95 header).
 * @param length total on-disk message length including the 3-byte header.
 * @param name short message name, e.g. "ATT", "RCOU".
 * @param format the format string, one char per column (see [FieldType]).
 * @param columns the column names, parallel to [format].
 *
 * Column byte offsets within the payload (header excluded) and the per-column [FieldType] are
 * precomputed for O(1) lookup by name during the hot parse loop.
 */
class MessageDefinition(
    val type: Int,
    val length: Int,
    val name: String,
    val format: String,
    val columns: List<String>
) {
    /** Payload size in bytes (message length minus the 3-byte header). */
    val payloadLength: Int = length - DataFlash.HEADER_LEN

    private val fieldTypes: List<FieldType?> = format.map { FieldType.fromCode(it) }

    /** column name -> byte offset within the payload. */
    private val offsets: Map<String, Int>

    /** column name -> field type. */
    private val typesByName: Map<String, FieldType>

    init {
        val off = HashMap<String, Int>()
        val typ = HashMap<String, FieldType>()
        var cursor = 0
        val n = minOf(columns.size, fieldTypes.size)
        for (i in 0 until n) {
            val ft = fieldTypes[i] ?: break // unknown format char: can't compute further offsets
            val col = columns[i]
            off[col] = cursor
            typ[col] = ft
            cursor += ft.size
        }
        offsets = off
        typesByName = typ
    }

    fun hasColumn(col: String): Boolean = offsets.containsKey(col)

    private inline fun <T> decode(raw: ByteArray, col: String, block: (FieldType, Int) -> T, default: T): T {
        val off = offsets[col] ?: return default
        val ft = typesByName[col] ?: return default
        if (off + ft.size > raw.size) return default
        return block(ft, off)
    }

    fun getDouble(raw: ByteArray, col: String): Double =
        decode(raw, col, { ft, off -> ft.decodeDouble(raw, off) }, Double.NaN)

    fun getLong(raw: ByteArray, col: String): Long =
        decode(raw, col, { ft, off -> ft.decodeLong(raw, off) }, 0L)

    fun getString(raw: ByteArray, col: String): String =
        decode(raw, col, { ft, off -> ft.decodeString(raw, off) }, "")
}
