# Graph Report - kftgcs  (2026-09-23)

## Corpus Check
- 232 files · ~936,147 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 60 file(s) not represented in the graph (top: .xml 20, .log 19, .bat 8)

## Summary
- 2888 nodes · 6024 edges · 166 communities (125 shown, 28 thin omitted)
- Extraction: 97% EXTRACTED · 3% INFERRED · 0% AMBIGUOUS · INFERRED: 180 edges (avg confidence: 0.88)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `3c918276`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- SavedMissionStateDao
- Backend WebSocket Telemetry Consumer
- SharedViewModel
- T12SerialVideoSource
- MavlinkTelemetryRepository
- FieldType
- LogUtils
- ProximityOverlay.kt
- Calibration Screen UI
- UdpMavConnection
- Notification
- VideoStreamPlayer.kt
- ParamManagementViewModel
- Settings Screen Navigation
- Video Forwarder State Machine
- CrashLogger
- Compass Calibration Screen UI
- UserSettingsManager
- AnalyzeLogScreen.kt
- RC Calibration Screen UI
- Text-to-Speech Announcements
- ServoOutputViewModel
- Level Calibration Screen UI
- TlogViewModel
- ObstacleDetectionManager
- BluetoothMavConnection
- OptionsViewModel
- EventType
- .setParameter
- OfflineMessageDao
- CrashAnalyzer
- ObstaclePathPlanner
- AppNavGraph.kt
- UsbSerialMavConnection
- GridUtils
- ReplayFrame
- WebSocketManager
- MissionStatus
- ObstacleSensorManager
- SpraySettingsViewModel
- SessionManager
- DiagnosticFlag
- AnalyzeLogUiState
- CameraProtocolManager
- AppStrings
- LatLng
- MotorTestViewModel
- ScreenRecordService
- UnifiedFlightTracker
- Mission State Repository
- Spray Telemetry Utils
- AboutDroneViewModel
- MavlinkFtpClient
- Geofence Utils
- ObstacleDetector
- Flight Dao
- .sendCommand
- ObstacleDetectionIntegrationExample
- Log Replay Screen
- Context
- test_websocket_connection.py
- TelemetryState
- Color.kt
- AuthViewModel
- TelemetryRepository.kt
- ConnectionPage.kt
- MissionItemInt
- FenceAction
- Backend Websocket Spec
- KeyboardType
- .run
- CameraTrackingState.kt
- MissionTemplateDatabase
- ObstacleDetectionViewModel
- Barometer Calibration View Model
- GridGenerator
- ObstacleDetectionScreen.kt
- ReplayMap.kt
- Flow Sensor Calibration Screen
- In App Update State
- GimbalController
- EventEntity
- Flight Log Exporter
- TlogRepository
- KmlBoundaryParser
- TelemetryEntity
- DataFlashParser
- Log Analysis View Model
- GcsMap
- FlightModeViewModel
- MissionTemplateRepository
- VideoStreamType
- UnifiedFlightTracker.kt
- MapDataEntity
- LogsScreen.kt
- MissionTemplateViewModel
- Time Formatter
- .imagePointToGps
- Welcome
- MissionTemplateTypeConverters
- Spray Calibration Screen
- fake_mavlink_udp.py
- Forgot Password Page
- GCSApplication
- ArmingCheckState
- SecurePinManager
- Sensor Settings Screen
- Video Stream Settings
- DisconnectionRTLHandler
- SharedViewModel.kt
- PlanScreen
- Shared View Model
- DroneCameraFeedOverlay
- Telemetry Overlay
- Settings Screen
- CalibrationCommands
- TlogIntegration
- Rc Stick View
- GridParameters
- VideoTrackingOverlay
- FlightState
- Shared View Model
- .startFlight
- Shared View Model
- MissionTemplateEntity
- .requestPostAuthSetup
- Gradlew
- BarometerCalibrationScreen.kt
- importLogUriToCache
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
- PreflightFailsafeDialog
- AboutDroneScreen
- .registerNetworkCallback
- DisconnectionRTLMonitor
- ObstacleDetectionStatus
- SensorType
- SelectFlyingMethodScreen.kt
- LevelSensorCalibrationScreen.kt

## God Nodes (most connected - your core abstractions)
1. `SharedViewModel` - 325 edges
2. `MavlinkTelemetryRepository` - 112 edges
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

## Communities (166 total, 28 thin omitted)

