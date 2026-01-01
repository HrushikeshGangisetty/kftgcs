package com.example.aerogcsclone.utils

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Language Manager for app-wide localization
 * Provides strings in English and Telugu based on selected language
 */
object AppStrings {
    private var currentLanguage: String = "en" // Default to English

    fun setLanguage(languageCode: String) {
        currentLanguage = languageCode
    }

    fun getCurrentLanguage(): String = currentLanguage

    // Helper function to get string based on current language
    private fun getString(english: String, telugu: String): String {
        return if (currentLanguage == "en") english else telugu
    }

    // Connection Page Strings
    val connectionTitle get() = getString("Connect to Drone", "డ్రోన్‌కు కనెక్ట్ చేయండి")
    val connectionType get() = getString("Connection Type", "కనెక్షన్ రకం")
    val tcp get() = getString("TCP", "టీసీపీ")
    val bluetooth get() = getString("Bluetooth", "బ్లూటూత్")
    val ipAddress get() = getString("IP Address", "ఐపీ చిరునామా")
    val port get() = getString("Port", "పోర్ట్")
    val selectDevice get() = getString("Select Device", "పరికరాన్ని ఎంచుకోండి")
    val noDevicesPaired get() = getString("No devices paired", "పరికరాలు జత చేయబడలేదు")
    val connect get() = getString("Connect", "కనెక్ట్ చేయండి")
    val cancel get() = getString("Cancel", "రద్దు చేయండి")
    val connecting get() = getString("Connecting...", "కనెక్ట్ అవుతోంది...")
    val connectionTimedOut get() = getString("Connection timed out. Please check your settings and try again.", "కనెక్షన్ గడువు ముగిసింది. దయచేసి మీ సెట్టింగ్‌లను తనిఖీ చేసి మళ్లీ ప్రయత్నించండి.")
    val connectionFailed get() = getString("Connection failed", "కనెక్షన్ విఫలమైంది")

    // Select Method Page Strings
    val selectFlightMode get() = getString("Select Flight Mode", "ఫ్లైట్ మోడ్‌ను ఎంచుకోండి")
    val automatic get() = getString("Automatic", "ఆటోమేటిక్")
    val manual get() = getString("Manual", "మాన్యువల్")
    val automaticDesc get() = getString("Plan and execute missions automatically", "స్వయంచాలకంగా మిషన్‌లను ప్లాన్ చేసి అమలు చేయండి")
    val manualDesc get() = getString("Control the drone manually", "డ్రోన్‌ను మాన్యువల్‌గా నియంత్రించండి")

    // Main Page / Telemetry Strings
    val altitude get() = getString("Altitude", "ఎత్తు")
    val speed get() = getString("Speed", "వేగం")
    val battery get() = getString("Battery", "బ్యాటరీ")
    val satellites get() = getString("Satellites", "ఉపగ్రహాలు")
    val mode get() = getString("Mode", "మోడ్")
    val armed get() = getString("Armed", "ఆర్మ్ అయింది")
    val disarmed get() = getString("Disarmed", "డిసార్మ్ అయింది")
    val connected get() = getString("Connected", "కనెక్ట్ అయింది")
    val disconnected get() = getString("Disconnected", "డిస్కనెక్ట్ అయింది")

    // Calibration Strings
    val calibration get() = getString("Calibration", "కేలిబ్రేషన్")
    val compass get() = getString("Compass", "కంపాస్")
    val accelerometer get() = getString("Accelerometer", "యాక్సిలరోమీటర్")
    val barometer get() = getString("Barometer", "బేరోమీటర్")
    val remoteController get() = getString("Remote Controller", "రిమోట్ కంట్రోలర్")
    val startCalibration get() = getString("Start Calibration", "కేలిబ్రేషన్ ప్రారంభించండి")
    val cancelCalibration get() = getString("Cancel Calibration", "కేలిబ్రేషన్ రద్దు చేయండి")
    val calibrationInProgress get() = getString("Calibration in Progress", "కేలిబ్రేషన్ పురోగతిలో ఉంది")
    val calibrationComplete get() = getString("Calibration Complete", "కేలిబ్రేషన్ పూర్తయింది")
    val calibrationFailed get() = getString("Calibration Failed", "కేలిబ్రేషన్ విఫలమైంది")
    val calibrationStarted get() = getString("Calibration Started", "కేలిబ్రేషన్ ప్రారంభమైంది")

