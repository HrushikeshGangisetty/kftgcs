# Graph Report - kftgcs  (2026-10-01)

## Corpus Check
- 207 files · ~918,158 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 54 file(s) not represented in the graph (top: .xml 20, .log 19, .aab 4)

## Summary
- 2670 nodes · 5535 edges · 149 communities (110 shown, 30 thin omitted)
- Extraction: 98% EXTRACTED · 2% INFERRED · 0% AMBIGUOUS · INFERRED: 121 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `0bea3fe7`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MissionStateRepository
- HobbywingEsc.kt
- SharedViewModel
- T12SerialVideoSource
- MavlinkTelemetryRepository
- FieldType
- FullParamListViewModel
- ProximityOverlay.kt
- Calibration Screen UI
- SpraySettingsViewModel
- Notification
- VideoStreamPlayer.kt
- ParamAuthTokenStore
- Screen
- .imagePointToGps
- CrashLogger
- Compass Calibration Screen UI
- UserSettingsManager
- SavedMissionStateDao
- RC Calibration Screen UI
- Text-to-Speech Announcements
- ServoOutputViewModel
- Level Calibration Screen UI
- TlogViewModel
- ObstacleDetectionManager
- UdpDiagnostics
- Data.kt
- EventType
- .connect
- ObstacleDetector
- CrashAnalyzer
- PlanScreen
- AppNavGraph.kt
- OptionsViewModel
- GridUtils
- ReplayFrame
- WebSocketManager
- ObstacleData.kt
- ObstacleSensorManager
- LogUtils
- SessionManager
- DiagnosticFlag
- AnalyzeLogUiState
- CameraProtocolManager
- AppStrings
- LatLng
- MotorTestViewModel
- BatteryMonitorViewModel
- UnifiedFlightTracker
- FenceAction
- Spray Telemetry Utils
- AboutDroneViewModel
- MavlinkFtpClient
- Geofence Utils
- ParamManagementViewModel
- Flight Dao
- .sendCommand
- TelemetryState
- Log Replay Screen
- Context
- OfflineMessageDao
- ObstacleDatabase
- BluetoothMavConnection
- AuthViewModel
- TelemetryRepository.kt
- ConnectionPage.kt
- MissionItemInt
- GCSApplication
- Backend Websocket Spec
- KeyboardType
- CameraTrackingState.kt
- .uploadGeofence
- UnifiedFlightTracker.kt
- AnalyzeLogScreen.kt
- ObstacleDetectionViewModel
- BarometerCalibrationViewModel
- SecurePinManager
- ObstacleDetectionScreen.kt
- ReplayMap.kt
- Flow Sensor Calibration Screen
- In App Update State
- CommandLong
- EventEntity
- Flight Log Exporter
- TlogRepository
- TlogIntegration
- TelemetryEntity
- DataFlashParser
- Log Analysis View Model
- GcsMap
- FlightModeViewModel
- ObstacleDetectionStatus
- MapDataEntity
- LogsScreen.kt
- FlightState
- .startFlight
- SignupPage.kt
- Welcome
- CalibrationsScreen
- Spray Calibration Screen
- fake_mavlink_udp.py
- Forgot Password Page
- .sendCommandAck
- ArmingCheckState
- VideoTrackingOverlay
- Sensor Settings Screen
- SyncWorker.kt
- DisconnectionRTLHandler
- SharedViewModel.kt
- .registerNetworkCallback
- Shared View Model
- Telemetry Overlay
- SettingsScreen
- MissionTemplateDatabase
- MissionStatus
- Rc Stick View
- VideoStreamSettings
- .newFtpClient
- Shared View Model
- DroneCameraFeedOverlay
- Shared View Model
- MissionTemplateEntity
- Gradlew
- PreflightFailsafeDialog
- LevelSensorCalibrationScreen
- Example Instrumented Test
- Shared View Model
- Example Unit Test
- ArtificialHorizon.kt
- Pavaman Logo
- Privacy-Policy
- Privacy-Policy
- Autonomous
- D Image Prev Ui
- Logbag
- Manual
- Security
- Security
- Security
- MotorTestScreen.kt

