package com.example.aerogcsclone.navigation

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.aerogcsclone.authentication.AuthViewModel
import com.example.aerogcsclone.authentication.LoginPage
import com.example.aerogcsclone.authentication.SignupPage
import com.example.aerogcsclone.authentication.WelcomeScreen
import com.example.aerogcsclone.authentication.OtpVerificationPage
import com.example.aerogcsclone.calibration.CalibrationScreen
import com.example.aerogcsclone.calibration.CalibrationViewModel
import com.example.aerogcsclone.calibration.CompassCalibrationScreen
import com.example.aerogcsclone.calibration.CompassCalibrationViewModel
import com.example.aerogcsclone.calibration.RCCalibrationScreen
import com.example.aerogcsclone.calibration.RCCalibrationViewModel
import com.example.aerogcsclone.calibration.BarometerCalibrationScreen
import com.example.aerogcsclone.calibration.BarometerCalibrationViewModel
import com.example.aerogcsclone.calibration.LevelCalibrationScreen
import com.example.aerogcsclone.calibration.LevelCalibrationViewModel
import com.example.aerogcsclone.integration.TlogIntegration
import com.example.aerogcsclone.telemetry.SharedViewModel
import com.example.aerogcsclone.uiconnection.ConnectionPage
import com.example.aerogcsclone.uimain.MainPage
import com.example.aerogcsclone.uimain.PlanScreen
import com.example.aerogcsclone.uimain.TopNavBar
import com.example.aerogcsclone.uimain.SettingsScreen
import com.example.aerogcsclone.uimain.SecurityScreen
import com.example.aerogcsclone.uimain.CalibrationsScreen
import com.example.aerogcsclone.uimain.SprayCalibrationScreen
import com.example.aerogcsclone.uimain.LevelSensorCalibrationScreen
import com.example.aerogcsclone.uimain.FlowSensorCalibrationScreen
import com.example.aerogcsclone.ui.components.PlotTemplatesScreen
import com.example.aerogcsclone.ui.logs.LogsScreen
import com.example.aerogcsclone.ui.LanguageSelectionPage
import com.example.aerogcsclone.uiflyingmethod.SelectFlyingMethodScreen
import com.example.aerogcsclone.viewmodel.MissionTemplateViewModel
import com.example.aerogcsclone.viewmodel.TlogViewModel

sealed class Screen(val route: String) {
    object Welcome : Screen("welcome")
    object Connection : Screen("connection")
    object Main : Screen("main")
    object Login : Screen("login")
    object Signup : Screen("signup")
    object LanguageSelection : Screen("language_selection")
    object Plan : Screen("plan")
    object PlotTemplates : Screen("plot_templates")
    object Logs : Screen("logs")
    object SelectMethod : Screen("select_method")
    object Settings : Screen("settings")
    object Calibrations : Screen("calibrations")
    object CompassCalibration : Screen("compass_calibration")
    object AccelerometerCalibration : Screen("accelerometer_calibration")
    object LevelCalibration : Screen("level_calibration")
    object BarometerCalibration : Screen("barometer_calibration")
    object SprayingSystem : Screen("spraying_system")
    object RemoteController : Screen("remote_controller")
    object Aircraft : Screen("aircraft")
    object RangeFinderSettings : Screen("rangefinder_settings")
    object AboutApp : Screen("about_app")
    // Spray Calibration routes
    object SprayCalibration : Screen("spray_calibration")
    object FlowSensorCalibration : Screen("flow_sensor_calibration")
    object LevelSensorCalibration : Screen("level_sensor_calibration")
    object PumpCalibration : Screen("pump_calibration")
    object SpraySystemTest : Screen("spray_system_test")
    object Security : Screen("security")
}

