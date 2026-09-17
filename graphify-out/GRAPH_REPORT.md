# Graph Report - kftgcs  (2026-09-17)

## Corpus Check
- 230 files · ~921,691 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 60 file(s) not represented in the graph (top: .xml 20, .log 19, .bat 8)

## Summary
- 2835 nodes · 5899 edges · 160 communities (122 shown, 25 thin omitted)
- Extraction: 97% EXTRACTED · 3% INFERRED · 0% AMBIGUOUS · INFERRED: 178 edges (avg confidence: 0.88)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `9983909c`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- GcsMap
- Backend WebSocket Telemetry Consumer
- SharedViewModel
- MissionTemplateEntity
- AuthViewModel
- KFT Firmware Authentication
- LogUtils
- ProximityOverlay.kt
- Calibration Screen UI
- UDP Connection Diagnostics
- Notification
- VideoStreamPlayer.kt
- Saved Mission State Persistence
- Settings Screen Navigation
- Video Forwarder State Machine
- GCS Application Init
- Compass Calibration Screen UI
- UserSettingsManager
- MavlinkTelemetryRepository
- RC Calibration Screen UI
- Text-to-Speech Announcements
- ServoOutputViewModel
- Level Calibration Screen UI
- TlogViewModel
- ObstacleDetectionManager
- BluetoothMavConnection
- OptionsViewModel
- EventType
- .run
- OfflineMessageDao
- Crash Analyzer
- Obstacle Path Planner
- AppNavGraph.kt
- Usb Serial Mav Connection
- GridGenerator
- Replay Timeline Builder
- Web Socket Manager
- ObstacleData.kt
- ObstacleSensorManager
- SpraySettingsViewModel
- Session Manager
- Log Analysis Screen
- Analyze Log View Model
- CameraProtocolManager
- AppStrings
- LatLng
- Motor Test View Model
- ScreenRecordService
- TelemetryState
- Mission State Repository
- Spray Telemetry Utils
- AboutDroneViewModel
- Mavlink Ftp Client
- Geofence Utils
- SavedMissionStateDao
- Flight Dao
- Grid Utils
- ObstacleDetectionIntegrationExample
- Log Replay Screen
- Context
- test_websocket_connection.py
- SharedViewModel.kt
- KmlBoundaryParser
- ApiService.kt
- Telemetry Repository
- ConnectionPage.kt
- Telemetry Repository
- Telemetry Repository
- Backend Websocket Spec
- KeyboardType
- CalibrationsScreen.kt
- Fence Types
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
- Tlog Repository
- Analyze Log Screen
- TelemetryEntity
- Data Flash Parser
- Log Analysis View Model
- ObstacleDetector
- FlightModeViewModel
- .requestPermission
- MapDataEntity
- LogsScreen.kt
- Time Formatter
- .imagePointToGps
- Welcome
- OnDeviceConnectListener
- Spray Calibration Screen
- Top Nav Bar
- Forgot Password Page
- android
- Shared View Model
- Data
- Sensor Settings Screen
- Video Stream Settings
- Color.kt
- Surface
- Motor Test Screen
- Shared View Model
- .pilotResetPassword
- Telemetry Overlay
- Settings Screen
- AboutDroneScreen
- Calibration Commands
- TermsAndConditionsScreen
- Rc Stick View
- WaypointListPanel
- DroneCameraFeedOverlay
- Barometer Calibration Screen
- VideoPlayerState
- Shared View Model
- Shared View Model
- Shared View Model
- PreflightFailsafeDialog
- ServerConfig
- Gradlew
- WebSocketManager.kt
- Local Log Import
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
- SelectFlyingMethodScreen.kt
- ParamLoginPage
- test_ws_quick.py

## God Nodes (most connected - your core abstractions)
1. `SharedViewModel` - 311 edges
2. `MavlinkTelemetryRepository` - 105 edges
3. `AppNavGraph()` - 66 edges
4. `Screen` - 63 edges
5. `Notification` - 61 edges
6. `TelemetryState` - 50 edges
7. `TextToSpeechManager` - 38 edges
8. `UnifiedFlightTracker` - 35 edges
9. `ObstacleDetectionManager` - 35 edges
10. `WebSocketManager` - 32 edges

## Surprising Connections (you probably didn't know these)
- `Reinforced Telemetry Package Protection` --references--> `BluetoothConnectionProvider`  [EXTRACTED]
  BLUETOOTH_PROGUARD_FIX.md → app/src/main/java/com/example/kftgcs/telemetry/connections/BluetoothConnectionProvider.kt