### Community 0 - "SavedMissionStateDao"
Cohesion: 0.17
Nodes (5): SavedMissionStateDao, SavedMissionStateEntity, Context, RoomDatabase, ObstacleDatabase

### Community 1 - "Backend WebSocket Telemetry Consumer"
Cohesion: 0.06
Nodes (75): AsyncWebsocketConsumer, TelemetryConsumer - Fixed version with proper error handling Copy this to your…, TelemetryConsumer, Admin, Meta, Mission, MissionEvent, MissionSummary (+67 more)

### Community 2 - "SharedViewModel"
Cohesion: 0.03
Nodes (5): kotlinx, SharedFlow, StateFlow, MissionCompletionData, SharedViewModel

### Community 3 - "T12SerialVideoSource"
Cohesion: 0.16
Nodes (9): UsbDevice, UsbPortOwnership, describe(), ByteArray, Job, T, UsbSerialPort, T12SerialVideoSource (+1 more)

### Community 4 - "MavlinkTelemetryRepository"
Cohesion: 0.09
Nodes (9): ByteArray, kotlinx, SharedFlow, StateFlow, MavlinkTelemetryRepository, ParamValue, RcChannels, ServoOutputRaw (+1 more)

### Community 5 - "FieldType"
Cohesion: 0.05
Nodes (43): AuthResult, AUTHENTICATED, DENIED, FAILED, LEGACY_FIRMWARE, ChallengeCompleteException, KFTAuth, object@L92 (+35 more)

### Community 6 - "LogUtils"
Cohesion: 0.05
Nodes (40): ArduPilotParamMetadataRepository, TypeToken, TypeToken, Context, TypeToken, BrakeWriteResult, BrakingSettingsState, BrakingSettingsViewModel (+32 more)

### Community 7 - "ProximityOverlay.kt"
Cohesion: 0.05
Nodes (57): Intent, MainActivity, Color, ObstacleWindow, obstacleWindowOf(), proximityColor(), ProximityData, RadarSwitchState (+49 more)

### Community 8 - "Calibration Screen UI"
Cohesion: 0.06
Nodes (36): CalibrationActions(), CalibrationContent(), CalibrationHeader(), CalibrationProgress(), CalibrationScreen(), CancelledContent(), DroneOrientationIcon(), FailedContent() (+28 more)

### Community 9 - "UdpMavConnection"
Cohesion: 0.06
Nodes (25): InetAddress, UdpDiagnostics, UdpPortScanner, UdpScanResult, buildHeartbeatProbe(), crcAccumulate(), BufferedMavConnection, ByteArray (+17 more)

### Community 10 - "Notification"
Cohesion: 0.09
Nodes (4): Notification, ResumePrepResult, NotificationItem(), NotificationPanel()

### Community 11 - "VideoStreamPlayer.kt"
Cohesion: 0.08
Nodes (29): BroadcastReceiver, Context, Flow, Intent, UsbDevice, UsbManager, UsbSerialPermission, BroadcastReceiver (+21 more)

### Community 12 - "ParamManagementViewModel"
Cohesion: 0.07
Nodes (27): LatLng, TypeToken, MissionStateTypeConverters, TypeToken, Error, OkHttpClient, T, ParamAuthApiService (+19 more)

### Community 13 - "Settings Screen Navigation"
Cohesion: 0.04
Nodes (47): AboutApp, AccelerometerCalibration, Aircraft, AnalyzeLog, BarometerCalibration, BatteryMonitorSettings, Calibrations, CompassCalibration (+39 more)

### Community 14 - "Video Forwarder State Machine"
Cohesion: 0.07
Nodes (26): ForwarderState, ERROR, RUNNING, STARTING, STOPPED, STOPPING, CoroutineScope, DatagramSocket (+18 more)

### Community 16 - "Compass Calibration Screen UI"
Cohesion: 0.09
Nodes (27): CancelledContent(), CompassCalibrationActions(), CompassCalibrationContent(), CompassCalibrationHeader(), CompassCalibrationProgress(), CompassCalibrationScreen(), CompassReportCard(), FailedContent() (+19 more)

### Community 17 - "UserSettingsManager"
Cohesion: 0.21
Nodes (8): FontSizeOption, LARGE, MEDIUM, SMALL, Color, Context, SharedPreferences, UserSettingsManager

