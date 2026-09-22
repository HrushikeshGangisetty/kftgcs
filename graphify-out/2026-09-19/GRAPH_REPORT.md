# Graph Report - kftgcs  (2026-09-19)

## Corpus Check
- 230 files · ~928,935 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 60 file(s) not represented in the graph (top: .xml 20, .log 19, .bat 8)

## Summary
- 2859 nodes · 5972 edges · 153 communities (114 shown, 27 thin omitted)
- Extraction: 97% EXTRACTED · 3% INFERRED · 0% AMBIGUOUS · INFERRED: 177 edges (avg confidence: 0.88)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `5df8cbf4`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- SavedMissionStateDao
- Backend WebSocket Telemetry Consumer
- SharedViewModel
- FlightManager
- ObstacleDetectionStatus
- KFT Firmware Authentication
- FullParamListViewModel
- ProximityOverlay.kt
- Calibration Screen UI
- UdpMavConnection
- Notification
- VideoStreamPlayer.kt
- ParamAuthTokenStore
- Settings Screen Navigation
- Video Forwarder State Machine
- GCSApplication
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
- ObstaclePathPlanner
- AppNavGraph.kt
- Usb Serial Mav Connection
- PlanScreen
- Replay Timeline Builder
- WebSocketManager
- ObstacleData.kt
- ObstacleSensorManager
- SpraySettingsViewModel
- SessionManager
- Log Analysis Screen
- AnalyzeLogUiState
- CameraProtocolManager
- AppStrings
- LatLng
- MotorTestViewModel
- LogUtils
- UnifiedFlightTracker
- Mission State Repository
- Spray Telemetry Utils
- AboutDroneViewModel
- Mavlink Ftp Client
- Geofence Utils
- ObstacleDatabase
- Flight Dao
- GridUtils
- ObstacleDetectionIntegrationExample
- Log Replay Screen
- Context
- test_websocket_connection.py
- TelemetryState
- CalibrationsScreen.kt
- AuthViewModel
- Telemetry Repository
- ConnectionPage.kt
- .sendCommand
- MissionItemInt
- Backend Websocket Spec
- KeyboardType
- Color.kt
- FenceAction
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
- AnalyzeLogScreen.kt
- TelemetryEntity
- Data Flash Parser
- Log Analysis View Model
- .registerNetworkCallback
- FlightModeViewModel
- test_ws_quick.py
- UnifiedFlightTracker.kt
- DisconnectionRTLHandler
- MapDataEntity
- LogsScreen.kt
- Time Formatter
- .imagePointToGps
- Welcome
- TlogIntegration
- Spray Calibration Screen
- Forgot Password Page
- SharedViewModel.kt
- Shared View Model
- Sensor Settings Screen
- Video Stream Settings
- FlightState
- Shared View Model
- GimbalState
- Telemetry Overlay
- Settings Screen
- VideoStreamType
- Calibration Commands
- Rc Stick View
- VideoRelayNotification.kt
- DroneCameraFeedOverlay
- Shared View Model
- Shared View Model
- MissionTemplateEntity
- Gradlew
- .setParameter
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
- ParamManagementViewModel

## God Nodes (most connected - your core abstractions)
1. `SharedViewModel` - 324 edges
2. `MavlinkTelemetryRepository` - 109 edges
3. `AppNavGraph()` - 66 edges
4. `Notification` - 64 edges
5. `Screen` - 63 edges
6. `TelemetryState` - 50 edges
7. `TextToSpeechManager` - 38 edges
8. `UnifiedFlightTracker` - 35 edges
9. `ObstacleDetectionManager` - 35 edges
10. `WebSocketManager` - 32 edges

## Surprising Connections (you probably didn't know these)
- `Enhanced Okio Protection` --references--> `BluetoothMavConnection`  [EXTRACTED]
  BLUETOOTH_PROGUARD_FIX.md → app/src/main/java/com/example/kftgcs/telemetry/connections/BluetoothMavConnection.kt
