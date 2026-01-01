package com.example.aerogcsclone.telemetry

import android.util.Log
import okhttp3.*
import org.json.JSONObject

/**
 * WebSocketManager - Handles telemetry streaming with session acknowledgment protocol
 *
 * ✅ STEP 1 — Session Flags (initialized to false)
 * ✅ STEP 2 — Send session_start on connection (onOpen)
 * ✅ STEP 3 — Wait for session_ack from server (onMessage)
 * ✅ STEP 4 — Gate telemetry until readyForTelemetry = true (sendTelemetry)
 */
class WebSocketManager {

    private val TAG = "WebSocketManager"
    private val client = OkHttpClient()
    private lateinit var webSocket: WebSocket

    // ✅ STEP 1 — Session Flags (DO NOT CHANGE INITIAL VALUES)
    private var sessionStarted = false
    private var telemetryEnabled = false
    private var readyForTelemetry = false

    // Mission tracking (received from backend)
    var missionId: String? = null
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

    // Change this URL based on your setup:
    // For emulator: ws://10.0.2.2:8080/ws/telemetry
    // For real device: ws://YOUR_PC_IP:8080/ws/telemetry (e.g., ws://192.168.1.100:8080/ws/telemetry)
    private val WS_URL = "ws://10.0.2.2:8080/ws/telemetry"

    var isConnected = false
        private set

    fun connect() {
        Log.e("WebSocketManager", "🔥 connect() method CALLED")
        try {
            Log.d(TAG, "Building WebSocket request for URL: $WS_URL")
            val request = Request.Builder()
                .url(WS_URL)
                .build()

            Log.d(TAG, "Creating WebSocket connection...")
            webSocket = client.newWebSocket(request, socketListener)
            Log.e(TAG, "✅ Attempting to connect to WebSocket at $WS_URL")
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

            val sessionStart = JSONObject().apply {
                put("type", "session_start")
                put("vehicle_name", "DRONE_01") // MUST match DB
            }

            webSocket.send(sessionStart.toString())
            Log.d(TAG, "📤 Sent session_start for DRONE_01")
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

        try {
            val telemetry = JSONObject().apply {
                put("type", "telemetry")
                put("ts", System.currentTimeMillis())

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
            }

            webSocket.send(telemetry.toString())
            Log.d(TAG, "📤 Sent telemetry: lat=$lat, lng=$lng, alt=$alt, speed=$speed, " +
                "voltage=$voltage, current=$current, battery=$batteryRemaining%, " +
                "mode=$flightMode, armed=$isArmed")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending telemetry: ${e.message}", e)
        }
    }

    fun disconnect() {
        if (isConnected) {
            webSocket.close(1000, "Client disconnect")
            Log.d(TAG, "Disconnecting WebSocket")
        }
    }
}

