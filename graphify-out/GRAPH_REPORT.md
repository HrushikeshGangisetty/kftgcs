# Graph Report - kftgcs  (2026-10-05)

## Corpus Check
- 197 files · ~903,195 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 54 file(s) not represented in the graph (top: .xml 20, .log 19, .aab 4)

## Summary
- 2424 nodes · 5008 edges · 122 communities (94 shown, 21 thin omitted)
- Extraction: 98% EXTRACTED · 2% INFERRED · 0% AMBIGUOUS · INFERRED: 124 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `30838020`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- T12SerialVideoSource
- .sendHobbywingEscRequests
- SharedViewModel
- .authenticate
- MavlinkTelemetryRepository
- FieldType
- FullParamListViewModel
- ProximityOverlay.kt
- CalibrationViewModel
- SpraySettingsViewModel
- Notification
- VideoStreamPlayer.kt
- ParamManagementViewModel
- Screen
- .imagePointToGps
- GCSApplication
- Compass Calibration Screen UI
- UserSettingsManager
- LogUtils
- RC Calibration Screen UI
- TextToSpeechManager
- ServoOutputScreen.kt
- Level Calibration Screen UI
- TlogViewModel
- SessionManager
- BluetoothMavConnection
- .sendCommand
- EventType
- .connect
- ServoOutputViewModel
- CrashAnalyzer
- PlanScreen
- AppNavGraph.kt
- OptionsViewModel
- GridUtils
- ReplayFrame
- WebSocketManager
- .requestPostAuthSetup
- SignupPage.kt
- ScreenRecordService
- MotorHealthScreen
- DiagnosticFlag
- AnalyzeLogScreen.kt
- CameraProtocolManager
- AppStrings
- LatLng
- MotorTestViewModel
- CalibrationsScreen
- TelemetryState
- ArtificialHorizon.kt
- VoltageFilter
- AboutDroneViewModel
- MavlinkFtpClient
- GeofenceUtils
- FlightEntity
- DroneCameraFeedOverlay
- LevelSensorCalibrationScreen
- Log Replay Screen
- .handleBatteryVoltageFailsafe
- LoginPage
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
- BarometerCalibrationViewModel
- ReplayMap.kt
- Flow Sensor Calibration Screen
- In App Update State
- CommandLong
- EventEntity
- Flight Log Exporter
- TlogRepository
- DataFlashParser
- Log Analysis View Model
- GcsMap
- MapDataEntity
- LogsScreen.kt
- Welcome
- Spray Calibration Screen
- fake_mavlink_udp.py
- Forgot Password Page
- VideoTrackingOverlay
- Sensor Settings Screen
- SharedViewModel.kt
- Shared View Model
- Telemetry Overlay
- SettingsScreen
- Rc Stick View
- VideoStreamSettings
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
1. `SharedViewModel` - 318 edges
2. `MavlinkTelemetryRepository` - 107 edges
3. `AppNavGraph()` - 67 edges
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
- `PlanScreen()` --calls--> `GridGenerator`  [INFERRED]
  app/src/main/java/com/example/kftgcs/uimain/PlanScreen.kt → app/src/main/java/com/example/kftgcs/grid/GridGenerator.kt
- `PlanScreen()` --calls--> `GridWaypoint`  [INFERRED]
  app/src/main/java/com/example/kftgcs/uimain/PlanScreen.kt → app/src/main/java/com/example/kftgcs/grid/GridWaypoint.kt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **CI Security Scan Pipeline** — github_workflows_security_dependencycheck, github_workflows_security_secretscanning, github_workflows_security_codeqlanalysis, github_workflows_security_androidlint [EXTRACTED 1.00]
- **Critical/High Priority Backend Data-Integrity Fixes (A, B, C)** — docs_backend_websocket_spec_socketauthentication, docs_backend_websocket_spec_vehiclededuplication, docs_backend_websocket_spec_droneuidupdate [EXTRACTED 1.00]

## Communities (122 total, 21 thin omitted)

