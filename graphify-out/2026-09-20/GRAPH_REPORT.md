# Graph Report - kftgcs  (2026-09-20)

## Corpus Check
- 230 files · ~933,269 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 60 file(s) not represented in the graph (top: .xml 20, .log 19, .bat 8)

## Summary
- 2771 nodes · 5767 edges · 156 communities (113 shown, 31 thin omitted)
- Extraction: 97% EXTRACTED · 3% INFERRED · 0% AMBIGUOUS · INFERRED: 170 edges (avg confidence: 0.89)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `5df8cbf4`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- SavedMissionStateDao
- Backend WebSocket Telemetry Consumer
- SharedViewModel
- GridGenerator
- KmlBoundaryParser
- FieldType
- FullParamListViewModel
- ProximityOverlay.kt
- Calibration Screen UI
- UdpMavConnection
- Notification
- VideoStreamPlayer.kt
- ParamManagementViewModel
- Settings Screen Navigation
- Video Forwarder State Machine
- GCSApplication
- Compass Calibration Screen UI
- UserSettingsManager
- .authenticate
- RC Calibration Screen UI
- Text-to-Speech Announcements
- ServoOutputViewModel
- Level Calibration Screen UI
- TlogViewModel
- ObstacleDetectionManager
- BluetoothMavConnection
- OptionsViewModel
- EventType
- .connect
- OfflineMessageDao
- Crash Analyzer
- ObstaclePathPlanner
- AppNavGraph.kt
- UsbSerialMavConnection
- GcsMap
- Replay Timeline Builder
- WebSocketManager
- ObstacleData.kt
- ObstacleSensorManager
- SpraySettingsViewModel
- SessionManager
- Log Analysis Screen
- AnalyzeLogScreen.kt
- CameraProtocolManager
- AppStrings
- LatLng
- MotorTestViewModel
- ScreenRecordService
- UnifiedFlightTracker
- Mission State Repository
- Spray Telemetry Utils
- AboutDroneViewModel
- Mavlink Ftp Client
- Geofence Utils
- ObstacleDetector
- Flight Dao
- GridUtils
- ObstacleDetectionIntegrationExample
- Log Replay Screen
- Context
- test_websocket_connection.py
- TelemetryState
- Color.kt
- AuthViewModel
- TelemetryRepository.kt
- ConnectionPage.kt
- BatteryMonitorViewModel.kt
- MessageDefinition
- Backend Websocket Spec
- KeyboardType
- TermsAndConditionsScreen
- BrakingSettingsViewModel
- CameraTrackingState.kt
- MissionTemplateDatabase
- ObstacleDetectionViewModel
- Barometer Calibration View Model
- MainActivity.kt
- ObstacleDetectionScreen.kt
- Replay Map
- Flow Sensor Calibration Screen
- In App Update State
- GimbalController
- EventEntity
- Flight Log Exporter
- TlogRepository
- .startFlight
- TelemetryEntity
- Data Flash Parser
- Log Analysis View Model
- .registerNetworkCallback
- FlightModeViewModel
- LogUtils
- UnifiedFlightTracker.kt
- DisconnectionRTLHandler
- MapDataEntity
- LogsScreen.kt
- TopNavBar.kt
- Time Formatter
- .imagePointToGps
- Welcome
- TlogIntegration
- Spray Calibration Screen
- FlightModeScreen
- Forgot Password Page
- SharedViewModel.kt
- Shared View Model
- AuthResult
- Sensor Settings Screen
- Video Stream Settings
- FrameClassMap
- .sendCommandAck
- FlightState
- Shared View Model
- DroneCameraFeedOverlay
- Telemetry Overlay
- Settings Screen
- Runnable
- CommandLong
- Rc Stick View
- VideoRelayNotification.kt
- VideoTrackingOverlay
- Shared View Model
- Shared View Model
- MissionTemplateEntity
- Gradlew
- Example Instrumented Test
- Shared View Model
- Example Unit Test
- Drone Pose
- Pavaman Logo
- Privacy-Policy
- Privacy-Policy
- Urls
- Autonomous
- D Image Prev Ui
- Logbag
- Manual
- Security
- Security
- Security
- MotorTestScreen.kt
- SelectFlyingMethodScreen.kt
- BarometerCalibrationScreen.kt
- PreflightFailsafeDialog

## God Nodes (most connected - your core abstractions)
1. `SharedViewModel` - 325 edges
2. `AppNavGraph()` - 66 edges
3. `Screen` - 63 edges
4. `Notification` - 56 edges
5. `TelemetryState` - 49 edges
6. `TextToSpeechManager` - 38 edges
7. `UnifiedFlightTracker` - 35 edges
8. `ObstacleDetectionManager` - 35 edges
9. `WebSocketManager` - 32 edges
10. `AuthViewModel` - 31 edges