### Community 18 - "AnalyzeLogScreen.kt"
Cohesion: 0.18
Nodes (16): DroneIdentifier, extractDroneUniqueId(), extractUuidString(), UByte, LogEntryInfo, SdLogEntry, SprayTelemetry, AccentButton() (+8 more)

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
Nodes (9): MissionParameters, MissionStatistics, ResumeOption, Job, LatLng, MissionItemInt, StateFlow, ObstacleDetectionManager (+1 more)

### Community 25 - "BluetoothMavConnection"
Cohesion: 0.11
Nodes (20): app/proguard-rules.pro, BluetoothConnectionProvider, CoroutinesMavConnection, BluetoothMavConnection, BufferedMavConnection, ByteArray, MavConnection, MavFrame (+12 more)

### Community 26 - "OptionsViewModel"
Cohesion: 0.07
Nodes (22): BatteryMonitorState, BatteryMonitorViewModel, BattMonitorOption, findHwVerPresetFor(), findSensorPresetFor(), HwVerPreset, StateFlow, ViewModel (+14 more)

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
Cohesion: 0.15
Nodes (17): NavController, WelcomeScreen(), AppNavGraph(), NavHostController, PlaceholderScreen(), NavController, ParamManagementHomeScreen(), ParamNavItem (+9 more)

### Community 33 - "UsbSerialMavConnection"
Cohesion: 0.17
Nodes (14): BufferedMavConnection, ByteArray, InputStream, MavConnection, MavFrame, MavMessage, OutputStream, T (+6 more)

### Community 35 - "ReplayFrame"
Cohesion: 0.14
Nodes (11): ReplayFrame, FlightMode, ReplayTimelineBuilder, ArtificialHorizon(), drawHorizon(), Modifier, rememberReplayController(), ReplayController (+3 more)

### Community 36 - "WebSocketManager"
Cohesion: 0.15
Nodes (3): Runnable, OkHttpClient, WebSocketManager

### Community 37 - "MissionStatus"
Cohesion: 0.25
Nodes (8): MissionStatus, CANCELLED, COMPLETED, FAILED, IN_PROGRESS, INTERRUPTED, NOT_STARTED, PARTIAL_COMPLETE

### Community 38 - "ObstacleSensorManager"
Cohesion: 0.16
Nodes (8): SensorReading, CalibrationResult, StateFlow, ObstacleSensorManager, Sensor, SensorEvent, SensorEventListener, SensorManager

### Community 39 - "SpraySettingsViewModel"
Cohesion: 0.15
Nodes (17): ConfirmSprayDialog(), EnumDropdown(), fmtSpray(), InfoCenterSpray(), androidx, NavController, SectionCard(), SprayFieldView() (+9 more)

### Community 40 - "SessionManager"
Cohesion: 0.22
Nodes (3): Context, SharedPreferences, SessionManager

### Community 41 - "DiagnosticFlag"
Cohesion: 0.17
Nodes (17): AnalysisResults(), CenteredStatus(), DiagnosticCard(), formatTime(), Color, ImageVector, NavHostController, LogAnalysisScreen() (+9 more)

### Community 42 - "AnalyzeLogUiState"
Cohesion: 0.17
Nodes (14): AnalyzeLogUiState, AnalyzeLogViewModel, BrowsingSd, Copying, Downloaded, Downloading, DownloadingSd, Error (+6 more)

### Community 43 - "CameraProtocolManager"
Cohesion: 0.15
Nodes (7): CameraProtocolManager, StateFlow, CameraFovStatus, TrackingImageStatus, CameraTrackingImageStatus, MavCameraFovStatus, VideoStreamInformation

### Community 44 - "AppStrings"
Cohesion: 0.16
Nodes (12): Modifier, NavController, LoginPage(), ConnectionStatusWidget(), DividerBlock(), InfoBlock(), InfoBlockGroup(), ImageVector (+4 more)

### Community 46 - "MotorTestViewModel"
Cohesion: 0.13
Nodes (7): FrameClassMap, FrameInfo, StateFlow, UInt, ViewModel, MotorTestState, MotorTestViewModel

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
Cohesion: 0.31
Nodes (4): AboutDroneViewModel, DroneInfoState, StateFlow, ViewModel

### Community 52 - "MavlinkFtpClient"
Cohesion: 0.30
Nodes (6): FtpEntry, ByteArray, UByte, MavlinkFtpClient, Resp, FileTransferProtocol