### Community 0 - "T12SerialVideoSource"
Cohesion: 0.17
Nodes (9): UsbDevice, UsbPortOwnership, describe(), ByteArray, Job, T, UsbSerialPort, T12SerialVideoSource (+1 more)

### Community 1 - ".sendHobbywingEscRequests"
Cohesion: 0.13
Nodes (12): HwCanFrame, HwEscConfig, HwEscReply, hwGetEscIdFrame(), hwMergeConfig(), hwParseReply(), hwServiceFrame(), UByte (+4 more)

### Community 2 - "SharedViewModel"
Cohesion: 0.03
Nodes (6): kotlinx, SharedFlow, StateFlow, MissionCompletionData, SharedViewModel, com

### Community 3 - ".authenticate"
Cohesion: 0.16
Nodes (15): AuthResult, AUTHENTICATED, DENIED, FAILED, LEGACY_FIRMWARE, KFTAuth, object@L92, ByteArray (+7 more)

### Community 4 - "MavlinkTelemetryRepository"
Cohesion: 0.10
Nodes (7): kotlinx, SharedFlow, StateFlow, MavlinkTelemetryRepository, ParamValue, RcChannels, ServoOutputRaw

### Community 5 - "FieldType"
Cohesion: 0.08
Nodes (26): DataFlash, FieldType, CHAR16, CHAR4, CHAR64, DOUBLE, FLOAT, INT16 (+18 more)

### Community 6 - "FullParamListViewModel"
Cohesion: 0.08
Nodes (33): ArduPilotParamMetadataRepository, TypeToken, TypeToken, Context, TypeToken, BreakingSettingsScreen(), ConfirmChangesDialog(), fmtBrake() (+25 more)

### Community 7 - "ProximityOverlay.kt"
Cohesion: 0.06
Nodes (55): Intent, MainActivity, Color, ObstacleWindow, obstacleWindowOf(), proximityColor(), ProximityData, RadarSwitchState (+47 more)

### Community 8 - "CalibrationViewModel"
Cohesion: 0.06
Nodes (36): CalibrationActions(), CalibrationContent(), CalibrationHeader(), CalibrationProgress(), CalibrationScreen(), CancelledContent(), DroneOrientationIcon(), FailedContent() (+28 more)

### Community 9 - "SpraySettingsViewModel"
Cohesion: 0.15
Nodes (17): ConfirmSprayDialog(), EnumDropdown(), fmtSpray(), InfoCenterSpray(), androidx, NavController, SectionCard(), SprayFieldView() (+9 more)

### Community 10 - "Notification"
Cohesion: 0.10
Nodes (4): Notification, ResumePrepResult, NotificationItem(), NotificationPanel()

### Community 11 - "VideoStreamPlayer.kt"
Cohesion: 0.08
Nodes (29): BroadcastReceiver, Context, Flow, Intent, UsbDevice, UsbManager, UsbSerialPermission, BroadcastReceiver (+21 more)

### Community 12 - "ParamManagementViewModel"
Cohesion: 0.09
Nodes (22): Error, OkHttpClient, T, ParamAuthApiService, ParamAuthErrorResponse, ParamAuthResult, ParamLoginRequest, ParamLoginResponse (+14 more)

### Community 13 - "Screen"
Cohesion: 0.04
Nodes (48): AboutApp, AccelerometerCalibration, Aircraft, AnalyzeLog, BarometerCalibration, BatteryMonitorSettings, Calibrations, CompassCalibration (+40 more)

### Community 14 - ".imagePointToGps"
Cohesion: 0.40
Nodes (3): GeoReferencer, FloatArray, LatLng

### Community 15 - "GCSApplication"
Cohesion: 0.12
Nodes (7): GCSApplication, Application, CrashLogger, Context, Context, SecurePinManager, SecretKey

### Community 16 - "Compass Calibration Screen UI"
Cohesion: 0.09
Nodes (27): CancelledContent(), CompassCalibrationActions(), CompassCalibrationContent(), CompassCalibrationHeader(), CompassCalibrationProgress(), CompassCalibrationScreen(), CompassReportCard(), FailedContent() (+19 more)