- `Enhanced Okio Protection` --references--> `BluetoothMavConnection`  [EXTRACTED]
  BLUETOOTH_PROGUARD_FIX.md → app/src/main/java/com/example/kftgcs/telemetry/connections/BluetoothMavConnection.kt
- `Authenticate the Socket (Issue A, CRITICAL)` --semantically_similar_to--> `Secret Scanning Job`  [INFERRED] [semantically similar]
  docs/backend_websocket_spec.md → .github/workflows/security.yml
- `Reinforced Telemetry Package Protection` --references--> `BluetoothMavConnection`  [EXTRACTED]
  BLUETOOTH_PROGUARD_FIX.md → app/src/main/java/com/example/kftgcs/telemetry/connections/BluetoothMavConnection.kt
- `GcsMap()` --calls--> `Polygon`  [INFERRED]
  app/src/main/java/com/example/kftgcs/uimain/GcsMap.kt → app/src/main/java/com/example/kftgcs/fence/FenceTypes.kt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Bluetooth k3-error ProGuard Root-Cause Fix** — bluetooth_proguard_fix_k3error, bluetooth_proguard_fix_logstripping, bluetooth_proguard_fix_okioprotection, bluetooth_proguard_fix_kotlinreflectionprotection, bluetooth_proguard_fix_telemetrypackageprotection [EXTRACTED 1.00]
- **CI Security Scan Pipeline** — github_workflows_security_dependencycheck, github_workflows_security_secretscanning, github_workflows_security_codeqlanalysis, github_workflows_security_androidlint [EXTRACTED 1.00]
- **Critical/High Priority Backend Data-Integrity Fixes (A, B, C)** — docs_backend_websocket_spec_socketauthentication, docs_backend_websocket_spec_vehiclededuplication, docs_backend_websocket_spec_droneuidupdate [EXTRACTED 1.00]

## Communities (160 total, 25 thin omitted)

### Community 0 - "GcsMap"
Cohesion: 0.13
Nodes (26): Context, LatLng, StateFlow, PhoneLocationProvider, rememberPhoneLocation(), DronePathPoint, averageAngles(), calculateOuterFence() (+18 more)

### Community 1 - "Backend WebSocket Telemetry Consumer"
Cohesion: 0.06
Nodes (75): AsyncWebsocketConsumer, TelemetryConsumer - Fixed version with proper error handling Copy this to your…, TelemetryConsumer, Admin, Meta, Mission, MissionEvent, MissionSummary (+67 more)

### Community 2 - "SharedViewModel"
Cohesion: 0.03
Nodes (7): com, kotlinx, SharedFlow, StateFlow, UInt, MissionCompletionData, SharedViewModel

### Community 3 - "MissionTemplateEntity"
Cohesion: 0.05
Nodes (31): Flow, MissionTemplateDao, GridParameters, MissionTemplateEntity, LatLng, MissionItemInt, TypeToken, MissionTemplateTypeConverters (+23 more)

### Community 4 - "AuthViewModel"
Cohesion: 0.12
Nodes (16): PilotLoginRequest, PilotLogoutRequest, PilotRegisterRequest, Authenticated, AuthState, AuthViewModel, Error, Context (+8 more)

### Community 5 - "KFT Firmware Authentication"
Cohesion: 0.05
Nodes (43): AuthResult, AUTHENTICATED, DENIED, FAILED, LEGACY_FIRMWARE, ChallengeCompleteException, KFTAuth, object@L92 (+35 more)

### Community 6 - "LogUtils"
Cohesion: 0.05
Nodes (39): ArduPilotParamMetadataRepository, TypeToken, TypeToken, Context, TypeToken, BrakeWriteResult, BrakingSettingsState, BrakingSettingsViewModel (+31 more)

### Community 7 - "ProximityOverlay.kt"
Cohesion: 0.19
Nodes (22): Color, proximityColor(), ProximityData, RadarThresholds, TerrainData, drawDroneIcon(), drawForwardBlip(), drawPolarGrid() (+14 more)

### Community 8 - "Calibration Screen UI"
Cohesion: 0.06
Nodes (36): CalibrationActions(), CalibrationContent(), CalibrationHeader(), CalibrationProgress(), CalibrationScreen(), CancelledContent(), DroneOrientationIcon(), FailedContent() (+28 more)