### Community 54 - "ObstacleDetector"
Cohesion: 0.20
Nodes (7): ThreatLevel, HIGH, LOW, MEDIUM, NONE, LatLng, ObstacleDetector

### Community 55 - "Flight Dao"
Cohesion: 0.19
Nodes (3): FlightDao, Flow, FlightEntity

### Community 56 - ".sendCommand"
Cohesion: 0.13
Nodes (7): CommandLong, MavCmd, MavResult, UByte, UInt, MavCmdId, CommandAck

### Community 57 - "ObstacleDetectionIntegrationExample"
Cohesion: 0.26
Nodes (4): CompleteFlightExample, LatLng, MissionItemInt, ObstacleDetectionIntegrationExample

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
Cohesion: 0.15
Nodes (11): NavController, SectionBody(), SectionTitle(), TermsAndConditionsScreen(), Modifier, NavController, ParamLoginPage(), CalibrationOptionCard() (+3 more)

### Community 63 - "AuthViewModel"
Cohesion: 0.06
Nodes (37): AdminInfo, AdminListResponse, ApiResponse, ApiService, Error, ErrorResponse, OkHttpClient, T (+29 more)

### Community 64 - "TelemetryRepository.kt"
Cohesion: 0.13
Nodes (14): AppScope, CoroutineScope, AltitudeLimits, ArmMagicValues, MavFrame, MavMessage, SprayerState, ACTIVE_FLOW (+6 more)

### Community 65 - "ConnectionPage.kt"
Cohesion: 0.18
Nodes (12): PairedDevice, UsbDeviceInfo, BluetoothConnectionContent(), ConnectionPage(), DeviceRow(), isPlausibleHost(), isUdpSelfTarget(), NavController (+4 more)

### Community 66 - "MissionItemInt"
Cohesion: 0.14
Nodes (5): Circle, FenceZone, Polygon, ReturnPoint, MissionItemInt

### Community 67 - "FenceAction"
Cohesion: 0.14
Nodes (8): FenceAction, ALWAYS_LAND, BRAKE, REPORT_ONLY, RTL, SMART_RTL, SMART_RTL_LAND, FenceStatus

### Community 68 - "Backend Websocket Spec"
Cohesion: 0.16
Nodes (15): consumers.py (backend), Atomic Telemetry Writes + Safe Key Access (Issue F, MEDIUM), client_id Dedup (Issue E, MEDIUM), drone_uid_update Handler (Issue C, HIGH), Protocol Alignment: session_ack / STATUS_STARTED (Issue H), resume_mission_id field, session_start message handler, Authenticate the Socket (Issue A, CRITICAL) (+7 more)

### Community 69 - "KeyboardType"
Cohesion: 0.23
Nodes (11): Modifier, NavController, OtpVerificationPage(), Modifier, NavController, MobileNumberField(), SignupPage(), BatteryMonitorScreen() (+3 more)

### Community 70 - ".run"
Cohesion: 0.11
Nodes (8): CoroutinesMavConnection, MavConnectionProvider, CoroutinesMavConnection, TcpConnectionProvider, CoroutinesMavConnection, UdpConnectionProvider, CoroutinesMavConnection, UsbSerialConnectionProvider

### Community 72 - "CameraTrackingState.kt"
Cohesion: 0.15
Nodes (12): UByte, CameraCapabilities, CameraInfo, TrackingMode, NONE, POINT, RECTANGLE, TrackingStatus (+4 more)

### Community 73 - "MissionTemplateDatabase"
Cohesion: 0.19
Nodes (5): Context, RoomDatabase, MissionTemplateDatabase, SyncWorker, CoroutineWorker

### Community 74 - "ObstacleDetectionViewModel"
Cohesion: 0.22
Nodes (6): ObstacleDetectionConfig, AndroidViewModel, LatLng, MissionItemInt, StateFlow, ObstacleDetectionViewModel

### Community 75 - "Barometer Calibration View Model"
Cohesion: 0.26
Nodes (5): BarometerCalibrationUiState, BarometerCalibrationViewModel, Job, StateFlow, ViewModel

### Community 76 - "GridGenerator"
Cohesion: 0.14
Nodes (9): GridGenerator, LatLng, GridMissionConverter, LatLng, MissionItemInt, UByte, GridSurveyParams, GridSurveyResult (+1 more)

### Community 77 - "ObstacleDetectionScreen.kt"
Cohesion: 0.28
Nodes (13): ObstacleInfo, RTLMonitoringState, InactiveContent(), InfoRow(), MonitoringContent(), ObstacleDetectedContent(), ObstacleDetectionScreen(), ResumeOptionCard() (+5 more)