## Surprising Connections (you probably didn't know these)
- `Enhanced Okio Protection` --references--> `BluetoothMavConnection`  [EXTRACTED]
  BLUETOOTH_PROGUARD_FIX.md → app/src/main/java/com/example/kftgcs/telemetry/connections/BluetoothMavConnection.kt
- `Authenticate the Socket (Issue A, CRITICAL)` --semantically_similar_to--> `Secret Scanning Job`  [INFERRED] [semantically similar]
  docs/backend_websocket_spec.md → .github/workflows/security.yml
- `Reinforced Telemetry Package Protection` --references--> `BluetoothConnectionProvider`  [EXTRACTED]
  BLUETOOTH_PROGUARD_FIX.md → app/src/main/java/com/example/kftgcs/telemetry/connections/BluetoothConnectionProvider.kt
- `Reinforced Telemetry Package Protection` --references--> `BluetoothMavConnection`  [EXTRACTED]
  BLUETOOTH_PROGUARD_FIX.md → app/src/main/java/com/example/kftgcs/telemetry/connections/BluetoothMavConnection.kt
- `TelemetryConsumer` --uses--> `Mission`  [INFERRED]
  consumers.py → models.py

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Bluetooth k3-error ProGuard Root-Cause Fix** — bluetooth_proguard_fix_k3error, bluetooth_proguard_fix_logstripping, bluetooth_proguard_fix_okioprotection, bluetooth_proguard_fix_kotlinreflectionprotection, bluetooth_proguard_fix_telemetrypackageprotection [EXTRACTED 1.00]
- **CI Security Scan Pipeline** — github_workflows_security_dependencycheck, github_workflows_security_secretscanning, github_workflows_security_codeqlanalysis, github_workflows_security_androidlint [EXTRACTED 1.00]
- **Critical/High Priority Backend Data-Integrity Fixes (A, B, C)** — docs_backend_websocket_spec_socketauthentication, docs_backend_websocket_spec_vehiclededuplication, docs_backend_websocket_spec_droneuidupdate [EXTRACTED 1.00]

## Communities (156 total, 31 thin omitted)

### Community 0 - "SavedMissionStateDao"
Cohesion: 0.16
Nodes (7): LatLng, TypeToken, MissionStateTypeConverters, TypeToken, SavedMissionStateDao, SavedMissionStateEntity, Gson

### Community 1 - "Backend WebSocket Telemetry Consumer"
Cohesion: 0.06
Nodes (75): AsyncWebsocketConsumer, TelemetryConsumer - Fixed version with proper error handling Copy this to your…, TelemetryConsumer, Admin, Meta, Mission, MissionEvent, MissionSummary (+67 more)

### Community 2 - "SharedViewModel"
Cohesion: 0.03
Nodes (6): com, SharedFlow, StateFlow, MissionCompletionData, SharedViewModel, kotlinx

### Community 3 - "GridGenerator"
Cohesion: 0.14
Nodes (9): GridGenerator, LatLng, GridMissionConverter, LatLng, MissionItemInt, UByte, GridSurveyParams, GridSurveyResult (+1 more)

### Community 4 - "KmlBoundaryParser"
Cohesion: 0.31
Nodes (6): KmlPolygonSelectionDialog(), KmlBoundaryParser, KmlParseResult, KmlPolygon, LatLng, XmlPullParser

### Community 5 - "FieldType"
Cohesion: 0.09
Nodes (21): FieldType, CHAR16, CHAR4, CHAR64, DOUBLE, FLOAT, INT16, INT16_ARRAY (+13 more)

### Community 6 - "FullParamListViewModel"
Cohesion: 0.08
Nodes (32): ArduPilotParamMetadataRepository, TypeToken, TypeToken, Context, TypeToken, BreakingSettingsScreen(), ConfirmChangesDialog(), fmtBrake() (+24 more)

### Community 7 - "ProximityOverlay.kt"
Cohesion: 0.08
Nodes (44): Color, ObstacleWindow, obstacleWindowOf(), proximityColor(), ProximityData, RadarSwitchState, RadarThresholds, TerrainData (+36 more)

### Community 8 - "Calibration Screen UI"
Cohesion: 0.06
Nodes (36): CalibrationActions(), CalibrationContent(), CalibrationHeader(), CalibrationProgress(), CalibrationScreen(), CancelledContent(), DroneOrientationIcon(), FailedContent() (+28 more)

