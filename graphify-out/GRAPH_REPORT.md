# Graph Report - kftgcs  (2026-10-03)

## Corpus Check
- 197 files · ~901,675 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 55 file(s) not represented in the graph (top: .xml 20, .log 19, .aab 4)

## Summary
- 2410 nodes · 4958 edges · 128 communities (96 shown, 24 thin omitted)
- Extraction: 98% EXTRACTED · 2% INFERRED · 0% AMBIGUOUS · INFERRED: 114 edges (avg confidence: 0.84)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `97f9b54f`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- UsbSerialMavConnection
- HobbywingEsc.kt
- SharedViewModel
- BluetoothMavConnection
- MavlinkTelemetryRepository
- FieldType
- LogUtils
- ProximityOverlay.kt
- CalibrationViewModel
- SpraySettingsViewModel
- Notification
- T12SerialVideoSource
- ParamManagementViewModel
- Screen
- CameraFovStatus
- GCSApplication
- Compass Calibration Screen UI
- UserSettingsManager
- FenceAction
- RC Calibration Screen UI
- TextToSpeechManager
- ServoOutputViewModel
- Level Calibration Screen UI
- TlogViewModel
- OptionsScreen.kt
- UdpDiagnostics
- Data.kt
- EventType
- .connect
- LimitFailsafePolicy
- CrashAnalyzer
- ObstaclePathPlanner
- AppNavGraph.kt
- OptionsViewModel
- GridUtils
- ReplayFrame
- WebSocketManager
- .sendSignedV2
- BarometerCalibrationScreen.kt
- ScreenRecordService
- SessionManager
- DiagnosticFlag
- AnalyzeLogUiState
- CameraProtocolManager
- AppStrings
- LatLng
- MotorTestViewModel
- BatteryMonitorViewModel
- TelemetryState
- .sendCommandAck
- VoltageFilter
- AboutDroneViewModel
- MavlinkFtpClient
- GeofenceUtils
- ParamLoginPage
- FlightEntity
- .sendCommand
- Log Replay Screen
- Context
- VideoStreamType
- TermsAndConditionsScreen
- AuthViewModel
- TelemetryRepository.kt
- ConnectionPage.kt
- MissionItemInt
- Backend Websocket Spec
- KeyboardType
- CameraTrackingState.kt
- .uploadGeofence
- AnalyzeLogScreen.kt
- SelectFlyingMethodScreen.kt
- BarometerCalibrationViewModel
- ParamManagementHomeScreen
- ReplayMap.kt
- Flow Sensor Calibration Screen
- In App Update State
- GimbalController
- EventEntity
- Flight Log Exporter
- TlogRepository
- DataFlashParser
- Log Analysis View Model
- PlanScreen
- MapDataEntity
- LogsScreen.kt
- Welcome
- CalibrationsScreen
- Spray Calibration Screen
- fake_mavlink_udp.py
- Forgot Password Page
- ArmingCheckState
- DroneCameraFeedOverlay
- Sensor Settings Screen
- SharedViewModel.kt
- Shared View Model
- Telemetry Overlay
- SettingsScreen
- Rc Stick View
- VideoStreamSettings
- .speak
- Shared View Model
- GimbalState
- Shared View Model
- MissionTemplateEntity
- Gradlew
- PreflightFailsafeDialog
- Example Instrumented Test
- Shared View Model
- Example Unit Test
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
1. `SharedViewModel` - 310 edges
2. `MavlinkTelemetryRepository` - 107 edges
3. `AppNavGraph()` - 66 edges
4. `Screen` - 64 edges
5. `Notification` - 53 edges
6. `TelemetryState` - 42 edges
7. `UnifiedFlightTracker` - 32 edges
8. `TextToSpeechManager` - 32 edges
9. `AuthViewModel` - 31 edges
10. `WebSocketManager` - 31 edges