### Community 78 - "ReplayMap.kt"
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

### Community 85 - "KmlBoundaryParser"
Cohesion: 0.38
Nodes (4): KmlBoundaryParser, KmlParseResult, LatLng, XmlPullParser

### Community 87 - "DataFlashParser"
Cohesion: 0.31
Nodes (3): DataFlashParser, ByteArray, ParseSummary

### Community 88 - "Log Analysis View Model"
Cohesion: 0.31
Nodes (8): AnalysisComplete, Error, AndroidViewModel, StateFlow, Loading, LogAnalysisUiState, LogAnalysisViewModel, Parsing

### Community 89 - "GcsMap"
Cohesion: 0.13
Nodes (26): Context, LatLng, StateFlow, PhoneLocationProvider, rememberPhoneLocation(), DronePathPoint, averageAngles(), calculateOuterFence() (+18 more)

### Community 90 - "FlightModeViewModel"
Cohesion: 0.16
Nodes (12): FlightModeDropdown(), FlightModeScreen(), FlightModeSlotCard(), InfoBanner(), Color, NavController, LoadingRow(), FlightModeOption (+4 more)

### Community 91 - "MissionTemplateRepository"
Cohesion: 0.28
Nodes (5): MissionTemplateRepository, Reachable, Result, StreamReachability, Unreachable

### Community 92 - "VideoStreamType"
Cohesion: 0.33
Nodes (6): VideoStreamType, MPEG_TS, RTPUDP, RTSP, TCP_MPEG, UNKNOWN

### Community 93 - "UnifiedFlightTracker.kt"
Cohesion: 0.18
Nodes (5): FlightManager, Job, FlightLoggingService, Job, StateFlow

### Community 95 - "LogsScreen.kt"
Cohesion: 0.27
Nodes (10): ExportFormat, CSV, JSON, TLOG, ActiveFlightCard(), FlightItem(), formatDuration(), Modifier (+2 more)

### Community 96 - "MissionTemplateViewModel"
Cohesion: 0.23
Nodes (7): AndroidViewModel, Flow, LatLng, MissionItemInt, StateFlow, MissionTemplateUiState, MissionTemplateViewModel

### Community 98 - ".imagePointToGps"
Cohesion: 0.40
Nodes (3): GeoReferencer, FloatArray, LatLng

### Community 99 - "Welcome"
Cohesion: 0.36
Nodes (9): KFT Play Store App Icon, KFT Wordmark Logo Mark, Kapil Group Logo, KFT Kapil Future Tech Logo, Agricultural Spraying Drone Background Photo, Version Label v1.01, Welcome Screen Splash Image, Kapil Future Tech (KFT) Brand Identity (+1 more)

### Community 100 - "MissionTemplateTypeConverters"
Cohesion: 0.26
Nodes (6): LatLng, MissionItemInt, TypeToken, MissionTemplateTypeConverters, TypeToken, TypeToken

### Community 101 - "Spray Calibration Screen"
Cohesion: 0.39
Nodes (8): CalibrationButton(), ConfigurationStatusCard(), androidx, Color, ImageVector, NavHostController, SprayCalibrationScreen(), StatusRow()

### Community 102 - "fake_mavlink_udp.py"
Cohesion: 0.35
Nodes (11): crc16(), crc_accumulate(), heartbeat_frame(), listen(), main(), parse_target(), push(), Fake MAVLink vehicle over UDP, for testing the app's UDP link without a drone.… (+3 more)

### Community 103 - "Forgot Password Page"
Cohesion: 0.54
Nodes (7): ForgotPasswordPage(), Modifier, NavController, Step1EmailContent(), Step2OtpContent(), Step3NewPasswordContent(), textFieldColors()

### Community 105 - "ArmingCheckState"
Cohesion: 0.29
Nodes (7): ArmingCheckSafetyDialog(), ArmingCheckState, HIDDEN, PROMPT_REBOOT, PROMPT_WRITE, WRITE_FAILED, WRITING

### Community 106 - "SecurePinManager"
Cohesion: 0.40
Nodes (3): Context, SecurePinManager, SecretKey

### Community 107 - "Sensor Settings Screen"
Cohesion: 0.39
Nodes (7): androidx, NavHostController, roundToStep(), SensorSettingsScreen(), StepButton(), ThresholdControl(), ClosedFloatingPointRange