### Community 9 - "UdpMavConnection"
Cohesion: 0.07
Nodes (24): InetAddress, UdpDiagnostics, UdpPortScanner, UdpScanResult, buildHeartbeatProbe(), crcAccumulate(), BufferedMavConnection, ByteArray (+16 more)

### Community 10 - "Notification"
Cohesion: 0.09
Nodes (5): Notification, android, ResumePrepResult, NotificationItem(), NotificationPanel()

### Community 11 - "VideoStreamPlayer.kt"
Cohesion: 0.07
Nodes (30): BroadcastReceiver, Context, Flow, Intent, UsbDevice, UsbManager, UsbUvcDeviceManager, BroadcastReceiver (+22 more)

### Community 12 - "ParamManagementViewModel"
Cohesion: 0.08
Nodes (22): Error, OkHttpClient, T, ParamAuthApiService, ParamAuthErrorResponse, ParamAuthResult, ParamLoginRequest, ParamLoginResponse (+14 more)

### Community 13 - "Settings Screen Navigation"
Cohesion: 0.04
Nodes (47): AboutApp, AccelerometerCalibration, Aircraft, AnalyzeLog, BarometerCalibration, BatteryMonitorSettings, Calibrations, CompassCalibration (+39 more)

### Community 14 - "Video Forwarder State Machine"
Cohesion: 0.07
Nodes (26): ForwarderState, ERROR, RUNNING, STARTING, STOPPED, STOPPING, CoroutineScope, DatagramSocket (+18 more)

### Community 15 - "GCSApplication"
Cohesion: 0.09
Nodes (10): GCSApplication, Application, CrashLogger, Context, Context, SecurePinManager, DisconnectionRTLMonitor, com (+2 more)

### Community 16 - "Compass Calibration Screen UI"
Cohesion: 0.09
Nodes (27): CancelledContent(), CompassCalibrationActions(), CompassCalibrationContent(), CompassCalibrationHeader(), CompassCalibrationProgress(), CompassCalibrationScreen(), CompassReportCard(), FailedContent() (+19 more)

### Community 17 - "UserSettingsManager"
Cohesion: 0.11
Nodes (20): FontSizeOption, LARGE, MEDIUM, SMALL, Color, Context, SharedPreferences, UserSettingsManager (+12 more)

### Community 18 - ".authenticate"
Cohesion: 0.19
Nodes (12): ChallengeCompleteException, KFTAuth, object@L92, ByteArray, CoroutinesMavConnection, FloatArray, MavFrame, MavMessage (+4 more)

### Community 19 - "RC Calibration Screen UI"
Cohesion: 0.09
Nodes (24): AllChannelsCard(), CalibrationButton(), ConnectionStatusCard(), NavController, MainControlsCard(), RCCalibrationHeader(), RCCalibrationScreen(), RCChannelBar() (+16 more)

### Community 20 - "Text-to-Speech Announcements"
Cohesion: 0.13
Nodes (3): TextToSpeechManager, OnInitListener, TextToSpeech

### Community 21 - "ServoOutputViewModel"
Cohesion: 0.11
Nodes (20): ServoChannel, ServoFunction, VehicleState, ArmedWarningBanner(), CompactFunctionDropdown(), CompactPwmField(), HeaderCell(), Dp (+12 more)

### Community 22 - "Level Calibration Screen UI"
Cohesion: 0.10
Nodes (23): CancelledContent(), FailedContent(), IdleContent(), InitiatingContent(), InProgressContent(), NavController, LevelCalibrationActions(), LevelCalibrationContent() (+15 more)

### Community 23 - "TlogViewModel"
Cohesion: 0.19
Nodes (5): AndroidViewModel, Flow, StateFlow, TlogUiState, TlogViewModel

### Community 24 - "ObstacleDetectionManager"
Cohesion: 0.13
Nodes (10): MissionParameters, MissionStatistics, Job, LatLng, MissionItemInt, StateFlow, ObstacleDetectionManager, UInt (+2 more)

### Community 25 - "BluetoothMavConnection"
Cohesion: 0.11
Nodes (20): app/proguard-rules.pro, BluetoothConnectionProvider, CoroutinesMavConnection, BluetoothMavConnection, BufferedMavConnection, ByteArray, MavConnection, MavFrame (+12 more)

### Community 26 - "OptionsViewModel"
Cohesion: 0.11
Nodes (12): ActionDropdown(), ActionRadioGroup(), NavHostController, NumericTextField(), OptionsScreen(), SectionCard(), SubLabel(), FailsafeOptions (+4 more)