## Surprising Connections (you probably didn't know these)
- `Authenticate the Socket (Issue A, CRITICAL)` --semantically_similar_to--> `Secret Scanning Job`  [INFERRED] [semantically similar]
  docs/backend_websocket_spec.md → .github/workflows/security.yml
- `GcsMap()` --calls--> `Polygon`  [INFERRED]
  app/src/main/java/com/example/kftgcs/uimain/GcsMap.kt → app/src/main/java/com/example/kftgcs/fence/FenceTypes.kt
- `GcsMap()` --calls--> `Circle`  [INFERRED]
  app/src/main/java/com/example/kftgcs/uimain/GcsMap.kt → app/src/main/java/com/example/kftgcs/fence/FenceTypes.kt
- `LogAnalysisScreen()` --calls--> `shareLogFile()`  [INFERRED]
  app/src/main/java/com/example/kftgcs/loganalysis/LogAnalysisScreen.kt → app/src/main/java/com/example/kftgcs/loganalysis/LogSharing.kt
- `MavlinkTelemetryRepository` --calls--> `FlowRateFilter`  [INFERRED]
  app/src/main/java/com/example/kftgcs/telemetry/TelemetryRepository.kt → app/src/main/java/com/example/kftgcs/telemetry/SprayTelemetryUtils.kt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **CI Security Scan Pipeline** — github_workflows_security_dependencycheck, github_workflows_security_secretscanning, github_workflows_security_codeqlanalysis, github_workflows_security_androidlint [EXTRACTED 1.00]
- **Critical/High Priority Backend Data-Integrity Fixes (A, B, C)** — docs_backend_websocket_spec_socketauthentication, docs_backend_websocket_spec_vehiclededuplication, docs_backend_websocket_spec_droneuidupdate [EXTRACTED 1.00]

## Communities (128 total, 24 thin omitted)

### Community 0 - "UsbSerialMavConnection"
Cohesion: 0.22
Nodes (11): BufferedMavConnection, ByteArray, MavConnection, MavFrame, MavMessage, UsbSerialPort, UsbSerialInputStream, UsbSerialMavConnection (+3 more)

### Community 1 - "HobbywingEsc.kt"
Cohesion: 0.21
Nodes (9): HwCanFrame, HwEscReply, hwGetEscIdFrame(), hwParseReply(), hwServiceFrame(), UByte, UInt, withTail() (+1 more)

### Community 2 - "SharedViewModel"
Cohesion: 0.03
Nodes (6): kotlinx, SharedFlow, StateFlow, MissionCompletionData, SharedViewModel, com

### Community 3 - "BluetoothMavConnection"
Cohesion: 0.24
Nodes (10): BluetoothMavConnection, BufferedMavConnection, ByteArray, MavConnection, MavFrame, MavMessage, T, UByte (+2 more)

### Community 4 - "MavlinkTelemetryRepository"
Cohesion: 0.08
Nodes (7): kotlinx, SharedFlow, StateFlow, MavlinkTelemetryRepository, ParamValue, RcChannels, ServoOutputRaw

### Community 5 - "FieldType"
Cohesion: 0.05
Nodes (41): AuthResult, AUTHENTICATED, DENIED, FAILED, LEGACY_FIRMWARE, KFTAuth, object@L92, ByteArray (+33 more)

### Community 6 - "LogUtils"
Cohesion: 0.05
Nodes (40): ArduPilotParamMetadataRepository, TypeToken, TypeToken, Context, TypeToken, BrakeWriteResult, BrakingSettingsState, BrakingSettingsViewModel (+32 more)

### Community 7 - "ProximityOverlay.kt"
Cohesion: 0.06
Nodes (51): FlightModeDropdown(), FlightModeScreen(), FlightModeSlotCard(), InfoBanner(), Color, NavController, LoadingRow(), FlightModeOption (+43 more)

