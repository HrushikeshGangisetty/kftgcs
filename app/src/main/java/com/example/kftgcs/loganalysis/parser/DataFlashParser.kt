package com.example.kftgcs.loganalysis.parser

import com.example.kftgcs.utils.LogUtils
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * One decoded DataFlash message. Holds the payload [raw] bytes and the [def] describing them; the
 * typed accessors decode columns on demand so we never materialise per-column objects.
 *
 * Instances are short-lived — they are handed to a callback during streaming and must not be
 * retained, since the underlying [raw] array belongs to a single message and the parser moves on.
 */
class LogMessage(val def: MessageDefinition, val raw: ByteArray) {
    val name: String get() = def.name

    fun getDouble(col: String): Double = def.getDouble(raw, col)
    fun getLong(col: String): Long = def.getLong(raw, col)
    fun getString(col: String): String = def.getString(raw, col)
    fun has(col: String): Boolean = def.hasColumn(col)

    /** Time since boot in microseconds; 0 for messages without a `TimeUS` column (e.g. FMT). */
    fun timeUs(): Long = if (def.hasColumn(COL_TIME_US)) def.getLong(raw, COL_TIME_US) else 0L

    companion object {
        const val COL_TIME_US = "TimeUS"
    }
}

/** Outcome of a streaming parse pass. */
data class ParseSummary(
    /** True if the stream ended in the middle of a message — typical of a hard crash / brownout. */
    val truncated: Boolean,
    /** The `TimeUS` of the last successfully decoded message (0 if none). */
    val lastTimeUs: Long,
    /** Total number of data messages emitted to the consumer. */
    val messageCount: Long
)

/**
 * Streaming reader for ArduPilot DataFlash (`.bin`) logs.
 *
 * Memory-safe by construction: the file is read sequentially through a [BufferedInputStream] and
 * each message is delivered to a callback. Nothing but the (tiny) FMT dictionary is retained, so a
 * 150 MB log never lands in memory at once.
 *
 * Resilient to corruption: it resynchronises on the `0xA3 0x95` header, so a truncated or partially
 * corrupt tail (common after a crash) stops the pass cleanly rather than throwing.
 */
class DataFlashParser(private val file: File) {

    private val definitions = HashMap<Int, MessageDefinition>()

    /**
     * Stream the log, invoking [onMessage] for every decoded data message (FMT dictionary entries
     * are consumed internally, not delivered). [onProgress], if supplied, is called periodically
     * with a 0f..1f fraction based on bytes consumed.
     *
     * @return a [ParseSummary] describing how the pass ended.
     */
    fun parse(
        onProgress: ((Float) -> Unit)? = null,
        onMessage: (LogMessage) -> Unit
    ): ParseSummary {
        val total = file.length().coerceAtLeast(1L)
        var consumed = 0L
        var lastTimeUs = 0L
        var messageCount = 0L
        var truncated = false
        var progressTick = 0

        BufferedInputStream(FileInputStream(file), BUFFER_SIZE).use { input ->
            var prev = input.read()
            if (prev != -1) consumed++

            while (prev != -1) {
                if (prev != DataFlash.HEAD1) {
                    prev = input.read(); if (prev != -1) consumed++
                    continue
                }
                // Saw HEAD1 — check for HEAD2.
                val b2 = input.read(); if (b2 != -1) consumed++
                if (b2 == -1) break
                if (b2 != DataFlash.HEAD2) {
                    // Not a header; re-examine b2 as a potential next HEAD1 (handles 0xA3 0xA3 …).
                    prev = b2
                    continue
                }

                val type = input.read(); if (type != -1) consumed++
                if (type == -1) { truncated = true; break }

                if (type == DataFlash.FMT_TYPE) {
                    val payload = readFully(input, DataFlash.FMT_PAYLOAD_LEN)
                    if (payload == null) { truncated = true; break }
                    consumed += DataFlash.FMT_PAYLOAD_LEN
                    registerFormat(payload)
                } else {
                    val def = definitions[type]
                    if (def == null) {
                        // Unknown type with no dictionary entry: treat as a false sync and resync.
                        prev = input.read(); if (prev != -1) consumed++
                        continue
                    }
                    val payload = readFully(input, def.payloadLength)
                    if (payload == null) { truncated = true; break }
                    consumed += def.payloadLength

                    val msg = LogMessage(def, payload)
                    val t = msg.timeUs()
                    if (t > 0L) lastTimeUs = t
                    messageCount++
                    onMessage(msg)
                }

                if (onProgress != null && (++progressTick and PROGRESS_MASK) == 0) {
                    onProgress((consumed.toFloat() / total).coerceIn(0f, 1f))
                }

                prev = input.read(); if (prev != -1) consumed++
            }
        }

        onProgress?.invoke(1f)
        return ParseSummary(truncated, lastTimeUs, messageCount)
    }

    /** Parse a raw 86-byte FMT payload and register the resulting definition. */
    private fun registerFormat(payload: ByteArray) {
        try {
            var p = 0
            val type = payload[p].toInt() and 0xFF; p += 1
            val length = payload[p].toInt() and 0xFF; p += 1
            val name = ascii(payload, p, DataFlash.FMT_NAME_LEN); p += DataFlash.FMT_NAME_LEN
            val format = ascii(payload, p, DataFlash.FMT_FORMAT_LEN); p += DataFlash.FMT_FORMAT_LEN
            val columnsStr = ascii(payload, p, DataFlash.FMT_COLUMNS_LEN)
            val columns = if (columnsStr.isEmpty()) emptyList() else columnsStr.split(",")
            definitions[type] = MessageDefinition(type, length, name, format, columns)
        } catch (e: Exception) {
            LogUtils.e(TAG, "Skipping malformed FMT entry", e)
        }
    }

    /** Read exactly [count] bytes, or null if EOF is reached first (truncated message). */
    private fun readFully(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var read = 0
        while (read < count) {
            val r = input.read(buf, read, count - read)
            if (r == -1) return null
            read += r
        }
        return buf
    }

    private fun ascii(bytes: ByteArray, offset: Int, len: Int): String {
        val sb = StringBuilder()
        var i = offset
        val end = (offset + len).coerceAtMost(bytes.size)
        while (i < end) {
            val c = bytes[i].toInt() and 0xFF
            if (c == 0) break
            sb.append(c.toChar())
            i++
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "DataFlashParser"
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_MASK = 0x3FF // report progress every 1024 messages
    }
}