### Community 9 - "UDP Connection Diagnostics"
Cohesion: 0.07
Nodes (24): InetAddress, UdpDiagnostics, UdpPortScanner, UdpScanResult, buildHeartbeatProbe(), crcAccumulate(), BufferedMavConnection, ByteArray (+16 more)

### Community 10 - "Notification"
Cohesion: 0.11
Nodes (4): Notification, android, NotificationItem(), NotificationPanel()

### Community 11 - "VideoStreamPlayer.kt"
Cohesion: 0.19
Nodes (12): Network, Usb, VideoSource, buildMediaSource(), describeError(), Modifier, UsbUvcStreamPlayer(), VideoPlaceholder() (+4 more)

### Community 12 - "Saved Mission State Persistence"
Cohesion: 0.08
Nodes (22): Error, OkHttpClient, T, ParamAuthApiService, ParamAuthErrorResponse, ParamAuthResult, ParamLoginRequest, ParamLoginResponse (+14 more)

### Community 13 - "Settings Screen Navigation"
Cohesion: 0.04
Nodes (47): AboutApp, AccelerometerCalibration, Aircraft, AnalyzeLog, BarometerCalibration, BatteryMonitorSettings, Calibrations, CompassCalibration (+39 more)

### Community 14 - "Video Forwarder State Machine"
Cohesion: 0.07
Nodes (26): ForwarderState, ERROR, RUNNING, STARTING, STOPPED, STOPPING, CoroutineScope, DatagramSocket (+18 more)

### Community 15 - "GCS Application Init"
Cohesion: 0.07
Nodes (13): GCSApplication, Application, CrashLogger, Context, Context, SecurePinManager, DisconnectionRTLHandler, CoroutineScope (+5 more)

### Community 16 - "Compass Calibration Screen UI"
Cohesion: 0.09
Nodes (27): CancelledContent(), CompassCalibrationActions(), CompassCalibrationContent(), CompassCalibrationHeader(), CompassCalibrationProgress(), CompassCalibrationScreen(), CompassReportCard(), FailedContent() (+19 more)

### Community 17 - "UserSettingsManager"
Cohesion: 0.21
Nodes (8): FontSizeOption, LARGE, MEDIUM, SMALL, Color, Context, SharedPreferences, UserSettingsManager

### Community 18 - "MavlinkTelemetryRepository"
Cohesion: 0.07
Nodes (12): ByteArray, kotlinx, MavResult, SharedFlow, StateFlow, UByte, MavlinkTelemetryRepository, CommandAck (+4 more)

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
Cohesion: 0.14
Nodes (9): ExportFormat, CSV, JSON, TLOG, AndroidViewModel, Flow, StateFlow, TlogUiState (+1 more)

### Community 24 - "ObstacleDetectionManager"
Cohesion: 0.14
Nodes (8): MissionParameters, MissionStatistics, Job, LatLng, MissionItemInt, StateFlow, ObstacleDetectionManager, MavMode

### Community 25 - "BluetoothMavConnection"
Cohesion: 0.14
Nodes (17): app/proguard-rules.pro, BluetoothMavConnection, BufferedMavConnection, ByteArray, MavConnection, MavFrame, MavMessage, T (+9 more)

### Community 26 - "OptionsViewModel"
Cohesion: 0.10
Nodes (12): ActionDropdown(), ActionRadioGroup(), NavHostController, NumericTextField(), OptionsScreen(), SectionCard(), SubLabel(), FailsafeOptions (+4 more)

### Community 27 - "EventType"
Cohesion: 0.08
Nodes (21): EventSeverity, CRITICAL, ERROR, INFO, WARNING, EventType, ARM_DISARM, CONNECTION_LOSS (+13 more)

### Community 28 - ".run"
Cohesion: 0.11
Nodes (8): CoroutinesMavConnection, MavConnectionProvider, CoroutinesMavConnection, TcpConnectionProvider, CoroutinesMavConnection, UdpConnectionProvider, CoroutinesMavConnection, UsbSerialConnectionProvider

### Community 29 - "OfflineMessageDao"
Cohesion: 0.16
Nodes (3): Flow, OfflineMessageDao, OfflineMessageEntity

### Community 31 - "Obstacle Path Planner"
Cohesion: 0.25
Nodes (3): LatLng, ObstaclePathPlanner, ObstacleZone

### Community 32 - "AppNavGraph.kt"
Cohesion: 0.17
Nodes (15): NavController, WelcomeScreen(), AppNavGraph(), NavHostController, PlaceholderScreen(), NavController, ParamManagementHomeScreen(), ParamNavItem (+7 more)