    // IMU Calibration Position Strings
    val placeVehicleLevel get() = getString("Place vehicle level", "వాహనాన్ని సమానంగా ఉంచండి")
    val placeVehicleOnLeft get() = getString("Place vehicle on left side", "వాహనాన్ని ఎడమ వైపు ఉంచండి")
    val placeVehicleOnRight get() = getString("Place vehicle on right side", "వాహనాన్ని కుడి వైపు ఉంచండి")
    val placeVehicleNoseDown get() = getString("Place vehicle nose down", "వాహనాన్ని నోస్ కిందకి ఉంచండి")
    val placeVehicleNoseUp get() = getString("Place vehicle nose up", "వాహనాన్ని నోస్ పైకి ఉంచండి")
    val placeVehicleOnBack get() = getString("Place vehicle on back", "వాహనాన్ని వెనక్కి తిప్పండి")

    // IMU Position voice announcements (more natural speech)
    val imuLevel get() = getString("Place the vehicle level", "అన్ని వైపులా సమానంగా పెట్టండి")
    val imuLeft get() = getString("Place on left side", "ఎడమ వైపు పెట్టండి")
    val imuRight get() = getString("Place on right side", "కుడి వైపు పెట్టండి")
    val imuNoseDown get() = getString("Place nose down", "నోస్ కిందకి పెట్టండి")
    val imuNoseUp get() = getString("Place nose up", "నోస్ పైకి పెట్టండి")
    val imuBack get() = getString("Place on back, upside down", "వెనక్కి తిప్పండి")

    // Compass Calibration Strings
    val rotateVehicle get() = getString("Rotate vehicle", "వాహనాన్ని తిప్పండి")
    val holdSteady get() = getString("Hold steady", "స్థిరంగా పట్టుకోండి")
    val compassCalibrationStarted get() = getString("Compass calibration started", "కంపాస్ కేలిబ్రేషన్ ప్రారంభమైంది")
    val compassCalibrationComplete get() = getString("Compass calibration complete", "కంపాస్ కేలిబ్రేషన్ పూర్తయింది")

    // Mission/Plan Strings
    val plan get() = getString("Plan", "ప్లాన్")
    val fly get() = getString("Fly", "ఎగరండి")
    val uploadMission get() = getString("Upload Mission", "మిషన్ అప్‌లోడ్ చేయండి")
    val startMission get() = getString("Start Mission", "మిషన్ ప్రారంభించండి")
    val clearMission get() = getString("Clear Mission", "మిషన్ క్లియర్ చేయండి")
    val missionUploaded get() = getString("Mission Uploaded", "మిషన్ అప్‌లోడ్ అయింది")
    val missionStarted get() = getString("Mission Started", "మిషన్ ప్రారంభమైంది")
    val waypoints get() = getString("Waypoints", "వేపాయింట్లు")
    val missionPaused get() = getString("Mission Paused", "మిషన్ పాజ్ అయింది")
    val missionResumed get() = getString("Mission Resumed", "మిషన్ రిజ్యూమ్ అయింది")
    val pausedAtWaypoint get() = getString("Paused at waypoint", "వేపాయింట్ వద్ద పాజ్ చేయబడింది")


    // NEW: Mission Completion Dialog
    val ok get() = getString("OK", "సరే")
    val missionCompleted get() = getString("Mission Completed!", "మిషన్ పూర్తయింది!")
    val totalTimeTaken get() = getString("Total time taken", "మొత్తం సమయం")
    val totalDistanceCovered get() = getString("Total distance covered", "మొత్తం దూరం")
    val liquidConsumed get() = getString("Liquid consumed", "వినియోగించిన ద్రవం")

    // NEW: Split Plan Dialog
    val yes get() = getString("Yes", "అవును")
    val no get() = getString("No", "కాదు")
    val confirmSplitPlan get() = getString("Confirm Split Plan", "విభజన ప్లాన్‌ను నిర్ధారించండి")
    val splitPlanMessage get() = getString("Are you sure you want to split the mission plan? This will pause the current mission and create a new split plan.", "మీరు మిషన్ ప్లాన్‌ను విభజించాలనుకుంటున్నారా? ఇది ప్రస్తుత మిషన్‌ను పాజ్ చేసి కొత్త విభజన ప్లాన్‌ను సృష్టిస్తుంది.")