### Community 108 - "Video Stream Settings"
Cohesion: 0.54
Nodes (7): VideoStreamInfo, DetectedStreamCard(), Modifier, UsbDevice, PresetChip(), UsbDeviceCard(), VideoStreamSettings()

### Community 109 - "DisconnectionRTLHandler"
Cohesion: 0.33
Nodes (3): DisconnectionRTLHandler, CoroutineScope, StateFlow

### Community 110 - "SharedViewModel.kt"
Cohesion: 0.08
Nodes (18): ConnectionType, BLUETOOTH, TCP, UDP, USB, BroadcastReceiver, Intent, Job (+10 more)

### Community 111 - "PlanScreen"
Cohesion: 0.29
Nodes (8): GridSourceSelectionDialog(), KmlPolygonSelectionDialog(), MissionChoiceDialog(), MissionTypeSelectionDialog(), SaveMissionDialog(), NavHostController, PlanScreen(), KmlPolygon

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

### Community 117 - "CalibrationCommands"
Cohesion: 0.53
Nodes (3): CalibrationCommands, CommandLong, UByte

### Community 118 - "TlogIntegration"
Cohesion: 0.43
Nodes (3): Application, TlogIntegration, ViewModelStoreOwner

### Community 119 - "Rc Stick View"
Cohesion: 0.67
Nodes (5): drawGimbal(), Modifier, Offset, norm(), RcStickView()

### Community 120 - "GridParameters"
Cohesion: 0.27
Nodes (4): GridParameters, Flow, LatLng, MissionItemInt

### Community 121 - "VideoTrackingOverlay"
Cohesion: 0.80
Nodes (5): CameraInfoBadge(), GimbalInfoBadge(), Modifier, TrackingStatusBadge(), VideoTrackingOverlay()

### Community 123 - "FlightState"
Cohesion: 0.33
Nodes (6): FlightState, ACTIVE, FINALIZING, IDLE, STARTING, STOPPING

### Community 124 - "Shared View Model"
Cohesion: 0.40
Nodes (5): ClearMissionState, CLEARING, FAILED, IDLE, SUCCESS

### Community 125 - ".startFlight"
Cohesion: 0.28
Nodes (3): MissionMode, AUTO, MANUAL

### Community 126 - "Shared View Model"
Cohesion: 0.40
Nodes (4): MissionType, GRID, NONE, WAYPOINT

### Community 127 - "MissionTemplateEntity"
Cohesion: 0.18
Nodes (9): Flow, MissionTemplateDao, MissionTemplateEntity, EnhancedMissionTemplateCard(), Modifier, PlotTemplatesScreen(), Modifier, TemplateListItem() (+1 more)

### Community 129 - "Gradlew"
Cohesion: 0.70
Nodes (4): gradlew script, die(), save(), warn()

### Community 131 - "BarometerCalibrationScreen.kt"
Cohesion: 0.70
Nodes (4): BarometerCalibrationScreen(), ImageVector, NavController, StatusIndicatorCard()

### Community 132 - "importLogUriToCache"
Cohesion: 0.83
Nodes (3): importLogUriToCache(), Context, Uri

### Community 134 - "Shared View Model"
Cohesion: 0.67
Nodes (3): UserFlightMode, AUTOMATIC, MANUAL

### Community 157 - "MotorTestScreen.kt"
Cohesion: 0.52
Nodes (6): Modifier, NavController, MotorTestScreen(), MtCard(), MtInputDialog(), MtNumberField()

### Community 158 - "PreflightFailsafeDialog"
Cohesion: 0.70
Nodes (4): formatVolts(), PreflightFailsafeDialog(), SummaryRow(), PreflightFailsafeSummary

### Community 159 - "AboutDroneScreen"
Cohesion: 0.46
Nodes (7): AboutDroneScreen(), DroneInfoCard(), FrameSelectRow(), InfoDivider(), InfoRow(), NavController, SectionHeader()

### Community 161 - "DisconnectionRTLMonitor"
Cohesion: 0.36
Nodes (3): DisconnectionRTLMonitor, com, StateFlow

### Community 162 - "ObstacleDetectionStatus"
Cohesion: 0.29
Nodes (7): ObstacleDetectionStatus, INACTIVE, MONITORING, OBSTACLE_DETECTED, READY_TO_RESUME, RESUMING, RTL_IN_PROGRESS