### Community 33 - "Usb Serial Mav Connection"
Cohesion: 0.17
Nodes (14): BufferedMavConnection, ByteArray, InputStream, MavConnection, MavFrame, MavMessage, OutputStream, T (+6 more)

### Community 34 - "GridGenerator"
Cohesion: 0.14
Nodes (9): GridGenerator, LatLng, GridMissionConverter, LatLng, MissionItemInt, UByte, GridSurveyParams, GridSurveyResult (+1 more)

### Community 35 - "Replay Timeline Builder"
Cohesion: 0.14
Nodes (11): ReplayFrame, FlightMode, ReplayTimelineBuilder, ArtificialHorizon(), drawHorizon(), Modifier, rememberReplayController(), ReplayController (+3 more)

### Community 36 - "Web Socket Manager"
Cohesion: 0.15
Nodes (3): Runnable, OkHttpClient, WebSocketManager

### Community 37 - "ObstacleData.kt"
Cohesion: 0.09
Nodes (21): MissionStatus, CANCELLED, COMPLETED, FAILED, IN_PROGRESS, INTERRUPTED, NOT_STARTED, PARTIAL_COMPLETE (+13 more)

### Community 38 - "ObstacleSensorManager"
Cohesion: 0.17
Nodes (7): CalibrationResult, StateFlow, ObstacleSensorManager, Sensor, SensorEvent, SensorEventListener, SensorManager

### Community 39 - "SpraySettingsViewModel"
Cohesion: 0.08
Nodes (26): BatteryMonitorState, BatteryMonitorViewModel, BattMonitorOption, findHwVerPresetFor(), findSensorPresetFor(), HwVerPreset, StateFlow, ViewModel (+18 more)

### Community 40 - "Session Manager"
Cohesion: 0.24
Nodes (3): Context, SharedPreferences, SessionManager

### Community 41 - "Log Analysis Screen"
Cohesion: 0.17
Nodes (17): AnalysisResults(), CenteredStatus(), DiagnosticCard(), formatTime(), Color, ImageVector, NavHostController, LogAnalysisScreen() (+9 more)

### Community 42 - "Analyze Log View Model"
Cohesion: 0.17
Nodes (14): AnalyzeLogUiState, AnalyzeLogViewModel, BrowsingSd, Copying, Downloaded, Downloading, DownloadingSd, Error (+6 more)

### Community 43 - "CameraProtocolManager"
Cohesion: 0.13
Nodes (10): CameraProtocolManager, StateFlow, UByte, CameraCapabilities, CameraFovStatus, TrackingImageStatus, CameraInformation, CameraTrackingImageStatus (+2 more)

### Community 44 - "AppStrings"
Cohesion: 0.15
Nodes (10): Modifier, NavController, LoginPage(), Modifier, NavController, MobileNumberField(), SignupPage(), NavHostController (+2 more)

### Community 46 - "Motor Test View Model"
Cohesion: 0.13
Nodes (7): FrameClassMap, FrameInfo, StateFlow, UInt, ViewModel, MotorTestState, MotorTestViewModel

### Community 47 - "ScreenRecordService"
Cohesion: 0.15
Nodes (13): NotificationType, ERROR, INFO, SUCCESS, WARNING, Context, IBinder, Intent (+5 more)

### Community 48 - "TelemetryState"
Cohesion: 0.05
Nodes (23): Application, TlogIntegration, FlightManager, Job, FlightState, ACTIVE, FINALIZING, IDLE (+15 more)

### Community 49 - "Mission State Repository"
Cohesion: 0.23
Nodes (7): LatLng, MissionItemInt, TypeToken, MissionStateRepository, TypeToken, TypeToken, SavedMissionState

### Community 50 - "Spray Telemetry Utils"
Cohesion: 0.11
Nodes (5): CalibrationPoint, FlowRateFilter, FlowRateValidator, TankLevelCalculator, VoltageFilter

### Community 51 - "AboutDroneViewModel"
Cohesion: 0.31
Nodes (4): AboutDroneViewModel, DroneInfoState, StateFlow, ViewModel

### Community 52 - "Mavlink Ftp Client"
Cohesion: 0.30
Nodes (6): FtpEntry, ByteArray, UByte, MavlinkFtpClient, Resp, FileTransferProtocol

