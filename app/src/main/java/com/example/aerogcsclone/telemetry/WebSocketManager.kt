package com.example.aerogcsclone.telemetry

import android.util.Log
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
        // Set to true when deploying to production with a real backend
        private const val USE_SECURE_CONNECTION = false

        // Production server (WSS - encrypted)
        // TODO: Replace with your actual production server URL before release
        private const val PRODUCTION_WSS_URL = "wss://your-secure-server.com/ws/telemetry"

        // Production server hostname for certificate pinning
        // TODO: Replace with your actual server hostname
        private const val PRODUCTION_HOST = "your-secure-server.com"

        // Certificate pin (SHA-256 hash of server's public key)
        // TODO: Generate and add your server's certificate pin before production release
        // Use: openssl s_client -connect your-server.com:443 | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
        private const val CERTIFICATE_PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        // Local development URLs (WS - unencrypted, allowed only on local network by network_security_config.xml)
        // For emulator: ws://10.0.2.2:8080/ws/telemetry
        // For real device on same network: ws://192.168.x.x:8080/ws/telemetry
        private const val LOCAL_DEV_URL = "ws://10.0.2.2:8080/ws/telemetry"

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
            Log.d("WebSocketManager", "🔒 Certificate pinning enabled for ${getProductionHost()}")
        } else if (isSecureConnectionEnabled()) {
            Log.w("WebSocketManager", "⚠️ Production mode enabled but certificate pin not configured!")
        }

        return builder.build()
    }

    private val client = buildSecureClient()
    private lateinit var webSocket: WebSocket

    // ✅ STEP 1 — Session Flags (DO NOT CHANGE INITIAL VALUES)
    private var sessionStarted = false
    private var telemetryEnabled = false
    private var readyForTelemetry = false

    // ✅ Pilot and Admin identification (must be set before connecting)
    var adminId: Int = -1  // Will be set from SessionManager
    var pilotId: Int = -1  // Will be set from SessionManager


    // 🔥 Drone UID - Real drone identifier from Flight Controller
    var droneUid: String = ""  // Set from TelemetryState / FC AUTOPILOT_VERSION

    // 🔥 Plot name - Selected plot/field name from UI
    var selectedPlotName: String = ""  // Set from UI when mission starts

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
        private set

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
        Log.e("WebSocketManager", "🔥 connect() method CALLED")

        // ✅ Validate pilotId and adminId are set from SessionManager
        if (pilotId <= 0) {
            Log.e(TAG, "⛔ Cannot connect - pilotId not set! Please login first.")
            return
        }
        if (adminId <= 0) {
            Log.w(TAG, "⚠️ adminId not set, using default value 1")
            adminId = 1
        }

        Log.d(TAG, "📋 Connecting with pilotId=$pilotId, adminId=$adminId")

        // Log security status
        if (isSecureConnectionEnabled()) {
            Log.d(TAG, "🔒 Using SECURE WebSocket connection (WSS)")
        } else {
            Log.w(TAG, "⚠️ Using INSECURE WebSocket (WS) - OK for local drone connections only")
        }

        try {
            Log.d(TAG, "Building WebSocket request for URL: $wsUrl")
            val request = Request.Builder()
                .url(wsUrl)
                .build()

            Log.d(TAG, "Creating WebSocket connection...")
            webSocket = client.newWebSocket(request, socketListener)
            Log.e(TAG, "✅ Attempting to connect to WebSocket at $wsUrl")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to connect to WebSocket: ${e.message}", e)
            e.printStackTrace()
        }
    }

    private val socketListener = object : WebSocketListener() {

        // ✅ STEP 2 — Send session_start on connection
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected = true
            sessionStarted = false
            readyForTelemetry = false

            // 🔥 Reset mission statistics when new session starts
            missionAlertsCount = 0
            missionBatteryStart = batteryRemaining  // Capture current battery as start
            Log.d(TAG, "📊 Mission stats reset - Battery start: $missionBatteryStart%")

            val sessionStart = JSONObject().apply {
                put("type", "session_start")
                put("vehicle_name", "DRONE_01") // MUST match DB
                put("admin_id", adminId)
                put("pilot_id", pilotId)
                // 🔥 REAL DRONE ID from Flight Controller (with SITL fallback)
                put("drone_uid", resolveDroneUid())
                // 🔥 Plot name from UI
                put("plot_name", selectedPlotName)
            }

            webSocket.send(sessionStart.toString())
            Log.d(TAG, "📤 Sent session_start: $sessionStart")
        }

        // ✅ STEP 3 — Android MUST wait for ACK
        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "📩 From server: $text")

            try {
                val msg = JSONObject(text)
                val messageType = msg.getString("type")

                when (messageType) {
                    "session_ack" -> {
                        sessionStarted = true
                        readyForTelemetry = true
                        telemetryEnabled = true
                        Log.d(TAG, "✅ Session acknowledged, telemetry allowed")
                    }
                    "mission_created" -> {
                        missionId = msg.getString("mission_id")
                        readyForTelemetry = true
                        Log.d(TAG, "🚀 Mission started: $missionId")
                    }
                    else -> {
                        Log.d(TAG, "📨 Received message type: $messageType")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse message as JSON: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isConnected = false
            sessionStarted = false
            telemetryEnabled = false
            readyForTelemetry = false
            Log.e("WebSocket", "Disconnected: ${t.message}")
            Log.e(TAG, "❌ WebSocket error: ${t.message}", t)
            Log.e(TAG, "Response: ${response?.toString()}")
            Log.e(TAG, "Error type: ${t.javaClass.simpleName}")
            t.printStackTrace()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            isConnected = false
            sessionStarted = false
            telemetryEnabled = false
            readyForTelemetry = false
            Log.d(TAG, "WebSocket closing: $code / $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isConnected = false
            sessionStarted = false
            telemetryEnabled = false
            readyForTelemetry = false
            Log.d(TAG, "WebSocket closed: $code / $reason")
        }
    }

    fun sendTelemetry() {
        // ✅ STEP 4 — Final Telemetry Gate (DO NOT REMOVE)
        // HARD GUARD - Check connection and session acknowledgment before sending
        if (!isConnected || !readyForTelemetry) {
            Log.e(TAG, "⛔ Telemetry blocked — WebSocket not ready (connected=$isConnected, readyForTelemetry=$readyForTelemetry)")
            return
        }

        // Additional safety check for WebSocket initialization
        if (!sessionStarted || !::webSocket.isInitialized) {
            Log.e(TAG, "⛔ Telemetry blocked — Session not started or WebSocket not initialized")
            return
        }

        // ✅ Check if we have mission_id from backend
        if (missionId == null) {
            Log.e(TAG, "⛔ Telemetry blocked — No mission_id received from backend yet")
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
                // 🔥 REAL DRONE ID (with SITL fallback)
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
            Log.d(TAG, "📤 Sent telemetry: mission=$missionId, pilot=$pilotId, admin=$adminId, " +
                "lat=$lat, lng=$lng, alt=$alt, speed=$speed, " +
                "voltage=$voltage, current=$current, battery=$batteryRemaining%, " +
                "mode=$flightMode, armed=$isArmed, spray=${if(sprayOn) "ON" else "OFF"}, " +
                "rate=${sprayRate}L/min, tank=${tankLevel}%")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending telemetry: ${e.message}", e)
        }
    }

    /**
     * Send mission status update to backend
     * @param status One of MISSION_STATUS_* constants
     */
    fun sendMissionStatus(status: Int) {
        // Safety checks
        if (!isConnected || missionId == null) {
            Log.e(TAG, "⛔ Cannot send mission status — socket not ready (connected=$isConnected, missionId=$missionId)")
            return
        }

        if (!::webSocket.isInitialized) {
            Log.e(TAG, "⛔ Cannot send mission status — webSocket not initialized")
            return
        }

        try {
            val msg = JSONObject().apply {
                put("type", "mission_status")
                put("mission_id", missionId)
                put("status", status)
                // 🔥 REAL DRONE ID (with SITL fallback)
                put("drone_uid", resolveDroneUid())
            }

            webSocket.send(msg.toString())
            val statusName = when (status) {
                MISSION_STATUS_CREATED -> "CREATED"
                MISSION_STATUS_STARTED -> "STARTED"
                MISSION_STATUS_PAUSED -> "PAUSED"
                MISSION_STATUS_RESUMED -> "RESUMED"
                MISSION_STATUS_ENDED -> "ENDED"
                else -> "UNKNOWN($status)"
            }
            Log.d(TAG, "📤 Sent mission status: $statusName (status=$status, mission_id=$missionId)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send mission status", e)
        }
    }

    /**
     * Send mission event to backend
     * @param eventType Type of event (e.g., "ARM", "DISARM", "TAKEOFF", "LAND", "RTL", etc.)
     * @param eventStatus Event severity/status (e.g., "INFO", "WARNING", "ERROR", "CRITICAL")
     * @param description Human-readable description of the event
     */
    fun sendMissionEvent(eventType: String, eventStatus: String, description: String) {
        // Safety checks
        if (!isConnected) {
            Log.e(TAG, "⛔ Cannot send mission event — socket not connected")
            return
        }

        if (!::webSocket.isInitialized) {
            Log.e(TAG, "⛔ Cannot send mission event — webSocket not initialized")
            return
        }

        try {
            val msg = JSONObject().apply {
                put("type", "mission_event")
                put("event_type", eventType)
                put("event_status", eventStatus)
                put("description", description)
                // 🔥 REAL DRONE ID (with SITL fallback)
                put("drone_uid", resolveDroneUid())
                // Include mission_id if available
                missionId?.let { put("mission_id", it) }
            }

            webSocket.send(msg.toString())

            // 🔥 Auto-increment alerts count for WARNING/ERROR/CRITICAL events
            if (eventStatus in listOf("WARNING", "ERROR", "CRITICAL")) {
                missionAlertsCount++
                Log.d(TAG, "📊 Alert count incremented: $missionAlertsCount (event: $eventType)")
            }

            Log.d(TAG, "📤 Sent mission event: type=$eventType, status=$eventStatus, desc=$description, drone_uid=${resolveDroneUid()}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send mission event", e)
        }
    }

    /**
     * Send mission summary to backend when mission ends
     * @param totalArea Total area covered in the mission (sq meters or hectares)
     * @param totalSprayUsed Total spray/liquid used (liters)
     * @param flyingTimeMinutes Total flying time in minutes
     * @param averageSpeed Average speed during mission (m/s)
     * @param batteryStart Battery percentage at mission start
     * @param batteryEnd Battery percentage at mission end
     * @param alertsCount Number of alerts/warnings during mission
     * @param status Mission completion status ("COMPLETED" or "FAILED")
     */
    fun sendMissionSummary(
        totalArea: Double,
        totalSprayUsed: Double,
        flyingTimeMinutes: Double,
        averageSpeed: Double,
        batteryStart: Int,
        batteryEnd: Int,
        alertsCount: Int,
        status: String  // "COMPLETED" or "FAILED"
    ) {
        if (!isConnected || missionId == null) {
            Log.e(TAG, "⛔ Cannot send mission summary — socket not ready (connected=$isConnected, missionId=$missionId)")
            return
        }

        if (!::webSocket.isInitialized) {
            Log.e(TAG, "⛔ Cannot send mission summary — webSocket not initialized")
            return
        }

        try {
            val msg = JSONObject().apply {
                put("type", "mission_summary")

                put("mission_id", missionId)
                put("drone_uid", resolveDroneUid())

                put("total_area", totalArea)
                put("total_spray_used", totalSprayUsed)
                put("flying_time_minutes", flyingTimeMinutes)
                put("average_speed", averageSpeed)

                put("battery_start", batteryStart)
                put("battery_end", batteryEnd)

                put("alerts_count", alertsCount)
                put("status", status)  // COMPLETED / FAILED
            }

            webSocket.send(msg.toString())
            Log.d(TAG, "📤 Mission summary sent: area=$totalArea, spray=$totalSprayUsed, time=$flyingTimeMinutes min, " +
                "speed=$averageSpeed, battery=$batteryStart%→$batteryEnd%, alerts=$alertsCount, status=$status")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send mission summary", e)
        }
    }

    fun disconnect() {
        if (isConnected) {
            webSocket.close(1000, "Client disconnect")
            Log.d(TAG, "Disconnecting WebSocket")
        }
    }
}