### Community 8 - "CalibrationViewModel"
Cohesion: 0.06
Nodes (36): CalibrationActions(), CalibrationContent(), CalibrationHeader(), CalibrationProgress(), CalibrationScreen(), CancelledContent(), DroneOrientationIcon(), FailedContent() (+28 more)

### Community 9 - "SpraySettingsViewModel"
Cohesion: 0.15
Nodes (17): ConfirmSprayDialog(), EnumDropdown(), fmtSpray(), InfoCenterSpray(), androidx, NavController, SectionCard(), SprayFieldView() (+9 more)

### Community 10 - "Notification"
Cohesion: 0.11
Nodes (4): Notification, ResumePrepResult, NotificationItem(), NotificationPanel()

### Community 11 - "T12SerialVideoSource"
Cohesion: 0.05
Nodes (38): UsbDevice, UsbPortOwnership, describe(), ByteArray, Job, T, UsbSerialPort, T12SerialVideoSource (+30 more)

### Community 12 - "ParamManagementViewModel"
Cohesion: 0.09
Nodes (23): Error, OkHttpClient, T, ParamAuthApiService, ParamAuthErrorResponse, ParamAuthResult, ParamLoginRequest, ParamLoginResponse (+15 more)

### Community 13 - "Screen"
Cohesion: 0.04
Nodes (48): AboutApp, AccelerometerCalibration, Aircraft, AnalyzeLog, BarometerCalibration, BatteryMonitorSettings, Calibrations, CompassCalibration (+40 more)

### Community 14 - "CameraFovStatus"
Cohesion: 0.26
Nodes (4): CameraFovStatus, GeoReferencer, FloatArray, LatLng

### Community 15 - "GCSApplication"
Cohesion: 0.09
Nodes (12): GCSApplication, Application, CrashLogger, Context, Context, SecurePinManager, SyncWorker, Context (+4 more)

### Community 16 - "Compass Calibration Screen UI"
Cohesion: 0.09
Nodes (27): CancelledContent(), CompassCalibrationActions(), CompassCalibrationContent(), CompassCalibrationHeader(), CompassCalibrationProgress(), CompassCalibrationScreen(), CompassReportCard(), FailedContent() (+19 more)

### Community 17 - "UserSettingsManager"
Cohesion: 0.10
Nodes (20): FontSizeOption, LARGE, MEDIUM, SMALL, Color, Context, SharedPreferences, UserSettingsManager (+12 more)

### Community 18 - "FenceAction"
Cohesion: 0.18
Nodes (8): FenceAction, ALWAYS_LAND, BRAKE, REPORT_ONLY, RTL, SMART_RTL, SMART_RTL_LAND, UInt

### Community 19 - "RC Calibration Screen UI"
Cohesion: 0.09
Nodes (24): AllChannelsCard(), CalibrationButton(), ConnectionStatusCard(), NavController, MainControlsCard(), RCCalibrationHeader(), RCCalibrationScreen(), RCChannelBar() (+16 more)

### Community 20 - "TextToSpeechManager"
Cohesion: 0.16
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

### Community 24 - "OptionsScreen.kt"
Cohesion: 0.46
Nodes (7): ActionDropdown(), ActionRadioGroup(), NavHostController, NumericTextField(), OptionsScreen(), SectionCard(), SubLabel()

### Community 25 - "UdpDiagnostics"
Cohesion: 0.13
Nodes (7): CoroutinesMavConnection, UdpConnectionProvider, UdpDiagnostics, UdpPortScanner, UdpScanResult, Inet4Address, InetAddress

### Community 26 - "Data.kt"
Cohesion: 0.16
Nodes (12): DroneIdentifier, EscReading, extractDroneUniqueId(), extractUuidString(), UByte, SprayTelemetry, escReadings(), MavResult (+4 more)

### Community 27 - "EventType"
Cohesion: 0.08
Nodes (23): EventSeverity, CRITICAL, ERROR, INFO, WARNING, EventType, ARM_DISARM, CONNECTION_LOSS (+15 more)