## God Nodes (most connected - your core abstractions)
1. `SharedViewModel` - 336 edges
2. `MavlinkTelemetryRepository` - 113 edges
3. `AppNavGraph()` - 66 edges
4. `Screen` - 64 edges
5. `Notification` - 62 edges
6. `TelemetryState` - 50 edges
7. `TextToSpeechManager` - 38 edges
8. `UnifiedFlightTracker` - 35 edges
9. `ObstacleDetectionManager` - 35 edges
10. `WebSocketManager` - 32 edges

## Surprising Connections (you probably didn't know these)
- `Authenticate the Socket (Issue A, CRITICAL)` --semantically_similar_to--> `Secret Scanning Job`  [INFERRED] [semantically similar]
  docs/backend_websocket_spec.md → .github/workflows/security.yml
- `GcsMap()` --calls--> `Polygon`  [INFERRED]
  app/src/main/java/com/example/kftgcs/uimain/GcsMap.kt → app/src/main/java/com/example/kftgcs/fence/FenceTypes.kt
- `GcsMap()` --calls--> `Circle`  [INFERRED]
  app/src/main/java/com/example/kftgcs/uimain/GcsMap.kt → app/src/main/java/com/example/kftgcs/fence/FenceTypes.kt
- `PlanScreen()` --calls--> `GridGenerator`  [INFERRED]
  app/src/main/java/com/example/kftgcs/uimain/PlanScreen.kt → app/src/main/java/com/example/kftgcs/grid/GridGenerator.kt
- `PlanScreen()` --calls--> `GridWaypoint`  [INFERRED]
  app/src/main/java/com/example/kftgcs/uimain/PlanScreen.kt → app/src/main/java/com/example/kftgcs/grid/GridWaypoint.kt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **CI Security Scan Pipeline** — github_workflows_security_dependencycheck, github_workflows_security_secretscanning, github_workflows_security_codeqlanalysis, github_workflows_security_androidlint [EXTRACTED 1.00]
- **Critical/High Priority Backend Data-Integrity Fixes (A, B, C)** — docs_backend_websocket_spec_socketauthentication, docs_backend_websocket_spec_vehiclededuplication, docs_backend_websocket_spec_droneuidupdate [EXTRACTED 1.00]

## Communities (149 total, 30 thin omitted)

### Community 0 - "MissionStateRepository"
Cohesion: 0.23
Nodes (7): LatLng, MissionItemInt, TypeToken, MissionStateRepository, TypeToken, TypeToken, SavedMissionState

### Community 1 - "HobbywingEsc.kt"
Cohesion: 0.21
Nodes (9): HwCanFrame, HwEscReply, hwGetEscIdFrame(), hwParseReply(), hwServiceFrame(), UByte, UInt, withTail() (+1 more)

### Community 2 - "SharedViewModel"
Cohesion: 0.03
Nodes (7): kotlinx, SharedFlow, StateFlow, MissionCompletionData, ResumePrepResult, SharedViewModel, com

### Community 3 - "T12SerialVideoSource"
Cohesion: 0.16
Nodes (9): UsbDevice, UsbPortOwnership, describe(), ByteArray, Job, T, UsbSerialPort, T12SerialVideoSource (+1 more)

### Community 4 - "MavlinkTelemetryRepository"
Cohesion: 0.09
Nodes (8): FenceStatus, kotlinx, SharedFlow, StateFlow, MavlinkTelemetryRepository, ParamValue, RcChannels, ServoOutputRaw

### Community 5 - "FieldType"
Cohesion: 0.05
Nodes (43): AuthResult, AUTHENTICATED, DENIED, FAILED, LEGACY_FIRMWARE, ChallengeCompleteException, KFTAuth, object@L92 (+35 more)

### Community 6 - "FullParamListViewModel"
Cohesion: 0.08
Nodes (32): ArduPilotParamMetadataRepository, TypeToken, TypeToken, Context, TypeToken, BreakingSettingsScreen(), ConfirmChangesDialog(), fmtBrake() (+24 more)

### Community 7 - "ProximityOverlay.kt"
Cohesion: 0.06
Nodes (55): Intent, MainActivity, Color, ObstacleWindow, obstacleWindowOf(), proximityColor(), ProximityData, RadarSwitchState (+47 more)