@Composable
fun AppNavGraph(navController: NavHostController) {
    val context = LocalContext.current
    val application = context.applicationContext as Application

    // Create SharedViewModel at the top level so it can be shared across screens
    val sharedViewModel: SharedViewModel = viewModel()

    // Create AuthViewModel at the top level
    val authViewModel: AuthViewModel = viewModel()

    // Check authentication status on app start
    LaunchedEffect(Unit) {
        authViewModel.checkAuthStatus(context)
    }

    // Initialize TTS when the navigation graph is created
    LaunchedEffect(Unit) {
        sharedViewModel.initializeTextToSpeech(context)
    }

    // Initialize TlogIntegration with proper parameters
    LaunchedEffect(Unit) {
        TlogIntegration.initialize(application, context as androidx.lifecycle.ViewModelStoreOwner, sharedViewModel)
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Welcome.route
    ) {
        composable(Screen.Welcome.route) {
            WelcomeScreen(navController = navController)
        }

        composable(Screen.Login.route) {
            LoginPage(
                navController = navController,
                authViewModel = authViewModel
            )
        }

        composable(Screen.Signup.route) {
            SignupPage(
                navController = navController,
                authViewModel = authViewModel
            )
        }

        composable("otp_verification/{email}") { backStackEntry ->
            val email = backStackEntry.arguments?.getString("email") ?: ""
            OtpVerificationPage(
                navController = navController,
                authViewModel = authViewModel,
                email = email
            )
        }

        composable(Screen.LanguageSelection.route) {
            LanguageSelectionPage(
                navController = navController,
                sharedViewModel = sharedViewModel
            )
        }

        composable(Screen.Connection.route) {
            // Pass the shared SharedViewModel to ConnectionPage for TTS announcements
            ConnectionPage(navController, sharedViewModel)
        }

        composable(Screen.Main.route) {
            val telemetryState by sharedViewModel.telemetryState.collectAsState()
            Column {
                TopNavBar(
                    telemetryState = telemetryState,
                    authViewModel = authViewModel,
                    navController = navController,
                    onToggleNotificationPanel = { sharedViewModel.toggleNotificationPanel() },
                    telemetryViewModel = sharedViewModel // Added SharedViewModel parameter
                )
                MainPage(
                    navController = navController,
                    authViewModel = authViewModel,
                    telemetryViewModel = sharedViewModel
                )
            }
        }

        composable(Screen.Plan.route) {
            val missionTemplateViewModel: MissionTemplateViewModel = viewModel()
            val telemetryState by sharedViewModel.telemetryState.collectAsState()

            // Render PlanScreen without the TopNavBar so the map can use the full screen
            PlanScreen(
                navController = navController,
                authViewModel = authViewModel,
                telemetryViewModel = sharedViewModel,
                missionTemplateViewModel = missionTemplateViewModel
            )
        }

        composable(Screen.PlotTemplates.route) {
            val missionTemplateViewModel: MissionTemplateViewModel = viewModel()
            val telemetryState by sharedViewModel.telemetryState.collectAsState()
            val templates by missionTemplateViewModel.templates.collectAsState(initial = emptyList())

            // TopNavBar removed - show only the plot templates screen
            PlotTemplatesScreen(
                templates = templates,
                onLoadTemplate = { template ->
                    missionTemplateViewModel.loadTemplate(template.id)
                    navController.navigate(Screen.Plan.route)
                },
                onDeleteTemplate = { template ->
                    missionTemplateViewModel.deleteTemplate(template)
                }
            )
        }

        composable(Screen.Logs.route) {
            val tlogViewModel: TlogViewModel = viewModel()
            val telemetryState by sharedViewModel.telemetryState.collectAsState()

            // TopNavBar removed - show logs screen directly
            LogsScreen(
                navController = navController,
                authViewModel = authViewModel,
                telemetryViewModel = sharedViewModel,
                tlogViewModel = tlogViewModel
            )
        }

        composable(Screen.SelectMethod.route) {
            SelectFlyingMethodScreen(navController = navController, sharedViewModel = sharedViewModel)
        }

        composable(Screen.Calibrations.route) {
            val telemetryState by sharedViewModel.telemetryState.collectAsState()

            // TopNavBar removed - show calibrations screen directly
            CalibrationsScreen(
                navController = navController,
                sharedViewModel = sharedViewModel
            )
        }

        composable(Screen.CompassCalibration.route) {
            // Create CompassCalibrationViewModel with SharedViewModel for TTS announcements
            val compassCalibrationViewModel: CompassCalibrationViewModel = viewModel { CompassCalibrationViewModel(sharedViewModel) }
            val telemetryState by sharedViewModel.telemetryState.collectAsState()

            // TopNavBar removed - show compass calibration directly
            CompassCalibrationScreen(
                navController = navController,
                viewModel = compassCalibrationViewModel
            )
        }

        composable(Screen.AccelerometerCalibration.route) {
            // Create CalibrationViewModel with SharedViewModel for TTS announcements and IMU calibration
            val calibrationViewModel: CalibrationViewModel = viewModel { CalibrationViewModel(sharedViewModel) }
            val telemetryState by sharedViewModel.telemetryState.collectAsState()

            // TopNavBar removed - show accelerometer calibration directly
            CalibrationScreen(
                viewModel = calibrationViewModel,
                navController = navController
            )
        }

        composable(Screen.LevelCalibration.route) {
            // Create LevelCalibrationViewModel with SharedViewModel for TTS announcements and level calibration
            val levelCalibrationViewModel: LevelCalibrationViewModel = viewModel { LevelCalibrationViewModel(sharedViewModel) }
            val telemetryState by sharedViewModel.telemetryState.collectAsState()

            // TopNavBar removed - show level calibration directly
            LevelCalibrationScreen(
                viewModel = levelCalibrationViewModel,
                navController = navController
            )
        }

        composable(Screen.Settings.route) {
            val telemetryState by sharedViewModel.telemetryState.collectAsState()

            // TopNavBar removed - show settings directly
            SettingsScreen(navController = navController)
        }

        composable(Screen.Security.route) {
            val telemetryState by sharedViewModel.telemetryState.collectAsState()
            // TopNavBar removed - show security screen directly
            SecurityScreen(navController = navController)
        }

        // Actual screens for Barometer Calibration and Remote Controller
        composable(Screen.BarometerCalibration.route) {
            val barometerCalibrationViewModel: BarometerCalibrationViewModel = viewModel { BarometerCalibrationViewModel(sharedViewModel) }
            val telemetryState by sharedViewModel.telemetryState.collectAsState()

            // TopNavBar removed - show barometer calibration directly
            BarometerCalibrationScreen(
                navController = navController,
                viewModel = barometerCalibrationViewModel
            )
        }

        composable(Screen.SprayingSystem.route) {
            // Show Spray Calibration Screen
            SprayCalibrationScreen(navController = navController, sharedViewModel = sharedViewModel)
        }

        composable(Screen.RemoteController.route) {
            val telemetryState by sharedViewModel.telemetryState.collectAsState()

            // TopNavBar removed - show RC Calibration directly
            RCCalibrationScreen(
                viewModel = remember(sharedViewModel) { RCCalibrationViewModel(sharedViewModel) },
                navController = navController
            )
        }

        composable(Screen.Aircraft.route) {
            val telemetryState by sharedViewModel.telemetryState.collectAsState()

            // TopNavBar removed
            PlaceholderScreen("Aircraft", "Aircraft configuration coming soon!")
        }

        composable(Screen.RangeFinderSettings.route) {
            val telemetryState by sharedViewModel.telemetryState.collectAsState()

            // TopNavBar removed
            PlaceholderScreen("RangeFinder Settings", "RangeFinder configuration coming soon!")
        }

        composable(Screen.AboutApp.route) {
            val telemetryState by sharedViewModel.telemetryState.collectAsState()

            // TopNavBar removed
            PlaceholderScreen("About App", "Ground Control Station v1.0\nDeveloped for drone operations")
        }

        // Spray Calibration screens
        composable(Screen.SprayCalibration.route) {
            SprayCalibrationScreen(navController = navController, sharedViewModel = sharedViewModel)
        }

        composable(Screen.FlowSensorCalibration.route) {
            FlowSensorCalibrationScreen(navController = navController, sharedViewModel = sharedViewModel)
        }

        composable(Screen.LevelSensorCalibration.route) {
            // TODO: Implement Level Sensor Calibration Screen
            LevelSensorCalibrationScreen(navController = navController, sharedViewModel = sharedViewModel)
        }

        composable(Screen.PumpCalibration.route) {
            // TODO: Implement Pump Calibration Screen
            PlaceholderScreen("Pump Calibration", "Pump calibration configuration coming soon!")
        }

        composable(Screen.SpraySystemTest.route) {
            // TODO: Implement Spray System Test Screen
            PlaceholderScreen("Spray System Test", "Spray system test configuration coming soon!")
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, description: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF23272A))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 28.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Text(
                text = description,
                color = Color.Gray,
                fontSize = 16.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