### Community 54 - "SavedMissionStateDao"
Cohesion: 0.12
Nodes (10): LatLng, TypeToken, MissionStateTypeConverters, TypeToken, SavedMissionStateDao, SavedMissionStateEntity, Context, RoomDatabase (+2 more)

### Community 55 - "Flight Dao"
Cohesion: 0.19
Nodes (3): FlightDao, Flow, FlightEntity

### Community 57 - "ObstacleDetectionIntegrationExample"
Cohesion: 0.23
Nodes (5): CompleteFlightExample, LatLng, MissionItemInt, ObstacleDetectionIntegrationExample, ObstacleDetectionConfig

### Community 58 - "Log Replay Screen"
Cohesion: 0.25
Nodes (14): Context, shareFile(), shareLogFile(), shareRecording(), CrashBanner(), DisplayPrefsMenu(), formatT(), NavHostController (+6 more)

### Community 59 - "Context"
Cohesion: 0.12
Nodes (5): BroadcastReceiver, Context, Intent, UsbDevice, BroadcastReceiver

### Community 60 - "test_websocket_connection.py"
Cohesion: 0.22
Nodes (12): main(), on_close(), on_error(), on_message(), on_open(), Called when an error occurs, Called when WebSocket connection is closed, WebSocket Connection Test Script ================================ This script… (+4 more)

### Community 61 - "SharedViewModel.kt"
Cohesion: 0.10
Nodes (15): BluetoothConnectionProvider, CoroutinesMavConnection, ConnectionType, BLUETOOTH, TCP, UDP, USB, Job (+7 more)

### Community 62 - "KmlBoundaryParser"
Cohesion: 0.31
Nodes (6): KmlPolygonSelectionDialog(), KmlBoundaryParser, KmlParseResult, KmlPolygon, LatLng, XmlPullParser

### Community 63 - "ApiService.kt"
Cohesion: 0.15
Nodes (16): AdminInfo, AdminListResponse, ApiResponse, ApiService, Error, ErrorResponse, OkHttpClient, T (+8 more)

### Community 64 - "Telemetry Repository"
Cohesion: 0.13
Nodes (14): AppScope, CoroutineScope, AltitudeLimits, ArmMagicValues, MavFrame, MavMessage, SprayerState, ACTIVE_FLOW (+6 more)

### Community 65 - "ConnectionPage.kt"
Cohesion: 0.22
Nodes (12): PairedDevice, UsbDeviceInfo, BluetoothConnectionContent(), ConnectionPage(), DeviceRow(), isPlausibleHost(), isUdpSelfTarget(), NavController (+4 more)

### Community 66 - "Telemetry Repository"
Cohesion: 0.17
Nodes (4): CommandLong, MavCmd, UInt, MavCmdId

### Community 67 - "Telemetry Repository"
Cohesion: 0.14
Nodes (7): Circle, FenceZone, Polygon, ReturnPoint, MissionItemInt, currentItem, totalItems

### Community 68 - "Backend Websocket Spec"
Cohesion: 0.16
Nodes (15): consumers.py (backend), Atomic Telemetry Writes + Safe Key Access (Issue F, MEDIUM), client_id Dedup (Issue E, MEDIUM), drone_uid_update Handler (Issue C, HIGH), Protocol Alignment: session_ack / STATUS_STARTED (Issue H), resume_mission_id field, session_start message handler, Authenticate the Socket (Issue A, CRITICAL) (+7 more)

### Community 69 - "KeyboardType"
Cohesion: 0.23
Nodes (11): Modifier, NavController, OtpVerificationPage(), BatteryMonitorScreen(), NavController, LabeledNumberField(), InstructionStep(), NavHostController (+3 more)

### Community 70 - "CalibrationsScreen.kt"
Cohesion: 0.70
Nodes (4): CalibrationOptionCard(), CalibrationsScreen(), ImageVector, NavController

### Community 71 - "Fence Types"
Cohesion: 0.15
Nodes (8): FenceAction, ALWAYS_LAND, BRAKE, REPORT_ONLY, RTL, SMART_RTL, SMART_RTL_LAND, FenceStatus

### Community 72 - "CameraTrackingState.kt"
Cohesion: 0.12
Nodes (15): CameraTrackingState, TrackingMode, NONE, POINT, RECTANGLE, TrackingStatus, ACTIVE, ERROR (+7 more)

### Community 73 - "MissionTemplateDatabase"
Cohesion: 0.21
Nodes (5): Context, RoomDatabase, MissionTemplateDatabase, SyncWorker, CoroutineWorker