### Community 8 - "Calibration Screen UI"
Cohesion: 0.06
Nodes (36): CalibrationActions(), CalibrationContent(), CalibrationHeader(), CalibrationProgress(), CalibrationScreen(), CancelledContent(), DroneOrientationIcon(), FailedContent() (+28 more)

### Community 9 - "SpraySettingsViewModel"
Cohesion: 0.15
Nodes (17): ConfirmSprayDialog(), EnumDropdown(), fmtSpray(), InfoCenterSpray(), androidx, NavController, SectionCard(), SprayFieldView() (+9 more)

### Community 10 - "Notification"
Cohesion: 0.09
Nodes (3): Notification, NotificationItem(), NotificationPanel()

### Community 11 - "VideoStreamPlayer.kt"
Cohesion: 0.08
Nodes (29): BroadcastReceiver, Context, Flow, Intent, UsbDevice, UsbManager, UsbSerialPermission, BroadcastReceiver (+21 more)

### Community 12 - "ParamAuthTokenStore"
Cohesion: 0.12
Nodes (14): Error, OkHttpClient, T, ParamAuthApiService, ParamAuthErrorResponse, ParamAuthResult, ParamLoginRequest, ParamLoginResponse (+6 more)

### Community 13 - "Screen"
Cohesion: 0.04
Nodes (48): AboutApp, AccelerometerCalibration, Aircraft, AnalyzeLog, BarometerCalibration, BatteryMonitorSettings, Calibrations, CompassCalibration (+40 more)

### Community 14 - ".imagePointToGps"
Cohesion: 0.36
Nodes (4): CameraInfo, GeoReferencer, FloatArray, LatLng

### Community 16 - "Compass Calibration Screen UI"
Cohesion: 0.09
Nodes (27): CancelledContent(), CompassCalibrationActions(), CompassCalibrationContent(), CompassCalibrationHeader(), CompassCalibrationProgress(), CompassCalibrationScreen(), CompassReportCard(), FailedContent() (+19 more)

### Community 17 - "UserSettingsManager"
Cohesion: 0.19
Nodes (8): FontSizeOption, LARGE, MEDIUM, SMALL, Color, Context, SharedPreferences, UserSettingsManager

### Community 18 - "SavedMissionStateDao"
Cohesion: 0.16
Nodes (7): LatLng, TypeToken, MissionStateTypeConverters, TypeToken, SavedMissionStateDao, SavedMissionStateEntity, Gson

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
Cohesion: 0.15
Nodes (8): MissionParameters, MissionStatistics, RTLMonitoringState, Job, LatLng, MissionItemInt, StateFlow, ObstacleDetectionManager

### Community 25 - "UdpDiagnostics"
Cohesion: 0.13
Nodes (7): CoroutinesMavConnection, UdpConnectionProvider, UdpDiagnostics, UdpPortScanner, UdpScanResult, Inet4Address, InetAddress

### Community 26 - "Data.kt"
Cohesion: 0.15
Nodes (12): DroneIdentifier, EscReading, extractDroneUniqueId(), extractUuidString(), UByte, SprayTelemetry, escReadings(), MavResult (+4 more)

### Community 27 - "EventType"
Cohesion: 0.08
Nodes (23): EventSeverity, CRITICAL, ERROR, INFO, WARNING, EventType, ARM_DISARM, CONNECTION_LOSS (+15 more)

### Community 31 - "PlanScreen"
Cohesion: 0.10
Nodes (15): GridSourceSelectionDialog(), KmlPolygonSelectionDialog(), MissionChoiceDialog(), MissionTypeSelectionDialog(), SaveMissionDialog(), NavHostController, PlanScreen(), KmlBoundaryParser (+7 more)

### Community 32 - "AppNavGraph.kt"
Cohesion: 0.16
Nodes (16): NavController, SectionBody(), SectionTitle(), TermsAndConditionsScreen(), NavController, WelcomeScreen(), AppNavGraph(), NavHostController (+8 more)