### Community 31 - "ObstaclePathPlanner"
Cohesion: 0.27
Nodes (3): LatLng, ObstaclePathPlanner, ObstacleZone

### Community 32 - "AppNavGraph.kt"
Cohesion: 0.18
Nodes (14): NavController, WelcomeScreen(), AppNavGraph(), NavHostController, PlaceholderScreen(), NavController, LanguageSelectionPage(), AboutAppScreen() (+6 more)

### Community 33 - "OptionsViewModel"
Cohesion: 0.14
Nodes (5): FailsafeOptions, AndroidViewModel, Context, StateFlow, OptionsViewModel

### Community 35 - "ReplayFrame"
Cohesion: 0.14
Nodes (11): ReplayFrame, FlightMode, ReplayTimelineBuilder, ArtificialHorizon(), drawHorizon(), Modifier, rememberReplayController(), ReplayController (+3 more)

### Community 36 - "WebSocketManager"
Cohesion: 0.05
Nodes (17): Context, MissionTemplateDatabase, Flow, OfflineMessageDao, OfflineMessageEntity, Intent, MainActivity, Runnable (+9 more)

### Community 37 - ".sendSignedV2"
Cohesion: 0.67
Nodes (3): T, UByte, UInt

### Community 38 - "BarometerCalibrationScreen.kt"
Cohesion: 0.70
Nodes (4): BarometerCalibrationScreen(), ImageVector, NavController, StatusIndicatorCard()

### Community 39 - "ScreenRecordService"
Cohesion: 0.15
Nodes (13): NotificationType, ERROR, INFO, SUCCESS, WARNING, Context, Intent, ScreenRecordService (+5 more)

### Community 40 - "SessionManager"
Cohesion: 0.26
Nodes (3): Context, SharedPreferences, SessionManager

### Community 41 - "DiagnosticFlag"
Cohesion: 0.17
Nodes (17): AnalysisResults(), CenteredStatus(), DiagnosticCard(), formatTime(), Color, ImageVector, NavHostController, LogAnalysisScreen() (+9 more)

### Community 42 - "AnalyzeLogUiState"
Cohesion: 0.14
Nodes (17): AnalyzeLogUiState, AnalyzeLogViewModel, BrowsingSd, Copying, Downloaded, Downloading, DownloadingSd, Error (+9 more)

### Community 43 - "CameraProtocolManager"
Cohesion: 0.16
Nodes (7): CameraProtocolManager, StateFlow, TrackingImageStatus, CameraTrackingImageStatus, CommandLong, MavCameraFovStatus, VideoStreamInformation

### Community 44 - "AppStrings"
Cohesion: 0.18
Nodes (8): Modifier, NavController, LoginPage(), Modifier, NavController, MobileNumberField(), SignupPage(), AppStrings

### Community 46 - "MotorTestViewModel"
Cohesion: 0.13
Nodes (7): FrameClassMap, FrameInfo, StateFlow, UInt, ViewModel, MotorTestState, MotorTestViewModel

### Community 47 - "BatteryMonitorViewModel"
Cohesion: 0.19
Nodes (8): BatteryMonitorState, BatteryMonitorViewModel, BattMonitorOption, HwVerPreset, StateFlow, ViewModel, sanitizeDecimalString(), SensorPreset

### Community 48 - "TelemetryState"
Cohesion: 0.05
Nodes (24): Application, TlogIntegration, FlightState, ACTIVE, FINALIZING, IDLE, STARTING, STOPPING (+16 more)

### Community 50 - "VoltageFilter"
Cohesion: 0.13
Nodes (4): CalibrationPoint, FlowRateFilter, FlowRateValidator, VoltageFilter

### Community 51 - "AboutDroneViewModel"
Cohesion: 0.18
Nodes (11): AboutDroneScreen(), DroneInfoCard(), FrameSelectRow(), InfoDivider(), InfoRow(), NavController, SectionHeader(), AboutDroneViewModel (+3 more)