    // NEW: Resume Mission Dialog
    val continueTxt get() = getString("Continue", "కొనసాగించు")
    val resumeMissionWarning get() = getString("Resume Mission Warning", "మిషన్ పునఃప్రారంభ హెచ్చరిక")
    val resumeWarningMessage get() = getString("⚠️ Warning: This will reprogram your mission, arm the vehicle, and issue a takeoff command (for copters).", "⚠️ హెచ్చరిక: ఇది మీ మిషన్‌ను రీప్రోగ్రామ్ చేస్తుంది, వాహనాన్ని ఆర్మ్ చేస్తుంది మరియు టేకాఫ్ కమాండ్ జారీ చేస్తుంది (కాప్టర్‌ల కోసం).")
    val resumeFilterMessage get() = getString("The mission will be filtered to skip waypoints before the resume point while preserving DO commands.", "మిషన్ రిజ్యూమ్ పాయింట్ ముందు వేపాయింట్‌లను దాటవేయడానికి ఫిల్టర్ చేయబడుతుంది, DO కమాండ్‌లను భద్రపరుస్తుంది.")
    val resumeMissionAt get() = getString("Resume Mission At", "మిషన్‌ను పునఃప్రారంభించండి")
    val enterWaypointNumber get() = getString("Enter the waypoint number to resume from:", "పునఃప్రారంభించడానికి వేపాయింట్ నంబర్‌ను నమోదు చేయండి:")
    val waypoint get() = getString("Waypoint #", "వేపాయింట్ #")
    val defaultWaypoint get() = getString("Default: Waypoint", "డిఫాల్ట్: వేపాయింట్")
    val lastAutoWaypoint get() = getString("(last auto waypoint)", "(చివరి ఆటో వేపాయింట్)")
    val resume get() = getString("Resume", "పునఃప్రారంభించు")
    val resumingMission get() = getString("Resuming Mission...", "మిషన్ పునఃప్రారంభమవుతోంది...")

    // NEW: Top Nav Bar
    val planMission get() = getString("Plan Mission", "మిషన్ ప్లాన్ చేయండి")
    val plotTemplates get() = getString("Plot Templates", "ప్లాట్ టెంప్లేట్లు")
    val reconnect get() = getString("Reconnect", "మళ్లీ కనెక్ట్ చేయండి")

    // NEW: Mission Choice Dialog
    val loadExistingTemplate get() = getString("Load Existing Template", "ఇప్పటికే ఉన్న టెంప్లేట్‌ను లోడ్ చేయండి")
    val createNewMission get() = getString("Create New Mission", "కొత్త మిషన్ సృష్టించండి")

    // NEW: Save Mission Dialog
    val projectName get() = getString("Project Name", "ప్రాజెక్ట్ పేరు")
    val enterProjectName get() = getString("Enter project name", "ప్రాజెక్ట్ పేరు నమోదు చేయండి")
    val plotName get() = getString("Plot Name", "ప్లాట్ పేరు")
    val enterPlotName get() = getString("Enter plot name", "ప్లాట్ పేరు నమోదు చేయండి")

    // NEW: Language Selection
    val changeLanguageLater get() = getString("You can change this later from settings.", "మీరు దీన్ని తర్వాత సెట్టింగ్‌ల నుండి మార్చవచ్చు.")

    // NEW: Logs Screen
    val exportOptions get() = getString("Export Options", "ఎగుమతి ఎంపికలు")
    val exportMessage get() = getString("Would you like to export all flights, or select specific flights to export?", "మీరు అన్ని విమానాలను ఎగుమతి చేయాలనుకుంటున్నారా లేదా ఎగుమతి చేయడానికి నిర్దిష్ట విమానాలను ఎంచుకోవాలనుకుంటున్నారా?")
    val exportAll get() = getString("Export All", "అన్నింటినీ ఎగుమతి చేయండి")
    val select get() = getString("Select", "ఎంచుకోండి")
    val selectFlightsToExport get() = getString("Select Flights to Export", "ఎగుమతి చేయడానికి విమానాలను ఎంచుకోండి")
    val recentFlights get() = getString("Recent flights", "ఇటీవలి విమానాలు")
    val noFlightsAvailable get() = getString("No flights available to select.", "ఎంచుకోవడానికి విమానాలు అందుబాటులో లేవు.")
    val exportFormat get() = getString("Export format", "ఎగుమతి ఫార్మాట్")
    val export get() = getString("Export", "ఎగుమతి చేయండి")
    val filterFlights get() = getString("Filter Flights", "విమానాలను ఫిల్టర్ చేయండి")

    // NEW: Common UI Elements
    val save get() = getString("Save", "సేవ్ చేయండి")
    val delete get() = getString("Delete", "తొలగించు")
    val edit get() = getString("Edit", "సవరించు")
    val close get() = getString("Close", "మూసివేయి")
    val settings get() = getString("Settings", "సెట్టింగ్‌లు")
    val warning get() = getString("Warning", "హెచ్చరిక")
    val error get() = getString("Error", "లోపం")
    val success get() = getString("Success", "విజయం")
    val loading get() = getString("Loading...", "లోడ్ అవుతోంది...")
    val pleaseWait get() = getString("Please wait", "దయచేసి వేచి ఉండండి")