### Community 74 - "ObstacleDetectionViewModel"
Cohesion: 0.27
Nodes (5): AndroidViewModel, LatLng, MissionItemInt, StateFlow, ObstacleDetectionViewModel

### Community 75 - "Barometer Calibration View Model"
Cohesion: 0.26
Nodes (5): BarometerCalibrationUiState, BarometerCalibrationViewModel, Job, StateFlow, ViewModel

### Community 76 - "MainActivity.kt"
Cohesion: 0.11
Nodes (18): Intent, MainActivity, ColorPickerPanel(), ColorSlider(), androidx, Color, NavController, SectionCard() (+10 more)

### Community 77 - "ObstacleDetectionScreen.kt"
Cohesion: 0.23
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
Cohesion: 0.16
Nodes (9): GimbalState, GimbalController, StateFlow, UByte, GimbalButton(), GimbalControlOverlay(), ImageVector, Modifier (+1 more)

### Community 82 - "EventEntity"
Cohesion: 0.33
Nodes (3): EventEntity, EventDao, Flow

### Community 83 - "Flight Log Exporter"
Cohesion: 0.32
Nodes (3): FlightExportData, FlightLogExporter, TlogEntry

### Community 85 - "Analyze Log Screen"
Cohesion: 0.33
Nodes (10): LogEntryInfo, SdLogEntry, AccentButton(), AnalyzeLogScreen(), CenteredStatus(), formatBytes(), formatTimestamp(), NavHostController (+2 more)

### Community 87 - "Data Flash Parser"
Cohesion: 0.31
Nodes (3): DataFlashParser, ByteArray, ParseSummary

### Community 88 - "Log Analysis View Model"
Cohesion: 0.31
Nodes (8): AnalysisComplete, Error, AndroidViewModel, StateFlow, Loading, LogAnalysisUiState, LogAnalysisViewModel, Parsing

### Community 89 - "ObstacleDetector"
Cohesion: 0.20
Nodes (7): ThreatLevel, HIGH, LOW, MEDIUM, NONE, LatLng, ObstacleDetector

### Community 90 - "FlightModeViewModel"
Cohesion: 0.16
Nodes (12): FlightModeDropdown(), FlightModeScreen(), FlightModeSlotCard(), InfoBanner(), Color, NavController, LoadingRow(), FlightModeOption (+4 more)

### Community 93 - ".requestPermission"
Cohesion: 0.32
Nodes (8): BroadcastReceiver, Context, Flow, Intent, UsbDevice, UsbManager, UsbUvcDeviceManager, BroadcastReceiver

### Community 95 - "LogsScreen.kt"
Cohesion: 0.62
Nodes (6): ActiveFlightCard(), FlightItem(), formatDuration(), Modifier, NavHostController, LogsScreen()

### Community 98 - ".imagePointToGps"
Cohesion: 0.36
Nodes (4): CameraInfo, GeoReferencer, FloatArray, LatLng

### Community 99 - "Welcome"
Cohesion: 0.36
Nodes (9): KFT Play Store App Icon, KFT Wordmark Logo Mark, Kapil Group Logo, KFT Kapil Future Tech Logo, Agricultural Spraying Drone Background Photo, Version Label v1.01, Welcome Screen Splash Image, Kapil Future Tech (KFT) Brand Identity (+1 more)

### Community 100 - "OnDeviceConnectListener"
Cohesion: 0.32
Nodes (5): UsbDevice, UsbUvcVideoSource, OnDeviceConnectListener, USBMonitor, UVCCamera

### Community 101 - "Spray Calibration Screen"
Cohesion: 0.39
Nodes (8): CalibrationButton(), ConfigurationStatusCard(), androidx, Color, ImageVector, NavHostController, SprayCalibrationScreen(), StatusRow()

### Community 102 - "Top Nav Bar"
Cohesion: 0.42
Nodes (8): ConnectionStatusWidget(), DividerBlock(), InfoBlock(), InfoBlockGroup(), ImageVector, Modifier, NavHostController, TopNavBar()

### Community 103 - "Forgot Password Page"
Cohesion: 0.54
Nodes (7): ForgotPasswordPage(), Modifier, NavController, Step1EmailContent(), Step2OtpContent(), Step3NewPasswordContent(), textFieldColors()

### Community 104 - "android"
Cohesion: 0.27
Nodes (4): android, SurfaceTextureListener, SurfaceTextureListener, SurfaceTextureListener

