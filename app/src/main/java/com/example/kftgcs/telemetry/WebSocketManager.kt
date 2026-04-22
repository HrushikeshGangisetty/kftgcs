package com.example.kftgcs.telemetry

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.kftgcs.database.MissionTemplateDatabase
import com.example.kftgcs.database.offline.OfflineMessageDao
import com.example.kftgcs.database.offline.OfflineMessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.*
import okhttp3.CertificatePinner
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocketManager — Handles telemetry streaming with offline queuing.
 *
 * Session protocol
 * ----------------
 * 1. connect()        → sends session_start
 * 2. session_ack      → readyForTelemetry = true
 * 3. mission_created  → missionId assigned, offline queue flushed
 * 4. sendTelemetry()  → gated by readyForTelemetry + missionId
 *
 * Offline queue
 * -------------
 * mission_status / mission_event / mission_summary are persisted to Room when the
 * WebSocket is not connected. On reconnect, syncPendingMessages() replays them in
 * order with the new missionId.  Telemetry is NOT queued (high-frequency; already
 * stored locally via TlogRepository).
 *
 * Reconnect strategy
 * ------------------
 * - Exponential backoff: 2 s → 4 s → 8 s → 16 s → 30 s (capped)
 * - ConnectivityManager.NetworkCallback resets attempts and retries when internet
 *   returns, even after all backoff attempts are exhausted.
 * - userDisconnected flag prevents the callback from re-opening a session that the
 *   user explicitly ended.
 */
class WebSocketManager {

    companion object {
        /** Mission status constants (0 is set by the backend on creation). */
        const val MISSION_STATUS_CREATED  = 0
        const val MISSION_STATUS_STARTED  = 1
        const val MISSION_STATUS_PAUSED   = 2
        const val MISSION_STATUS_RESUMED  = 3
        const val MISSION_STATUS_ENDED    = 4

        @Volatile
        private var INSTANCE: WebSocketManager? = null

        fun getInstance(): WebSocketManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebSocketManager().also { INSTANCE = it }
            }

        /**
         * Wire up the offline queue DAO and network-restore callback.
         * Must be called once from Application.onCreate() before the first connect().
         */
        fun initWithContext(context: Context) {
            getInstance().apply {
                if (offlineMessageDao == null) {
                    val appCtx = context.applicationContext
                    offlineMessageDao = MissionTemplateDatabase
                        .getDatabase(appCtx)
                        .offlineMessageDao()
                    registerNetworkCallback(appCtx)
                }
            }
        }

        // ── Connection URLs ──────────────────────────────────────────────────

        private const val USE_SECURE_CONNECTION = true
        private const val PRODUCTION_WSS_URL    = "wss://kftgcs.com/ws/telemetry/"
        private const val PRODUCTION_HOST       = "kftgcs.com"
        // Replace with real SHA-256 pin before production release.
        // openssl s_client -connect kftgcs.com:443 | openssl x509 -pubkey -noout |
        //   openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
        private const val CERTIFICATE_PIN       = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        fun getWebSocketUrl(): String        = PRODUCTION_WSS_URL
        fun isSecureConnectionEnabled(): Boolean = USE_SECURE_CONNECTION
        fun getCertificatePin(): String      = CERTIFICATE_PIN
        fun getProductionHost(): String      = PRODUCTION_HOST
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private val TAG = "WebSocketManager"

    // Offline queue (null until initWithContext is called)
    private var offlineMessageDao: OfflineMessageDao? = null

    /**
     * Reactive stream of the number of PENDING offline messages.
     * Collect this in the UI to show a "⏳ N messages queued" badge.
     * Returns a flow that always emits 0 if the DAO hasn't been initialised yet.
     */
    val pendingCountFlow: Flow<Int>
        get() = offlineMessageDao?.countPendingFlow() ?: flowOf(0)

    // Single-threaded IO scope for all DB operations.
    // SupervisorJob means one failed child doesn't cancel siblings.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Prevents two concurrent coroutines from flushing the same rows.
    private val isFlushInProgress = AtomicBoolean(false)

    // Main-thread handler for reconnect scheduling and ack timeout.
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // Session-ack fallback: enable telemetry if backend never sends session_ack.
    private val SESSION_ACK_TIMEOUT_MS = 3_000L
    private var sessionAckTimeoutRunnable: Runnable? = null

    // ── Reconnect state ──────────────────────────────────────────────────────