### Community 27 - "EventType"
Cohesion: 0.08
Nodes (21): EventSeverity, CRITICAL, ERROR, INFO, WARNING, EventType, ARM_DISARM, CONNECTION_LOSS (+13 more)

### Community 29 - "OfflineMessageDao"
Cohesion: 0.14
Nodes (5): Flow, OfflineMessageDao, OfflineMessageEntity, Flow, CertificatePinner

### Community 31 - "ObstaclePathPlanner"
Cohesion: 0.25
Nodes (3): LatLng, ObstaclePathPlanner, ObstacleZone

### Community 32 - "AppNavGraph.kt"
Cohesion: 0.14
Nodes (18): NavController, WelcomeScreen(), AppNavGraph(), NavHostController, PlaceholderScreen(), Modifier, NavController, ParamLoginPage() (+10 more)

### Community 33 - "UsbSerialMavConnection"
Cohesion: 0.09
Nodes (22): CoroutinesMavConnection, MavConnectionProvider, CoroutinesMavConnection, TcpConnectionProvider, CoroutinesMavConnection, UdpConnectionProvider, CoroutinesMavConnection, UsbSerialConnectionProvider (+14 more)

### Community 34 - "GcsMap"
Cohesion: 0.11
Nodes (30): Circle, FenceZone, Polygon, ReturnPoint, Context, LatLng, StateFlow, PhoneLocationProvider (+22 more)

### Community 35 - "Replay Timeline Builder"
Cohesion: 0.14
Nodes (11): ReplayFrame, FlightMode, ReplayTimelineBuilder, ArtificialHorizon(), drawHorizon(), Modifier, rememberReplayController(), ReplayController (+3 more)

### Community 37 - "ObstacleData.kt"
Cohesion: 0.09
Nodes (21): MissionStatus, CANCELLED, COMPLETED, FAILED, IN_PROGRESS, INTERRUPTED, NOT_STARTED, PARTIAL_COMPLETE (+13 more)

### Community 38 - "ObstacleSensorManager"
Cohesion: 0.16
Nodes (8): SensorReading, CalibrationResult, StateFlow, ObstacleSensorManager, Sensor, SensorEvent, SensorEventListener, SensorManager

### Community 39 - "SpraySettingsViewModel"
Cohesion: 0.15
Nodes (17): ConfirmSprayDialog(), EnumDropdown(), fmtSpray(), InfoCenterSpray(), androidx, NavController, SectionCard(), SprayFieldView() (+9 more)

### Community 40 - "SessionManager"
Cohesion: 0.24
Nodes (3): Context, SharedPreferences, SessionManager

### Community 41 - "Log Analysis Screen"
Cohesion: 0.17
Nodes (17): AnalysisResults(), CenteredStatus(), DiagnosticCard(), formatTime(), Color, ImageVector, NavHostController, LogAnalysisScreen() (+9 more)

### Community 42 - "AnalyzeLogScreen.kt"
Cohesion: 0.09
Nodes (34): DroneIdentifier, extractDroneUniqueId(), extractUuidString(), UByte, LogEntryInfo, SdLogEntry, SprayTelemetry, AccentButton() (+26 more)

### Community 43 - "CameraProtocolManager"
Cohesion: 0.14
Nodes (10): CameraProtocolManager, StateFlow, UByte, CameraCapabilities, CameraFovStatus, TrackingImageStatus, CameraInformation, CameraTrackingImageStatus (+2 more)

### Community 44 - "AppStrings"
Cohesion: 0.15
Nodes (10): Modifier, NavController, LoginPage(), Modifier, NavController, MobileNumberField(), SignupPage(), NavHostController (+2 more)

### Community 46 - "MotorTestViewModel"
Cohesion: 0.17
Nodes (5): StateFlow, UInt, ViewModel, MotorTestState, MotorTestViewModel

### Community 47 - "ScreenRecordService"
Cohesion: 0.15
Nodes (13): NotificationType, ERROR, INFO, SUCCESS, WARNING, Context, IBinder, Intent (+5 more)

### Community 49 - "Mission State Repository"
Cohesion: 0.23
Nodes (7): LatLng, MissionItemInt, TypeToken, MissionStateRepository, TypeToken, TypeToken, SavedMissionState

### Community 50 - "Spray Telemetry Utils"
Cohesion: 0.11
Nodes (5): CalibrationPoint, FlowRateFilter, FlowRateValidator, TankLevelCalculator, VoltageFilter

### Community 51 - "AboutDroneViewModel"
Cohesion: 0.18
Nodes (11): AboutDroneScreen(), DroneInfoCard(), FrameSelectRow(), InfoDivider(), InfoRow(), NavController, SectionHeader(), AboutDroneViewModel (+3 more)