- `Authenticate the Socket (Issue A, CRITICAL)` --semantically_similar_to--> `Secret Scanning Job`  [INFERRED] [semantically similar]
  docs/backend_websocket_spec.md → .github/workflows/security.yml
- `Reinforced Telemetry Package Protection` --references--> `BluetoothConnectionProvider`  [EXTRACTED]
  BLUETOOTH_PROGUARD_FIX.md → app/src/main/java/com/example/kftgcs/telemetry/connections/BluetoothConnectionProvider.kt
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

## Communities (153 total, 27 thin omitted)

### Community 0 - "SavedMissionStateDao"
Cohesion: 0.16
Nodes (7): LatLng, TypeToken, MissionStateTypeConverters, TypeToken, SavedMissionStateDao, SavedMissionStateEntity, Gson

### Community 1 - "Backend WebSocket Telemetry Consumer"
Cohesion: 0.06
Nodes (75): AsyncWebsocketConsumer, TelemetryConsumer - Fixed version with proper error handling Copy this to your…, TelemetryConsumer, Admin, Meta, Mission, MissionEvent, MissionSummary (+67 more)

### Community 2 - "SharedViewModel"
Cohesion: 0.03
Nodes (5): kotlinx, SharedFlow, StateFlow, MissionCompletionData, SharedViewModel

### Community 4 - "ObstacleDetectionStatus"
Cohesion: 0.29
Nodes (7): ObstacleDetectionStatus, INACTIVE, MONITORING, OBSTACLE_DETECTED, READY_TO_RESUME, RESUMING, RTL_IN_PROGRESS

### Community 5 - "KFT Firmware Authentication"
Cohesion: 0.05
Nodes (43): AuthResult, AUTHENTICATED, DENIED, FAILED, LEGACY_FIRMWARE, ChallengeCompleteException, KFTAuth, object@L92 (+35 more)

### Community 6 - "FullParamListViewModel"
Cohesion: 0.08
Nodes (32): ArduPilotParamMetadataRepository, TypeToken, TypeToken, Context, TypeToken, BreakingSettingsScreen(), ConfirmChangesDialog(), fmtBrake() (+24 more)

### Community 7 - "ProximityOverlay.kt"
Cohesion: 0.06
Nodes (53): Color, proximityColor(), ProximityData, RadarThresholds, TerrainData, MissionCompletionDialog(), FailsafeAlertPopup(), FloatingButtons() (+45 more)

### Community 8 - "Calibration Screen UI"
Cohesion: 0.06
Nodes (36): CalibrationActions(), CalibrationContent(), CalibrationHeader(), CalibrationProgress(), CalibrationScreen(), CancelledContent(), DroneOrientationIcon(), FailedContent() (+28 more)

### Community 9 - "UdpMavConnection"
Cohesion: 0.07
Nodes (24): InetAddress, UdpDiagnostics, UdpPortScanner, UdpScanResult, buildHeartbeatProbe(), crcAccumulate(), BufferedMavConnection, ByteArray (+16 more)

### Community 10 - "Notification"
Cohesion: 0.08
Nodes (4): Notification, ResumePrepResult, NotificationItem(), NotificationPanel()

### Community 11 - "VideoStreamPlayer.kt"
Cohesion: 0.07
Nodes (30): BroadcastReceiver, Context, Flow, Intent, UsbDevice, UsbManager, UsbUvcDeviceManager, BroadcastReceiver (+22 more)

### Community 12 - "ParamAuthTokenStore"
Cohesion: 0.12
Nodes (14): Error, OkHttpClient, T, ParamAuthApiService, ParamAuthErrorResponse, ParamAuthResult, ParamLoginRequest, ParamLoginResponse (+6 more)

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
Cohesion: 0.21
Nodes (8): FontSizeOption, LARGE, MEDIUM, SMALL, Color, Context, SharedPreferences, UserSettingsManager

