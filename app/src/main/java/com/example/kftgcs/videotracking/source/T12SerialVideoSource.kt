package com.example.kftgcs.videotracking.source

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.example.kftgcs.telemetry.connections.UsbPortOwnership
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Reads the Skydroid T12's video feed over USB and decodes it to a [Surface].
 *
 * The T12 shows up as a Silicon Labs CP2102 (VID 0x10C4 / PID 0xEA60, decimal 4292/60000). Its
 * video is H.264 carried inside a vendor framing protocol on that serial link. Everything below
 * was taken from Skydroid's own `uartVideo-release.aar` (package `com.shenyaocn.android.UartVideo`,
 * bundled in `Vigiair/Skydroid-USB-Video-to-UDP`) by disassembling `VideoClient`,
 * `VideoInputStream` and its `Cp2102SerialDriver`. It is not guesswork:
 *
 * **Line settings:** 4,000,000 baud, 8N1 (`VideoClient.openUsbSerial`), with DTR and RTS raised.
 *
 * **The host polls; the T12 only answers.** Nothing arrives until the host starts polling. Each
 * poll cycle (`VideoInputStream.d()`, run in a tight loop):
 *
 *  1. Host writes `FF 02 00 <ack> <len> <type> <payload[len]>`
 *     - `ack` = `0x55` if the previous read was a good frame, `0x00` if it was not.
 *     - `type`: `A5` command (AT text), `A3` data, `A7` GPS, `A1` debug. An idle poll is just
 *       `FF 02 00 <ack> 00 A5`.
 *     - The payload is at most 250 bytes. Longer messages are split. Commands are spaced at least
 *       180 ms apart, and any queued packet at least 30 ms apart.
 *  2. Host reads (20 ms timeout). A good response is exactly 512 bytes:
 *     `FF <lenHi> <lenLo> <type> <payload[508]>`, with `len` capped at 508.
 *     - `A5` = H.264 elementary-stream bytes, `A3` = data, `A7` = GPS, `A1` = debug.
 *  3. On a short or bad read, the host waits out the rest of the 20 ms slot and sends `ack = 00`
 *     next time.
 *
 * The H.264 bytes are reassembled by splitting on `00 00 00 01` start codes, the same way the
 * vendor's `VideoInputStream$1` does, and fed to [MediaCodec].
 *
 * **One port, one owner.** Telemetry over USB ([com.example.kftgcs.telemetry.connections.UsbSerialMavConnection])
 * wants this same CP2102. Two openers fight over the interface: the second force-claims it and the
 * first sees `rc=-1` on every transfer. So both register in [UsbPortOwnership], and video refuses to
 * start while telemetry holds the device.
 */
class T12SerialVideoSource(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) {
    /** The one live session: open port + decoder + poll loop + feed loop. */
    private var sessionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val nalQueue = LinkedBlockingQueue<ByteArray>(NAL_QUEUE_CAPACITY)

    /** Outbound packets, each `[type, payload...]`, drained by the poll loop. */
    private val txQueue = ConcurrentLinkedQueue<ByteArray>()

    @Volatile
    var isStreaming: Boolean = false
        private set

    // Link counters, reported in the error text because the app runs on the RC with no logcat.
    @Volatile private var pollsSent = 0
    @Volatile private var goodFrames = 0
    @Volatile private var badReads = 0
    @Volatile private var videoFrames = 0
    @Volatile private var dataFrames = 0
    @Volatile private var otherFrames = 0