### Community 105 - "Shared View Model"
Cohesion: 0.29
Nodes (7): ArmingCheckSafetyDialog(), ArmingCheckState, HIDDEN, PROMPT_REBOOT, PROMPT_WRITE, WRITE_FAILED, WRITING

### Community 106 - "Data"
Cohesion: 0.36
Nodes (6): DroneIdentifier, extractDroneUniqueId(), extractUuidString(), UByte, SprayTelemetry, OpenDroneIdBasicId

### Community 107 - "Sensor Settings Screen"
Cohesion: 0.39
Nodes (7): androidx, NavHostController, roundToStep(), SensorSettingsScreen(), StepButton(), ThresholdControl(), ClosedFloatingPointRange

### Community 108 - "Video Stream Settings"
Cohesion: 0.54
Nodes (7): VideoStreamInfo, DetectedStreamCard(), Modifier, UsbDevice, PresetChip(), UsbDeviceCard(), VideoStreamSettings()

### Community 109 - "Color.kt"
Cohesion: 0.22
Nodes (6): GridSourceSelectionDialog(), MissionChoiceDialog(), MissionTypeSelectionDialog(), SaveMissionDialog(), NavHostController, PlanScreen()

### Community 110 - "Surface"
Cohesion: 0.42
Nodes (9): MissionCompletionDialog(), FailsafeAlertPopup(), FloatingButtons(), Modifier, NavHostController, MainPage(), SprayStatusPopup(), StatusPanel() (+1 more)

### Community 111 - "Motor Test Screen"
Cohesion: 0.52
Nodes (6): Modifier, NavController, MotorTestScreen(), MtCard(), MtInputDialog(), MtNumberField()

### Community 112 - "Shared View Model"
Cohesion: 0.29
Nodes (6): GridSetupSource, DRONE_POSITION, KML_IMPORT, MAP_DRAW, NONE, RC_CONTROL

### Community 113 - ".pilotResetPassword"
Cohesion: 0.25
Nodes (4): PilotResetPasswordRequest, PasswordResetSuccess, ResetOtpSent, ResetOtpVerified

### Community 114 - "Telemetry Overlay"
Cohesion: 0.57
Nodes (6): Cell(), EscRow(), fmt(), fmtPlain(), Modifier, TelemetryOverlay()

### Community 115 - "Settings Screen"
Cohesion: 0.48
Nodes (6): androidx, ImageVector, NavHostController, NumberedButton(), SettingsEntry, SettingsScreen()

### Community 116 - "AboutDroneScreen"
Cohesion: 0.46
Nodes (7): AboutDroneScreen(), DroneInfoCard(), FrameSelectRow(), InfoDivider(), InfoRow(), NavController, SectionHeader()

### Community 117 - "Calibration Commands"
Cohesion: 0.53
Nodes (3): CalibrationCommands, CommandLong, UByte

### Community 118 - "TermsAndConditionsScreen"
Cohesion: 0.70
Nodes (4): NavController, SectionBody(), SectionTitle(), TermsAndConditionsScreen()

### Community 119 - "Rc Stick View"
Cohesion: 0.67
Nodes (5): drawGimbal(), Modifier, Offset, norm(), RcStickView()

### Community 120 - "WaypointListPanel"
Cohesion: 0.60
Nodes (5): androidx, LatLng, Modifier, WaypointListPanel(), WaypointRow()

### Community 121 - "DroneCameraFeedOverlay"
Cohesion: 0.42
Nodes (9): CameraPlaceholder(), DroneCameraFeedOverlay(), Modifier, VideoStreamView(), CameraInfoBadge(), GimbalInfoBadge(), Modifier, TrackingStatusBadge() (+1 more)

### Community 122 - "Barometer Calibration Screen"
Cohesion: 0.70
Nodes (4): BarometerCalibrationScreen(), ImageVector, NavController, StatusIndicatorCard()

### Community 123 - "VideoPlayerState"
Cohesion: 0.33
Nodes (6): VideoPlayerState, BUFFERING, ERROR, IDLE, LOADING, PLAYING

### Community 124 - "Shared View Model"
Cohesion: 0.40
Nodes (5): ClearMissionState, CLEARING, FAILED, IDLE, SUCCESS

### Community 126 - "Shared View Model"
Cohesion: 0.40
Nodes (4): MissionType, GRID, NONE, WAYPOINT

### Community 127 - "PreflightFailsafeDialog"
Cohesion: 0.70
Nodes (4): formatVolts(), PreflightFailsafeDialog(), SummaryRow(), PreflightFailsafeSummary