    // NEW: Floating Action Buttons
    val start get() = getString("Start", "ప్రారంభించు")
    val pause get() = getString("Pause", "పాజ్")
    val resumeBtn get() = getString("Resume", "పునఃప్రారంభించు")
    val split get() = getString("Split", "విభజించు")
    val recenter get() = getString("Recenter", "మధ్యలో ఉంచు")
    val mapType get() = getString("Map Type", "మ్యాప్ రకం")

    // NEW: Status Panel Labels
    val alt get() = getString("Alt", "ఎత్తు")
    val speedLabel get() = getString("Speed", "వేగం")
    val area get() = getString("Area", "ప్రాంతం")
    val flow get() = getString("Flow", "ప్రవాహం")
    val wp get() = getString("WP", "వే.పా")
    val time get() = getString("Time", "సమయం")
    val distance get() = getString("Distance", "దూరం")
    val consumed get() = getString("Consumed", "వినియోగించారు")

    // NEW: Plan Screen
    val addPoint get() = getString("Add Point", "పాయింట్ జోడించు")
    val saveMission get() = getString("Save Mission", "మిషన్ సేవ్ చేయండి")
    val editPlan get() = getString("Edit Plan", "ప్లాన్ సవరించు")
    val planUnlocked get() = getString("Plan unlocked for editing", "ప్లాన్ సవరణ కోసం అన్‌లాక్ చేయబడింది")
    val planSavedEditRequired get() = getString("Plan is saved. Click 'Edit Plan' to make changes.", "ప్లాన్ సేవ్ చేయబడింది. మార్పులు చేయడానికి 'ప్లాన్ సవరించు' క్లిక్ చేయండి.")
    val uploadMissionBtn get() = getString("Upload Mission", "మిషన్ అప్‌లోడ్ చేయండి")
    val templateLoaded get() = getString("Template loaded successfully", "టెంప్లేట్ విజయవంతంగా లోడ్ చేయబడింది")
    val selectPolygonPoint get() = getString("Please select a polygon point to delete", "తొలగించడానికి దయచేసి పాలిగాన్ పాయింట్‌ను ఎంచుకోండి")
    val selectWaypoint get() = getString("Please select a waypoint to delete", "తొలగించడానికి దయచేసి వేపాయింట్‌ను ఎంచుకోండి")
    val gridMissionUploaded get() = getString("Grid mission uploaded", "గ్రిడ్ మిషన్ అప్‌లోడ్ చేయబడింది")
    val failedToUploadGrid get() = getString("Failed to upload grid mission", "గ్రిడ్ మిషన్ అప్‌లోడ్ విఫలమైంది")
    val missionUploadedSuccess get() = getString("Mission uploaded", "మిషన్ అప్‌లోడ్ చేయబడింది")
    val failedToUploadMission get() = getString("Failed to upload mission", "మిషన్ అప్‌లోడ్ విఫలమైంది")
    val noWaypointsToUpload get() = getString("No waypoints to upload", "అప్‌లోడ్ చేయడానికి వేపాయింట్లు లేవు")
    val noGPSLocation get() = getString("No GPS location available", "GPS స్థానం అందుబాటులో లేదు")
    val lineSpacing get() = getString("Line Spacing", "లైన్ అంతరం")
    val gridAngle get() = getString("Grid Angle", "గ్రిడ్ కోణం")
    val speedSetting get() = getString("Speed", "వేగం")
    val altitudeSetting get() = getString("Altitude", "ఎత్తు")
    val holdNosePosition get() = getString("Hold Nose Position", "నోస్ స్థానం పట్టుకోండి")
    val clearAll get() = getString("Clear All", "అన్నీ క్లియర్ చేయండి")
    val drawPolygon get() = getString("Draw Polygon", "పాలిగాన్ గీయండి")
    val generateGrid get() = getString("Generate Grid", "గ్రిడ్ సృష్టించు")