    /**
     * Opens the T12's serial port, starts the vendor poll loop, asks for video and decodes it into
     * [surface]. [onReady] fires on the first decoded frame. [onError] fires on any failure,
     * including "polling, but no video arrived", with link counters so the cause can be told apart.
     */
    fun connect(
        surface: Surface,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        val previous = sessionJob
        sessionJob = scope.launch {
            // Exactly one session at a time. Two overlapping opens fight over the single USB port
            // and over the Surface — MediaCodec.configure throws IllegalArgumentException while
            // another codec still holds it, and the loser's cleanup then closed the winner's port.
            // That pair is what produced "[MediaCodec.configure] IllegalArgumentException" on one
            // attempt and "[poll write] Connection closed" on the other.
            previous?.cancelAndJoin()

            UsbPortOwnership.claim(device, OWNER)?.let { holder ->
                if (holder != OWNER) {
                    onError(
                        "The T12's USB port is already in use by $holder. The T12 has one serial " +
                            "port, so video cannot start while telemetry is connected over that same " +
                            "USB link. Connect telemetry another way (UDP/Bluetooth), or disconnect it " +
                            "before starting video."
                    )
                    return@launch
                }
            }

            // Everything this session owns is local, so a failing session can only tear down its
            // own port and codec, never another session's.
            var serialPort: UsbSerialPort? = null
            var codec: MediaCodec? = null
            try {
                val driver = stage("probeDevice") {
                    UsbSerialProber.getDefaultProber().probeDevice(device)
                        ?: throw IOException("No USB-serial driver for ${device.deviceName}")
                }
                val connection = stage("openDevice") {
                    usbManager.openDevice(device)
                        ?: throw IOException("Failed to open T12 USB device (permission not granted?)")
                }
                val opened = stage("driver.ports") {
                    driver.ports.firstOrNull() ?: throw IOException("USB serial driver has no ports")
                }

                stage("serialPort.open") { opened.open(connection) }
                serialPort = opened
                stage("serialPort.setParameters(4M 8N1)") {
                    opened.setParameters(
                        T12_BAUD_RATE,
                        UsbSerialPort.DATABITS_8,
                        UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE
                    )
                }
                // The vendor driver asserts both lines on open (SET_MHS 0x0303).
                stage("serialPort.setDTR/RTS") {
                    runCatching { opened.setDTR(true) }
                    runCatching { opened.setRTS(true) }
                }

                val mediaCodec = stage("MediaCodec.createDecoderByType") {
                    MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                }
                codec = mediaCodec
                stage("MediaCodec.configure") {
                    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                    mediaCodec.configure(format, surface, null, 0)
                }
                stage("MediaCodec.start") { mediaCodec.start() }

                resetCounters()
                txQueue.clear()
                nalQueue.clear()
                isStreaming = true

                // Same string the vendor's setVideoSize(Size_HD) queues, with its default exposure.
                enqueue(TYPE_COMMAND, START_VIDEO_COMMAND.toByteArray(Charsets.US_ASCII))

                val feed = launch { feedDecoderLoop(mediaCodec, onReady, onError) }
                pollLoop(opened, onError)
                feed.cancel()
            } catch (e: CancellationException) {
                // release(), or a newer connect() taking over — normal teardown, see finally.
            } catch (e: Exception) {
                Timber.e(e, "T12SerialVideoSource: connect failed")
                onError("Could not start T12 video: ${e.describe()}")
            } finally {
                isStreaming = false
                runCatching { serialPort?.close() }
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                UsbPortOwnership.release(device, OWNER)
            }
        }
    }

    /** Splits [payload] into ≤250-byte packets of `[type, chunk...]`, like the vendor's `a(byte, byte[])`. */
    private fun enqueue(type: Byte, payload: ByteArray) {
        var off = 0
        while (off < payload.size) {
            val n = minOf(MAX_TX_PAYLOAD, payload.size - off)
            val pkt = ByteArray(n + 1)
            pkt[0] = type
            System.arraycopy(payload, off, pkt, 1, n)
            txQueue.offer(pkt)
            off += n
        }
    }