### Community 52 - "Mavlink Ftp Client"
Cohesion: 0.30
Nodes (6): FtpEntry, ByteArray, UByte, MavlinkFtpClient, Resp, FileTransferProtocol

### Community 54 - "ObstacleDetector"
Cohesion: 0.20
Nodes (7): ThreatLevel, HIGH, LOW, MEDIUM, NONE, LatLng, ObstacleDetector

### Community 55 - "Flight Dao"
Cohesion: 0.19
Nodes (3): FlightDao, Flow, FlightEntity

### Community 57 - "ObstacleDetectionIntegrationExample"
Cohesion: 0.19
Nodes (7): Context, RoomDatabase, ObstacleDatabase, CompleteFlightExample, LatLng, MissionItemInt, ObstacleDetectionIntegrationExample

### Community 58 - "Log Replay Screen"
Cohesion: 0.25
Nodes (14): Context, shareFile(), shareLogFile(), shareRecording(), CrashBanner(), DisplayPrefsMenu(), formatT(), NavHostController (+6 more)

### Community 59 - "Context"
Cohesion: 0.11
Nodes (3): BatteryFsAction, Context, UInt

### Community 60 - "test_websocket_connection.py"
Cohesion: 0.15
Nodes (14): main(), on_close(), on_error(), on_message(), on_open(), Called when an error occurs, Called when WebSocket connection is closed, WebSocket Connection Test Script ================================ This script… (+6 more)

### Community 61 - "TelemetryState"
Cohesion: 0.27
Nodes (5): TelemetryState, CameraTrackingState, LatLng, StateFlow, TrackingManager

### Community 62 - "Color.kt"
Cohesion: 0.16
Nodes (10): GridSourceSelectionDialog(), MissionChoiceDialog(), MissionTypeSelectionDialog(), SaveMissionDialog(), CalibrationOptionCard(), CalibrationsScreen(), ImageVector, NavController (+2 more)

### Community 63 - "AuthViewModel"
Cohesion: 0.06
Nodes (37): AdminInfo, AdminListResponse, ApiResponse, ApiService, Error, ErrorResponse, OkHttpClient, T (+29 more)

### Community 64 - "TelemetryRepository.kt"
Cohesion: 0.17
Nodes (10): AppScope, CoroutineScope, AltitudeLimits, ArmMagicValues, SprayerState, ACTIVE_FLOW, DEBOUNCING_EMPTY, IDLE (+2 more)

### Community 65 - "ConnectionPage.kt"
Cohesion: 0.18
Nodes (12): PairedDevice, UsbDeviceInfo, BluetoothConnectionContent(), ConnectionPage(), DeviceRow(), isPlausibleHost(), isUdpSelfTarget(), NavController (+4 more)

### Community 66 - "BatteryMonitorViewModel.kt"
Cohesion: 0.19
Nodes (10): BatteryMonitorState, BatteryMonitorViewModel, BattMonitorOption, findHwVerPresetFor(), findSensorPresetFor(), HwVerPreset, StateFlow, ViewModel (+2 more)

### Community 67 - "MessageDefinition"
Cohesion: 0.27
Nodes (5): DataFlash, ByteArray, T, MessageDefinition, ByteBuffer

### Community 68 - "Backend Websocket Spec"
Cohesion: 0.16
Nodes (15): consumers.py (backend), Atomic Telemetry Writes + Safe Key Access (Issue F, MEDIUM), client_id Dedup (Issue E, MEDIUM), drone_uid_update Handler (Issue C, HIGH), Protocol Alignment: session_ack / STATUS_STARTED (Issue H), resume_mission_id field, session_start message handler, Authenticate the Socket (Issue A, CRITICAL) (+7 more)

### Community 69 - "KeyboardType"
Cohesion: 0.23
Nodes (11): Modifier, NavController, OtpVerificationPage(), BatteryMonitorScreen(), NavController, LabeledNumberField(), InstructionStep(), NavHostController (+3 more)

### Community 70 - "TermsAndConditionsScreen"
Cohesion: 0.70
Nodes (4): NavController, SectionBody(), SectionTitle(), TermsAndConditionsScreen()

### Community 71 - "BrakingSettingsViewModel"
Cohesion: 0.24
Nodes (6): BrakeWriteResult, BrakingSettingsState, BrakingSettingsViewModel, Job, StateFlow, ViewModel

### Community 72 - "CameraTrackingState.kt"
Cohesion: 0.13
Nodes (14): TrackingMode, NONE, POINT, RECTANGLE, TrackingStatus, ACTIVE, ERROR, IDLE (+6 more)