### Community 17 - "UserSettingsManager"
Cohesion: 0.19
Nodes (8): FontSizeOption, LARGE, MEDIUM, SMALL, Color, Context, SharedPreferences, UserSettingsManager

### Community 18 - "LogUtils"
Cohesion: 0.05
Nodes (30): BatteryMonitorState, BatteryMonitorViewModel, BattMonitorOption, HwVerPreset, StateFlow, ViewModel, sanitizeDecimalString(), SensorPreset (+22 more)

### Community 19 - "RC Calibration Screen UI"
Cohesion: 0.09
Nodes (24): AllChannelsCard(), CalibrationButton(), ConnectionStatusCard(), NavController, MainControlsCard(), RCCalibrationHeader(), RCCalibrationScreen(), RCChannelBar() (+16 more)

### Community 20 - "TextToSpeechManager"
Cohesion: 0.16
Nodes (3): TextToSpeechManager, OnInitListener, TextToSpeech

### Community 21 - "ServoOutputScreen.kt"
Cohesion: 0.17
Nodes (17): ServoChannel, ServoFunction, VehicleState, ArmedWarningBanner(), CompactFunctionDropdown(), CompactPwmField(), HeaderCell(), Dp (+9 more)

### Community 22 - "Level Calibration Screen UI"
Cohesion: 0.10
Nodes (23): CancelledContent(), FailedContent(), IdleContent(), InitiatingContent(), InProgressContent(), NavController, LevelCalibrationActions(), LevelCalibrationContent() (+15 more)

### Community 23 - "TlogViewModel"
Cohesion: 0.19
Nodes (5): AndroidViewModel, Flow, StateFlow, TlogUiState, TlogViewModel

### Community 24 - "SessionManager"
Cohesion: 0.26
Nodes (3): Context, SharedPreferences, SessionManager

### Community 25 - "BluetoothMavConnection"
Cohesion: 0.06
Nodes (26): BluetoothConnectionProvider, CoroutinesMavConnection, BluetoothMavConnection, BufferedMavConnection, ByteArray, MavConnection, MavFrame, MavMessage (+18 more)

### Community 27 - "EventType"
Cohesion: 0.08
Nodes (23): EventSeverity, CRITICAL, ERROR, INFO, WARNING, EventType, ARM_DISARM, CONNECTION_LOSS (+15 more)

### Community 29 - "ServoOutputViewModel"
Cohesion: 0.23
Nodes (4): StateFlow, ViewModel, ServoOutputUiState, ServoOutputViewModel

### Community 31 - "PlanScreen"
Cohesion: 0.08
Nodes (21): Context, LatLng, StateFlow, PhoneLocationProvider, rememberPhoneLocation(), GridSourceSelectionDialog(), KmlPolygonSelectionDialog(), MissionChoiceDialog() (+13 more)

### Community 32 - "AppNavGraph.kt"
Cohesion: 0.10
Nodes (25): NavController, WelcomeScreen(), AppNavGraph(), NavHostController, PlaceholderScreen(), Modifier, NavController, ParamLoginPage() (+17 more)

### Community 33 - "OptionsViewModel"
Cohesion: 0.06
Nodes (21): FenceAction, ALWAYS_LAND, BRAKE, REPORT_ONLY, RTL, SMART_RTL, SMART_RTL_LAND, UInt (+13 more)

### Community 35 - "ReplayFrame"
Cohesion: 0.17
Nodes (8): ReplayFrame, FlightMode, ReplayTimelineBuilder, rememberReplayController(), ReplayController, Modifier, relativeTimestamp(), ReplayScrubber()

### Community 36 - "WebSocketManager"
Cohesion: 0.06
Nodes (15): Context, MissionTemplateDatabase, Flow, OfflineMessageDao, OfflineMessageEntity, Runnable, SyncWorker, Context (+7 more)