### Community 18 - "MavlinkTelemetryRepository"
Cohesion: 0.08
Nodes (9): ByteArray, kotlinx, SharedFlow, StateFlow, MavlinkTelemetryRepository, ParamValue, RcChannels, ServoOutputRaw (+1 more)

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
Cohesion: 0.14
Nodes (8): MissionParameters, MissionStatistics, Job, LatLng, MissionItemInt, StateFlow, ObstacleDetectionManager, MavMode

### Community 25 - "BluetoothMavConnection"
Cohesion: 0.11
Nodes (20): app/proguard-rules.pro, BluetoothConnectionProvider, CoroutinesMavConnection, BluetoothMavConnection, BufferedMavConnection, ByteArray, MavConnection, MavFrame (+12 more)

### Community 26 - "OptionsViewModel"
Cohesion: 0.11
Nodes (12): ActionDropdown(), ActionRadioGroup(), NavHostController, NumericTextField(), OptionsScreen(), SectionCard(), SubLabel(), FailsafeOptions (+4 more)

### Community 27 - "EventType"
Cohesion: 0.08
Nodes (21): EventSeverity, CRITICAL, ERROR, INFO, WARNING, EventType, ARM_DISARM, CONNECTION_LOSS (+13 more)

### Community 28 - ".run"
Cohesion: 0.11
Nodes (8): CoroutinesMavConnection, MavConnectionProvider, CoroutinesMavConnection, TcpConnectionProvider, CoroutinesMavConnection, UdpConnectionProvider, CoroutinesMavConnection, UsbSerialConnectionProvider

### Community 29 - "OfflineMessageDao"
Cohesion: 0.13
Nodes (5): Flow, OfflineMessageDao, OfflineMessageEntity, Flow, CertificatePinner

### Community 31 - "ObstaclePathPlanner"
Cohesion: 0.25
Nodes (3): LatLng, ObstaclePathPlanner, ObstacleZone

### Community 32 - "AppNavGraph.kt"
Cohesion: 0.18
Nodes (14): NavController, WelcomeScreen(), AppNavGraph(), NavHostController, PlaceholderScreen(), NavController, LanguageSelectionPage(), AboutAppScreen() (+6 more)

### Community 33 - "Usb Serial Mav Connection"
Cohesion: 0.17
Nodes (14): BufferedMavConnection, ByteArray, InputStream, MavConnection, MavFrame, MavMessage, OutputStream, T (+6 more)

### Community 34 - "PlanScreen"
Cohesion: 0.05
Nodes (47): GridGenerator, LatLng, GridMissionConverter, LatLng, MissionItemInt, UByte, GridSurveyParams, GridSurveyResult (+39 more)

### Community 35 - "Replay Timeline Builder"
Cohesion: 0.14
Nodes (11): ReplayFrame, FlightMode, ReplayTimelineBuilder, ArtificialHorizon(), drawHorizon(), Modifier, rememberReplayController(), ReplayController (+3 more)

### Community 36 - "WebSocketManager"
Cohesion: 0.15
Nodes (3): Runnable, OkHttpClient, WebSocketManager

### Community 37 - "ObstacleData.kt"
Cohesion: 0.13
Nodes (14): MissionStatus, CANCELLED, COMPLETED, FAILED, IN_PROGRESS, INTERRUPTED, NOT_STARTED, PARTIAL_COMPLETE (+6 more)

### Community 38 - "ObstacleSensorManager"
Cohesion: 0.09
Nodes (15): SensorReading, ThreatLevel, HIGH, LOW, MEDIUM, NONE, LatLng, ObstacleDetector (+7 more)

### Community 39 - "SpraySettingsViewModel"
Cohesion: 0.15
Nodes (17): ConfirmSprayDialog(), EnumDropdown(), fmtSpray(), InfoCenterSpray(), androidx, NavController, SectionCard(), SprayFieldView() (+9 more)

### Community 40 - "SessionManager"
Cohesion: 0.24
Nodes (3): Context, SharedPreferences, SessionManager