### Community 33 - "OptionsViewModel"
Cohesion: 0.06
Nodes (26): BufferedMavConnection, ByteArray, MavConnection, MavFrame, MavMessage, T, UByte, UInt (+18 more)

### Community 35 - "ReplayFrame"
Cohesion: 0.17
Nodes (8): ReplayFrame, FlightMode, ReplayTimelineBuilder, rememberReplayController(), ReplayController, Modifier, relativeTimestamp(), ReplayScrubber()

### Community 36 - "WebSocketManager"
Cohesion: 0.14
Nodes (4): Runnable, OkHttpClient, WebSocketManager, WebSocket

### Community 37 - "ObstacleData.kt"
Cohesion: 0.17
Nodes (11): ObstacleDetectionConfig, SensorType, LIDAR, PROXIMITY, SIMULATED, ULTRASONIC, ThreatLevel, HIGH (+3 more)

### Community 38 - "ObstacleSensorManager"
Cohesion: 0.16
Nodes (8): SensorReading, CalibrationResult, StateFlow, ObstacleSensorManager, Sensor, SensorEvent, SensorEventListener, SensorManager

### Community 39 - "LogUtils"
Cohesion: 0.07
Nodes (24): BrakeWriteResult, BrakingSettingsState, BrakingSettingsViewModel, Job, StateFlow, ViewModel, requestServoParameters(), Context (+16 more)

### Community 40 - "SessionManager"
Cohesion: 0.24
Nodes (3): Context, SharedPreferences, SessionManager

### Community 41 - "DiagnosticFlag"
Cohesion: 0.17
Nodes (17): AnalysisResults(), CenteredStatus(), DiagnosticCard(), formatTime(), Color, ImageVector, NavHostController, LogAnalysisScreen() (+9 more)

### Community 42 - "AnalyzeLogUiState"
Cohesion: 0.14
Nodes (17): AnalyzeLogUiState, AnalyzeLogViewModel, BrowsingSd, Copying, Downloaded, Downloading, DownloadingSd, Error (+9 more)

### Community 43 - "CameraProtocolManager"
Cohesion: 0.14
Nodes (10): CameraProtocolManager, StateFlow, UByte, CameraCapabilities, CameraFovStatus, TrackingImageStatus, CameraInformation, CameraTrackingImageStatus (+2 more)

### Community 44 - "AppStrings"
Cohesion: 0.15
Nodes (10): Modifier, NavController, LoginPage(), Dp, NavController, SelectFlyingMethodScreen(), StyledFlyingMethodCard(), NavHostController (+2 more)

### Community 46 - "MotorTestViewModel"
Cohesion: 0.13
Nodes (7): FrameClassMap, FrameInfo, StateFlow, UInt, ViewModel, MotorTestState, MotorTestViewModel

### Community 47 - "BatteryMonitorViewModel"
Cohesion: 0.18
Nodes (10): BatteryMonitorState, BatteryMonitorViewModel, BattMonitorOption, findHwVerPresetFor(), findSensorPresetFor(), HwVerPreset, StateFlow, ViewModel (+2 more)

### Community 49 - "FenceAction"
Cohesion: 0.22
Nodes (7): FenceAction, ALWAYS_LAND, BRAKE, REPORT_ONLY, RTL, SMART_RTL, SMART_RTL_LAND

### Community 50 - "Spray Telemetry Utils"
Cohesion: 0.11
Nodes (5): CalibrationPoint, FlowRateFilter, FlowRateValidator, TankLevelCalculator, VoltageFilter

### Community 51 - "AboutDroneViewModel"
Cohesion: 0.18
Nodes (11): AboutDroneScreen(), DroneInfoCard(), FrameSelectRow(), InfoDivider(), InfoRow(), NavController, SectionHeader(), AboutDroneViewModel (+3 more)

### Community 52 - "MavlinkFtpClient"
Cohesion: 0.30
Nodes (6): FtpEntry, ByteArray, UByte, MavlinkFtpClient, Resp, FileTransferProtocol

### Community 54 - "ParamManagementViewModel"
Cohesion: 0.15
Nodes (14): Modifier, NavController, ParamLoginPage(), NavController, ParamManagementHomeScreen(), ParamNavItem, Error, Idle (+6 more)