### Community 38 - "SignupPage.kt"
Cohesion: 0.70
Nodes (4): Modifier, NavController, MobileNumberField(), SignupPage()

### Community 39 - "ScreenRecordService"
Cohesion: 0.15
Nodes (13): NotificationType, ERROR, INFO, SUCCESS, WARNING, Context, Intent, ScreenRecordService (+5 more)

### Community 40 - "MotorHealthScreen"
Cohesion: 0.60
Nodes (5): EscIdRow(), NavHostController, LimitEditor(), MotorHealthScreen(), PendingChange

### Community 41 - "DiagnosticFlag"
Cohesion: 0.17
Nodes (17): AnalysisResults(), CenteredStatus(), DiagnosticCard(), formatTime(), Color, ImageVector, NavHostController, LogAnalysisScreen() (+9 more)

### Community 42 - "AnalyzeLogScreen.kt"
Cohesion: 0.08
Nodes (33): DroneIdentifier, extractDroneUniqueId(), extractUuidString(), UByte, LogEntryInfo, SdLogEntry, SprayTelemetry, AccentButton() (+25 more)

### Community 43 - "CameraProtocolManager"
Cohesion: 0.15
Nodes (7): CameraProtocolManager, StateFlow, CameraFovStatus, TrackingImageStatus, CameraTrackingImageStatus, MavCameraFovStatus, VideoStreamInformation

### Community 44 - "AppStrings"
Cohesion: 0.15
Nodes (11): BarometerCalibrationScreen(), ImageVector, NavController, StatusIndicatorCard(), Dp, NavController, SelectFlyingMethodScreen(), StyledFlyingMethodCard() (+3 more)

### Community 46 - "MotorTestViewModel"
Cohesion: 0.13
Nodes (7): FrameClassMap, FrameInfo, StateFlow, UInt, ViewModel, MotorTestState, MotorTestViewModel

### Community 47 - "CalibrationsScreen"
Cohesion: 0.70
Nodes (4): CalibrationOptionCard(), CalibrationsScreen(), ImageVector, NavController

### Community 48 - "TelemetryState"
Cohesion: 0.05
Nodes (24): Application, TlogIntegration, FlightState, ACTIVE, FINALIZING, IDLE, STARTING, STOPPING (+16 more)

### Community 49 - "ArtificialHorizon.kt"
Cohesion: 0.83
Nodes (3): ArtificialHorizon(), drawHorizon(), Modifier

### Community 50 - "VoltageFilter"
Cohesion: 0.13
Nodes (4): CalibrationPoint, FlowRateFilter, FlowRateValidator, VoltageFilter

### Community 51 - "AboutDroneViewModel"
Cohesion: 0.18
Nodes (11): AboutDroneScreen(), DroneInfoCard(), FrameSelectRow(), InfoDivider(), InfoRow(), NavController, SectionHeader(), AboutDroneViewModel (+3 more)

### Community 52 - "MavlinkFtpClient"
Cohesion: 0.10
Nodes (20): BufferedMavConnection, ByteArray, MavConnection, MavFrame, MavMessage, T, UByte, UInt (+12 more)

### Community 55 - "FlightEntity"
Cohesion: 0.20
Nodes (3): FlightDao, Flow, FlightEntity

### Community 56 - "DroneCameraFeedOverlay"
Cohesion: 0.52
Nodes (5): CameraPlaceholder(), DroneCameraFeedOverlay(), Modifier, VideoStreamView(), CameraTrackingState

### Community 57 - "LevelSensorCalibrationScreen"
Cohesion: 0.70
Nodes (4): InstructionStep(), NavHostController, LevelSensorCalibrationScreen(), ReadingRow()

### Community 58 - "Log Replay Screen"
Cohesion: 0.25
Nodes (14): Context, shareFile(), shareLogFile(), shareRecording(), CrashBanner(), DisplayPrefsMenu(), formatT(), NavHostController (+6 more)