### Community 41 - "Log Analysis Screen"
Cohesion: 0.17
Nodes (17): AnalysisResults(), CenteredStatus(), DiagnosticCard(), formatTime(), Color, ImageVector, NavHostController, LogAnalysisScreen() (+9 more)

### Community 42 - "AnalyzeLogUiState"
Cohesion: 0.14
Nodes (17): AnalyzeLogUiState, AnalyzeLogViewModel, BrowsingSd, Copying, Downloaded, Downloading, DownloadingSd, Error (+9 more)

### Community 43 - "CameraProtocolManager"
Cohesion: 0.15
Nodes (7): CameraProtocolManager, StateFlow, CameraFovStatus, TrackingImageStatus, CameraTrackingImageStatus, MavCameraFovStatus, VideoStreamInformation

### Community 44 - "AppStrings"
Cohesion: 0.16
Nodes (12): Modifier, NavController, LoginPage(), ConnectionStatusWidget(), DividerBlock(), InfoBlock(), InfoBlockGroup(), ImageVector (+4 more)

### Community 46 - "MotorTestViewModel"
Cohesion: 0.13
Nodes (7): FrameClassMap, FrameInfo, StateFlow, UInt, ViewModel, MotorTestState, MotorTestViewModel

### Community 47 - "LogUtils"
Cohesion: 0.05
Nodes (31): BatteryMonitorState, BatteryMonitorViewModel, BattMonitorOption, findHwVerPresetFor(), findSensorPresetFor(), HwVerPreset, StateFlow, ViewModel (+23 more)

### Community 48 - "UnifiedFlightTracker"
Cohesion: 0.12
Nodes (5): Job, MissionMode, AUTO, MANUAL, UnifiedFlightTracker

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

### Community 54 - "ObstacleDatabase"
Cohesion: 0.47
Nodes (3): Context, RoomDatabase, ObstacleDatabase

### Community 55 - "Flight Dao"
Cohesion: 0.19
Nodes (3): FlightDao, Flow, FlightEntity

### Community 57 - "ObstacleDetectionIntegrationExample"
Cohesion: 0.31
Nodes (4): CompleteFlightExample, LatLng, MissionItemInt, ObstacleDetectionIntegrationExample

### Community 58 - "Log Replay Screen"
Cohesion: 0.25
Nodes (14): Context, shareFile(), shareLogFile(), shareRecording(), CrashBanner(), DisplayPrefsMenu(), formatT(), NavHostController (+6 more)

### Community 59 - "Context"
Cohesion: 0.09
Nodes (7): BatteryFsAction, BroadcastReceiver, Context, Intent, UInt, UsbDevice, BroadcastReceiver

### Community 60 - "test_websocket_connection.py"
Cohesion: 0.22
Nodes (12): main(), on_close(), on_error(), on_message(), on_open(), Called when an error occurs, Called when WebSocket connection is closed, WebSocket Connection Test Script ================================ This script… (+4 more)

### Community 61 - "TelemetryState"
Cohesion: 0.32
Nodes (4): TelemetryState, LatLng, StateFlow, TrackingManager

### Community 62 - "CalibrationsScreen.kt"
Cohesion: 0.70
Nodes (4): CalibrationOptionCard(), CalibrationsScreen(), ImageVector, NavController

### Community 63 - "AuthViewModel"
Cohesion: 0.06
Nodes (37): AdminInfo, AdminListResponse, ApiResponse, ApiService, Error, ErrorResponse, OkHttpClient, T (+29 more)

### Community 64 - "Telemetry Repository"
Cohesion: 0.13
Nodes (14): AppScope, CoroutineScope, AltitudeLimits, ArmMagicValues, MavFrame, MavMessage, SprayerState, ACTIVE_FLOW (+6 more)

### Community 65 - "ConnectionPage.kt"
Cohesion: 0.18
Nodes (12): PairedDevice, UsbDeviceInfo, BluetoothConnectionContent(), ConnectionPage(), DeviceRow(), isPlausibleHost(), isUdpSelfTarget(), NavController (+4 more)