### Community 55 - "Flight Dao"
Cohesion: 0.19
Nodes (3): FlightDao, Flow, FlightEntity

### Community 57 - "TelemetryState"
Cohesion: 0.27
Nodes (5): TelemetryState, CameraTrackingState, LatLng, StateFlow, TrackingManager

### Community 58 - "Log Replay Screen"
Cohesion: 0.25
Nodes (14): Context, shareFile(), shareLogFile(), shareRecording(), CrashBanner(), DisplayPrefsMenu(), formatT(), NavHostController (+6 more)

### Community 59 - "Context"
Cohesion: 0.09
Nodes (7): BatteryFsAction, BroadcastReceiver, Context, Intent, UInt, UsbDevice, BroadcastReceiver

### Community 60 - "OfflineMessageDao"
Cohesion: 0.13
Nodes (5): Flow, OfflineMessageDao, OfflineMessageEntity, Flow, CertificatePinner

### Community 61 - "ObstacleDatabase"
Cohesion: 0.47
Nodes (3): Context, RoomDatabase, ObstacleDatabase

### Community 62 - "BluetoothMavConnection"
Cohesion: 0.24
Nodes (10): BluetoothMavConnection, BufferedMavConnection, ByteArray, MavConnection, MavFrame, MavMessage, T, UByte (+2 more)

### Community 63 - "AuthViewModel"
Cohesion: 0.05
Nodes (45): AdminInfo, AdminListResponse, ApiResponse, ApiService, Error, ErrorResponse, OkHttpClient, T (+37 more)

### Community 64 - "TelemetryRepository.kt"
Cohesion: 0.11
Nodes (17): AppScope, CoroutineScope, AltitudeLimits, ArmMagicValues, MavFrame, MavMessage, UInt, MavCmdId (+9 more)

### Community 65 - "ConnectionPage.kt"
Cohesion: 0.14
Nodes (17): ConnectionType, BLUETOOTH, TCP, UDP, USB, PairedDevice, UsbDeviceInfo, BluetoothConnectionContent() (+9 more)

### Community 66 - "MissionItemInt"
Cohesion: 0.13
Nodes (7): Circle, FenceZone, Polygon, ReturnPoint, MissionItemInt, currentItem, totalItems

### Community 68 - "Backend Websocket Spec"
Cohesion: 0.16
Nodes (15): consumers.py (backend), Atomic Telemetry Writes + Safe Key Access (Issue F, MEDIUM), client_id Dedup (Issue E, MEDIUM), drone_uid_update Handler (Issue C, HIGH), Protocol Alignment: session_ack / STATUS_STARTED (Issue H), resume_mission_id field, session_start message handler, Authenticate the Socket (Issue A, CRITICAL) (+7 more)

### Community 69 - "KeyboardType"
Cohesion: 0.27
Nodes (9): Modifier, NavController, OtpVerificationPage(), BatteryMonitorScreen(), CalculatedCard(), NavController, LabeledNumberField(), SaveButton() (+1 more)

### Community 70 - "CameraTrackingState.kt"
Cohesion: 0.13
Nodes (14): TrackingMode, NONE, POINT, RECTANGLE, TrackingStatus, ACTIVE, ERROR, IDLE (+6 more)

### Community 72 - "UnifiedFlightTracker.kt"
Cohesion: 0.18
Nodes (5): FlightManager, Job, FlightLoggingService, Job, StateFlow

### Community 73 - "AnalyzeLogScreen.kt"
Cohesion: 0.33
Nodes (10): LogEntryInfo, SdLogEntry, AccentButton(), AnalyzeLogScreen(), CenteredStatus(), formatBytes(), formatTimestamp(), NavHostController (+2 more)

### Community 74 - "ObstacleDetectionViewModel"
Cohesion: 0.27
Nodes (5): AndroidViewModel, LatLng, MissionItemInt, StateFlow, ObstacleDetectionViewModel

### Community 75 - "BarometerCalibrationViewModel"
Cohesion: 0.18
Nodes (9): BarometerCalibrationScreen(), ImageVector, NavController, StatusIndicatorCard(), BarometerCalibrationUiState, BarometerCalibrationViewModel, Job, StateFlow (+1 more)