    // Authentication Screens
    val loginWithPavaman get() = getString("Login with pavaman", "పవమాన్‌తో లాగిన్ చేయండి")
    val loginCredentials get() = getString("Login with pavaman credentials", "పవమాన్ ఆధారాలతో లాగిన్ చేయండి")
    val firstName get() = getString("First Name", "మొదటి పేరు")
    val lastName get() = getString("Last Name", "చివరి పేరు")
    val email get() = getString("Email", "ఇమెయిల్")
    val mobileNumber get() = getString("Mobile Number", "మొబైల్ నంబర్")
    val password get() = getString("Password", "పాస్‌వర్డ్")
    val confirmPassword get() = getString("Confirm Password", "పాస్‌వర్డ్ నిర్ధారించండి")
    val re_password get() = getString("Re-Password", "పాస్‌వర్డ్ మళ్లీ నమోదు చేయండి")
    val login get() = getString("Login", "లాగిన్")
    val signInWithGoogle get() = getString("Sign in with Google", "గూగుల్‌తో సైన్ ఇన్ చేయండి")
    val signupWithPavaman get() = getString("Signup with Pavaman", "పవమాన్‌తో సైన్అప్ చేయండి")
    val createCustomCredentials get() = getString("Create your custom mail and password", "మీ కస్టమ్ మెయిల్ మరియు పాస్‌వర్డ్ సృష్టించండి")
    val createAccount get() = getString("Create account", "ఖాతా సృష్టించు")
    val alreadyHaveAccount get() = getString("if already have an account, Login", "ఇప్పటికే ఖాతా ఉంటే, లాగిన్ చేయండి")

    // Calibration Screen Strings
    val accelerometerCalibration get() = getString("Accelerometer Calibration", "యాక్సిలరోమీటర్ కేలిబ్రేషన్")
    val back get() = getString("Back", "వెనక్కి")
    val cancelCalibrationQuestion get() = getString("Cancel Calibration?", "కేలిబ్రేషన్ రద్దు చేయాలా?")
    val cancelCalibrationConfirm get() = getString("Are you sure you want to cancel the calibration process?", "మీరు కేలిబ్రేషన్ ప్రక్రియను రద్దు చేయాలనుకుంటున్నారా?")
    val yesCancel get() = getString("Yes, Cancel", "అవును, రద్దు చేయండి")
    val continueCal get() = getString("Continue Calibration", "కేలిబ్రేషన్ కొనసాగించు")
    val rebootYourDrone get() = getString("Reboot Your Drone", "మీ డ్రోన్‌ను రీబూట్ చేయండి")
    val imuCalibrationCompleted get() = getString("IMU calibration completed successfully!\n\nPlease reboot your drone for the calibration settings to take effect.", "IMU కేలిబ్రేషన్ విజయవంతంగా పూర్తయింది!\n\nకేలిబ్రేషన్ సెట్టింగ్‌లు అమలులోకి రావడానికి దయచేసి మీ డ్రోన్‌ను రీబూట్ చేయండి.")
    val initiateReboot get() = getString("Initiate Reboot", "రీబూట్ ప్రారంభించు")
    val later get() = getString("Later", "తర్వాత")
    val position get() = getString("Position", "స్థానం")
    val of get() = getString("of", "లో")
    val connectedStatus get() = getString("✓ Connected", "✓ కనెక్ట్ అయింది")
    val notConnected get() = getString("⚠ Not Connected", "⚠ కనెక్ట్ అవ్వలేదు")
    val readyToCalibrate get() = getString("Ready to calibrate", "కేలిబ్రేట్ చేయడానికి సిద్ధంగా ఉంది")
    val pleaseConnectDroneFirst get() = getString("Please connect to drone first", "దయచేసి ముందుగా డ్రోన్‌కు కనెక్ట్ చేయండి")
    val calibrationInstructions get() = getString("This process will guide you through 6 different positions to calibrate the accelerometer.", "ఈ ప్రక్రియ యాక్సిలరోమీటర్‌ను కేలిబ్రేట్ చేయడానికి 6 వేర్వేరు స్థానాల ద్వారా మీకు మార్గనిర్देశం చేస్తుంది.")
    val ensureStablePosition get() = getString("Ensure the drone is in a stable position before starting", "ప్రారంభించే ముందు డ్రోన్ స్థిరమైన స్థితిలో ఉందని నిర్ధారించుకోండి")
    val compassCalProgress get() = getString("Compass Calibration Progress", "కంపాస్ కేలిబ్రేషన్ పురోగతి")
    val compassCalInProgress get() = getString("Compass Calibration in Progress...", "కంపాస్ కేలిబ్రేషన్ పురోగతిలో ఉంది...")
    val calibratingPosition get() = getString("Calibrating position...", "స్థానాన్ని కేలిబ్రేట్ చేస్తోంది...")
    val processingData get() = getString("Processing data", "డేటాను ప్రాసెస్ చేస్తోంది")
    val calibrationSuccess get() = getString("Calibration Successful!", "కేలిబ్రేషన్ విజయవంతమైంది!")
    val calibrationError get() = getString("Calibration Error", "కేలిబ్రేషన్ లోపం")
    val calibrationCancelled get() = getString("Calibration Cancelled", "కేలిబ్రేషన్ రద్దు చేయబడింది")
    val retry get() = getString("Retry", "మళ్లీ ప్రయత్నించు")
    val reset get() = getString("Reset", "రీసెట్")