### Community 66 - ".sendCommand"
Cohesion: 0.13
Nodes (7): CommandLong, MavCmd, MavResult, UByte, UInt, MavCmdId, CommandAck

### Community 67 - "MissionItemInt"
Cohesion: 0.12
Nodes (7): Circle, FenceZone, Polygon, ReturnPoint, MissionItemInt, currentItem, totalItems

### Community 68 - "Backend Websocket Spec"
Cohesion: 0.16
Nodes (15): consumers.py (backend), Atomic Telemetry Writes + Safe Key Access (Issue F, MEDIUM), client_id Dedup (Issue E, MEDIUM), drone_uid_update Handler (Issue C, HIGH), Protocol Alignment: session_ack / STATUS_STARTED (Issue H), resume_mission_id field, session_start message handler, Authenticate the Socket (Issue A, CRITICAL) (+7 more)

### Community 69 - "KeyboardType"
Cohesion: 0.22
Nodes (12): Modifier, NavController, MobileNumberField(), SignupPage(), BatteryMonitorScreen(), NavController, LabeledNumberField(), InstructionStep() (+4 more)

### Community 70 - "Color.kt"
Cohesion: 0.20
Nodes (7): Modifier, NavController, OtpVerificationPage(), NavController, SectionBody(), SectionTitle(), TermsAndConditionsScreen()

### Community 71 - "FenceAction"
Cohesion: 0.17
Nodes (8): FenceAction, ALWAYS_LAND, BRAKE, REPORT_ONLY, RTL, SMART_RTL, SMART_RTL_LAND, FenceStatus

### Community 72 - "CameraTrackingState.kt"
Cohesion: 0.15
Nodes (12): UByte, CameraCapabilities, CameraInfo, TrackingMode, NONE, POINT, RECTANGLE, TrackingStatus (+4 more)

### Community 73 - "MissionTemplateDatabase"
Cohesion: 0.19
Nodes (5): Context, RoomDatabase, MissionTemplateDatabase, SyncWorker, CoroutineWorker

### Community 74 - "ObstacleDetectionViewModel"
Cohesion: 0.21
Nodes (6): ResumeOption, AndroidViewModel, LatLng, MissionItemInt, StateFlow, ObstacleDetectionViewModel

### Community 75 - "Barometer Calibration View Model"
Cohesion: 0.26
Nodes (5): BarometerCalibrationUiState, BarometerCalibrationViewModel, Job, StateFlow, ViewModel

### Community 76 - "MainActivity.kt"
Cohesion: 0.35
Nodes (4): Intent, MainActivity, Bundle, ComponentActivity

### Community 77 - "ObstacleDetectionScreen.kt"
Cohesion: 0.30
Nodes (13): ObstacleInfo, RTLMonitoringState, InactiveContent(), InfoRow(), MonitoringContent(), ObstacleDetectedContent(), ObstacleDetectionScreen(), ResumeOptionCard() (+5 more)

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
Cohesion: 0.39
Nodes (3): EventEntity, EventDao, Flow

### Community 83 - "Flight Log Exporter"
Cohesion: 0.32
Nodes (3): FlightExportData, FlightLogExporter, TlogEntry

### Community 85 - "AnalyzeLogScreen.kt"
Cohesion: 0.18
Nodes (16): DroneIdentifier, extractDroneUniqueId(), extractUuidString(), UByte, LogEntryInfo, SdLogEntry, SprayTelemetry, AccentButton() (+8 more)

### Community 87 - "Data Flash Parser"
Cohesion: 0.31
Nodes (3): DataFlashParser, ByteArray, ParseSummary

### Community 88 - "Log Analysis View Model"
Cohesion: 0.31
Nodes (8): AnalysisComplete, Error, AndroidViewModel, StateFlow, Loading, LogAnalysisUiState, LogAnalysisViewModel, Parsing

