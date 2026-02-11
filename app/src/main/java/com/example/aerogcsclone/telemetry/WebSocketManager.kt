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
        Log.e("WS_DEBUG", "🔥🔥🔥 connect() method CALLED 🔥🔥🔥")
        Log.e("WS_DEBUG", "📋 Thread: ${Thread.currentThread().name}")

        // ✅ Validate pilotId and adminId are set from SessionManager
        if (pilotId <= 0) {
            Log.e(TAG, "⛔ Cannot connect - pilotId not set! Please login first.")
            Log.e("WS_DEBUG", "❌ ABORT: pilotId=$pilotId is invalid")
            return
        }
        if (adminId <= 0) {
            Log.w(TAG, "⚠️ adminId not set, using default value 1")
            adminId = 1
        }

        // 🔥 Enable auto-reconnection when connect() is called
        shouldReconnect = true
        if (!isReconnecting) {
            reconnectAttempts = 0  // Reset attempts only on fresh connect
        }
        isReconnecting = false

        Log.e("WS_DEBUG", "📋 pilotId=$pilotId, adminId=$adminId, droneUid='$droneUid'")
        Log.d(TAG, "📋 Connecting with pilotId=$pilotId, adminId=$adminId")

        // Log security status
        if (isSecureConnectionEnabled()) {
            Log.d(TAG, "🔒 Using SECURE WebSocket connection (WSS)")
        } else {
            Log.w(TAG, "⚠️ Using INSECURE WebSocket (WS) - OK for local drone connections only")
        }

        try {
            Log.e("WS_DEBUG", "📡 Target URL: $wsUrl")
            Log.d(TAG, "Building WebSocket request for URL: $wsUrl")
            val request = Request.Builder()
                .url(wsUrl)
                .build()

            Log.e("WS_DEBUG", "📡 Request built, calling client.newWebSocket()...")
            Log.d(TAG, "Creating WebSocket connection...")
            webSocket = client.newWebSocket(request, socketListener)
            Log.e("WS_DEBUG", "✅ client.newWebSocket() called - waiting for onOpen/onFailure")
            Log.e(TAG, "✅ Attempting to connect to WebSocket at $wsUrl")
        } catch (e: Exception) {
            Log.e("WS_DEBUG", "❌❌❌ EXCEPTION in connect(): ${e.message}", e)
            Log.e(TAG, "❌ Failed to connect to WebSocket: ${e.message}", e)
            e.printStackTrace()
        }
    }

    private val socketListener = object : WebSocketListener() {

        // ✅ STEP 2 — Send session_start on connection
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // 🔥 CRITICAL DEBUG LOG - MUST SEE THIS IN LOGCAT
            Log.e("WS_DEBUG", "🔥🔥🔥 onOpen() CALLED — preparing session_start 🔥🔥🔥")

            isConnected = true
            sessionStarted = false
            readyForTelemetry = false
            connectionOpenedTime = System.currentTimeMillis()
            sessionAckReceivedTime = 0

            // 🔥 Enhanced logging for debugging backend connection
            Log.e(TAG, "🔥🔥🔥 WebSocket CONNECTED to: $wsUrl 🔥🔥🔥")
            Log.e(TAG, "📡 Response Code: ${response.code}")
            Log.e(TAG, "📡 Response Message: ${response.message}")
            Log.e(TAG, "📡 Response Protocol: ${response.protocol}")
            Log.e(TAG, "📡 This should trigger TelemetryConsumer.connect() in Django backend!")

            // 🔥 Reset mission statistics when new session starts
            missionAlertsCount = 0
            missionBatteryStart = batteryRemaining  // Capture current battery as start
            Log.d(TAG, "📊 Mission stats reset - Battery start: $missionBatteryStart%")

            // 🔥 Resolve drone UID and log details
            val droneUidToSend = resolveDroneUid()
            val isFallback = droneUid.isBlank()
            Log.d(TAG, "📋 Session Details:")
            Log.d(TAG, "   - Admin ID: $adminId")
            Log.d(TAG, "   - Pilot ID: $pilotId")
            Log.d(TAG, "   - Drone UID: $droneUidToSend ${if (isFallback) "(⚠️ FALLBACK - FC not yet identified)" else "(✅ REAL FC UID)"}")
            Log.d(TAG, "   - Plot: $selectedPlotName")
            Log.d(TAG, "   - Flight Mode: $selectedFlightMode")
            Log.d(TAG, "   - Mission Type: $selectedMissionType")

            try {
                val sessionStart = JSONObject().apply {
                    put("type", "session_start")
                    put("vehicle_name", "DRONE_01") // MUST match DB
                    put("admin_id", adminId)
                    put("pilot_id", pilotId)
                    // 🔥 REAL DRONE ID from Flight Controller (with SITL fallback)
                    put("drone_uid", droneUidToSend)
                    // 🔥 Plot name from UI
                    put("plot_name", selectedPlotName)
                    // 🔥 Flight mode - Automatic or Manual
                    put("flight_mode", selectedFlightMode)
                    // 🔥 Mission type - Grid or Waypoint
                    put("mission_type", selectedMissionType)
                    // 🔥 Grid setup source - How grid boundary was created
                    put("grid_setup_source", gridSetupSource)
                }

                val payload = sessionStart.toString()

                // 🔥 CRITICAL: Log BEFORE sending
                Log.e("WS_DEBUG", "📤 About to send session_start payload: $payload")

                val sent = webSocket.send(payload)

                // 🔥 CRITICAL DEBUG LOG - Check if send() succeeded
                Log.e("WS_DEBUG", "📤📤📤 session_start send result = $sent 📤📤📤")

                if (!sent) {
                    Log.e("WS_DEBUG", "❌❌❌ CRITICAL: send() returned FALSE - message NOT sent! ❌❌❌")
                } else {
                    Log.e("WS_DEBUG", "✅ send() returned TRUE - message should reach server")
                }

                Log.e(TAG, "📤📤📤 Sending session_start to Django backend 📤📤📤")
                Log.e(TAG, "📤 Payload: $payload")
                Log.e(TAG, "📤 Send result: $sent")
                Log.e(TAG, "📤 Waiting for session_ack from TelemetryConsumer...")
            } catch (e: Exception) {
                Log.e("WS_DEBUG", "❌❌❌ EXCEPTION in onOpen while sending session_start: ${e.message}", e)
                e.printStackTrace()
            }

            // 🔥 FALLBACK: Enable telemetry after timeout if backend doesn't send session_ack
            // Cancel any existing timeout
            sessionAckTimeoutRunnable?.let { handler.removeCallbacks(it) }

            // Create new timeout runnable
            sessionAckTimeoutRunnable = Runnable {
                if (!readyForTelemetry && isConnected) {
                    Log.w(TAG, "⚠️ Backend didn't send session_ack within ${sessionAckTimeout}ms")
                    Log.w(TAG, "🔄 Enabling telemetry anyway (fallback mode)")
                    sessionStarted = true
                    readyForTelemetry = true
                    telemetryEnabled = true
                    Log.d(TAG, "✅ Telemetry enabled via FALLBACK mechanism")
                }
            }

            // Schedule timeout
            handler.postDelayed(sessionAckTimeoutRunnable!!, sessionAckTimeout)
            Log.d(TAG, "⏱️ Started ${sessionAckTimeout}ms timeout for session_ack")
        }

        // ✅ STEP 3 — Android MUST wait for ACK
        override fun onMessage(webSocket: WebSocket, text: String) {
            // 🔥 CRITICAL DEBUG - Log ALL incoming messages
            Log.e("WS_DEBUG", "🔥 onMessage() CALLED - received data from server")
            Log.e("WS_DEBUG", "📩 Raw text length: ${text.length}")
            Log.e(TAG, "📩📩📩 MESSAGE FROM DJANGO BACKEND 📩📩📩")
            Log.e(TAG, "📩 Raw message: $text")

            try {
                val msg = JSONObject(text)
                val messageType = msg.getString("type")

                Log.e(TAG, "📩 Message type: $messageType")

                when (messageType) {
                    "session_ack" -> {
                        // Cancel the timeout since we got the ack
                        sessionAckTimeoutRunnable?.let { handler.removeCallbacks(it) }

                        sessionStarted = true
                        readyForTelemetry = true
                        telemetryEnabled = true
                        sessionAckReceivedTime = System.currentTimeMillis()

                        Log.e(TAG, "✅✅✅ SESSION_ACK RECEIVED FROM BACKEND - TELEMETRY ENABLED ✅✅✅")
                        Log.e(TAG, "✅ TelemetryConsumer.receive() was triggered successfully!")
                        Log.e(TAG, "✅ Backend is properly configured and responding correctly")
                        Log.e(TAG, "⏳ Waiting for mission_created message from backend...")
                    }
                    "mission_created" -> {
                        missionId = msg.getString("mission_id")
                        readyForTelemetry = true
                        // 🔥 Reset reconnect attempts on successful mission creation
                        reconnectAttempts = 0
                        Log.e(TAG, "🚀🚀🚀 MISSION CREATED BY BACKEND 🚀🚀🚀")
                        Log.e(TAG, "🚀 Mission ID: $missionId")
                        Log.e(TAG, "🚀 Mission was inserted into PostgreSQL database!")
                    }
                    "error" -> {
                        // 🔥 Handle error messages from backend
                        val errorMessage = msg.optString("message", "Unknown error")
                        Log.e(TAG, "❌❌❌ ERROR FROM BACKEND ❌❌❌")
                        Log.e(TAG, "❌ Error message: $errorMessage")
                        Log.e(TAG, "❌ Check: Admin(id=$adminId) and Pilot(id=$pilotId) must exist in database!")
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
            // 🔥 CRITICAL DEBUG - Connection failure tracking
            Log.e("WS_DEBUG", "❌❌❌ onFailure() CALLED ❌❌❌")
            Log.e("WS_DEBUG", "❌ Error: ${t.javaClass.simpleName}: ${t.message}")
            Log.e("WS_DEBUG", "❌ Response code: ${response?.code}")

            // Cancel timeout
            sessionAckTimeoutRunnable?.let { handler.removeCallbacks(it) }

            isConnected = false
            sessionStarted = false
            telemetryEnabled = false
            readyForTelemetry = false
            Log.e(TAG, "❌❌❌ WEBSOCKET CONNECTION FAILED ❌❌❌")
            Log.e(TAG, "❌ Error: ${t.message}", t)
            Log.e(TAG, "❌ Response: ${response?.toString()}")
            Log.e(TAG, "❌ Response code: ${response?.code}")
            Log.e(TAG, "❌ Error type: ${t.javaClass.simpleName}")
            Log.e(TAG, "❌ URL attempted: $wsUrl")
            Log.e(TAG, "❌ Check if Django server is running: daphne -b 0.0.0.0 -p 8000 pavaman_gcs.asgi:application")
            t.printStackTrace()

            // 🔥 Trigger auto-reconnection on failure
            scheduleReconnect("connection_failure")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // 🔥 CRITICAL DEBUG - Track when connection is closing
            Log.e("WS_DEBUG", "⚠️⚠️⚠️ onClosing() CALLED ⚠️⚠️⚠️")
            Log.e("WS_DEBUG", "⚠️ Close code: $code, reason: $reason")
            Log.e("WS_DEBUG", "⚠️ sessionStarted=$sessionStarted, readyForTelemetry=$readyForTelemetry, missionId=$missionId")

            // Cancel timeout
            sessionAckTimeoutRunnable?.let { handler.removeCallbacks(it) }

            // 🔥 Detect if connection closed immediately after session_ack (backend error!)
            if (sessionAckReceivedTime > 0 && missionId == null) {
                val timeSinceAck = System.currentTimeMillis() - sessionAckReceivedTime
                if (timeSinceAck < 500) {
                    Log.e(TAG, "🔴🔴🔴 CONNECTION CLOSED ${timeSinceAck}ms AFTER session_ack! 🔴🔴🔴")
                    Log.e(TAG, "🔴 This means the BACKEND failed during database operations!")
                    Log.e(TAG, "🔴 Check Django/Daphne logs for: Admin.DoesNotExist or Pilot.DoesNotExist")
                    Log.e(TAG, "🔴 Verify Admin(id=$adminId) and Pilot(id=$pilotId) exist in database!")
                }
            }

            // 🔥 Mark as disconnected to prevent further sends, but keep session state for reconnect
            isConnected = false
            readyForTelemetry = false
            Log.d(TAG, "WebSocket closing: $code / $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // 🔥 CRITICAL DEBUG - Track when connection is fully closed
            Log.e("WS_DEBUG", "🔴🔴🔴 onClosed() CALLED 🔴🔴🔴")
            Log.e("WS_DEBUG", "🔴 Close code: $code, reason: $reason")

            // Cancel timeout
            sessionAckTimeoutRunnable?.let { handler.removeCallbacks(it) }

            isConnected = false
            sessionStarted = false
            telemetryEnabled = false
            readyForTelemetry = false
            Log.d(TAG, "WebSocket closed: $code / $reason")

            // 🔥 Auto-reconnect unless this was a deliberate disconnect (code 1000 from client)
            // Code 1000 from server = unexpected close, should reconnect
            // Code 1000 from disconnect() = deliberate, don't reconnect
            if (shouldReconnect) {
                scheduleReconnect("server_closed_$code")
            }
        }
    }

    /**
     * Send drone UID update to backend when real UID becomes available after session_start
     * This handles the timing issue where session_start is sent before AUTOPILOT_VERSION is received
     */
    private fun sendDroneUidUpdate(realDroneUid: String) {
        if (!isConnected || !::webSocket.isInitialized) {
            Log.w(TAG, "⚠️ Cannot send drone_uid_update - not connected")
            return
        }

        try {
            val msg = JSONObject().apply {
                put("type", "drone_uid_update")
                put("mission_id", missionId)
                put("drone_uid", realDroneUid)
            }
            val sent = webSocket.send(msg.toString())
            Log.i(TAG, "📤🔥 Sent drone_uid_update: mission=$missionId, droneUid=$realDroneUid (sent=$sent)")
            Log.i(TAG, "✅ Backend should now update vehicle_id from SITL_DRONE_001 to real UID: $realDroneUid")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send drone_uid_update: ${e.message}", e)
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
     * @param totalAcres Total area covered in acres
     * @param totalSprayUsed Total spray/liquid used (liters)
     * @param flyingTimeMinutes Total flying time in minutes
     * @param averageSpeed Average speed during mission (m/s)
     * @param batteryStart Battery percentage at mission start
     * @param batteryEnd Battery percentage at mission end
     * @param alertsCount Number of alerts/warnings during mission
     * @param status Mission completion status ("COMPLETED" or "FAILED")
     * @param projectName Project name entered by user
     * @param plotName Plot name entered by user
     * @param cropType Crop type entered by user (optional)
     * @param totalSprayedAcres Total acres sprayed (distance with spray ON)
     */
    fun sendMissionSummary(
        totalAcres: Double,
        totalSprayUsed: Double,
        flyingTimeMinutes: Double,
        averageSpeed: Double,
        batteryStart: Int,
        batteryEnd: Int,
        alertsCount: Int,
        status: String,  // "COMPLETED" or "FAILED"
        projectName: String = "",
        plotName: String = "",
        cropType: String = "",
        totalSprayedAcres: Double = 0.0
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

                put("total_acres", totalAcres)
                put("total_spray_used", totalSprayUsed)
                put("flying_time_minutes", flyingTimeMinutes)
                put("average_speed", averageSpeed)

                put("battery_start", batteryStart)
                put("battery_end", batteryEnd)

                put("alerts_count", alertsCount)
                put("status", status)  // COMPLETED / FAILED

                // Additional fields from completion dialog
                put("project_name", projectName)
                put("plot_name", plotName)
                put("crop_type", cropType)
                put("total_sprayed_acres", totalSprayedAcres)
            }

            webSocket.send(msg.toString())
            Log.d(TAG, "📤 Mission summary sent: acres=$totalAcres, spray=$totalSprayUsed, time=$flyingTimeMinutes min, " +
                "speed=$averageSpeed, battery=$batteryStart%→$batteryEnd%, alerts=$alertsCount, status=$status, " +
                "project=$projectName, plot=$plotName, crop=$cropType, sprayedAcres=$totalSprayedAcres")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send mission summary", e)
        }
    }

    /**
     * Schedule auto-reconnection after connection failure or unexpected close
     */
    private fun scheduleReconnect(reason: String) {
        if (!shouldReconnect) {
            Log.d(TAG, "🔌 Auto-reconnect disabled, skipping reconnection")
            return
        }

        if (reconnectAttempts >= maxReconnectAttempts) {
            Log.e(TAG, "❌ Max reconnection attempts ($maxReconnectAttempts) reached. Giving up.")
            shouldReconnect = false
            return
        }

        reconnectAttempts++
        val delay = reconnectDelayMs * reconnectAttempts  // Exponential backoff

        Log.w(TAG, "🔄 Scheduling reconnection attempt $reconnectAttempts/$maxReconnectAttempts in ${delay}ms (reason: $reason)")

        // Cancel any existing reconnect runnable
        reconnectRunnable?.let { handler.removeCallbacks(it) }

        reconnectRunnable = Runnable {
            if (shouldReconnect && !isConnected) {
                Log.i(TAG, "🔄 Attempting reconnection ($reconnectAttempts/$maxReconnectAttempts)...")
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
                Log.i(TAG, "🔌 WebSocket disconnecting - Mission ended")
            } else {
                Log.d(TAG, "🔌 WebSocket already disconnected")
            }

            // Reset state
            isConnected = false
            sessionStarted = false
            telemetryEnabled = false
            readyForTelemetry = false
            missionId = null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during WebSocket disconnect: ${e.message}", e)
        }
    }

    /**
     * Check if WebSocket is ready to send telemetry
     * @return true if connected, session acknowledged, and mission created
     */
    fun isReadyForTelemetry(): Boolean {
        return isConnected && readyForTelemetry && sessionStarted && missionId != null
    }

    /**
     * Force reset connection state and attempt fresh connection
     */
    fun reconnect() {
        Log.i(TAG, "🔄 Force reconnecting WebSocket...")
        if (::webSocket.isInitialized) {
            try {
                webSocket.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel existing WebSocket", e)
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
}
