package com.example.aerogcsclone

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
import com.example.aerogcsclone.navigation.AppNavGraph
import com.example.aerogcsclone.integration.TlogIntegration
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.MapsInitializer
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.example.aerogcsclone.telemetry.SharedViewModel
import com.example.aerogcsclone.telemetry.WebSocketManager
import com.example.aerogcsclone.api.SessionManager

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
    private val wsManager = WebSocketManager()

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
        android.util.Log.e("MAIN_ACTIVITY", "🔥 MainActivity onCreate CALLED")

        // Handle the splash screen transition.
        installSplashScreen()

        super.onCreate(savedInstanceState)

        // Initialize Maps SDK with new API
        MapsInitializer.initialize(applicationContext, MapsInitializer.Renderer.LATEST) {
            // You can log or handle the chosen renderer here
        }

        // ✅ Connect to WebSocket server for telemetry streaming
        android.util.Log.e("MAIN_ACTIVITY", "🔌 About to connect WebSocket...")

        // ✅ Get pilotId and adminId from SessionManager (values from database after login)
        val pilotId = SessionManager.getPilotId(this)
        val adminId = SessionManager.getAdminId(this)

        android.util.Log.e("MAIN_ACTIVITY", "📋 SessionManager values: pilotId=$pilotId, adminId=$adminId")

        // ✅ Set the values on WebSocketManager before connecting
        wsManager.pilotId = pilotId
        wsManager.adminId = adminId

        wsManager.connect()
        android.util.Log.e("MAIN_ACTIVITY", "✅ WebSocket connect() method called with pilotId=$pilotId, adminId=$adminId")

        // ✅ Throttled telemetry sender - sends every 1 second instead of on every update
        Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
            override fun run() {
                wsManager.sendTelemetry()
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
                    if (telemetryState.connected) {
                        android.util.Log.d("WebSocketTelemetry", "📊 Updating telemetry data from MAVSDK (connected=${telemetryState.connected})")

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

                        // Debug logging for battery telemetry
                        android.util.Log.d("WebSocketTelemetry", "Battery Data -> " +
                            "Voltage: ${telemetryState.voltage}, " +
                            "Current: ${telemetryState.currentA}, " +
                            "Remaining: ${telemetryState.batteryPercent}%")

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

                        // NOTE: Don't call sendTelemetry() here - throttled sender handles it
                    } else {
                        android.util.Log.w("WebSocketTelemetry", "⚠️ Skipping telemetry update - drone not connected")
                    }

                    // Log status changes for debugging
                    if (GCSApplication.isDroneInFlight) {
                        android.util.Log.d("MainActivity", "Drone in flight - crash protection active (Alt: ${altitude}m)")
                    }
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

        requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
    }
}