### Community 59 - ".handleBatteryVoltageFailsafe"
Cohesion: 0.11
Nodes (3): BatteryFsAction, Context, UInt

### Community 60 - "LoginPage"
Cohesion: 0.83
Nodes (3): Modifier, NavController, LoginPage()

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
Cohesion: 0.08
Nodes (23): AppScope, CoroutineScope, EscReading, AltitudeLimits, ArmMagicValues, escReadings(), MavFrame, MavMessage (+15 more)

### Community 65 - "ConnectionPage.kt"
Cohesion: 0.18
Nodes (12): PairedDevice, UsbDeviceInfo, BluetoothConnectionContent(), ConnectionPage(), DeviceRow(), isPlausibleHost(), isUdpSelfTarget(), NavController (+4 more)

### Community 66 - "MissionItemInt"
Cohesion: 0.15
Nodes (5): Circle, FenceZone, Polygon, ReturnPoint, MissionItemInt

### Community 68 - "Backend Websocket Spec"
Cohesion: 0.16
Nodes (15): consumers.py (backend), Atomic Telemetry Writes + Safe Key Access (Issue F, MEDIUM), client_id Dedup (Issue E, MEDIUM), drone_uid_update Handler (Issue C, HIGH), Protocol Alignment: session_ack / STATUS_STARTED (Issue H), resume_mission_id field, session_start message handler, Authenticate the Socket (Issue A, CRITICAL) (+7 more)

### Community 69 - "KeyboardType"
Cohesion: 0.31
Nodes (9): Modifier, NavController, OtpVerificationPage(), BatteryMonitorScreen(), CalculatedCard(), NavController, LabeledNumberField(), SaveButton() (+1 more)

### Community 70 - "CameraTrackingState.kt"
Cohesion: 0.15
Nodes (12): UByte, CameraCapabilities, CameraInfo, TrackingMode, NONE, POINT, RECTANGLE, TrackingStatus (+4 more)

### Community 75 - "BarometerCalibrationViewModel"
Cohesion: 0.29
Nodes (5): BarometerCalibrationUiState, BarometerCalibrationViewModel, Job, StateFlow, ViewModel

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
Cohesion: 0.20
Nodes (5): GimbalController, StateFlow, UByte, CommandLong, GimbalDeviceAttitudeStatus

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

### Community 89 - "GcsMap"
Cohesion: 0.10
Nodes (28): GridGenerator, LatLng, GridMissionConverter, LatLng, MissionItemInt, UByte, GridSurveyParams, GridSurveyResult (+20 more)

### Community 95 - "LogsScreen.kt"
Cohesion: 0.27
Nodes (10): ExportFormat, CSV, JSON, TLOG, ActiveFlightCard(), FlightItem(), formatDuration(), Modifier (+2 more)

### Community 99 - "Welcome"
Cohesion: 0.36
Nodes (9): KFT Play Store App Icon, KFT Wordmark Logo Mark, Kapil Group Logo, KFT Kapil Future Tech Logo, Agricultural Spraying Drone Background Photo, Version Label v1.01, Welcome Screen Splash Image, Kapil Future Tech (KFT) Brand Identity (+1 more)

### Community 101 - "Spray Calibration Screen"
Cohesion: 0.39
Nodes (8): CalibrationButton(), ConfigurationStatusCard(), androidx, Color, ImageVector, NavHostController, SprayCalibrationScreen(), StatusRow()

### Community 102 - "fake_mavlink_udp.py"
Cohesion: 0.35
Nodes (11): crc16(), crc_accumulate(), heartbeat_frame(), listen(), main(), parse_target(), push(), Fake MAVLink vehicle over UDP, for testing the app's UDP link without a drone.… (+3 more)

### Community 103 - "Forgot Password Page"
Cohesion: 0.54
Nodes (7): ForgotPasswordPage(), Modifier, NavController, Step1EmailContent(), Step2OtpContent(), Step3NewPasswordContent(), textFieldColors()