    /** Set to false only by an explicit disconnect() call. */
    @Volatile private var userDisconnected = false
    @Volatile private var shouldReconnect  = false
    @Volatile private var isReconnecting   = false
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 5
    private val BASE_RECONNECT_DELAY_MS = 2_000L
    private val MAX_RECONNECT_DELAY_MS  = 30_000L
    private var reconnectRunnable: Runnable? = null

    // ── Session flags (all written from OkHttp thread, must be @Volatile) ───

    @Volatile
    var isConnected = false
        private set

    @Volatile
    private var sessionStarted = false

    @Volatile
    private var telemetryEnabled = false

    @Volatile
    private var readyForTelemetry = false

    // Timing (debug only)
    private var connectionOpenedTime  = 0L
    private var sessionAckReceivedTime = 0L

    // ── Mission / drone metadata (set from UI before connect) ────────────────

    var adminId: Int = -1
    var pilotId: Int = -1
    var superAdminId: Int = -1


    var droneUid: String = ""
        set(value) {
            val old = field
            field = value
            if (value.isNotBlank() && old != value && isConnected && missionId != null) {
                sendDroneUidUpdate(value)
            }
        }

    var selectedPlotName:   String = ""
    var selectedFlightMode: String = "AUTOMATIC"
    var selectedMissionType: String = "NONE"
    var gridSetupSource:    String = "NONE"
    var isMissionActive:    Boolean = false

    // Assigned by backend in mission_created message
    @Volatile var missionId: String? = null

    // Stats
    var missionBatteryStart: Int = 100
    var missionAlertsCount:  Int = 0
        private set

    // Live telemetry values (written from MAVSDK callbacks)
    var lat = 0.0; var lng = 0.0; var alt = 0.0; var speed = 0.0
    var roll = 0.0; var pitch = 0.0; var yaw = 0.0
    var voltage = 0.0; var current = 0.0; var batteryRemaining = 0
    var hdop = 0.0; var satellites = 0
    var flightMode = "UNKNOWN"; var isArmed = false; var failsafe = false
    var sprayOn = false; var sprayRate = 0.0; var flowPulse = 0; var tankLevel = 0.0

    // ── OkHttp client ────────────────────────────────────────────────────────

    private val client: OkHttpClient by lazy { buildSecureClient() }
    private lateinit var webSocket: WebSocket

    private val wsUrl get() = getWebSocketUrl()

