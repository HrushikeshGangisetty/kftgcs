package com.example.kftgcs.telemetry

import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.FileTransferProtocol
import com.example.kftgcs.utils.LogUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A minimal MAVLink FTP (`FILE_TRANSFER_PROTOCOL`) client — enough to browse the flight controller's
 * SD card and download a DataFlash `.bin` directly, bypassing the MAVLink LOG_REQUEST log index.
 *
 * This exists because our custom FC returns an *empty* log index over LOG_REQUEST_LIST even though the
 * logs are present in `/APM/LOGS` on the SD card (an SD reader sees them fine). MAVLink FTP is the
 * directory-aware path every other GCS (Mission Planner, QGC) uses to reach those files.
 *
 * Transport is the same as the existing log download: it collects [FileTransferProtocol] frames off
 * [frames] and sends requests via [send]. Larger reads use the BurstReadFile opcode with the same
 * resume-on-stall watchdog as `MavlinkTelemetryRepository.downloadLog`.
 *
 * @param frames the shared inbound MAVLink frame flow (`connection.mavFrame`).
 * @param send sends one FTP message to the FC (wraps `connection.trySendUnsignedV2`).
 * @param targetSystem/@param targetComponent the FC's ids.
 */
class MavlinkFtpClient(
    private val frames: SharedFlow<MavFrame<out MavMessage<*>>>,
    private val send: suspend (FileTransferProtocol) -> Unit,
    private val targetSystem: UByte,
    private val targetComponent: UByte
) {
    /** One entry from a directory listing. */
    data class FtpEntry(val name: String, val sizeBytes: Long, val isDir: Boolean)

    private var seq = 0

    /**
     * List [dirPath] (e.g. `/APM/LOGS`). Pages through the directory until the FC NAKs with EOF.
     * Throws [IOException] on timeout or a non-EOF error (e.g. directory not found).
     */
    suspend fun listDirectory(dirPath: String): List<FtpEntry> {
        val entries = ArrayList<FtpEntry>()
        var offset = 0L
        while (true) {
            val resp = request(frame(OP_LIST, offset = offset, data = dirPath.toByteArray(Charsets.US_ASCII)))
                ?: throw IOException("FTP ListDirectory timed out for $dirPath")
            if (resp.opcode == OP_NAK) {
                val err = resp.errorCode()
                if (err == ERR_EOF) break
                if (err == ERR_FILE_NOT_FOUND) throw IOException("Directory not found: $dirPath")
                throw IOException("FTP ListDirectory failed (err=$err) for $dirPath")
            }
            val added = parseListEntries(resp.data(), entries)
            if (added == 0) break // guard against an FC that never NAKs
            offset += added
        }
        return entries
    }

    /**
     * Download the file at [filePath] over MAVLink FTP, returning its raw bytes and reporting progress
     * `0f..1f`. Opens read-only, burst-reads with a resume-on-stall watchdog, then terminates the
     * session. Throws [IOException] on open failure, timeout, or a stalled transfer.
     */
    suspend fun downloadFile(filePath: String, onProgress: (Float) -> Unit): ByteArray {
        val open = request(frame(OP_OPEN_RO, data = filePath.toByteArray(Charsets.US_ASCII)))
            ?: throw IOException("FTP OpenFileRO timed out for $filePath")
        if (open.opcode == OP_NAK) {
            throw IOException("FTP OpenFileRO failed (err=${open.errorCode()}) for $filePath")
        }
        val session = open.session
        val fileSize = if (open.size >= 4) le32(open.data(), 0) else 0L

        try {
            return burstRead(session, fileSize, onProgress)
        } finally {
            // Best-effort session cleanup so the FC frees the slot.
            withTimeoutOrNull(1000L) {
                request(frame(OP_TERMINATE, session = session), timeoutMs = 800L, retries = 1)
            }
        }
    }

    private suspend fun burstRead(
        session: Int,
        fileSize: Long,
        onProgress: (Float) -> Unit
    ): ByteArray = coroutineScope {
        var buffer = ByteArray(if (fileSize in 1..MAX_ALLOC) fileSize.toInt() else 64 * 1024)
        val hwm = AtomicInteger(0)
        val lastDataAt = AtomicLong(System.currentTimeMillis())
        val finished = CompletableDeferred<Unit>()

        val collector = launch {
            frames.collect { f ->
                val m = f.message
                if (m !is FileTransferProtocol) return@collect
                val r = Resp(m.payload)
                if (r.session != session) return@collect

                if (r.opcode == OP_ACK && r.reqOpcode == OP_BURST_READ) {
                    val ofs = r.offset.toInt()
                    val data = r.data()
                    val end = ofs + data.size
                    if (end > buffer.size) buffer = buffer.copyOf(maxOf(end, buffer.size * 2))
                    System.arraycopy(data, 0, buffer, ofs, data.size)
                    if (end > hwm.get()) hwm.set(end)
                    lastDataAt.set(System.currentTimeMillis())
                    if (fileSize > 0) onProgress((hwm.get().toFloat() / fileSize).coerceIn(0f, 1f))

                    val doneBySize = fileSize > 0 && hwm.get() >= fileSize
                    val doneByBurst = r.burstComplete && (fileSize <= 0 || hwm.get() >= fileSize)
                    if ((doneBySize || doneByBurst) && !finished.isCompleted) finished.complete(Unit)
                } else if (r.opcode == OP_NAK && r.reqOpcode == OP_BURST_READ) {
                    // EOF means the FC has sent everything from the requested offset.
                    if (r.errorCode() == ERR_EOF && !finished.isCompleted) finished.complete(Unit)
                }
            }
        }

        try {
            send(frame(OP_BURST_READ, session = session, offset = 0))

            var lastProgress = 0
            var stalls = 0
            while (!finished.isCompleted) {
                delay(200L)
                if (finished.isCompleted) break
                val h = hwm.get()
                if (h > lastProgress) {
                    lastProgress = h
                    stalls = 0
                    continue
                }
                if (System.currentTimeMillis() - lastDataAt.get() >= IDLE_MS) {
                    stalls++
                    if (stalls > MAX_STALL_RETRIES) {
                        throw IOException("FTP download stalled at $h bytes after $stalls resume attempts")
                    }
                    LogUtils.w("MavFtp", "Burst stalled at $h bytes; resume $stalls/$MAX_STALL_RETRIES")
                    send(frame(OP_BURST_READ, session = session, offset = h.toLong()))
                    lastDataAt.set(System.currentTimeMillis())
                }
            }
            onProgress(1f)
            val h = hwm.get()
            if (h == buffer.size) buffer else buffer.copyOf(h)
        } finally {
            collector.cancel()
        }
    }

    /**
     * Send [msg] and wait for the next [FileTransferProtocol] reply, retransmitting the same request a
     * few times if the link drops it. Returns null only if every attempt times out.
     */
    private suspend fun request(
        msg: FileTransferProtocol,
        timeoutMs: Long = 2000L,
        retries: Int = 3
    ): Resp? {
        repeat(retries) {
            val resp = coroutineScope {
                val deferred = CompletableDeferred<Resp>()
                val collector = launch {
                    frames.collect { f ->
                        val m = f.message
                        if (m is FileTransferProtocol && !deferred.isCompleted) {
                            deferred.complete(Resp(m.payload))
                        }
                    }
                }
                try {
                    send(msg)
                    withTimeoutOrNull(timeoutMs) { deferred.await() }
                } finally {
                    collector.cancel()
                }
            }
            if (resp != null) return resp
        }
        return null
    }

    /** Split an ACK data block into directory records and append files/dirs to [out]. */
    private fun parseListEntries(data: ByteArray, out: MutableList<FtpEntry>): Int {
        var count = 0
        var start = 0
        for (i in data.indices) {
            if (data[i].toInt() == 0) {
                if (i > start) {
                    parseRecord(String(data, start, i - start, Charsets.US_ASCII), out)
                }
                count++ // every record (F/D/S) advances the directory offset
                start = i + 1
            }
        }
        return count
    }

    /** Records are `F<name>\t<size>`, `D<name>`, or `S...` (skip). */
    private fun parseRecord(rec: String, out: MutableList<FtpEntry>) {
        if (rec.isEmpty()) return
        when (rec[0]) {
            'F' -> {
                val body = rec.substring(1)
                val tab = body.indexOf('\t')
                val name = if (tab >= 0) body.substring(0, tab) else body
                val size = if (tab >= 0) body.substring(tab + 1).toLongOrNull() ?: 0L else 0L
                if (name.isNotEmpty()) out.add(FtpEntry(name, size, isDir = false))
            }
            'D' -> {
                val name = rec.substring(1)
                if (name.isNotEmpty() && name != "." && name != "..") {
                    out.add(FtpEntry(name, 0L, isDir = true))
                }
            }
            // 'S' and anything else: skip.
        }
    }

    /** Build a 251-byte FTP payload frame. */
    private fun frame(
        opcode: Int,
        session: Int = 0,
        offset: Long = 0L,
        data: ByteArray = EMPTY
    ): FileTransferProtocol {
        val p = UByteArray(PAYLOAD_LEN)
        val s = seq++ and 0xFFFF
        p[0] = (s and 0xFF).toUByte()
        p[1] = ((s shr 8) and 0xFF).toUByte()
        p[2] = session.toUByte()
        p[3] = opcode.toUByte()
        p[4] = data.size.toUByte()
        // p[5] req_opcode, p[6] burst_complete, p[7] padding all 0 for requests.
        p[8] = (offset and 0xFF).toUByte()
        p[9] = ((offset shr 8) and 0xFF).toUByte()
        p[10] = ((offset shr 16) and 0xFF).toUByte()
        p[11] = ((offset shr 24) and 0xFF).toUByte()
        val n = minOf(data.size, MAX_DATA)
        for (i in 0 until n) p[HEADER_LEN + i] = data[i].toUByte()
        return FileTransferProtocol(
            targetNetwork = 0u.toUByte(),
            targetSystem = targetSystem,
            targetComponent = targetComponent,
            payload = p.asList()
        )
    }

    /** Decoded view over an inbound FTP payload. */
    private class Resp(private val payload: List<UByte>) {
        val session: Int get() = payload[2].toInt()
        val opcode: Int get() = payload[3].toInt()
        val size: Int get() = payload[4].toInt()
        val reqOpcode: Int get() = payload[5].toInt()
        val burstComplete: Boolean get() = payload[6].toInt() == 1
        val offset: Long get() = le32FromU(payload, 8)

        fun data(): ByteArray {
            val n = size.coerceIn(0, MAX_DATA)
            return ByteArray(n) { payload[HEADER_LEN + it].toByte() }
        }

        fun errorCode(): Int = if (size >= 1) data()[0].toInt() and 0xFF else -1
    }

    companion object {
        private const val OP_TERMINATE = 1
        private const val OP_LIST = 3
        private const val OP_OPEN_RO = 4
        private const val OP_BURST_READ = 15
        private const val OP_NAK = 129
        private const val OP_ACK = 128

        private const val ERR_EOF = 6
        private const val ERR_FILE_NOT_FOUND = 10

        private const val PAYLOAD_LEN = 251
        private const val HEADER_LEN = 12
        private const val MAX_DATA = PAYLOAD_LEN - HEADER_LEN // 239

        private const val IDLE_MS = 3000L
        private const val MAX_STALL_RETRIES = 8
        private const val MAX_ALLOC = 512L * 1024L * 1024L // sanity cap for pre-sizing the buffer

        private val EMPTY = ByteArray(0)

        private fun le32(b: ByteArray, i: Int): Long =
            (b[i].toLong() and 0xFF) or
                ((b[i + 1].toLong() and 0xFF) shl 8) or
                ((b[i + 2].toLong() and 0xFF) shl 16) or
                ((b[i + 3].toLong() and 0xFF) shl 24)

        private fun le32FromU(b: List<UByte>, i: Int): Long =
            (b[i].toLong() and 0xFF) or
                ((b[i + 1].toLong() and 0xFF) shl 8) or
                ((b[i + 2].toLong() and 0xFF) shl 16) or
                ((b[i + 3].toLong() and 0xFF) shl 24)
    }
}
