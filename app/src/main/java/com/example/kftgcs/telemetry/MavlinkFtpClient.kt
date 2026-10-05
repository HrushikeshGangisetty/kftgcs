package com.example.kftgcs.telemetry

import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.FileTransferProtocol
import com.example.kftgcs.utils.LogUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.OutputStream
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
     * Download the file at [filePath] over MAVLink FTP into [dest], reporting progress `0f..1f`.
     * Opens read-only, burst-reads with a resume-on-stall watchdog, then terminates the session.
     * The bytes are streamed to `<dest>.part` and renamed on success, so a log of any size never
     * sits in memory and a failed transfer leaves no half-written [dest] behind. Throws
     * [IOException] on open failure, timeout, or a stalled transfer.
     */
    suspend fun downloadFile(filePath: String, dest: File, onProgress: (Float) -> Unit) {
        val open = request(frame(OP_OPEN_RO, data = filePath.toByteArray(Charsets.US_ASCII)))
            ?: throw IOException("FTP OpenFileRO timed out for $filePath")
        if (open.opcode == OP_NAK) {
            throw IOException("FTP OpenFileRO failed (err=${open.errorCode()}) for $filePath")
        }
        val session = open.session
        val fileSize = if (open.size >= 4) le32(open.data(), 0) else 0L

        val part = File(dest.path + ".part")
        try {
            part.outputStream().buffered().use { out -> burstRead(session, fileSize, out, onProgress) }
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("Could not save ${dest.name}")
            onProgress(1f)
        } finally {
            part.delete() // no-op once renamed
            // Best-effort session cleanup so the FC frees the slot. NonCancellable so it still runs
            // when the user leaves the screen mid-download.
            withContext(NonCancellable) {
                withTimeoutOrNull(1000L) {
                    request(frame(OP_TERMINATE, session = session), timeoutMs = 800L, retries = 1)
                }
            }
        }
    }

    private suspend fun burstRead(
        session: Int,
        fileSize: Long,
        out: OutputStream,
        onProgress: (Float) -> Unit
    ): Unit = coroutineScope {
        // Bytes written so far. Only the packet at exactly this offset is accepted, so what is on
        // disk is always contiguous and a dropped packet can never leave a zero-filled hole.
        val received = AtomicLong(0)
        val lastDataAt = AtomicLong(System.currentTimeMillis())
        val finished = CompletableDeferred<Unit>()
        val chunk = ByteArray(MAX_DATA)
        var lastPercent = -1

        val collector = launch {
            frames.collect { f ->
                val m = f.message
                if (m !is FileTransferProtocol) return@collect
                val r = Resp(m.payload)
                if (r.session != session || r.reqOpcode != OP_BURST_READ) return@collect
                if (finished.isCompleted) return@collect

                if (r.opcode == OP_ACK) {
                    // Any burst packet proves the FC is still sending, even one we have to discard.
                    lastDataAt.set(System.currentTimeMillis())
                    val ofs = r.offset
                    val n = r.size.coerceIn(0, MAX_DATA)
                    if (ofs == received.get()) {
                        r.copyData(chunk, n)
                        out.write(chunk, 0, n)
                        val written = received.addAndGet(n.toLong())
                        if (fileSize > 0) {
                            val percent = (written * 100 / fileSize).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                    // ofs > received is a gap (a packet was lost). The FC does not read a new
                    // request until its current burst is over, so there is nothing to gain by
                    // asking now: the rest of this burst is discarded and the next one, requested
                    // below, starts from the gap.

                    val have = received.get()
                    if (fileSize > 0 && have >= fileSize) {
                        finished.complete(Unit)
                    } else if (r.burstComplete && ofs + n >= have) {
                        // The FC ends every burst after a fixed number of packets and waits for the
                        // client to ask for the next one. (ofs + n < have is the tail of a
                        // duplicate burst that a later request already covers.)
                        send(frame(OP_BURST_READ, session = session, offset = have))
                    }
                } else if (r.opcode == OP_NAK) {
                    val err = r.errorCode()
                    if (err == ERR_EOF) {
                        // EOF means the FC has nothing past the requested offset. With a known size
                        // that only ends the transfer once we really have every byte.
                        if (fileSize <= 0 || received.get() >= fileSize) finished.complete(Unit)
                    } else {
                        finished.completeExceptionally(
                            IOException("FTP read failed (err=$err) at ${received.get()} bytes")
                        )
                    }
                }
            }
        }

        try {
            send(frame(OP_BURST_READ, session = session, offset = 0))

            var lastProgress = 0L
            var stalls = 0
            while (!finished.isCompleted) {
                delay(200L)
                if (finished.isCompleted) break
                val h = received.get()
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
                    send(frame(OP_BURST_READ, session = session, offset = h))
                    lastDataAt.set(System.currentTimeMillis())
                }
            }
            finished.await() // rethrows a NAK failure
        } finally {
            collector.cancel()
        }
    }

    /**
     * Send [msg] and wait for the [FileTransferProtocol] reply to that opcode, retransmitting the
     * same request a few times if the link drops it. Returns null only if every attempt times out.
     * Replies to any other opcode are ignored: a burst packet still in flight from an earlier
     * transfer must not be taken as the answer to this request.
     */
    private suspend fun request(
        msg: FileTransferProtocol,
        timeoutMs: Long = 2000L,
        retries: Int = 3
    ): Resp? {
        val opcode = msg.payload[3].toInt()
        repeat(retries) {
            val resp = coroutineScope {
                val deferred = CompletableDeferred<Resp>()
                val collector = launch {
                    frames.collect { f ->
                        val m = f.message
                        if (m is FileTransferProtocol) {
                            val r = Resp(m.payload)
                            if (r.reqOpcode == opcode && !deferred.isCompleted) deferred.complete(r)
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
        val s = seq.getAndIncrement() and 0xFFFF
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

        /** Copy the first [n] data bytes into [into] without allocating. */
        fun copyData(into: ByteArray, n: Int) {
            for (i in 0 until n) into[i] = payload[HEADER_LEN + i].toByte()
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
        private val EMPTY = ByteArray(0)

        // Shared by every client. A new client is built per operation, and if each one restarted
        // at 0 the FC could see a request numbered one below its last reply, take it for a
        // retransmission and answer with that cached reply instead.
        private val seq = AtomicInteger(0)

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
