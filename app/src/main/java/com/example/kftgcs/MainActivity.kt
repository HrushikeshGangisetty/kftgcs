package com.example.kftgcs

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.example.kftgcs.navigation.AppNavGraph
import com.example.kftgcs.integration.TlogIntegration
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.viewbinding.BuildConfig
//import com.example.kftgcs.BuildConfig
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.example.kftgcs.telemetry.SharedViewModel
import com.example.kftgcs.telemetry.WebSocketManager
import com.example.kftgcs.api.SessionManager

// ✅ Dark theme setup
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF1E88E5),
    onPrimary = Color.White,
    background = Color.Black,
    surface = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

class MainActivity : ComponentActivity() {

    private val hasRequiredPermissions = mutableStateOf(false)
    private val wsManager by lazy { WebSocketManager.getInstance() }

    // 🔥 Flag to prevent duplicate low battery events
    private var lowBatteryEventSent = false

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fineLocation = grants.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)
        val coarseLocation = grants.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)

        val bluetoothScanGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            grants.getOrDefault(Manifest.permission.BLUETOOTH_SCAN, false)
        } else {
            true // Not required for older APIs
        }

        val bluetoothConnectGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            grants.getOrDefault(Manifest.permission.BLUETOOTH_CONNECT, false)
        } else {
            true // Not required for older APIs
        }

        // For the app to function, we need location and the relevant BT permissions.
        hasRequiredPermissions.value = (fineLocation || coarseLocation) && bluetoothScanGranted && bluetoothConnectGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        // Handle the splash screen transition.
        installSplashScreen()

        super.onCreate(savedInstanceState)

        // MapsInitializer is now called early in GCSApplication.onCreate()
        // to avoid race conditions with map tile loading

        // ✅ WebSocket connection is now managed by mission lifecycle
        // - Opens when mission starts (SharedViewModel.startMission)
        // - Closes when mission ends (TelemetryRepository)

        // ✅ Get pilotId and adminId from SessionManager (values from database after login)
        val pilotId = SessionManager.getPilotId(this)
        val adminId = SessionManager.getAdminId(this)


        // ✅ Pre-set the values on WebSocketManager (connection happens on mission start)
        wsManager.pilotId = pilotId
        wsManager.adminId = adminId
        wsManager.droneUid = ""  // Will be updated when FC sends AUTOPILOT_VERSION (leave blank to force real UID)

        if (BuildConfig.DEBUG) {
            android.util.Log.d("MAIN_ACTIVITY", "🔧 WebSocketManager initialized with pilotId=$pilotId, adminId=$adminId")
            android.util.Log.d("MAIN_ACTIVITY", "⏳ Waiting for AUTOPILOT_VERSION to set real drone UID...")
        }

        // ✅ Throttled telemetry sender - sends every 1 second (only when connected)
        Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
            override fun run() {
                // Only send telemetry if WebSocket is connected
                if (wsManager.isConnected) {
                    wsManager.sendTelemetry()
                }
                Handler(Looper.getMainLooper()).postDelayed(this, 1000)
            }
        }, 1000)

        setContent {
            val navController = rememberNavController()
            val systemUiController = rememberSystemUiController()

            // Create SharedViewModel instance and initialize TTS
            val sharedViewModel: SharedViewModel = viewModel()

            // Initialize TextToSpeech when the app starts
            LaunchedEffect(Unit) {
                sharedViewModel.initializeTextToSpeech(this@MainActivity)
            }

            // Monitor flight status for crash handler and update WebSocket telemetry
            LaunchedEffect(Unit) {
                sharedViewModel.telemetryState.collect { telemetryState ->
                    // Update connection status
                    GCSApplication.isConnectedToDrone = telemetryState.connected

                    // Determine if drone is in flight
                    val isArmed = telemetryState.armed
                    val altitude = telemetryState.altitudeRelative ?: 0f
                    val isInAir = altitude > 0.5f // Consider drone in flight if >0.5m altitude

                    GCSApplication.isDroneInFlight = isArmed && isInAir

                    // ✅ Update WebSocket telemetry with real-time MAVSDK data
                    // NOTE: This updates the values frequently, but sendTelemetry() is only called every 1 second by the Handler
                    if (telemetryState.connected) {
                        // Position
                        wsManager.lat = telemetryState.latitude ?: 0.0
                        wsManager.lng = telemetryState.longitude ?: 0.0
                        wsManager.alt = (telemetryState.altitudeRelative ?: 0f).toDouble()
                        wsManager.speed = (telemetryState.groundspeed ?: 0f).toDouble()

                        // Attitude
                        wsManager.roll = (telemetryState.roll ?: 0f).toDouble()
                        wsManager.pitch = (telemetryState.pitch ?: 0f).toDouble()
                        wsManager.yaw = (telemetryState.heading ?: 0f).toDouble()

                        // Battery
                        wsManager.voltage = (telemetryState.voltage ?: 0f).toDouble()
                        wsManager.current = (telemetryState.currentA ?: 0f).toDouble()
                        wsManager.batteryRemaining = telemetryState.batteryPercent ?: 0

                        // GPS
                        wsManager.satellites = telemetryState.sats ?: 0
                        wsManager.hdop = (telemetryState.hdop ?: 0f).toDouble()

                        // Status
                        wsManager.flightMode = telemetryState.mode ?: "UNKNOWN"
                        wsManager.isArmed = telemetryState.armed
                        wsManager.failsafe = false // TODO: Add failsafe detection if available

                        // Spray telemetry
                        wsManager.sprayOn = telemetryState.sprayTelemetry.sprayEnabled
                        wsManager.sprayRate = (telemetryState.sprayTelemetry.flowRateLiterPerMin ?: 0f).toDouble()
                        wsManager.flowPulse = telemetryState.sprayTelemetry.rc7Value ?: 0
                        wsManager.tankLevel = (telemetryState.sprayTelemetry.tankLevelPercent ?: 0).toDouble()

                        // 🔥 Drone UID from Flight Controller (AUTOPILOT_VERSION)
                        telemetryState.droneUid?.let { uid ->
                            if (wsManager.droneUid != uid) {
                                wsManager.droneUid = uid
                            }
                        }

                        // 🔥 Low Battery Event Detection
                        val batteryPercent = telemetryState.batteryPercent
                        if (batteryPercent != null && batteryPercent <= 20 && !lowBatteryEventSent && wsManager.isConnected) {
                            try {
                                wsManager.sendMissionEvent(
                                    eventType = "LOW_BATTERY",
                                    eventStatus = "WARNING",
                                    description = "Battery dropped below 20% (${batteryPercent}%)"
                                )
                                lowBatteryEventSent = true
                            } catch (e: Exception) {
                                // Failed to send LOW_BATTERY event - silently handled
                            }
                        } else if (batteryPercent != null && batteryPercent > 25) {
                            // Reset flag when battery is above 25% (allows re-triggering if battery replaced)
                            lowBatteryEventSent = false
                        }

                        // NOTE: Don't call sendTelemetry() here - throttled sender handles it every 1 second
                    }

                    // Drone in flight status tracked for crash protection
                }
            }

            // Hide the system status bar for immersive experience
            SideEffect {
                systemUiController.isStatusBarVisible = false
            }
            MaterialTheme(colorScheme = DarkColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavGraph(navController = navController)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        askForPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cleanup TlogIntegration when activity is destroyed
        TlogIntegration.destroy()
        // Cleanup WebSocket connection
        wsManager.disconnect()
    }


    private fun askForPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // POST_NOTIFICATIONS is required on Android 13+ (API 33) for foreground service
        // notifications to be visible. Without this, the persistent notification for
        // VideoRelayService may be silently blocked by the OS.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
    }
}