### Community 73 - "MissionTemplateDatabase"
Cohesion: 0.19
Nodes (5): Context, RoomDatabase, MissionTemplateDatabase, SyncWorker, CoroutineWorker

### Community 74 - "ObstacleDetectionViewModel"
Cohesion: 0.27
Nodes (5): AndroidViewModel, LatLng, MissionItemInt, StateFlow, ObstacleDetectionViewModel

### Community 75 - "Barometer Calibration View Model"
Cohesion: 0.26
Nodes (5): BarometerCalibrationUiState, BarometerCalibrationViewModel, Job, StateFlow, ViewModel

### Community 76 - "MainActivity.kt"
Cohesion: 0.35
Nodes (4): Intent, MainActivity, Bundle, ComponentActivity

### Community 77 - "ObstacleDetectionScreen.kt"
Cohesion: 0.21
Nodes (14): ObstacleInfo, ResumeOption, RTLMonitoringState, InactiveContent(), InfoRow(), MonitoringContent(), ObstacleDetectedContent(), ObstacleDetectionScreen() (+6 more)

### Community 78 - "Replay Map"
Cohesion: 0.36
Nodes (12): GoogleMapReplay(), isOnline(), BitmapDescriptor, Context, LatLng, Modifier, rememberDroneIcon(), ReplayMap() (+4 more)

### Community 79 - "Flow Sensor Calibration Screen"
Cohesion: 0.23
Nodes (12): FlowCalibrationState, CALIBRATING, COMPLETED, ERROR, IDLE, FlowSensorCalibrationScreen(), InstructionStep(), Color (+4 more)

### Community 80 - "In App Update State"
Cohesion: 0.22
Nodes (7): Available, Downloaded, InAppUpdateState, None, rememberInAppUpdateState(), UpdateUiState, AppUpdateInfo

### Community 81 - "GimbalController"
Cohesion: 0.21
Nodes (4): GimbalController, StateFlow, UByte, GimbalDeviceAttitudeStatus

### Community 82 - "EventEntity"
Cohesion: 0.33
Nodes (3): EventEntity, EventDao, Flow

### Community 83 - "Flight Log Exporter"
Cohesion: 0.32
Nodes (3): FlightExportData, FlightLogExporter, TlogEntry

### Community 85 - ".startFlight"
Cohesion: 0.28
Nodes (3): MissionMode, AUTO, MANUAL

### Community 87 - "Data Flash Parser"
Cohesion: 0.31
Nodes (3): DataFlashParser, ByteArray, ParseSummary

### Community 88 - "Log Analysis View Model"
Cohesion: 0.31
Nodes (8): AnalysisComplete, Error, AndroidViewModel, StateFlow, Loading, LogAnalysisUiState, LogAnalysisViewModel, Parsing

### Community 90 - "FlightModeViewModel"
Cohesion: 0.25
Nodes (5): FlightModeOption, FlightModeState, FlightModeViewModel, StateFlow, ViewModel

### Community 92 - "UnifiedFlightTracker.kt"
Cohesion: 0.18
Nodes (5): FlightManager, Job, FlightLoggingService, Job, StateFlow

### Community 93 - "DisconnectionRTLHandler"
Cohesion: 0.33
Nodes (4): DisconnectionRTLHandler, CoroutineScope, MavlinkTelemetryRepository, StateFlow

### Community 95 - "LogsScreen.kt"
Cohesion: 0.27
Nodes (10): ExportFormat, CSV, JSON, TLOG, ActiveFlightCard(), FlightItem(), formatDuration(), Modifier (+2 more)

### Community 96 - "TopNavBar.kt"
Cohesion: 0.42
Nodes (8): ConnectionStatusWidget(), DividerBlock(), InfoBlock(), InfoBlockGroup(), ImageVector, Modifier, NavHostController, TopNavBar()

### Community 98 - ".imagePointToGps"
Cohesion: 0.36
Nodes (4): CameraInfo, GeoReferencer, FloatArray, LatLng

### Community 99 - "Welcome"
Cohesion: 0.36
Nodes (9): KFT Play Store App Icon, KFT Wordmark Logo Mark, Kapil Group Logo, KFT Kapil Future Tech Logo, Agricultural Spraying Drone Background Photo, Version Label v1.01, Welcome Screen Splash Image, Kapil Future Tech (KFT) Brand Identity (+1 more)

### Community 100 - "TlogIntegration"
Cohesion: 0.43
Nodes (3): Application, TlogIntegration, ViewModelStoreOwner