### Community 90 - "FlightModeViewModel"
Cohesion: 0.16
Nodes (12): FlightModeDropdown(), FlightModeScreen(), FlightModeSlotCard(), InfoBanner(), Color, NavController, LoadingRow(), FlightModeOption (+4 more)

### Community 92 - "UnifiedFlightTracker.kt"
Cohesion: 0.24
Nodes (3): FlightLoggingService, Job, StateFlow

### Community 93 - "DisconnectionRTLHandler"
Cohesion: 0.33
Nodes (3): DisconnectionRTLHandler, CoroutineScope, StateFlow

### Community 95 - "LogsScreen.kt"
Cohesion: 0.27
Nodes (10): ExportFormat, CSV, JSON, TLOG, ActiveFlightCard(), FlightItem(), formatDuration(), Modifier (+2 more)

### Community 98 - ".imagePointToGps"
Cohesion: 0.40
Nodes (3): GeoReferencer, FloatArray, LatLng

### Community 99 - "Welcome"
Cohesion: 0.36
Nodes (9): KFT Play Store App Icon, KFT Wordmark Logo Mark, Kapil Group Logo, KFT Kapil Future Tech Logo, Agricultural Spraying Drone Background Photo, Version Label v1.01, Welcome Screen Splash Image, Kapil Future Tech (KFT) Brand Identity (+1 more)

### Community 100 - "TlogIntegration"
Cohesion: 0.43
Nodes (3): Application, TlogIntegration, ViewModelStoreOwner

### Community 101 - "Spray Calibration Screen"
Cohesion: 0.39
Nodes (8): CalibrationButton(), ConfigurationStatusCard(), androidx, Color, ImageVector, NavHostController, SprayCalibrationScreen(), StatusRow()

### Community 103 - "Forgot Password Page"
Cohesion: 0.54
Nodes (7): ForgotPasswordPage(), Modifier, NavController, Step1EmailContent(), Step2OtpContent(), Step3NewPasswordContent(), textFieldColors()

### Community 104 - "SharedViewModel.kt"
Cohesion: 0.10
Nodes (14): ConnectionType, BLUETOOTH, TCP, UDP, USB, Job, MavCmd, MavResult (+6 more)

### Community 105 - "Shared View Model"
Cohesion: 0.29
Nodes (7): ArmingCheckSafetyDialog(), ArmingCheckState, HIDDEN, PROMPT_REBOOT, PROMPT_WRITE, WRITE_FAILED, WRITING

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

### Community 113 - "GimbalState"
Cohesion: 0.60
Nodes (5): GimbalState, GimbalButton(), GimbalControlOverlay(), ImageVector, Modifier

### Community 114 - "Telemetry Overlay"
Cohesion: 0.57
Nodes (6): Cell(), EscRow(), fmt(), fmtPlain(), Modifier, TelemetryOverlay()

### Community 115 - "Settings Screen"
Cohesion: 0.48
Nodes (6): androidx, ImageVector, NavHostController, NumberedButton(), SettingsEntry, SettingsScreen()

### Community 116 - "VideoStreamType"
Cohesion: 0.33
Nodes (6): VideoStreamType, MPEG_TS, RTPUDP, RTSP, TCP_MPEG, UNKNOWN

### Community 117 - "Calibration Commands"
Cohesion: 0.53
Nodes (3): CalibrationCommands, CommandLong, UByte

### Community 119 - "Rc Stick View"
Cohesion: 0.67
Nodes (5): drawGimbal(), Modifier, Offset, norm(), RcStickView()

### Community 121 - "DroneCameraFeedOverlay"
Cohesion: 0.33
Nodes (10): CameraPlaceholder(), DroneCameraFeedOverlay(), Modifier, VideoStreamView(), CameraTrackingState, CameraInfoBadge(), GimbalInfoBadge(), Modifier (+2 more)

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