### Community 76 - "SecurePinManager"
Cohesion: 0.40
Nodes (3): Context, SecurePinManager, SecretKey

### Community 77 - "ObstacleDetectionScreen.kt"
Cohesion: 0.27
Nodes (13): ObstacleInfo, ResumeOption, InactiveContent(), InfoRow(), MonitoringContent(), ObstacleDetectedContent(), ObstacleDetectionScreen(), ResumeOptionCard() (+5 more)

### Community 78 - "ReplayMap.kt"
Cohesion: 0.36
Nodes (12): GoogleMapReplay(), isOnline(), BitmapDescriptor, Context, LatLng, Modifier, rememberDroneIcon(), ReplayMap() (+4 more)

### Community 79 - "Flow Sensor Calibration Screen"
Cohesion: 0.23
Nodes (12): FlowCalibrationState, CALIBRATING, COMPLETED, ERROR, IDLE, FlowSensorCalibrationScreen(), InstructionStep(), Color (+4 more)

### Community 80 - "In App Update State"
Cohesion: 0.22
Nodes (7): Available, Downloaded, InAppUpdateState, None, rememberInAppUpdateState(), UpdateUiState, AppUpdateInfo

### Community 81 - "CommandLong"
Cohesion: 0.17
Nodes (5): GimbalController, StateFlow, UByte, CommandLong, GimbalDeviceAttitudeStatus

### Community 82 - "EventEntity"
Cohesion: 0.27
Nodes (3): EventEntity, EventDao, Flow

### Community 83 - "Flight Log Exporter"
Cohesion: 0.32
Nodes (3): FlightExportData, FlightLogExporter, TlogEntry

### Community 85 - "TlogIntegration"
Cohesion: 0.43
Nodes (3): Application, TlogIntegration, ViewModelStoreOwner

### Community 87 - "DataFlashParser"
Cohesion: 0.31
Nodes (3): DataFlashParser, ByteArray, ParseSummary

### Community 88 - "Log Analysis View Model"
Cohesion: 0.31
Nodes (8): AnalysisComplete, Error, AndroidViewModel, StateFlow, Loading, LogAnalysisUiState, LogAnalysisViewModel, Parsing

### Community 89 - "GcsMap"
Cohesion: 0.07
Nodes (35): GridGenerator, LatLng, GridMissionConverter, LatLng, MissionItemInt, UByte, GridSurveyParams, GridSurveyResult (+27 more)

### Community 90 - "FlightModeViewModel"
Cohesion: 0.16
Nodes (12): FlightModeDropdown(), FlightModeScreen(), FlightModeSlotCard(), InfoBanner(), Color, NavController, LoadingRow(), FlightModeOption (+4 more)

### Community 92 - "ObstacleDetectionStatus"
Cohesion: 0.29
Nodes (7): ObstacleDetectionStatus, INACTIVE, MONITORING, OBSTACLE_DETECTED, READY_TO_RESUME, RESUMING, RTL_IN_PROGRESS

### Community 95 - "LogsScreen.kt"
Cohesion: 0.27
Nodes (10): ExportFormat, CSV, JSON, TLOG, ActiveFlightCard(), FlightItem(), formatDuration(), Modifier (+2 more)

### Community 96 - "FlightState"
Cohesion: 0.33
Nodes (6): FlightState, ACTIVE, FINALIZING, IDLE, STARTING, STOPPING

### Community 97 - ".startFlight"
Cohesion: 0.28
Nodes (3): MissionMode, AUTO, MANUAL

### Community 98 - "SignupPage.kt"
Cohesion: 0.70
Nodes (4): Modifier, NavController, MobileNumberField(), SignupPage()

### Community 99 - "Welcome"
Cohesion: 0.36
Nodes (9): KFT Play Store App Icon, KFT Wordmark Logo Mark, Kapil Group Logo, KFT Kapil Future Tech Logo, Agricultural Spraying Drone Background Photo, Version Label v1.01, Welcome Screen Splash Image, Kapil Future Tech (KFT) Brand Identity (+1 more)