### Community 52 - "MavlinkFtpClient"
Cohesion: 0.27
Nodes (6): FtpEntry, ByteArray, UByte, MavlinkFtpClient, Resp, FileTransferProtocol

### Community 54 - "ParamLoginPage"
Cohesion: 0.83
Nodes (3): Modifier, NavController, ParamLoginPage()

### Community 55 - "FlightEntity"
Cohesion: 0.20
Nodes (3): FlightDao, Flow, FlightEntity

### Community 58 - "Log Replay Screen"
Cohesion: 0.25
Nodes (14): Context, shareFile(), shareLogFile(), shareRecording(), CrashBanner(), DisplayPrefsMenu(), formatT(), NavHostController (+6 more)

### Community 61 - "VideoStreamType"
Cohesion: 0.33
Nodes (6): VideoStreamType, MPEG_TS, RTPUDP, RTSP, TCP_MPEG, UNKNOWN

### Community 62 - "TermsAndConditionsScreen"
Cohesion: 0.70
Nodes (4): NavController, SectionBody(), SectionTitle(), TermsAndConditionsScreen()

### Community 63 - "AuthViewModel"
Cohesion: 0.05
Nodes (44): AdminInfo, AdminListResponse, ApiResponse, ApiService, Error, ErrorResponse, OkHttpClient, T (+36 more)

### Community 64 - "TelemetryRepository.kt"
Cohesion: 0.11
Nodes (17): AppScope, CoroutineScope, AltitudeLimits, ArmMagicValues, MavFrame, MavMessage, UInt, MavCmdId (+9 more)

### Community 65 - "ConnectionPage.kt"
Cohesion: 0.14
Nodes (17): ConnectionType, BLUETOOTH, TCP, UDP, USB, PairedDevice, UsbDeviceInfo, BluetoothConnectionContent() (+9 more)

### Community 66 - "MissionItemInt"
Cohesion: 0.14
Nodes (7): Circle, FenceZone, Polygon, ReturnPoint, MissionItemInt, currentItem, totalItems

### Community 68 - "Backend Websocket Spec"
Cohesion: 0.16
Nodes (15): consumers.py (backend), Atomic Telemetry Writes + Safe Key Access (Issue F, MEDIUM), client_id Dedup (Issue E, MEDIUM), drone_uid_update Handler (Issue C, HIGH), Protocol Alignment: session_ack / STATUS_STARTED (Issue H), resume_mission_id field, session_start message handler, Authenticate the Socket (Issue A, CRITICAL) (+7 more)

### Community 69 - "KeyboardType"
Cohesion: 0.19
Nodes (13): Modifier, NavController, OtpVerificationPage(), BatteryMonitorScreen(), CalculatedCard(), NavController, LabeledNumberField(), SaveButton() (+5 more)

### Community 70 - "CameraTrackingState.kt"
Cohesion: 0.15
Nodes (12): UByte, CameraCapabilities, CameraInfo, TrackingMode, NONE, POINT, RECTANGLE, TrackingStatus (+4 more)

### Community 73 - "AnalyzeLogScreen.kt"
Cohesion: 0.33
Nodes (10): LogEntryInfo, SdLogEntry, AccentButton(), AnalyzeLogScreen(), CenteredStatus(), formatBytes(), formatTimestamp(), NavHostController (+2 more)

### Community 74 - "SelectFlyingMethodScreen.kt"
Cohesion: 0.70
Nodes (4): Dp, NavController, SelectFlyingMethodScreen(), StyledFlyingMethodCard()

### Community 75 - "BarometerCalibrationViewModel"
Cohesion: 0.29
Nodes (5): BarometerCalibrationUiState, BarometerCalibrationViewModel, Job, StateFlow, ViewModel