    // Obstacle Detection Screen
    val obstacleDetection get() = getString("Obstacle Detection", "అడ్డంకి గుర్తింపు")
    val monitoringActive get() = getString("✅ Monitoring Active", "✅ పర్యవేక్షణ సక్రియంగా ఉంది")
    val obstacleDetected get() = getString("⚠️ Obstacle Detected!", "⚠️ అడ్డంకి గుర్తించబడింది!")
    val returningHome get() = getString("🏠 Returning Home", "🏠 ఇంటికి తిరిగి వస్తోంది")
    val readyToResume get() = getString("✈️ Ready to Resume", "✈️ పునఃప్రారంభించడానికి సిద్ధంగా ఉంది")
    val resumingMissionStatus get() = getString("🔄 Resuming Mission", "🔄 మిషన్ పునఃప్రారంభమవుతోంది")
    val inactive get() = getString("⚪ Inactive", "⚪ నిష్క్రియంగా ఉంది")
    val distanceLabel get() = getString("Distance", "దూరం")
    val threatLevel get() = getString("Threat Level", "ముప్పు స్థాయి")
    val currentDistance get() = getString("Current Distance", "ప్రస్తుత దూరం")
    val minimumSafeDistance get() = getString("Minimum Safe Distance", "కనీస సురక్షిత దూరం")
    val actionTaken get() = getString("Action Taken", "తీసుకున్న చర్య")
    val obstacleCleared get() = getString("Obstacle Cleared", "అడ్డంకి తొలగించబడింది")
    val safeToResume get() = getString("Safe to resume mission", "మిషన్‌ను పునఃప్రారంభించడం సురక్షితం")
    val homeLocation get() = getString("Home Location", "ఇంటి స్థానం")
    val currentAlt get() = getString("Current Altitude", "ప్రస్తుత ఎత్తు")
    val distanceToHome get() = getString("Distance to Home", "ఇంటికి దూరం")
    val estimatedTime get() = getString("Estimated Time", "అంచనా సమయం")
    val rtlStatus get() = getString("RTL Status", "RTL స్థితి")
    val resumeOptions get() = getString("Resume Options", "పునఃప్రారంభ ఎంపికలు")
    val selectResumeMethod get() = getString("Select how you'd like to resume the mission:", "మీరు మిషన్‌ను ఎలా పునఃప్రారంభించాలనుకుంటున్నారో ఎంచుకోండి:")
    val fromLastWaypoint get() = getString("From Last Waypoint", "చివరి వేపాయింట్ నుండి")
    val continueFromLast get() = getString("Continue from last completed waypoint", "చివరిగా పూర్తయిన వేపాయింట్ నుండి కొనసాగించు")
    val fromCurrentPosition get() = getString("From Current Position", "ప్రస్తుత స్థానం నుండి")
    val startFromCurrent get() = getString("Start from current drone position", "ప్రస్తుత డ్రోన్ స్థానం నుండి ప్రారంభించు")
    val fromBeginning get() = getString("From Beginning", "ప్రారంభం నుండి")
    val restartEntireMission get() = getString("Restart entire mission from the start", "మొత్తం మిషన్‌ను ప్రారంభం నుండి పునఃప్రారంభించు")
    val preparingResume get() = getString("Preparing to resume mission...", "మిషన్‌ను పునఃప్రారంభించడానికి సిద్ధమవుతోంది...")
    val uploadingRoute get() = getString("Uploading new route", "కొత్త మార్గాన్ని అప్‌లోడ్ చేస్తోంది")
    val verifyingConnection get() = getString("Verifying connection", "కనెక్షన్‌ను ధృవీకరిస్తోంది")
    val monitoringForObstacles get() = getString("Monitoring for Obstacles", "అడ్డంకుల కోసం పర్యవేక్షిస్తోంది")
    val systemActiveScanning get() = getString("System is actively scanning for obstacles in the flight path.", "వ్యవస్థ విమాన మార్గంలో అడ్డంకుల కోసం చురుకుగా స్కాన్ చేస్తోంది.")
    val detectionSensitivity get() = getString("Detection Sensitivity", "గుర్తింపు సున్నితత్వం")
    val high get() = getString("High", "అధికం")
    val scanRange get() = getString("Scan Range", "స్కాన్ పరిధి")
    val noObstaclesDetected get() = getString("No obstacles detected", "అడ్డంకులు గుర్తించబడలేదు")
    val obstacleWarning get() = getString("Obstacle Warning", "అడ్డంకి హెచ్చరిక")
    val obstacleInPath get() = getString("An obstacle has been detected in the flight path!", "విమాన మార్గంలో అడ్డంకి గుర్తించబడింది!")
    val systemInactive get() = getString("System Inactive", "వ్యవస్థ నిష్క్రియంగా ఉంది")
    val activateMonitoring get() = getString("Activate obstacle detection monitoring", "అడ్డంకి గుర్తింపు పర్యవేక్షణను సక్రియం చేయండి")