### Community 165 - "ParamManagementViewModel"
Cohesion: 0.15
Nodes (14): Modifier, NavController, ParamLoginPage(), NavController, ParamManagementHomeScreen(), ParamNavItem, Error, Idle (+6 more)

## Knowledge Gaps
- **271 isolated node(s):** `ErrorResponse`, `Error`, `AdminInfo`, `VehicleInfo`, `AUTHENTICATED` (+266 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 631 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **27 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `SharedViewModel` connect `SharedViewModel` to `.setParameter`, `FullParamListViewModel`, `ProximityOverlay.kt`, `Calibration Screen UI`, `Shared View Model`, `Notification`, `Compass Calibration Screen UI`, `MavlinkTelemetryRepository`, `RC Calibration Screen UI`, `Text-to-Speech Announcements`, `ServoOutputViewModel`, `Level Calibration Screen UI`, `ObstacleDetectionManager`, `OptionsViewModel`, `.run`, `AppNavGraph.kt`, `SelectFlyingMethodScreen.kt`, `PlanScreen`, `PreflightFailsafeDialog`, `SpraySettingsViewModel`, `SessionManager`, `AppStrings`, `LatLng`, `MotorTestViewModel`, `LogUtils`, `AboutDroneViewModel`, `ObstacleDetectionIntegrationExample`, `Context`, `TelemetryState`, `CalibrationsScreen.kt`, `ConnectionPage.kt`, `KeyboardType`, `FenceAction`, `ObstacleDetectionViewModel`, `Barometer Calibration View Model`, `MainActivity.kt`, `Flow Sensor Calibration Screen`, `AnalyzeLogScreen.kt`, `FlightModeViewModel`, `UnifiedFlightTracker.kt`, `LogsScreen.kt`, `TlogIntegration`, `Spray Calibration Screen`, `SharedViewModel.kt`, `Shared View Model`, `Sensor Settings Screen`, `Shared View Model`, `Settings Screen`, `DroneCameraFeedOverlay`, `.clearGeofenceFromFC`, `Shared View Model`, `Shared View Model`?**
  _High betweenness centrality (0.367) - this node is a cross-community bridge._
- **Why does `TelemetryState` connect `TelemetryState` to `Telemetry Repository`, `SharedViewModel`, `FlightManager`, `PlanScreen`, `.imagePointToGps`, `ProximityOverlay.kt`, `SharedViewModel.kt`, `Notification`, `AppStrings`, `UnifiedFlightTracker`, `MavlinkTelemetryRepository`, `AnalyzeLogScreen.kt`, `ObstacleDetectionManager`, `DroneCameraFeedOverlay`, `.clearGeofenceFromFC`, `UnifiedFlightTracker.kt`, `DisconnectionRTLHandler`?**
  _High betweenness centrality (0.094) - this node is a cross-community bridge._
- **Why does `MavlinkTelemetryRepository` connect `MavlinkTelemetryRepository` to `Telemetry Repository`, `.uploadGeofence`, `SharedViewModel`, `.sendCommand`, `MissionItemInt`, `KFT Firmware Authentication`, `FenceAction`, `AnalyzeLogUiState`, `CameraProtocolManager`, `GimbalController`, `Spray Telemetry Utils`, `TelemetryState`, `AnalyzeLogScreen.kt`, `.run`, `DisconnectionRTLHandler`?**
  _High betweenness centrality (0.093) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `MavlinkTelemetryRepository` (e.g. with `FlowRateFilter` and `VoltageFilter`) actually correct?**
  _`MavlinkTelemetryRepository` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 8 inferred relationships involving `Notification` (e.g. with `.arm()` and `.clampRtlAltBelowFenceCeiling()`) actually correct?**
  _`Notification` has 8 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ErrorResponse`, `Error`, `AdminInfo` to the rest of the system?**
  _271 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Backend WebSocket Telemetry Consumer` be split into smaller, more focused modules?**
  _Cohesion score 0.05890257558790594 - nodes in this community are weakly interconnected._