### Community 77 - "ParamManagementHomeScreen"
Cohesion: 0.83
Nodes (3): NavController, ParamManagementHomeScreen(), ParamNavItem

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
Cohesion: 0.25
Nodes (4): GimbalController, StateFlow, UByte, GimbalDeviceAttitudeStatus

### Community 82 - "EventEntity"
Cohesion: 0.21
Nodes (4): EventEntity, EventDao, Flow, TelemetryDao

### Community 83 - "Flight Log Exporter"
Cohesion: 0.32
Nodes (3): FlightExportData, FlightLogExporter, TlogEntry

### Community 84 - "TlogRepository"
Cohesion: 0.24
Nodes (3): TelemetryEntity, Flow, TlogRepository

### Community 87 - "DataFlashParser"
Cohesion: 0.31
Nodes (3): DataFlashParser, ByteArray, ParseSummary

### Community 88 - "Log Analysis View Model"
Cohesion: 0.31
Nodes (8): AnalysisComplete, Error, AndroidViewModel, StateFlow, Loading, LogAnalysisUiState, LogAnalysisViewModel, Parsing

### Community 89 - "PlanScreen"
Cohesion: 0.06
Nodes (46): GridGenerator, LatLng, GridMissionConverter, LatLng, MissionItemInt, UByte, GridSurveyParams, GridSurveyResult (+38 more)

### Community 95 - "LogsScreen.kt"
Cohesion: 0.27
Nodes (10): ExportFormat, CSV, JSON, TLOG, ActiveFlightCard(), FlightItem(), formatDuration(), Modifier (+2 more)

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

### Community 106 - "DroneCameraFeedOverlay"
Cohesion: 0.33
Nodes (10): CameraPlaceholder(), DroneCameraFeedOverlay(), Modifier, VideoStreamView(), CameraTrackingState, CameraInfoBadge(), GimbalInfoBadge(), Modifier (+2 more)

### Community 107 - "Sensor Settings Screen"
Cohesion: 0.39
Nodes (7): androidx, NavHostController, roundToStep(), SensorSettingsScreen(), StepButton(), ThresholdControl(), ClosedFloatingPointRange

### Community 110 - "SharedViewModel.kt"
Cohesion: 0.08
Nodes (20): BluetoothConnectionProvider, CoroutinesMavConnection, CoroutinesMavConnection, MavConnectionProvider, CoroutinesMavConnection, TcpConnectionProvider, CoroutinesMavConnection, UsbSerialConnectionProvider (+12 more)

### Community 112 - "Shared View Model"
Cohesion: 0.29
Nodes (6): GridSetupSource, DRONE_POSITION, KML_IMPORT, MAP_DRAW, NONE, RC_CONTROL

### Community 114 - "Telemetry Overlay"
Cohesion: 0.57
Nodes (6): Cell(), EscRow(), fmt(), fmtPlain(), Modifier, TelemetryOverlay()

### Community 115 - "SettingsScreen"
Cohesion: 0.48
Nodes (6): androidx, ImageVector, NavHostController, NumberedButton(), SettingsEntry, SettingsScreen()

### Community 119 - "Rc Stick View"
Cohesion: 0.67
Nodes (5): drawGimbal(), Modifier, Offset, norm(), RcStickView()

### Community 120 - "VideoStreamSettings"
Cohesion: 0.54
Nodes (7): VideoStreamInfo, DetectedStreamCard(), Modifier, UsbDevice, PresetChip(), UsbDeviceCard(), VideoStreamSettings()

### Community 124 - "Shared View Model"
Cohesion: 0.40
Nodes (5): ClearMissionState, CLEARING, FAILED, IDLE, SUCCESS

### Community 125 - "GimbalState"
Cohesion: 0.60
Nodes (5): GimbalState, GimbalButton(), GimbalControlOverlay(), ImageVector, Modifier

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