    // Plot Templates Screen
    val savedMissions get() = getString("Saved Missions", "సేవ్ చేసిన మిషన్లు")
    val templatesAvailable get() = getString("templates available", "టెంప్లేట్లు అందుబాటులో ఉన్నాయి")
    val templateAvailable get() = getString("template available", "టెంప్లేట్ అందుబాటులో ఉంది")
    val noMissionTemplates get() = getString("No Mission Templates", "మిషన్ టెంప్లేట్లు లేవు")
    val noTemplatesSaved get() = getString("You haven't saved any mission templates yet.", "మీరు ఇంకా మిషన్ టెంప్లేట్‌లను సేవ్ చేయలేదు.")
    val createFirstTemplate get() = getString("Create your first template by planning a mission and saving it.", "మిషన్‌ను ప్లాన్ చేసి సేవ్ చేయడం ద్వారా మీ మొదటి టెంప్లేట్‌ను సృష్టించండి.")
    val project get() = getString("Project", "ప్రాజెక్ట్")
    val plot get() = getString("Plot", "ప్లాట్")
    val waypointsCount get() = getString("waypoints", "వేపాయింట్లు")
    val created get() = getString("Created", "సృష్టించబడింది")
    val load get() = getString("Load", "లోడ్ చేయండి")
    val deleteTemplate get() = getString("Delete Template", "టెంప్లేట్ తొలగించు")
    val confirmDeleteTemplate get() = getString("Are you sure you want to delete this mission template?", "మీరు ఈ మిషన్ టెంప్లేట్‌ను తొలగించాలనుకుంటున్నారా?")
    val thisActionCannotBeUndone get() = getString("This action cannot be undone.", "ఈ చర్యను రద్దు చేయలేము.")

    // Additional Plot Templates Screen Strings
    val createAndSaveMissions get() = getString("You haven't saved any mission templates yet.", "మీరు ఇంకా మిషన్ టెంప్లేట్‌లను సేవ్ చేయలేదు.")
    val quickTip get() = getString("Quick Tip", "శీఘ్ర చిట్కా")
    val goToPlanScreen get() = getString("Go to Plan screen and save your mission to see it here!", "ప్లాన్ స్క్రీన్‌కు వెళ్లి మీ మిషన్‌ను సేవ్ చేసి ఇక్కడ చూడండి!")
    val lastUpdated get() = getString("Last Updated", "చివరి నవీకరణ")
    val loadMission get() = getString("Load Mission", "మిషన్ లోడ్ చేయండి")
    val deleteMissionTemplate get() = getString("Delete Mission Template", "మిషన్ టెంప్లేట్ తొలగించు")
    val deleteConfirmationMessage get() = getString("Are you sure you want to delete this mission template?", "మీరు ఈ మిషన్ టెంప్లేట్‌ను తొలగించాలనుకుంటున్నారా?")
    val undoneActionWarning get() = getString("⚠️ This action cannot be undone.", "⚠️ ఈ చర్యను రద్దు చేయలేము.")

    // TopNavBar Strings
    val pavamanAviation get() = getString("Pavaman Aviation", "పవమాన్ ఏవియేషన్")
    val menu get() = getString("Menu", "మెనూ")
    val home get() = getString("Home", "హోమ్")
    val spray get() = getString("Spray", "స్ప్రే")
    val geofence get() = getString("Geofence", "జియోఫెన్స్")
    val on get() = getString("ON", "ఆన్")
    val off get() = getString("OFF", "ఆఫ్")
    val notifications get() = getString("Notifications", "నోటిఫికేషన్లు")
    val more get() = getString("More", "మరిన్ని")
    val logs get() = getString("Logs", "లాగ్స్")
    val disconnect get() = getString("Disconnect", "డిస్కనెక్ట్ చేయండి")
    val language get() = getString("Language", "భాష")
    val logout get() = getString("Logout", "లాగౌట్")