### Community 163 - "SensorType"
Cohesion: 0.40
Nodes (5): SensorType, LIDAR, PROXIMITY, SIMULATED, ULTRASONIC

### Community 164 - "SelectFlyingMethodScreen.kt"
Cohesion: 0.70
Nodes (4): Dp, NavController, SelectFlyingMethodScreen(), StyledFlyingMethodCard()

### Community 165 - "LevelSensorCalibrationScreen.kt"
Cohesion: 0.70
Nodes (4): InstructionStep(), NavHostController, LevelSensorCalibrationScreen(), ReadingRow()

## Knowledge Gaps
- **271 isolated node(s):** `ErrorResponse`, `Error`, `AdminInfo`, `VehicleInfo`, `AUTHENTICATED` (+266 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 636 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **28 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `SharedViewModel` connect `SharedViewModel` to `MavlinkTelemetryRepository`, `LogUtils`, `ProximityOverlay.kt`, `Calibration Screen UI`, `Shared View Model`, `Notification`, `Compass Calibration Screen UI`, `AnalyzeLogScreen.kt`, `RC Calibration Screen UI`, `Text-to-Speech Announcements`, `ServoOutputViewModel`, `Level Calibration Screen UI`, `ObstacleDetectionManager`, `OptionsViewModel`, `.setParameter`, `PreflightFailsafeDialog`, `AppNavGraph.kt`, `SelectFlyingMethodScreen.kt`, `LevelSensorCalibrationScreen.kt`, `SpraySettingsViewModel`, `SessionManager`, `AppStrings`, `LatLng`, `MotorTestViewModel`, `AboutDroneViewModel`, `ObstacleDetectionIntegrationExample`, `Context`, `TelemetryState`, `Color.kt`, `ConnectionPage.kt`, `FenceAction`, `.run`, `ObstacleDetectionViewModel`, `Barometer Calibration View Model`, `Flow Sensor Calibration Screen`, `GcsMap`, `FlightModeViewModel`, `UnifiedFlightTracker.kt`, `LogsScreen.kt`, `Spray Calibration Screen`, `ArmingCheckState`, `Sensor Settings Screen`, `SharedViewModel.kt`, `PlanScreen`, `Shared View Model`, `Settings Screen`, `.clearGeofenceFromFC`, `TlogIntegration`, `.handleAltitudeFailsafe`, `Shared View Model`, `Shared View Model`?**
  _High betweenness centrality (0.343) - this node is a cross-community bridge._
- **Why does `MavlinkTelemetryRepository` connect `MavlinkTelemetryRepository` to `TelemetryRepository.kt`, `.requestPostAuthSetup`, `SharedViewModel`, `FenceAction`, `MissionItemInt`, `FieldType`, `.run`, `.uploadGeofence`, `AnalyzeLogUiState`, `CameraProtocolManager`, `DisconnectionRTLHandler`, `GimbalController`, `Spray Telemetry Utils`, `AnalyzeLogScreen.kt`, `.sendCommand`, `TelemetryState`?**
  _High betweenness centrality (0.095) - this node is a cross-community bridge._
- **Why does `TelemetryState` connect `TelemetryState` to `TelemetryRepository.kt`, `SharedViewModel`, `.imagePointToGps`, `MavlinkTelemetryRepository`, `ProximityOverlay.kt`, `Notification`, `AppStrings`, `DisconnectionRTLHandler`, `SharedViewModel.kt`, `UnifiedFlightTracker`, `DroneCameraFeedOverlay`, `AnalyzeLogScreen.kt`, `UnifiedFlightTracker.kt`, `.clearGeofenceFromFC`, `ObstacleDetectionManager`, `GcsMap`, `.handleAltitudeFailsafe`, `.startFlight`?**
  _High betweenness centrality (0.084) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `MavlinkTelemetryRepository` (e.g. with `FlowRateFilter` and `VoltageFilter`) actually correct?**
  _`MavlinkTelemetryRepository` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 8 inferred relationships involving `Notification` (e.g. with `.arm()` and `.clampRtlAltBelowFenceCeiling()`) actually correct?**
  _`Notification` has 8 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ErrorResponse`, `Error`, `AdminInfo` to the rest of the system?**
  _271 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Backend WebSocket Telemetry Consumer` be split into smaller, more focused modules?**
  _Cohesion score 0.05890257558790594 - nodes in this community are weakly interconnected._