### Community 101 - "Spray Calibration Screen"
Cohesion: 0.39
Nodes (8): CalibrationButton(), ConfigurationStatusCard(), androidx, Color, ImageVector, NavHostController, SprayCalibrationScreen(), StatusRow()

### Community 102 - "FlightModeScreen"
Cohesion: 0.46
Nodes (7): FlightModeDropdown(), FlightModeScreen(), FlightModeSlotCard(), InfoBanner(), Color, NavController, LoadingRow()

### Community 103 - "Forgot Password Page"
Cohesion: 0.54
Nodes (7): ForgotPasswordPage(), Modifier, NavController, Step1EmailContent(), Step2OtpContent(), Step3NewPasswordContent(), textFieldColors()

### Community 104 - "SharedViewModel.kt"
Cohesion: 0.07
Nodes (23): FenceAction, ALWAYS_LAND, BRAKE, REPORT_ONLY, RTL, SMART_RTL, SMART_RTL_LAND, FenceStatus (+15 more)

### Community 105 - "Shared View Model"
Cohesion: 0.29
Nodes (7): ArmingCheckSafetyDialog(), ArmingCheckState, HIDDEN, PROMPT_REBOOT, PROMPT_WRITE, WRITE_FAILED, WRITING

### Community 106 - "AuthResult"
Cohesion: 0.40
Nodes (5): AuthResult, AUTHENTICATED, DENIED, FAILED, LEGACY_FIRMWARE

### Community 107 - "Sensor Settings Screen"
Cohesion: 0.39
Nodes (7): androidx, NavHostController, roundToStep(), SensorSettingsScreen(), StepButton(), ThresholdControl(), ClosedFloatingPointRange

### Community 108 - "Video Stream Settings"
Cohesion: 0.54
Nodes (7): VideoStreamInfo, DetectedStreamCard(), Modifier, UsbDevice, PresetChip(), UsbDeviceCard(), VideoStreamSettings()

### Community 111 - "FlightState"
Cohesion: 0.33
Nodes (6): FlightState, ACTIVE, FINALIZING, IDLE, STARTING, STOPPING

### Community 112 - "Shared View Model"
Cohesion: 0.29
Nodes (6): GridSetupSource, DRONE_POSITION, KML_IMPORT, MAP_DRAW, NONE, RC_CONTROL

### Community 113 - "DroneCameraFeedOverlay"
Cohesion: 0.36
Nodes (9): CameraPlaceholder(), DroneCameraFeedOverlay(), Modifier, VideoStreamView(), GimbalState, GimbalButton(), GimbalControlOverlay(), ImageVector (+1 more)

### Community 114 - "Telemetry Overlay"
Cohesion: 0.57
Nodes (6): Cell(), EscRow(), fmt(), fmtPlain(), Modifier, TelemetryOverlay()

### Community 115 - "Settings Screen"
Cohesion: 0.48
Nodes (6): androidx, ImageVector, NavHostController, NumberedButton(), SettingsEntry, SettingsScreen()

### Community 117 - "CommandLong"
Cohesion: 0.27
Nodes (4): CalibrationCommands, UByte, CommandLong, MavCmd

### Community 119 - "Rc Stick View"
Cohesion: 0.67
Nodes (5): drawGimbal(), Modifier, Offset, norm(), RcStickView()

### Community 121 - "VideoTrackingOverlay"
Cohesion: 0.80
Nodes (5): CameraInfoBadge(), GimbalInfoBadge(), Modifier, TrackingStatusBadge(), VideoTrackingOverlay()

### Community 124 - "Shared View Model"
Cohesion: 0.40
Nodes (5): ClearMissionState, CLEARING, FAILED, IDLE, SUCCESS

### Community 126 - "Shared View Model"
Cohesion: 0.40
Nodes (4): MissionType, GRID, NONE, WAYPOINT

### Community 127 - "MissionTemplateEntity"
Cohesion: 0.05
Nodes (31): Flow, MissionTemplateDao, GridParameters, MissionTemplateEntity, LatLng, MissionItemInt, TypeToken, MissionTemplateTypeConverters (+23 more)

### Community 129 - "Gradlew"
Cohesion: 0.70
Nodes (4): gradlew script, die(), save(), warn()

### Community 134 - "Shared View Model"
Cohesion: 0.67
Nodes (3): UserFlightMode, AUTOMATIC, MANUAL

### Community 157 - "MotorTestScreen.kt"
Cohesion: 0.52
Nodes (6): Modifier, NavController, MotorTestScreen(), MtCard(), MtInputDialog(), MtNumberField()