    /**
     * The single thread that owns the port: one poll write + one frame read per cycle, exactly as
     * `VideoInputStream.d()` does. Runs on the session coroutine; the port is closed by the
     * session's own `finally`, so nothing else ever writes to it.
     */
    private fun CoroutineScope.pollLoop(serialPort: UsbSerialPort, onError: (String) -> Unit) {
        val readBuf = ByteArray(READ_BUFFER_SIZE)
        val h264 = ByteArrayOutputStream(READ_BUFFER_SIZE * 4)
        var lastReadBad = false
        var lastDequeueAt = 0L
        var lastCommandAt = 0L
        val startedAt = System.currentTimeMillis()
        var watchdogFired = false

        try {
            while (isActive && isStreaming) {
                val cycleStart = System.currentTimeMillis()

                // ---- 1. write: queued packet if its spacing allows, otherwise an idle poll ----
                var out: ByteArray? = null
                if (cycleStart - lastDequeueAt > TX_SPACING_MS) {
                    val head = txQueue.peek()
                    val isCommand = head != null && head[0] == TYPE_COMMAND
                    // The vendor drops commands that come too fast; queue them instead.
                    if (head != null && (!isCommand || cycleStart - lastCommandAt >= COMMAND_SPACING_MS)) {
                        out = txQueue.poll()
                        lastDequeueAt = cycleStart
                        if (isCommand) lastCommandAt = cycleStart
                    }
                }
                val payload = out ?: IDLE_POLL
                val packet = ByteArray(POLL_HEADER_SIZE + payload.size)
                packet[0] = 0xFF.toByte()
                packet[1] = 0x02
                packet[2] = 0x00
                packet[3] = (if (lastReadBad) 0x00 else 0x55).toByte()
                packet[4] = (payload.size - 1).toByte()
                System.arraycopy(payload, 0, packet, POLL_HEADER_SIZE, payload.size)
                lastReadBad = false

                try {
                    serialPort.write(packet, WRITE_TIMEOUT_MS)
                } catch (e: IOException) {
                    throw IOException("[poll write] ${e.describe()}", e)
                }
                pollsSent++

                // ---- 2. read one response frame ----
                val n = serialPort.read(readBuf, READ_TIMEOUT_MS)
                if (n != FRAME_SIZE || readBuf[0] != 0xFF.toByte()) {
                    badReads++
                    lastReadBad = true
                    val elapsed = System.currentTimeMillis() - cycleStart
                    if (elapsed < POLL_PERIOD_MS) Thread.sleep(POLL_PERIOD_MS - elapsed)
                } else {
                    goodFrames++
                    val len = minOf(
                        ((readBuf[1].toInt() and 0xFF) shl 8) or (readBuf[2].toInt() and 0xFF),
                        MAX_RX_PAYLOAD
                    )
                    when (readBuf[3]) {
                        TYPE_VIDEO -> {
                            videoFrames++
                            h264.write(readBuf, FRAME_HEADER_SIZE, len)
                            if (h264.size() > H264_PARSE_THRESHOLD) {
                                val (nals, remainder) = parseAnnexBFrames(h264.toByteArray())
                                nals.forEach { nalQueue.offer(it, NAL_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                                h264.reset()
                                h264.write(remainder)
                            }
                        }
                        TYPE_DATA -> dataFrames++
                        else -> otherFrames++
                    }
                }

                if (!watchdogFired && videoFrames == 0 &&
                    System.currentTimeMillis() - startedAt > NO_VIDEO_TIMEOUT_MS
                ) {
                    watchdogFired = true
                    onError("T12 port is open but no video arrived after ${NO_VIDEO_TIMEOUT_MS / 1000}s. ${linkStats()}")
                }
            }
        } catch (e: Exception) {
            if (isActive && isStreaming) {
                Timber.e(e, "T12SerialVideoSource: poll loop failed")
                onError("T12 video link dropped: ${e.describe()}. ${linkStats()}")
                isStreaming = false
            }
        } finally {
            // Best-effort "video off", sent framed like every other command.
            runCatching {
                val stop = STOP_VIDEO_COMMAND.toByteArray(Charsets.US_ASCII)
                val packet = ByteArray(POLL_HEADER_SIZE + 1 + stop.size)
                packet[0] = 0xFF.toByte(); packet[1] = 0x02; packet[2] = 0x00; packet[3] = 0x55
                packet[4] = stop.size.toByte()
                packet[5] = TYPE_COMMAND
                System.arraycopy(stop, 0, packet, POLL_HEADER_SIZE + 1, stop.size)
                serialPort.write(packet, 200)
            }
        }
    }

    /** Pulls parsed NAL units off [nalQueue] and feeds them into the decoder. */
    private fun CoroutineScope.feedDecoderLoop(codec: MediaCodec, onReady: () -> Unit, onError: (String) -> Unit) {
        var firstFrame = true
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (isActive && isStreaming) {
                val nal = nalQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue

                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(nal)
                    codec.queueInputBuffer(inIndex, 0, nal.size, System.nanoTime() / 1000, 0)
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                while (outIndex >= 0) {
                    codec.releaseOutputBuffer(outIndex, true) // true = render to surface
                    if (firstFrame) {
                        firstFrame = false
                        onReady()
                    }
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
        } catch (e: Exception) {
            if (isActive && isStreaming) {
                Timber.e(e, "T12SerialVideoSource: decoder feed loop failed")
                onError("T12 video decode failed: ${e.describe()}")
            }
        }
    }

    fun release() {
        isStreaming = false
        sessionJob?.cancel()
        sessionJob = null
        nalQueue.clear()
        txQueue.clear()
    }

    private fun resetCounters() {
        pollsSent = 0; goodFrames = 0; badReads = 0
        videoFrames = 0; dataFrames = 0; otherFrames = 0
    }

    private fun linkStats(): String =
        "polls=$pollsSent good=$goodFrames bad=$badReads video=$videoFrames data=$dataFrames other=$otherFrames"

    /**
     * Runs [block], tagging any thrown exception with which setup step it came from, since several
     * usb-serial / MediaCodec exceptions carry a null or unhelpful message.
     */
    private inline fun <T> stage(name: String, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            throw IOException("[$name] ${e.describe()}", e)
        }

    companion object {
        /** Name registered in [UsbPortOwnership] while video holds the port. */
        const val OWNER = "T12 video"

        // --- Vendor protocol constants (from uartVideo-release.aar, see class doc) ---
        private const val T12_BAUD_RATE = 4_000_000

        private const val TYPE_COMMAND: Byte = 0xA5.toByte() // host→T12 AT commands
        private const val TYPE_VIDEO: Byte = 0xA5.toByte()   // T12→host H.264 bytes
        private const val TYPE_DATA: Byte = 0xA3.toByte()    // data passthrough, both directions

        private const val POLL_HEADER_SIZE = 5               // FF 02 00 <ack> <len>
        private val IDLE_POLL = byteArrayOf(TYPE_COMMAND)    // zero-length command = keep-alive
        private const val MAX_TX_PAYLOAD = 250
        private const val FRAME_SIZE = 512
        private const val FRAME_HEADER_SIZE = 4              // FF <lenHi> <lenLo> <type>
        private const val MAX_RX_PAYLOAD = 508

        private const val POLL_PERIOD_MS = 20L
        private const val TX_SPACING_MS = 30L
        private const val COMMAND_SPACING_MS = 180L
        private const val READ_TIMEOUT_MS = 20
        private const val WRITE_TIMEOUT_MS = 1_000
        private const val READ_BUFFER_SIZE = 2048

        // Vendor's setVideoSize(Size_HD) string plus its default auto-exposure settings.
        private const val START_VIDEO_COMMAND =
            "AT+VIDEO -m0 -p2 -f15 -b900 -e1\r\n" +
                "AT+AE -o0 -i1 -h1 -r0\r\n" +
                "AT+AEM -a0 -b0 -c0 -d0 -e8192 -f1024 -g1024 -h1024\r\n"
        private const val STOP_VIDEO_COMMAND = "AT+VIDEO -e0\r\n"

        // --- Local tuning ---
        private const val H264_PARSE_THRESHOLD = 2048        // vendor parses once >2 KB is buffered
        private const val NAL_QUEUE_CAPACITY = 64
        private const val NAL_OFFER_TIMEOUT_MS = 50L
        private const val NO_VIDEO_TIMEOUT_MS = 8_000L

        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720

        /**
         * Splits a byte buffer into complete Annex-B NAL units (each starting with
         * `00 00 00 01` or `00 00 01`) plus a trailing remainder that does not yet
         * contain a full unit.
         */
        internal fun parseAnnexBFrames(data: ByteArray): Pair<List<ByteArray>, ByteArray> {
            val startCodes = findStartCodeOffsets(data)
            if (startCodes.size < 2) {
                return emptyList<ByteArray>() to data
            }
            val frames = ArrayList<ByteArray>(startCodes.size - 1)
            for (i in 0 until startCodes.size - 1) {
                val start = startCodes[i]
                val end = startCodes[i + 1]
                frames.add(data.copyOfRange(start, end))
            }
            val remainder = data.copyOfRange(startCodes.last(), data.size)
            return frames to remainder
        }

        private fun findStartCodeOffsets(data: ByteArray): List<Int> {
            val offsets = ArrayList<Int>()
            var i = 0
            while (i < data.size - 3) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                    if (data[i + 2] == 1.toByte()) {
                        offsets.add(i)
                        i += 3
                        continue
                    } else if (i < data.size - 4 && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                        offsets.add(i)
                        i += 4
                        continue
                    }
                }
                i++
            }
            return offsets
        }
    }
}

/**
 * `e.message` is frequently null or unhelpful for the exceptions this class
 * hits, so fall back to the exception's class name.
 */
private fun Throwable.describe(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