### Community 106 - "VideoTrackingOverlay"
Cohesion: 0.80
Nodes (5): CameraInfoBadge(), GimbalInfoBadge(), Modifier, TrackingStatusBadge(), VideoTrackingOverlay()

### Community 107 - "Sensor Settings Screen"
Cohesion: 0.39
Nodes (7): androidx, NavHostController, roundToStep(), SensorSettingsScreen(), StepButton(), ThresholdControl(), ClosedFloatingPointRange

### Community 110 - "SharedViewModel.kt"
Cohesion: 0.09
Nodes (18): ConnectionType, BLUETOOTH, TCP, UDP, USB, BroadcastReceiver, Intent, Job (+10 more)

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
- **21 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `SharedViewModel` connect `SharedViewModel` to `.sendHobbywingEscRequests`, `PreflightFailsafeDialog`, `MavlinkTelemetryRepository`, `FullParamListViewModel`, `ProximityOverlay.kt`, `CalibrationViewModel`, `SpraySettingsViewModel`, `Notification`, `Shared View Model`, `Compass Calibration Screen UI`, `LogUtils`, `RC Calibration Screen UI`, `TextToSpeechManager`, `ServoOutputScreen.kt`, `Level Calibration Screen UI`, `SessionManager`, `.connect`, `ServoOutputViewModel`, `PlanScreen`, `AppNavGraph.kt`, `OptionsViewModel`, `MotorHealthScreen`, `AnalyzeLogScreen.kt`, `AppStrings`, `LatLng`, `MotorTestViewModel`, `CalibrationsScreen`, `TelemetryState`, `AboutDroneViewModel`, `MavlinkFtpClient`, `DroneCameraFeedOverlay`, `LevelSensorCalibrationScreen`, `.handleBatteryVoltageFailsafe`, `AuthViewModel`, `ConnectionPage.kt`, `.uploadGeofence`, `BarometerCalibrationViewModel`, `Flow Sensor Calibration Screen`, `GcsMap`, `LogsScreen.kt`, `Spray Calibration Screen`, `Sensor Settings Screen`, `SharedViewModel.kt`, `Shared View Model`, `SettingsScreen`, `.handleAltitudeFailsafe`, `Shared View Model`, `Shared View Model`?**
  _High betweenness centrality (0.395) - this node is a cross-community bridge._
- **Why does `MavlinkTelemetryRepository` connect `MavlinkTelemetryRepository` to `TelemetryRepository.kt`, `.sendHobbywingEscRequests`, `SharedViewModel`, `.authenticate`, `MissionItemInt`, `.requestPostAuthSetup`, `.uploadGeofence`, `AnalyzeLogScreen.kt`, `CameraProtocolManager`, `TelemetryState`, `CommandLong`, `VoltageFilter`, `.sendCommand`, `.connect`?**
  _High betweenness centrality (0.153) - this node is a cross-community bridge._
- **Why does `LogUtils` connect `LogUtils` to `TelemetryRepository.kt`, `OptionsViewModel`, `FullParamListViewModel`, `ScreenRecordService`, `SpraySettingsViewModel`, `AnalyzeLogScreen.kt`, `MotorTestViewModel`, `SharedViewModel.kt`, `TelemetryState`, `MavlinkFtpClient`, `ServoOutputScreen.kt`, `DataFlashParser`, `Log Analysis View Model`, `Log Replay Screen`, `MissionTemplateEntity`, `PlanScreen`?**
  _High betweenness centrality (0.111) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `MavlinkTelemetryRepository` (e.g. with `FlowRateFilter` and `VoltageFilter`) actually correct?**
  _`MavlinkTelemetryRepository` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 7 inferred relationships involving `Notification` (e.g. with `.arm()` and `.resumeMission()`) actually correct?**
  _`Notification` has 7 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ErrorResponse`, `Error`, `AdminInfo` to the rest of the system?**
  _250 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `.sendHobbywingEscRequests` be split into smaller, more focused modules?**
  _Cohesion score 0.1310483870967742 - nodes in this community are weakly interconnected._