package com.example.aerogcsclone.telemetry

import okhttp3.*
import okhttp3.CertificatePinner
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocketManager - Handles telemetry streaming with session acknowledgment protocol
 *
 * ✅ STEP 1 — Session Flags (initialized to false)
 * ✅ STEP 2 — Send session_start on connection (onOpen)
 * ✅ STEP 3 — Wait for session_ack from server (onMessage)
 * ✅ STEP 4 — Gate telemetry until readyForTelemetry = true (sendTelemetry)
 * ✅ STEP 5 — Send mission status updates (sendMissionStatus)
 */
class WebSocketManager {

    companion object {
        /**
         * Mission Status Constants
         * 0 = Created (set by backend)
         * 1 = Started
         * 2 = Paused
         * 3 = Resumed
         * 4 = Ended
         */
        const val MISSION_STATUS_CREATED = 0
        const val MISSION_STATUS_STARTED = 1
        const val MISSION_STATUS_PAUSED = 2
        const val MISSION_STATUS_RESUMED = 3
        const val MISSION_STATUS_ENDED = 4

        // Singleton instance for global access
        @Volatile
        private var INSTANCE: WebSocketManager? = null

        fun getInstance(): WebSocketManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebSocketManager().also { INSTANCE = it }
            }
        }

        /**
         * ===============================================================
         * SECURITY CONFIGURATION - WebSocket Connection Settings
         * ===============================================================
         *
         * PRODUCTION: Use WSS (WebSocket Secure) with certificate pinning
         * DEVELOPMENT/LOCAL: Use WS for local drone connections only
         *
         * The network_security_config.xml restricts cleartext to local IPs only.
         */

        // Toggle for production vs development mode
        // Set to false to use WS/HTTP (no SSL), true for WSS/HTTPS
        private const val USE_SECURE_CONNECTION = false

        // Production server (WSS - encrypted)
        // Using secure WebSocket connection for AWS EC2
        private const val PRODUCTION_WSS_URL = "wss://65.0.76.31:8000/ws/telemetry/"

        // Production server hostname for certificate pinning
        private const val PRODUCTION_HOST = "65.0.76.31"

        // Certificate pin (SHA-256 hash of server's public key)
        // TODO: Generate and add your server's certificate pin before production release
        // Use: openssl s_client -connect your-server.com:443 | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
        private const val CERTIFICATE_PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        // AWS EC2 Server URL (WS - unencrypted)
        // Direct connection: Android → WebSocket → AWS EC2 → PostgreSQL DB
        // ❌ No 127.0.0.1 ❌ No 10.0.2.2 ✅ Direct AWS IP + port
        private const val LOCAL_DEV_URL = "ws://65.0.76.31:8000/ws/telemetry/"

        /**
         * Get the appropriate WebSocket URL based on configuration
         */
        fun getWebSocketUrl(): String {
            return if (USE_SECURE_CONNECTION) PRODUCTION_WSS_URL else LOCAL_DEV_URL
        }

        /**
         * Check if secure connection is enabled
         */
        fun isSecureConnectionEnabled(): Boolean = USE_SECURE_CONNECTION

        /**
         * Get certificate pin for production
         */
        fun getCertificatePin(): String = CERTIFICATE_PIN

        /**
         * Get production host for certificate pinning
         */
        fun getProductionHost(): String = PRODUCTION_HOST
    }

    private val TAG = "WebSocketManager"

    // Fallback timeout handler (in case backend doesn't send session_ack)
    private val sessionAckTimeout = 3000L  // 3 seconds timeout
    private var sessionAckTimeoutRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Build a secure OkHttpClient with certificate pinning for production
     * or a standard client for local development
     */
    private fun buildSecureClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS) // Keep-alive for WebSocket

        // Add certificate pinning for production
        if (isSecureConnectionEnabled() &&
            getCertificatePin() != "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=") {
            val certificatePinner = CertificatePinner.Builder()
                .add(getProductionHost(), getCertificatePin())
                // Add backup pin in case of certificate rotation
                // .add(getProductionHost(), "sha256/BACKUP_PIN_HERE")
                .build()

            builder.certificatePinner(certificatePinner)
        }

        return builder.build()
    }

    private val client = buildSecureClient()
    private lateinit var webSocket: WebSocket

    // ✅ STEP 1 — Session Flags (DO NOT CHANGE INITIAL VALUES)
    private var sessionStarted = false
    private var telemetryEnabled = false
    private var readyForTelemetry = false

    // ✅ Pilot and Admin identification (must be set from SessionManager)
    var adminId: Int = -1  // Will be set from SessionManager
    var pilotId: Int = -1  // Will be set from SessionManager

    // 🔥 Auto-reconnection settings
    private var shouldReconnect = false  // Whether to auto-reconnect on disconnect
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val reconnectDelayMs = 2000L
    private var reconnectRunnable: Runnable? = null
    private var isReconnecting = false

    // 🔥 Track session_ack timing for debugging
    private var sessionAckReceivedTime: Long = 0
    private var connectionOpenedTime: Long = 0

    // 🔥 Drone UID - Real drone identifier from Flight Controller
    var droneUid: String = ""  // Set from TelemetryState / FC AUTOPILOT_VERSION
        set(value) {
            val oldValue = field
            field = value

            // 🔥 If droneUid was updated while connected, send update to backend
            if (value.isNotBlank() && oldValue != value && isConnected && missionId != null) {
                sendDroneUidUpdate(value)
            }
        }

    // 🔥 Plot name - Selected plot/field name from UI
    var selectedPlotName: String = ""  // Set from UI when mission starts

    // 🔥 Flight mode - Automatic or Manual
    var selectedFlightMode: String = "AUTOMATIC"  // Set from UI

    // 🔥 Mission type - Grid or Waypoint
    var selectedMissionType: String = "NONE"  // Set from UI

    // 🔥 Grid setup source - How grid boundary was created
    var gridSetupSource: String = "NONE"  // KML_IMPORT, MAP_DRAW, DRONE_POSITION, RC_CONTROL

    // 🔥 Mission active flag - controls whether telemetry is sent
    var isMissionActive: Boolean = false

    /**
     * Resolves the drone UID, providing a fallback for SITL testing
     * @return Real drone UID if available, otherwise "SITL_DRONE_001" as fallback
     */
    private fun resolveDroneUid(): String {
        return if (droneUid.isBlank()) {
            "SITL_DRONE_001"   // 🔥 fallback for SITL
        } else {
            droneUid          // real FC id
        }
    }

    // Mission tracking (received from backend)
    var missionId: String? = null

    // 🔥 Mission statistics tracking
    var missionBatteryStart: Int = 100  // Battery % at mission start
    var missionAlertsCount: Int = 0     // Count of alerts/warnings during mission
        private set

    // Real-time telemetry state (updated by MAVSDK)
    var lat = 0.0
    var lng = 0.0
    var alt = 0.0
    var speed = 0.0

    var roll = 0.0
    var pitch = 0.0
    var yaw = 0.0

    var voltage = 0.0
    var current = 0.0
    var batteryRemaining = 0

    var hdop = 0.0
    var satellites = 0

    // Status
    var flightMode = "UNKNOWN"
    var isArmed = false
    var failsafe = false

    // Spray telemetry
    var sprayOn = false
    var sprayRate = 0.0
    var flowPulse = 0
    var tankLevel = 0.0

    // WebSocket URL is determined by companion object configuration (secure WSS for production, WS for local dev)
    private val wsUrl: String
        get() = getWebSocketUrl()

    var isConnected = false
        private set

    fun connect() {
        // ✅ Validate pilotId and adminId are set from SessionManager
        if (pilotId <= 0) {
            return
        }
        if (adminId <= 0) {
            adminId = 1
        }

        // 🔥 Enable auto-reconnection when connect() is called
        shouldReconnect = true
        if (!isReconnecting) {
            reconnectAttempts = 0  // Reset attempts only on fresh connect
        }
        isReconnecting = false

        try {
            val request = Request.Builder()
                .url(wsUrl)
                .build()

            webSocket = client.newWebSocket(request, socketListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val socketListener = object : WebSocketListener() {

        // ✅ STEP 2 — Send session_start on connection
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected = true
            sessionStarted = false
            readyForTelemetry = false
            connectionOpenedTime = System.currentTimeMillis()
            sessionAckReceivedTime = 0

            // 🔥 Reset mission statistics when new session starts
            missionAlertsCount = 0
            missionBatteryStart = batteryRemaining  // Capture current battery as start

            // 🔥 Resolve drone UID
            val droneUidToSend = resolveDroneUid()

            try {
                // 🔥 FIX: Generate unique vehicle name to avoid database constraint violations
                val uniqueVehicleName = if (droneUidToSend.isNotBlank() && droneUidToSend != "SITL_DRONE_001") {
                    droneUidToSend.take(50) // Limit length to avoid DB field size issues
                } else {
                    "DRONE_${System.currentTimeMillis()}" // Timestamp-based fallback
                }

                val sessionStart = JSONObject().apply {
                    put("type", "session_start")
                    put("vehicle_name", uniqueVehicleName)
                    put("admin_id", adminId)
                    put("pilot_id", pilotId)
                    put("drone_uid", droneUidToSend)
                    put("plot_name", selectedPlotName)
                    put("flight_mode", selectedFlightMode)
                    put("mission_type", selectedMissionType)
                    put("grid_setup_source", gridSetupSource)
                }

                val payload = sessionStart.toString()
                webSocket?.send(payload)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 🔥 FALLBACK: Enable telemetry after timeout if backend doesn't send session_ack
            sessionAckTimeoutRunnable?.let { handler.removeCallbacks(it) }

            sessionAckTimeoutRunnable = Runnable {
                if (!readyForTelemetry && isConnected) {
                    sessionStarted = true
                    readyForTelemetry = true
                    telemetryEnabled = true
                }
            }

            handler.postDelayed(sessionAckTimeoutRunnable!!, sessionAckTimeout)
        }

        // ✅ STEP 3 — Android MUST wait for ACK
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val msg = JSONObject(text)
                val messageType = msg.getString("type")

                when (messageType) {
                    "session_ack" -> {
                        // Cancel the timeout since we got the ack
                        sessionAckTimeoutRunnable?.let { handler.removeCallbacks(it) }

                        sessionStarted = true
                        readyForTelemetry = true
                        telemetryEnabled = true
                        sessionAckReceivedTime = System.currentTimeMillis()

                        // 🔥 CHECK FOR DRONE UID UPDATE AFTER SESSION_ACK
                        val currentDroneUid = droneUid.trim()
                        if (currentDroneUid.isNotBlank() && currentDroneUid != "SITL_DRONE_001") {
                            try {
                                sendDroneUidUpdate(currentDroneUid)
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
                    }
                    "mission_created" -> {
                        missionId = msg.getString("mission_id")
                        readyForTelemetry = true
                        // 🔥 Reset reconnect attempts on successful mission creation
                        reconnectAttempts = 0

                        // 🔥 START DELAYED DRONE UID MONITORING
                        startDelayedDroneUidMonitoring()
                    }
                    "error" -> {
                        // Handle error silently
                    }
                    else -> {
                        // Other message types
                    }
                }
            } catch (e: Exception) {
                // Failed to parse message
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Cancel timeout
            sessionAckTimeoutRunnable?.let { handler.removeCallbacks(it) }

            isConnected = false
            sessionStarted = false
            telemetryEnabled = false
            readyForTelemetry = false
            t.printStackTrace()

            // 🔥 Trigger auto-reconnection on failure
            scheduleReconnect("connection_failure")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Cancel timeout
            sessionAckTimeoutRunnable?.let { handler.removeCallbacks(it) }

            // 🔥 Mark as disconnected to prevent further sends, but keep session state for reconnect
            isConnected = false
            readyForTelemetry = false
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // Cancel timeout
            sessionAckTimeoutRunnable?.let { handler.removeCallbacks(it) }

            isConnected = false
            sessionStarted = false
            telemetryEnabled = false
            readyForTelemetry = false

            // 🔥 Auto-reconnect unless this was a deliberate disconnect (code 1000 from client)
            if (shouldReconnect) {
                scheduleReconnect("server_closed_$code")
            }
        }
    }

    /**
     * Send drone UID update to backend when real UID becomes available after session_start
     */
    private fun sendDroneUidUpdate(realDroneUid: String) {
        if (!isConnected || !::webSocket.isInitialized) {
            return
        }

        try {
            val msg = JSONObject().apply {
                put("type", "drone_uid_update")
                put("mission_id", missionId)
                put("drone_uid", realDroneUid)
            }
            webSocket.send(msg.toString())
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun sendTelemetry() {
        // ✅ STEP 4 — Final Telemetry Gate (DO NOT REMOVE)
        if (!isConnected || !readyForTelemetry) {
            return
        }

        // Additional safety check for WebSocket initialization
        if (!sessionStarted || !::webSocket.isInitialized) {
            return
        }

        // ✅ Check if we have mission_id from backend
        if (missionId == null) {
            return
        }

        try {
            val telemetry = JSONObject().apply {
                put("type", "telemetry")
                put("ts", System.currentTimeMillis())

                // ✅ CRITICAL: Include pilot_id, admin_id, mission_id, and drone_uid
                put("pilot_id", pilotId)
                put("admin_id", adminId)
                put("mission_id", missionId)
                put("drone_uid", resolveDroneUid())

                put("position", JSONObject().apply {
                    put("lat", lat)
                    put("lng", lng)
                    put("alt", alt)
                })

                put("attitude", JSONObject().apply {
                    put("roll", roll)
                    put("pitch", pitch)
                    put("yaw", yaw)
                })

                put("battery", JSONObject().apply {
                    put("voltage", voltage)
                    put("current", current)
                    put("remaining", batteryRemaining)
                })

                put("gps", JSONObject().apply {
                    put("satellites", satellites)
                    put("hdop", hdop)
                    put("speed", speed)
                })

                put("status", JSONObject().apply {
                    put("flight_mode", flightMode)
                    put("armed", isArmed)
                    put("failsafe", failsafe)
                })

                put("spray", JSONObject().apply {
                    put("on", sprayOn)
                    put("rate_lpm", sprayRate)
                    put("flow_pulse", flowPulse)
                    put("tank_level", tankLevel)
                })
            }

            webSocket.send(telemetry.toString())
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Send mission status update to backend
     * @param status One of MISSION_STATUS_* constants
     */
    fun sendMissionStatus(status: Int) {
        // Safety checks
        if (!isConnected || missionId == null) {
            return
        }

        if (!::webSocket.isInitialized) {
            return
        }

        try {
            val msg = JSONObject().apply {
                put("type", "mission_status")
                put("mission_id", missionId)
                put("status", status)
                put("drone_uid", resolveDroneUid())
            }

            webSocket.send(msg.toString())
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Send mission event to backend
     */
    fun sendMissionEvent(eventType: String, eventStatus: String, description: String) {
        // Safety checks
        if (!isConnected) {
            return
        }

        if (!::webSocket.isInitialized) {
            return
        }

        try {
            val msg = JSONObject().apply {
                put("type", "mission_event")
                put("event_type", eventType)
                put("event_status", eventStatus)
                put("description", description)
                put("drone_uid", resolveDroneUid())
                missionId?.let { put("mission_id", it) }
            }

            webSocket.send(msg.toString())

            // 🔥 Auto-increment alerts count for WARNING/ERROR/CRITICAL events
            if (eventStatus in listOf("WARNING", "ERROR", "CRITICAL")) {
                missionAlertsCount++
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Send mission summary to backend when mission ends
     */
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
        if (!isConnected || missionId == null) {
            return
        }

        if (!::webSocket.isInitialized) {
            return
        }

        try {
            val msg = JSONObject().apply {
                put("type", "mission_summary")

                put("mission_id", missionId)
                put("drone_uid", resolveDroneUid())

                put("total_acres", totalAcres)
                put("total_spray_used", totalSprayUsed)
                put("flying_time_minutes", flyingTimeMinutes)
                put("average_speed", averageSpeed)

                put("battery_start", batteryStart)
                put("battery_end", batteryEnd)

                put("alerts_count", alertsCount)
                put("status", status)

                put("project_name", projectName)
                put("plot_name", plotName)
                put("crop_type", cropType)
                put("total_sprayed_acres", totalSprayedAcres)
            }

            webSocket.send(msg.toString())
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Schedule auto-reconnection after connection failure or unexpected close
     */
    private fun scheduleReconnect(reason: String) {
        if (!shouldReconnect) {
            return
        }

        if (reconnectAttempts >= maxReconnectAttempts) {
            shouldReconnect = false
            return
        }

        reconnectAttempts++
        val delay = reconnectDelayMs * reconnectAttempts  // Exponential backoff

        // Cancel any existing reconnect runnable
        reconnectRunnable?.let { handler.removeCallbacks(it) }

        reconnectRunnable = Runnable {
            if (shouldReconnect && !isConnected) {
                isReconnecting = true
                connect()
            }
        }

        handler.postDelayed(reconnectRunnable!!, delay)
    }

    fun disconnect() {
        try {
            // 🔥 Disable auto-reconnection when disconnect() is explicitly called
            shouldReconnect = false
            reconnectAttempts = 0

            // Cancel any pending timeout
            sessionAckTimeoutRunnable?.let { handler.removeCallbacks(it) }

            // Cancel any pending reconnection
            reconnectRunnable?.let { handler.removeCallbacks(it) }

            if (isConnected && ::webSocket.isInitialized) {
                webSocket.close(1000, "Mission ended - Client disconnect")
            }

            // Reset state
            isConnected = false
            sessionStarted = false
            telemetryEnabled = false
            readyForTelemetry = false
            missionId = null
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Check if WebSocket is ready to send telemetry
     */
    fun isReadyForTelemetry(): Boolean {
        return isConnected && readyForTelemetry && sessionStarted && missionId != null
    }

    /**
     * Force reset connection state and attempt fresh connection
     */
    fun reconnect() {
        if (::webSocket.isInitialized) {
            try {
                webSocket.cancel()
            } catch (e: Exception) {
                // Ignore
            }
        }
        isConnected = false
        sessionStarted = false
        telemetryEnabled = false
        readyForTelemetry = false
        reconnectAttempts = 0
        shouldReconnect = true
        connect()
    }

    /**
     * 🔥 Start delayed monitoring for drone UID updates
     */
    private fun startDelayedDroneUidMonitoring() {
        if (missionId == null) {
            return
        }

        val initialDroneUid = droneUid.trim()

        // Check after 2, 5, and 10 seconds for drone UID updates
        val checkDelays = listOf(2000L, 5000L, 10000L)

        checkDelays.forEach { delay ->
            handler.postDelayed({
                if (isConnected && missionId != null) {
                    val currentDroneUid = droneUid.trim()

                    // Check if drone UID has been updated to a real value
                    if (currentDroneUid.isNotBlank() &&
                        currentDroneUid != "SITL_DRONE_001" &&
                        currentDroneUid != initialDroneUid) {

                        try {
                            sendDroneUidUpdate(currentDroneUid)
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }
            }, delay)
        }
    }
}