    // Geofence Settings Popup
    val geofenceSettings get() = getString("Geofence Settings", "జియోఫెన్స్ సెట్టింగ్‌లు")
    val enableGeofence get() = getString("Enable Geofence", "జియోఫెన్స్ ప్రారంభించు")
    val polygonFenceActive get() = getString("Polygon fence active around mission plan", "మిషన్ ప్లాన్ చుట్టూ పాలిగాన్ ఫెన్స్ సక్రియంగా ఉంది")
    val geofenceDisabled get() = getString("Geofence disabled", "జియోఫెన్స్ నిలిపివేయబడింది")
    val bufferDistance get() = getString("Buffer Distance", "బఫర్ దూరం")
    val adjustPolygonBuffer get() = getString("Adjust polygon buffer distance around mission plan", "మిషన్ ప్లాన్ చుట్టూ పాలిగాన్ బఫర్ దూరాన్ని సర్దుబాటు చేయండి")

    // Spray Settings Popup
    val spraySettings get() = getString("Spray Settings", "స్ప్రే సెట్టింగ్‌లు")
    val enableSpray get() = getString("Enable Spray", "స్ప్రే ప్రారంభించు")
    val sprayActive get() = getString("Spray active", "స్ప్రే సక్రియంగా ఉంది")
    val sprayInactive get() = getString("Spray inactive", "స్ప్రే నిష్క్రియంగా ఉంది")
    val sprayRate get() = getString("Spray Rate", "స్ప్రే రేటు")
    val adjustSprayIntensity get() = getString("Adjust spray intensity", "స్ప్రే తీవ్రతను సర్దుబాటు చేయండి")

    // Notification Messages
    val droneArmed get() = getString("Drone Armed", "డ్రోన్ ఆర్మ్ చేయబడింది")
    val droneDisarmed get() = getString("Drone Disarmed", "డ్రోన్ డిసార్మ్ చేయబడింది")
    val executingWaypoint get() = getString("Executing waypoint #", "వేపాయింట్ # అమలు చేస్తోంది")
    val reachedWaypoint get() = getString("Reached waypoint #", "వేపాయింట్ # చేరుకుంది")
    val sprayerEnabled get() = getString("Sprayer Enabled", "స్ప్రేయర్ ప్రారంభించబడింది")
    val sprayerDisabled get() = getString("Sprayer Disabled", "స్ప్రేయర్ నిలిపివేయబడింది")
    val missionResumedNotif get() = getString("Mission resumed", "మిషన్ రిజ్యూమ్ అయింది")
    val resumeMissionFailed get() = getString("Resume mission failed", "మిషన్ పునఃప్రారంభం విఫలమైంది")
    val missionUpload get() = getString("Mission upload", "మిషన్ అప్‌లోడ్")

    // Additional notification messages
    val missionStartSent get() = getString("Mission start sent", "మిషన్ ప్రారంభ సందేశం పంపబడింది")
    val failedToStartMission get() = getString("Failed to start mission", "మిషన్ ప్రారంభించడం విఫలమైంది")
    val pinSet get() = getString("PIN set", "పిన్ సెట్ చేయబడింది")
    val pleaseEnter4DigitPIN get() = getString("Please enter a 4-digit PIN", "దయచేసి 4-అంకెల పిన్ నమోదు చేయండి")
    val waypointReordered get() = getString("Waypoint reordered", "వేపాయింట్ క్రమం మార్చబడింది")
    val resumeFailed get() = getString("Resume failed", "పునఃప్రారంభం విఫలమైంది")
    val unknownError get() = getString("Unknown error", "తెలియని లోపం")

    // Split Plan Feature
    val splitPlanTitle get() = getString("Split Plan", "ప్లాన్ విభజించు")
    val splitPlanDescription get() = getString("Select the portion of the grid to fly", "ఎగరడానికి గ్రిడ్ భాగాన్ని ఎంచుకోండి")
    val splitStart get() = getString("Start", "ప్రారంభం")
    val splitEnd get() = getString("End", "ముగింపు")
    val gridLinesSelected get() = getString("Grid Lines Selected", "ఎంచుకున్న గ్రిడ్ లైన్లు")
    val applySplit get() = getString("Apply Split", "విభజన వర్తింపజేయండి")
    val cancelSplit get() = getString("Cancel", "రద్దు చేయండి")
    val splitApplied get() = getString("Split plan applied successfully", "విభజన ప్లాన్ విజయవంతంగా వర్తింపజేయబడింది")
    val noGridToSplit get() = getString("No grid mission to split. Please create a grid survey first.", "విభజించడానికి గ్రిడ్ మిషన్ లేదు. దయచేసి ముందుగా గ్రిడ్ సర్వే సృష్టించండి.")
    val splitPlanBtn get() = getString("Split Plan", "ప్లాన్ విభజించు")
    val originalPlan get() = getString("Original Plan", "అసలు ప్లాన్")
    val splitPortion get() = getString("Split Portion", "విభజన భాగం")
}

// Composable for reactive language updates
@Suppress("unused")
val LocalLanguage = staticCompositionLocalOf { "en" }