### Community 100 - "CalibrationsScreen"
Cohesion: 0.70
Nodes (4): CalibrationOptionCard(), CalibrationsScreen(), ImageVector, NavController

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

### Community 106 - "VideoTrackingOverlay"
Cohesion: 0.80
Nodes (5): CameraInfoBadge(), GimbalInfoBadge(), Modifier, TrackingStatusBadge(), VideoTrackingOverlay()

### Community 107 - "Sensor Settings Screen"
Cohesion: 0.39
Nodes (7): androidx, NavHostController, roundToStep(), SensorSettingsScreen(), StepButton(), ThresholdControl(), ClosedFloatingPointRange

### Community 109 - "DisconnectionRTLHandler"
Cohesion: 0.33
Nodes (3): DisconnectionRTLHandler, CoroutineScope, StateFlow

### Community 110 - "SharedViewModel.kt"
Cohesion: 0.09
Nodes (16): BluetoothConnectionProvider, CoroutinesMavConnection, CoroutinesMavConnection, MavConnectionProvider, CoroutinesMavConnection, TcpConnectionProvider, CoroutinesMavConnection, UsbSerialConnectionProvider (+8 more)

### Community 112 - "Shared View Model"
Cohesion: 0.29
Nodes (6): GridSetupSource, DRONE_POSITION, KML_IMPORT, MAP_DRAW, NONE, RC_CONTROL

### Community 114 - "Telemetry Overlay"
Cohesion: 0.57
Nodes (6): Cell(), EscRow(), fmt(), fmtPlain(), Modifier, TelemetryOverlay()

### Community 115 - "SettingsScreen"
Cohesion: 0.48
Nodes (6): androidx, ImageVector, NavHostController, NumberedButton(), SettingsEntry, SettingsScreen()

### Community 117 - "MissionTemplateDatabase"
Cohesion: 0.32
Nodes (3): Context, RoomDatabase, MissionTemplateDatabase

### Community 118 - "MissionStatus"
Cohesion: 0.25
Nodes (8): MissionStatus, CANCELLED, COMPLETED, FAILED, IN_PROGRESS, INTERRUPTED, NOT_STARTED, PARTIAL_COMPLETE

### Community 119 - "Rc Stick View"
Cohesion: 0.67
Nodes (5): drawGimbal(), Modifier, Offset, norm(), RcStickView()

### Community 120 - "VideoStreamSettings"
Cohesion: 0.54
Nodes (7): VideoStreamInfo, DetectedStreamCard(), Modifier, UsbDevice, PresetChip(), UsbDeviceCard(), VideoStreamSettings()

### Community 124 - "Shared View Model"
Cohesion: 0.40
Nodes (5): ClearMissionState, CLEARING, FAILED, IDLE, SUCCESS

### Community 125 - "DroneCameraFeedOverlay"
Cohesion: 0.36
Nodes (9): CameraPlaceholder(), DroneCameraFeedOverlay(), Modifier, VideoStreamView(), GimbalState, GimbalButton(), GimbalControlOverlay(), ImageVector (+1 more)

### Community 126 - "Shared View Model"
Cohesion: 0.40
Nodes (4): MissionType, GRID, NONE, WAYPOINT

### Community 127 - "MissionTemplateEntity"
Cohesion: 0.06
Nodes (31): Flow, MissionTemplateDao, GridParameters, MissionTemplateEntity, LatLng, MissionItemInt, TypeToken, MissionTemplateTypeConverters (+23 more)

### Community 129 - "Gradlew"
Cohesion: 0.70
Nodes (4): gradlew script, die(), save(), warn()

### Community 130 - "PreflightFailsafeDialog"
Cohesion: 0.70
Nodes (4): formatVolts(), PreflightFailsafeDialog(), SummaryRow(), PreflightFailsafeSummary

### Community 131 - "LevelSensorCalibrationScreen"
Cohesion: 0.70
Nodes (4): InstructionStep(), NavHostController, LevelSensorCalibrationScreen(), ReadingRow()

### Community 134 - "Shared View Model"
Cohesion: 0.67
Nodes (3): UserFlightMode, AUTOMATIC, MANUAL

### Community 137 - "ArtificialHorizon.kt"
Cohesion: 0.83
Nodes (3): ArtificialHorizon(), drawHorizon(), Modifier