### Community 160 - "SelectFlyingMethodScreen.kt"
Cohesion: 0.70
Nodes (4): Dp, NavController, SelectFlyingMethodScreen(), StyledFlyingMethodCard()

### Community 162 - "BarometerCalibrationScreen.kt"
Cohesion: 0.70
Nodes (4): BarometerCalibrationScreen(), ImageVector, NavController, StatusIndicatorCard()

### Community 163 - "PreflightFailsafeDialog"
Cohesion: 0.70
Nodes (4): formatVolts(), PreflightFailsafeDialog(), SummaryRow(), PreflightFailsafeSummary

## Knowledge Gaps
- **271 isolated node(s):** `ErrorResponse`, `Error`, `AdminInfo`, `VehicleInfo`, `AUTHENTICATED` (+266 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 615 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **31 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `SharedViewModel` connect `SharedViewModel` to `.handleAltitudeFailsafe`, `FullParamListViewModel`, `ProximityOverlay.kt`, `Calibration Screen UI`, `Shared View Model`, `Notification`, `Compass Calibration Screen UI`, `RC Calibration Screen UI`, `Text-to-Speech Announcements`, `ServoOutputViewModel`, `Level Calibration Screen UI`, `ObstacleDetectionManager`, `OptionsViewModel`, `.connect`, `AppNavGraph.kt`, `SelectFlyingMethodScreen.kt`, `GcsMap`, `PreflightFailsafeDialog`, `SpraySettingsViewModel`, `SessionManager`, `AnalyzeLogScreen.kt`, `LatLng`, `MotorTestViewModel`, `AboutDroneViewModel`, `ObstacleDetectionIntegrationExample`, `Context`, `TelemetryState`, `Color.kt`, `ConnectionPage.kt`, `BatteryMonitorViewModel.kt`, `KeyboardType`, `BrakingSettingsViewModel`, `ObstacleDetectionViewModel`, `Barometer Calibration View Model`, `MainActivity.kt`, `Flow Sensor Calibration Screen`, `FlightModeViewModel`, `LogUtils`, `UnifiedFlightTracker.kt`, `LogsScreen.kt`, `TopNavBar.kt`, `TlogIntegration`, `Spray Calibration Screen`, `SharedViewModel.kt`, `Shared View Model`, `Sensor Settings Screen`, `.sendCommandAck`, `Shared View Model`, `Settings Screen`, `CommandLong`, `.clearGeofenceFromFC`, `Shared View Model`, `Shared View Model`?**
  _High betweenness centrality (0.342) - this node is a cross-community bridge._
- **Why does `LogUtils` connect `LogUtils` to `FullParamListViewModel`, `UdpMavConnection`, `OptionsViewModel`, `GcsMap`, `SpraySettingsViewModel`, `AnalyzeLogScreen.kt`, `MotorTestViewModel`, `ScreenRecordService`, `Mavlink Ftp Client`, `Log Replay Screen`, `Color.kt`, `TelemetryRepository.kt`, `BatteryMonitorViewModel.kt`, `BrakingSettingsViewModel`, `Data Flash Parser`, `Log Analysis View Model`, `FlightModeViewModel`, `UnifiedFlightTracker.kt`, `SharedViewModel.kt`, `MissionTemplateEntity`?**
  _High betweenness centrality (0.099) - this node is a cross-community bridge._
- **Why does `TelemetryState` connect `TelemetryState` to `TelemetryRepository.kt`, `TopNavBar.kt`, `SharedViewModel`, `GcsMap`, `.handleAltitudeFailsafe`, `.imagePointToGps`, `ProximityOverlay.kt`, `SharedViewModel.kt`, `AnalyzeLogScreen.kt`, `Notification`, `UnifiedFlightTracker`, `DroneCameraFeedOverlay`, `.startFlight`, `ObstacleDetectionManager`, `.clearGeofenceFromFC`, `UnifiedFlightTracker.kt`, `DisconnectionRTLHandler`?**
  _High betweenness centrality (0.085) - this node is a cross-community bridge._
- **What connects `ErrorResponse`, `Error`, `AdminInfo` to the rest of the system?**
  _271 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Backend WebSocket Telemetry Consumer` be split into smaller, more focused modules?**
  _Cohesion score 0.05890257558790594 - nodes in this community are weakly interconnected._
- **Should `SharedViewModel` be split into smaller, more focused modules?**
  _Cohesion score 0.028826355525051476 - nodes in this community are weakly interconnected._
- **Should `GridGenerator` be split into smaller, more focused modules?**
  _Cohesion score 0.14408602150537633 - nodes in this community are weakly interconnected._