### Community 134 - "Shared View Model"
Cohesion: 0.67
Nodes (3): UserFlightMode, AUTOMATIC, MANUAL

### Community 157 - "MotorTestScreen.kt"
Cohesion: 0.52
Nodes (6): Modifier, NavController, MotorTestScreen(), MtCard(), MtInputDialog(), MtNumberField()

## Knowledge Gaps
- **250 isolated node(s):** `ErrorResponse`, `Error`, `AdminInfo`, `VehicleInfo`, `AUTHENTICATED` (+245 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 529 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **24 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `SharedViewModel` connect `SharedViewModel` to `HobbywingEsc.kt`, `PreflightFailsafeDialog`, `MavlinkTelemetryRepository`, `LogUtils`, `ProximityOverlay.kt`, `CalibrationViewModel`, `SpraySettingsViewModel`, `Notification`, `Shared View Model`, `Compass Calibration Screen UI`, `FenceAction`, `RC Calibration Screen UI`, `TextToSpeechManager`, `ServoOutputViewModel`, `Level Calibration Screen UI`, `OptionsScreen.kt`, `.connect`, `AppNavGraph.kt`, `OptionsViewModel`, `WebSocketManager`, `SessionManager`, `LatLng`, `MotorTestViewModel`, `BatteryMonitorViewModel`, `TelemetryState`, `.sendCommandAck`, `AboutDroneViewModel`, `.write`, `Context`, `AuthViewModel`, `ConnectionPage.kt`, `KeyboardType`, `.uploadGeofence`, `AnalyzeLogScreen.kt`, `SelectFlyingMethodScreen.kt`, `BarometerCalibrationViewModel`, `Flow Sensor Calibration Screen`, `PlanScreen`, `LogsScreen.kt`, `CalibrationsScreen`, `Spray Calibration Screen`, `ArmingCheckState`, `DroneCameraFeedOverlay`, `Sensor Settings Screen`, `SharedViewModel.kt`, `Shared View Model`, `SettingsScreen`, `.clearGeofenceFromFC`, `.speak`, `Shared View Model`, `Shared View Model`?**
  _High betweenness centrality (0.386) - this node is a cross-community bridge._
- **Why does `MavlinkTelemetryRepository` connect `MavlinkTelemetryRepository` to `TelemetryRepository.kt`, `HobbywingEsc.kt`, `SharedViewModel`, `MissionItemInt`, `FieldType`, `.uploadGeofence`, `AnalyzeLogScreen.kt`, `AnalyzeLogUiState`, `CameraProtocolManager`, `CameraFovStatus`, `TelemetryState`, `GimbalController`, `VoltageFilter`, `.sendCommand`, `Data.kt`, `.connect`?**
  _High betweenness centrality (0.147) - this node is a cross-community bridge._
- **Why does `LogUtils` connect `LogUtils` to `TelemetryRepository.kt`, `OptionsViewModel`, `ProximityOverlay.kt`, `ScreenRecordService`, `SpraySettingsViewModel`, `AnalyzeLogUiState`, `MotorTestViewModel`, `BatteryMonitorViewModel`, `TelemetryState`, `GCSApplication`, `SharedViewModel.kt`, `MavlinkFtpClient`, `DataFlashParser`, `Log Analysis View Model`, `PlanScreen`, `Log Replay Screen`, `MissionTemplateEntity`?**
  _High betweenness centrality (0.111) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `MavlinkTelemetryRepository` (e.g. with `FlowRateFilter` and `VoltageFilter`) actually correct?**
  _`MavlinkTelemetryRepository` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 7 inferred relationships involving `Notification` (e.g. with `.arm()` and `.resumeMission()`) actually correct?**
  _`Notification` has 7 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ErrorResponse`, `Error`, `AdminInfo` to the rest of the system?**
  _250 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `SharedViewModel` be split into smaller, more focused modules?**
  _Cohesion score 0.03239289446185998 - nodes in this community are weakly interconnected._