    private fun buildSecureClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)   // Fail fast — backoff handles retry
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)

        val pin = getCertificatePin()
        if (isSecureConnectionEnabled() &&
            pin != "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=") {
            builder.certificatePinner(
                CertificatePinner.Builder()
                    .add(getProductionHost(), pin)
                    .build()
            )
        }
        return builder.build()
    }

    // ── ConnectivityManager network callback ─────────────────────────────────

    private fun registerNetworkCallback(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, networkCallback)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        /**
         * Called when a network with INTERNET capability becomes available.
         * If we are not deliberately disconnected and the socket is down, reset
         * the backoff counter and attempt to reconnect immediately.
         */
        override fun onAvailable(network: Network) {
            if (userDisconnected || isConnected) return
            if (pilotId <= 0) return  // No session credentials yet

            android.util.Log.i(TAG, "Network available — resetting reconnect state")
            shouldReconnect    = true
            reconnectAttempts  = 0
            // Cancel any pending delayed reconnect so we don't double-fire
            reconnectRunnable?.let { handler.removeCallbacks(it) }
            handler.post { connect() }
        }

        override fun onLost(network: Network) {
            // onFailure / onClosed from OkHttp handles the socket tear-down.
            android.util.Log.w(TAG, "Network lost")
        }
    }

    // ── Public connect / disconnect ──────────────────────────────────────────

    fun connect() {
        if (pilotId <= 0) {
            android.util.Log.e(TAG, "Cannot connect — pilotId not set")
            return
        }
        if (adminId <= 0) {
            android.util.Log.w(TAG, "adminId not set, defaulting to 1")
            adminId = 1
        }

        userDisconnected  = false
        shouldReconnect   = true
        if (!isReconnecting) reconnectAttempts = 0
        isReconnecting    = false

        try {
            android.util.Log.i(TAG, "┌─── WebSocket CONNECT ───")
            android.util.Log.i(TAG, "│ URL:          $wsUrl")
            android.util.Log.i(TAG, "│ pilotId:      $pilotId")
            android.util.Log.i(TAG, "│ adminId:      $adminId")
            android.util.Log.i(TAG, "│ superAdminId: $superAdminId")
            android.util.Log.i(TAG, "│ droneUid:     $droneUid")
            android.util.Log.i(TAG, "└──────────────────────────")
            webSocket = client.newWebSocket(Request.Builder().url(wsUrl).build(), socketListener)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "WebSocket connection failed: ${e.message}", e)
        }
    }

    fun disconnect() {
        userDisconnected  = true
        shouldReconnect   = false
        reconnectAttempts = 0

        sessionAckTimeoutRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable?.let { handler.removeCallbacks(it) }

        if (isConnected && ::webSocket.isInitialized) {
            try { webSocket.close(1000, "Mission ended — client disconnect") }
            catch (e: Exception) { /* ignore */ }
        }

        isConnected       = false
        sessionStarted    = false
        telemetryEnabled  = false
        readyForTelemetry = false
        missionId         = null
    }

    /** Hard-reset + fresh connect (e.g. from UI "reconnect" button). */
    fun reconnect() {
        if (::webSocket.isInitialized) {
            try { webSocket.cancel() } catch (e: Exception) { /* ignore */ }
        }
        isConnected       = false
        sessionStarted    = false
        telemetryEnabled  = false
        readyForTelemetry = false
        reconnectAttempts = 0
        shouldReconnect   = true
        connect()
    }

    fun isReadyForTelemetry(): Boolean =
        isConnected && readyForTelemetry && sessionStarted && missionId != null

    // ── OkHttp WebSocket listener ────────────────────────────────────────────

    private val socketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            android.util.Log.i(TAG, "WebSocket CONNECTED (${response.code})")
            isConnected           = true
            sessionStarted        = false
            readyForTelemetry     = false
            // Reset missionId to prevent stale ID from a previous session.
            // A fresh missionId will arrive via the mission_created message.
            missionId             = null
            connectionOpenedTime  = System.currentTimeMillis()
            sessionAckReceivedTime = 0
            missionAlertsCount    = 0
            missionBatteryStart   = batteryRemaining

            val droneUidToSend = resolveDroneUid()
            val vehicleName = if (droneUidToSend.isNotBlank() && droneUidToSend != "SITL_DRONE_001")
                droneUidToSend.take(50)
            else
                "DRONE_${System.currentTimeMillis()}"

            try {
                // Only pilot_id is needed — backend derives admin & superadmin from Pilot
                val payload = JSONObject().apply {
                    put("type",              "session_start")
                    put("vehicle_name",      vehicleName)
                    put("pilot_id",          pilotId)
                    put("drone_uid",         droneUidToSend)
                    put("plot_name",         selectedPlotName)
                    put("flight_mode",       selectedFlightMode)
                    put("mission_type",      selectedMissionType)
                    put("grid_setup_source", gridSetupSource)
                }.toString()

                android.util.Log.i(TAG, "┌─── session_start PAYLOAD ───")
                android.util.Log.i(TAG, "│ pilot_id:      $pilotId")
                android.util.Log.i(TAG, "│ drone_uid:     $droneUidToSend")
                android.util.Log.i(TAG, "│ vehicle_name:  $vehicleName")
                android.util.Log.i(TAG, "│ plot_name:     $selectedPlotName")
                android.util.Log.i(TAG, "│ Full JSON:     $payload")
                android.util.Log.i(TAG, "└─────────────────────────────")
                webSocket.send(payload)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to send session_start: ${e.message}", e)
            }

            // Fallback: enable telemetry if backend never sends session_ack
            sessionAckTimeoutRunnable?.let { handler.removeCallbacks(it) }
            sessionAckTimeoutRunnable = Runnable {
                if (!readyForTelemetry && isConnected) {
                    android.util.Log.w(TAG, "session_ack timeout — enabling telemetry anyway")
                    sessionStarted    = true
                    readyForTelemetry = true
                    telemetryEnabled  = true
                }
            }
            handler.postDelayed(sessionAckTimeoutRunnable!!, SESSION_ACK_TIMEOUT_MS)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val msg  = JSONObject(text)
                val type = msg.getString("type")
                android.util.Log.i(TAG, "┌─── WS RECEIVED: $type ───")
                android.util.Log.i(TAG, "│ Raw: $text")
                android.util.Log.i(TAG, "└──────────────────────────")

                when (type) {
                    "session_ack" -> {
                        sessionAckTimeoutRunnable?.let { handler.removeCallbacks(it) }
                        sessionStarted         = true
                        readyForTelemetry      = true
                        telemetryEnabled       = true
                        sessionAckReceivedTime = System.currentTimeMillis()
                        android.util.Log.i(TAG, "✅ session_ack received — telemetry enabled (adminId=$adminId, pilotId=$pilotId)")

                        val uid = droneUid.trim()
                        if (uid.isNotBlank() && uid != "SITL_DRONE_001") {
                            sendDroneUidUpdate(uid)
                        }
                    }

                    "mission_created" -> {
                        missionId         = msg.getString("mission_id")
                        readyForTelemetry = true
                        reconnectAttempts = 0
                        android.util.Log.i(TAG, "✅ mission_created: missionId=$missionId (adminId=$adminId, pilotId=$pilotId)")

                        startDelayedDroneUidMonitoring()
                        syncPendingMessages()     // Flush offline queue
                    }

                    "error" -> {
                        android.util.Log.e(TAG, "❌ Backend error: $text")
                        android.util.Log.e(TAG, "   (adminId=$adminId, pilotId=$pilotId, missionId=$missionId)")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to parse message: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            sessionAckTimeoutRunnable?.let { handler.removeCallbacks(it) }
            android.util.Log.e(TAG, "WebSocket FAILURE: ${t.message} | response=${response?.code}")
            diagnoseTlsError(t)

            isConnected       = false
            sessionStarted    = false
            telemetryEnabled  = false
            readyForTelemetry = false

            scheduleReconnect("failure")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            sessionAckTimeoutRunnable?.let { handler.removeCallbacks(it) }
            isConnected       = false
            sessionStarted    = false
            telemetryEnabled  = false
            readyForTelemetry = false
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            sessionAckTimeoutRunnable?.let { handler.removeCallbacks(it) }
            isConnected       = false
            sessionStarted    = false
            telemetryEnabled  = false
            readyForTelemetry = false

            if (shouldReconnect) scheduleReconnect("closed_$code")
        }
    }

    // ── Reconnect scheduling ─────────────────────────────────────────────────

    /**
     * Exponential backoff: 2 s, 4 s, 8 s, 16 s, 30 s (capped).
     * After MAX_RECONNECT_ATTEMPTS the handler stops scheduling.
     * The ConnectivityManager callback will restart the cycle when internet returns.
     */
    private fun scheduleReconnect(reason: String) {
        if (!shouldReconnect || userDisconnected) return

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            android.util.Log.w(TAG, "Max reconnect attempts reached — waiting for network")
            shouldReconnect = false
            return
        }

        reconnectAttempts++
        val delay = minOf(
            BASE_RECONNECT_DELAY_MS * (1L shl (reconnectAttempts - 1)),  // 2^(n-1) * base
            MAX_RECONNECT_DELAY_MS
        )

        android.util.Log.i(TAG, "Reconnect attempt $reconnectAttempts in ${delay}ms ($reason)")

        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = Runnable {
            if (shouldReconnect && !isConnected) {
                isReconnecting = true
                connect()
            }
        }
        handler.postDelayed(reconnectRunnable!!, delay)
    }

    // ── Send helpers ─────────────────────────────────────────────────────────

    private fun resolveDroneUid() = droneUid.ifBlank { "SITL_DRONE_001" }

    // Counter for periodic telemetry logging (avoid log spam)
    private var telemetrySendCount = 0

    private fun sendDroneUidUpdate(realUid: String) {
        if (!isConnected || !::webSocket.isInitialized) return
        try {
            webSocket.send(JSONObject().apply {
                put("type",       "drone_uid_update")
                put("mission_id", missionId)
                put("drone_uid",  realUid)
                put("super_admin_id", superAdminId)
            }.toString())
        } catch (e: Exception) { /* ignore */ }
    }

    fun sendTelemetry() {
        if (!isConnected || !readyForTelemetry || !sessionStarted) return
        if (!::webSocket.isInitialized || missionId == null)       return

        telemetrySendCount++
        // Log on first send and every 60th send to avoid spam
        if (telemetrySendCount == 1 || telemetrySendCount % 60 == 0) {
            android.util.Log.i(TAG, "📡 sendTelemetry #$telemetrySendCount — adminId=$adminId, pilotId=$pilotId, superAdminId=$superAdminId, missionId=$missionId, droneUid=${resolveDroneUid()}")
        }

        try {
            webSocket.send(JSONObject().apply {
                put("type",       "telemetry")
                put("ts",         System.currentTimeMillis())
                put("pilot_id",   pilotId)
                put("admin_id",   adminId)
                put("super_admin_id", superAdminId)
                put("mission_id", missionId)
                put("drone_uid",  resolveDroneUid())

                put("position", JSONObject().apply {
                    put("lat", lat); put("lng", lng); put("alt", alt)
                })
                put("attitude", JSONObject().apply {
                    put("roll", roll); put("pitch", pitch); put("yaw", yaw)
                })
                put("battery", JSONObject().apply {
                    put("voltage", voltage); put("current", current)
                    put("remaining", batteryRemaining)
                })
                put("gps", JSONObject().apply {
                    put("satellites", satellites); put("hdop", hdop); put("speed", speed)
                })
                put("status", JSONObject().apply {
                    put("flight_mode", flightMode); put("armed", isArmed)
                    put("failsafe", failsafe)
                })
                put("spray", JSONObject().apply {
                    put("on", sprayOn); put("rate_lpm", sprayRate)
                    put("flow_pulse", flowPulse); put("tank_level", tankLevel)
                })
            }.toString())
        } catch (e: Exception) { /* ignore — next tick will retry */ }
    }

    fun sendMissionStatus(status: Int) {
        android.util.Log.i(TAG, "📋 sendMissionStatus: status=$status, missionId=$missionId, adminId=$adminId, pilotId=$pilotId")
        val payload = JSONObject().apply {
            put("type",       "mission_status")
            put("mission_id", missionId)
            put("status",     status)
            put("drone_uid",  resolveDroneUid())
            put("super_admin_id", superAdminId)
        }.toString()

        if (!isConnected || !::webSocket.isInitialized || missionId == null) {
            enqueueOffline("mission_status", payload)
            return
        }
        try {
            webSocket.send(payload)
        } catch (e: Exception) {
            enqueueOffline("mission_status", payload)
        }
    }

    fun sendMissionEvent(eventType: String, eventStatus: String, description: String) {
        // Count alerts regardless of connectivity
        if (eventStatus in listOf("WARNING", "ERROR", "CRITICAL")) missionAlertsCount++

        val payload = JSONObject().apply {
            put("type",         "mission_event")
            put("event_type",   eventType)
            put("event_status", eventStatus)
            put("description",  description)
            put("drone_uid",    resolveDroneUid())
            put("super_admin_id", superAdminId)
            // Always include mission_id key so syncPendingMessages() can reliably
            // overwrite it with the current session's missionId during flush.
            put("mission_id",   missionId ?: JSONObject.NULL)
        }.toString()

        if (!isConnected || !::webSocket.isInitialized) {
            enqueueOffline("mission_event", payload)
            return
        }
        try {
            webSocket.send(payload)
        } catch (e: Exception) {
            enqueueOffline("mission_event", payload)
        }
    }

    fun sendMissionSummary(
        totalAcres: Double,
        totalSprayUsed: Double,
        flyingTimeMinutes: Double,
        averageSpeed: Double,
        batteryStart: Int,
        batteryEnd: Int,
        alertsCount: Int,
        status: String,
        projectName: String = "",
        plotName: String = "",
        cropType: String = "",
        totalSprayedAcres: Double = 0.0
    ) {
        val payload = JSONObject().apply {
            put("type",                "mission_summary")
            put("mission_id",          missionId)
            put("drone_uid",           resolveDroneUid())
            put("super_admin_id",      superAdminId)
            put("total_acres",         totalAcres)
            put("total_spray_used",    totalSprayUsed)
            put("flying_time_minutes", flyingTimeMinutes)
            put("average_speed",       averageSpeed)
            put("battery_start",       batteryStart)
            put("battery_end",         batteryEnd)
            put("alerts_count",        alertsCount)
            put("status",              status)
            put("project_name",        projectName)
            put("plot_name",           plotName)
            put("crop_type",           cropType)
            put("total_sprayed_acres", totalSprayedAcres)
        }.toString()

        if (!isConnected || !::webSocket.isInitialized || missionId == null) {
            enqueueOffline("mission_summary", payload)
            return
        }
        try {
            webSocket.send(payload)
        } catch (e: Exception) {
            enqueueOffline("mission_summary", payload)
        }
    }

    // ── Offline queue ────────────────────────────────────────────────────────

    private fun enqueueOffline(messageType: String, payload: String) {
        val dao = offlineMessageDao ?: run {
            android.util.Log.w(TAG, "offlineMessageDao not initialized — message dropped: $messageType")
            return
        }
        ioScope.launch {
            try {
                dao.insert(OfflineMessageEntity(messageType = messageType, payload = payload))
                android.util.Log.i(TAG, "Queued offline: $messageType (pending=${dao.countPending()})")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to queue offline message: ${e.message}")
            }
        }
    }

    /**
     * Send all PENDING offline messages in creation order.
     *
     * - Only one concurrent flush is allowed (AtomicBoolean guard).
     * - Rewrites mission_id to the current live session before sending.
     * - On send failure: increments retryCount; marks FAILED after MAX_RETRIES.
     * - Deletes row after confirmed send.
     * - Stops immediately if the connection drops mid-flush (remaining rows
     *   are retried on the next reconnect).
     *
     * Called automatically after mission_created, and by SyncWorker as a fallback.
     */
    fun syncPendingMessages() {
        val dao           = offlineMessageDao ?: return
        val currentMission = missionId       ?: return

        if (!isFlushInProgress.compareAndSet(false, true)) {
            android.util.Log.d(TAG, "Flush already in progress — skipping")
            return
        }

        ioScope.launch {
            try {
                val pending = dao.getPendingBelowMaxRetry()
                if (pending.isEmpty()) return@launch

                android.util.Log.i(TAG, "Flushing ${pending.size} offline message(s)…")

                for (msg in pending) {
                    if (!isConnected || !::webSocket.isInitialized) {
                        android.util.Log.w(TAG, "Connection lost during flush — stopping at id=${msg.id}")
                        break
                    }
                    try {
                        val json = JSONObject(msg.payload)
                        json.put("mission_id", currentMission)
                        // Attach clientId so the backend can deduplicate on replay
                        json.put("client_id", msg.clientId)

                        // Guard send against connection dying mid-flush.
                        // IllegalStateException / IOException = socket closed under us.
                        val sent = try {
                            webSocket.send(json.toString())
                        } catch (sendEx: Exception) {
                            android.util.Log.w(TAG,
                                "Socket send failed for id=${msg.id}: ${sendEx.message}")
                            // Connection died — stop flush, don't penalise the message.
                            break
                        }

                        if (sent) {
                            dao.deleteById(msg.id)
                            android.util.Log.i(TAG, "Flushed ${msg.messageType} (id=${msg.id})")
                        } else {
                            // OkHttp returns false when the outgoing queue is full
                            // or the socket is closing — treat as transient, stop flush.
                            android.util.Log.w(TAG,
                                "webSocket.send() returned false for id=${msg.id} — stopping flush")
                            break
                        }

                        // Small delay between sends to avoid flooding the backend
                        kotlinx.coroutines.delay(50)
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Failed to flush id=${msg.id}: ${e.message}")
                        dao.incrementRetry(msg.id)
                        if (msg.retryCount + 1 >= OfflineMessageEntity.MAX_RETRIES) {
                            dao.markFailed(msg.id)
                            android.util.Log.w(TAG, "Message id=${msg.id} marked FAILED after ${OfflineMessageEntity.MAX_RETRIES} retries")
                        }
                        break  // Stop on error — retry remaining on next reconnect
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Offline queue flush error: ${e.message}")
            } finally {
                isFlushInProgress.set(false)
            }
        }
    }

    // ── Drone UID monitoring ─────────────────────────────────────────────────

    private fun startDelayedDroneUidMonitoring() {
        val initialUid = droneUid.trim()
        listOf(2_000L, 5_000L, 10_000L).forEach { delay ->
            handler.postDelayed({
                if (!isConnected || missionId == null) return@postDelayed
                val current = droneUid.trim()
                if (current.isNotBlank() &&
                    current != "SITL_DRONE_001" &&
                    current != initialUid) {
                    sendDroneUidUpdate(current)
                }
            }, delay)
        }
    }

    // ── Diagnostics ──────────────────────────────────────────────────────────

    private fun diagnoseTlsError(t: Throwable) {
        val msg = t.message ?: return
        val hint = when {
            "Unable to resolve host" in msg -> "DNS resolution failed — check internet / DNS"
            "Connection refused"     in msg -> "Server not accepting connections on port 443"
            "Connection reset"       in msg -> "Server closed connection — check SSL cert / WS endpoint"
            "SSL" in msg || "Certificate" in msg -> "TLS handshake failed — check certificate validity"
            "timeout" in msg                -> "Connection timed out — check server / firewall"
            else -> return
        }
        android.util.Log.e(TAG, "Diagnosis: $hint")
    }
}