### Community 157 - "MotorTestScreen.kt"
Cohesion: 0.52
Nodes (6): Modifier, NavController, MotorTestScreen(), MtCard(), MtInputDialog(), MtNumberField()

## Knowledge Gaps
- **268 isolated node(s):** `ErrorResponse`, `Error`, `AdminInfo`, `VehicleInfo`, `AUTHENTICATED` (+263 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 583 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **30 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `SharedViewModel` connect `SharedViewModel` to `HobbywingEsc.kt`, `PreflightFailsafeDialog`, `LevelSensorCalibrationScreen`, `MavlinkTelemetryRepository`, `FullParamListViewModel`, `ProximityOverlay.kt`, `Calibration Screen UI`, `SpraySettingsViewModel`, `Notification`, `Shared View Model`, `Compass Calibration Screen UI`, `RC Calibration Screen UI`, `Text-to-Speech Announcements`, `ServoOutputViewModel`, `Level Calibration Screen UI`, `ObstacleDetectionManager`, `.connect`, `PlanScreen`, `AppNavGraph.kt`, `OptionsViewModel`, `LogUtils`, `SessionManager`, `AppStrings`, `LatLng`, `MotorTestViewModel`, `BatteryMonitorViewModel`, `FenceAction`, `AboutDroneViewModel`, `TelemetryState`, `Context`, `AuthViewModel`, `ConnectionPage.kt`, `KeyboardType`, `.uploadGeofence`, `UnifiedFlightTracker.kt`, `AnalyzeLogScreen.kt`, `ObstacleDetectionViewModel`, `BarometerCalibrationViewModel`, `Flow Sensor Calibration Screen`, `TlogIntegration`, `GcsMap`, `FlightModeViewModel`, `LogsScreen.kt`, `CalibrationsScreen`, `Spray Calibration Screen`, `.sendCommandAck`, `ArmingCheckState`, `Sensor Settings Screen`, `SharedViewModel.kt`, `Shared View Model`, `SettingsScreen`, `.clearGeofenceFromFC`, `.handleAltitudeFailsafe`, `Shared View Model`, `Shared View Model`?**
  _High betweenness centrality (0.411) - this node is a cross-community bridge._
- **Why does `TelemetryState` connect `TelemetryState` to `TelemetryRepository.kt`, `.startFlight`, `SharedViewModel`, `MavlinkTelemetryRepository`, `.handleAltitudeFailsafe`, `ProximityOverlay.kt`, `UnifiedFlightTracker.kt`, `Notification`, `DisconnectionRTLHandler`, `SharedViewModel.kt`, `.imagePointToGps`, `UnifiedFlightTracker`, `.clearGeofenceFromFC`, `ObstacleDetectionManager`, `GcsMap`, `Data.kt`, `DroneCameraFeedOverlay`, `AuthViewModel`?**
  _High betweenness centrality (0.128) - this node is a cross-community bridge._
- **Why does `MavlinkTelemetryRepository` connect `MavlinkTelemetryRepository` to `TelemetryRepository.kt`, `HobbywingEsc.kt`, `SharedViewModel`, `MissionItemInt`, `FieldType`, `.uploadGeofence`, `AnalyzeLogScreen.kt`, `AnalyzeLogUiState`, `CameraProtocolManager`, `DisconnectionRTLHandler`, `CommandLong`, `Spray Telemetry Utils`, `.sendCommand`, `TelemetryState`, `Data.kt`, `.newFtpClient`, `.connect`?**
  _High betweenness centrality (0.112) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `MavlinkTelemetryRepository` (e.g. with `FlowRateFilter` and `VoltageFilter`) actually correct?**
  _`MavlinkTelemetryRepository` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 8 inferred relationships involving `Notification` (e.g. with `.arm()` and `.clampRtlAltBelowFenceCeiling()`) actually correct?**
  _`Notification` has 8 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ErrorResponse`, `Error`, `AdminInfo` to the rest of the system?**
  _268 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `SharedViewModel` be split into smaller, more focused modules?**
  _Cohesion score 0.028403113822848727 - nodes in this community are weakly interconnected._