### Community 129 - "Gradlew"
Cohesion: 0.70
Nodes (4): gradlew script, die(), save(), warn()

### Community 131 - "WebSocketManager.kt"
Cohesion: 0.38
Nodes (3): Context, Flow, CertificatePinner

### Community 132 - "Local Log Import"
Cohesion: 0.83
Nodes (3): importLogUriToCache(), Context, Uri

### Community 134 - "Shared View Model"
Cohesion: 0.67
Nodes (3): UserFlightMode, AUTOMATIC, MANUAL

### Community 157 - "SelectFlyingMethodScreen.kt"
Cohesion: 0.70
Nodes (4): Dp, NavController, SelectFlyingMethodScreen(), StyledFlyingMethodCard()

### Community 158 - "ParamLoginPage"
Cohesion: 0.83
Nodes (3): Modifier, NavController, ParamLoginPage()

## Knowledge Gaps
- **271 isolated node(s):** `ErrorResponse`, `Error`, `AdminInfo`, `VehicleInfo`, `AUTHENTICATED` (+266 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 628 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **25 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `SharedViewModel` connect `SharedViewModel` to `GcsMap`, `LogUtils`, `ProximityOverlay.kt`, `Calibration Screen UI`, `Shared View Model`, `Notification`, `Compass Calibration Screen UI`, `MavlinkTelemetryRepository`, `RC Calibration Screen UI`, `Text-to-Speech Announcements`, `ServoOutputViewModel`, `Level Calibration Screen UI`, `ObstacleDetectionManager`, `OptionsViewModel`, `.run`, `SelectFlyingMethodScreen.kt`, `AppNavGraph.kt`, `SpraySettingsViewModel`, `Session Manager`, `LatLng`, `Motor Test View Model`, `TelemetryState`, `AboutDroneViewModel`, `ObstacleDetectionIntegrationExample`, `Context`, `SharedViewModel.kt`, `ConnectionPage.kt`, `KeyboardType`, `CalibrationsScreen.kt`, `Fence Types`, `CameraTrackingState.kt`, `ObstacleDetectionViewModel`, `Barometer Calibration View Model`, `MainActivity.kt`, `Flow Sensor Calibration Screen`, `Analyze Log Screen`, `FlightModeViewModel`, `.handleAltitudeFailsafe`, `Shared View Model`, `LogsScreen.kt`, `Spray Calibration Screen`, `Top Nav Bar`, `Shared View Model`, `Sensor Settings Screen`, `Color.kt`, `Surface`, `Shared View Model`, `Settings Screen`, `Shared View Model`, `Shared View Model`, `Shared View Model`, `PreflightFailsafeDialog`?**
  _High betweenness centrality (0.370) - this node is a cross-community bridge._
- **Why does `MavlinkTelemetryRepository` connect `MavlinkTelemetryRepository` to `Telemetry Repository`, `Telemetry Repository`, `SharedViewModel`, `Telemetry Repository`, `Telemetry Repository`, `KFT Firmware Authentication`, `Fence Types`, `Analyze Log View Model`, `CameraProtocolManager`, `GCS Application Init`, `TelemetryState`, `GimbalController`, `Spray Telemetry Utils`, `Analyze Log Screen`, `.run`?**
  _High betweenness centrality (0.096) - this node is a cross-community bridge._
- **Why does `TelemetryState` connect `TelemetryState` to `Telemetry Repository`, `GcsMap`, `SharedViewModel`, `.imagePointToGps`, `Top Nav Bar`, `Data`, `Notification`, `Surface`, `GCS Application Init`, `MavlinkTelemetryRepository`, `ObstacleDetectionManager`, `DroneCameraFeedOverlay`, `.handleAltitudeFailsafe`, `Shared View Model`, `SharedViewModel.kt`?**
  _High betweenness centrality (0.091) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `MavlinkTelemetryRepository` (e.g. with `FlowRateFilter` and `VoltageFilter`) actually correct?**
  _`MavlinkTelemetryRepository` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 8 inferred relationships involving `Notification` (e.g. with `.arm()` and `.clampRtlAltBelowFenceCeiling()`) actually correct?**
  _`Notification` has 8 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ErrorResponse`, `Error`, `AdminInfo` to the rest of the system?**
  _271 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `GcsMap` be split into smaller, more focused modules?**
  _Cohesion score 0.12941176470588237 - nodes in this community are weakly interconnected._