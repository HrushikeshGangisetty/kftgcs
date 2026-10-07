# Graph Report - kftgcs  (2026-10-06)

## Corpus Check
- 198 files · ~901,536 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 54 file(s) not represented in the graph (top: .xml 20, .log 19, .aab 4)

## Summary
- 2433 nodes · 5029 edges · 135 communities (104 shown, 22 thin omitted)
- Extraction: 97% EXTRACTED · 3% INFERRED · 0% AMBIGUOUS · INFERRED: 126 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `10e11821`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- BatteryMonitorViewModel
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
- T12SerialVideoSource
- ParamManagementViewModel
- Screen
- .imagePointToGps
- GCSApplication
- Compass Calibration Screen UI
- UserSettingsManager
- FlightModeViewModel
- RC Calibration Screen UI
- TextToSpeechManager
- ServoOutputViewModel
- Level Calibration Screen UI
- TlogViewModel
- Data.kt
- BluetoothMavConnection
- SessionManager
- EventType
- .connect
- AnalyzeLogScreen.kt
- CrashAnalyzer
- ObstaclePathPlanner
- AppNavGraph.kt
- OptionsViewModel
- GridUtils
- ReplayFrame
- WebSocketManager
- VideoStreamPlayer.kt
- UsbSerialMavConnection
- ScreenRecordService
- MotorHealthScreen
- DiagnosticFlag
- AnalyzeLogUiState
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
- LogUtils
- FlightEntity
- FenceAction
- Log Replay Screen
- .handleBatteryVoltageFailsafe
- ArmingCheckState
- .request
- TermsAndConditionsScreen
- AuthViewModel
- TelemetryRepository.kt
- ConnectionPage.kt
- MissionItemInt
- .sendCommand
- Backend Websocket Spec
- KeyboardType
- CameraTrackingState.kt
- .uploadGeofence
- android
- .sendCommandAck
- SelectFlyingMethodScreen.kt
- BarometerCalibrationViewModel
- OptionsScreen.kt
- LimitFailsafePolicy
- ReplayMap.kt
- Flow Sensor Calibration Screen
- In App Update State
- CommandLong
- EventEntity
- Flight Log Exporter
- TlogRepository
- .sendSignedV2
- VideoPlayerState
- DataFlashParser
- Log Analysis View Model
- GcsMap
- BarometerCalibrationScreen.kt
- ParamLoginPage
- MapDataEntity
- LogsScreen.kt
- ParamManagementHomeScreen
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
- DroneCameraFeedOverlay
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
1. `SharedViewModel` - 320 edges
2. `MavlinkTelemetryRepository` - 111 edges
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

## Communities (135 total, 22 thin omitted)

### Community 0 - "BatteryMonitorViewModel"
Cohesion: 0.19
Nodes (8): BatteryMonitorState, BatteryMonitorViewModel, BattMonitorOption, HwVerPreset, StateFlow, ViewModel, sanitizeDecimalString(), SensorPreset

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
Cohesion: 0.08
Nodes (9): kotlinx, SharedFlow, StateFlow, MavlinkTelemetryRepository, currentItem, ParamValue, RcChannels, ServoOutputRaw (+1 more)

### Community 5 - "FieldType"
Cohesion: 0.08
Nodes (26): DataFlash, FieldType, CHAR16, CHAR4, CHAR64, DOUBLE, FLOAT, INT16 (+18 more)

### Community 6 - "FullParamListViewModel"
Cohesion: 0.06
Nodes (39): ArduPilotParamMetadataRepository, TypeToken, TypeToken, Context, TypeToken, BrakeWriteResult, BrakingSettingsState, BrakingSettingsViewModel (+31 more)

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

### Community 11 - "T12SerialVideoSource"
Cohesion: 0.17
Nodes (9): UsbDevice, UsbPortOwnership, describe(), ByteArray, Job, T, UsbSerialPort, T12SerialVideoSource (+1 more)

### Community 12 - "ParamManagementViewModel"
Cohesion: 0.09
Nodes (22): Error, OkHttpClient, T, ParamAuthApiService, ParamAuthErrorResponse, ParamAuthResult, ParamLoginRequest, ParamLoginResponse (+14 more)

### Community 13 - "Screen"
Cohesion: 0.04
Nodes (48): AboutApp, AccelerometerCalibration, Aircraft, AnalyzeLog, BarometerCalibration, BatteryMonitorSettings, Calibrations, CompassCalibration (+40 more)

### Community 14 - ".imagePointToGps"
Cohesion: 0.36
Nodes (4): CameraInfo, GeoReferencer, FloatArray, LatLng

### Community 15 - "GCSApplication"
Cohesion: 0.10
Nodes (10): GCSApplication, Application, CrashLogger, Context, Context, SecurePinManager, Context, WifiMulticast (+2 more)

### Community 16 - "Compass Calibration Screen UI"
Cohesion: 0.09
Nodes (27): CancelledContent(), CompassCalibrationActions(), CompassCalibrationContent(), CompassCalibrationHeader(), CompassCalibrationProgress(), CompassCalibrationScreen(), CompassReportCard(), FailedContent() (+19 more)

### Community 17 - "UserSettingsManager"
Cohesion: 0.19
Nodes (8): FontSizeOption, LARGE, MEDIUM, SMALL, Color, Context, SharedPreferences, UserSettingsManager

### Community 18 - "FlightModeViewModel"
Cohesion: 0.18
Nodes (12): FlightModeDropdown(), FlightModeScreen(), FlightModeSlotCard(), InfoBanner(), Color, NavController, LoadingRow(), FlightModeOption (+4 more)

### Community 19 - "RC Calibration Screen UI"
Cohesion: 0.09
Nodes (24): AllChannelsCard(), CalibrationButton(), ConnectionStatusCard(), NavController, MainControlsCard(), RCCalibrationHeader(), RCCalibrationScreen(), RCChannelBar() (+16 more)

### Community 20 - "TextToSpeechManager"
Cohesion: 0.16
Nodes (3): TextToSpeechManager, OnInitListener, TextToSpeech

### Community 21 - "ServoOutputViewModel"
Cohesion: 0.10
Nodes (21): ServoChannel, ServoFunction, VehicleState, ArmedWarningBanner(), CompactFunctionDropdown(), CompactPwmField(), HeaderCell(), Dp (+13 more)

### Community 22 - "Level Calibration Screen UI"
Cohesion: 0.10
Nodes (23): CancelledContent(), FailedContent(), IdleContent(), InitiatingContent(), InProgressContent(), NavController, LevelCalibrationActions(), LevelCalibrationContent() (+15 more)

### Community 23 - "TlogViewModel"
Cohesion: 0.19
Nodes (5): AndroidViewModel, Flow, StateFlow, TlogUiState, TlogViewModel

### Community 24 - "Data.kt"
Cohesion: 0.16
Nodes (12): DroneIdentifier, EscReading, extractDroneUniqueId(), extractUuidString(), UByte, SprayTelemetry, escReadings(), MavResult (+4 more)

### Community 25 - "BluetoothMavConnection"
Cohesion: 0.06
Nodes (26): BluetoothConnectionProvider, CoroutinesMavConnection, BluetoothMavConnection, BufferedMavConnection, ByteArray, MavConnection, MavFrame, MavMessage (+18 more)

### Community 26 - "SessionManager"
Cohesion: 0.24
Nodes (3): Context, SharedPreferences, SessionManager

### Community 27 - "EventType"
Cohesion: 0.08
Nodes (23): EventSeverity, CRITICAL, ERROR, INFO, WARNING, EventType, ARM_DISARM, CONNECTION_LOSS (+15 more)

### Community 29 - "AnalyzeLogScreen.kt"
Cohesion: 0.33
Nodes (10): LogEntryInfo, SdLogEntry, AccentButton(), AnalyzeLogScreen(), CenteredStatus(), formatBytes(), formatTimestamp(), NavHostController (+2 more)

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
Cohesion: 0.17
Nodes (8): ReplayFrame, FlightMode, ReplayTimelineBuilder, rememberReplayController(), ReplayController, Modifier, relativeTimestamp(), ReplayScrubber()

### Community 36 - "WebSocketManager"
Cohesion: 0.06
Nodes (15): Context, MissionTemplateDatabase, Flow, OfflineMessageDao, OfflineMessageEntity, Runnable, SyncWorker, Context (+7 more)

### Community 37 - "VideoStreamPlayer.kt"
Cohesion: 0.19
Nodes (12): Network, Usb, VideoSource, buildMediaSource(), describeError(), Modifier, T12SerialStreamPlayer(), VideoPlaceholder() (+4 more)

### Community 38 - "UsbSerialMavConnection"
Cohesion: 0.22
Nodes (11): BufferedMavConnection, ByteArray, MavConnection, MavFrame, MavMessage, UsbSerialPort, UsbSerialInputStream, UsbSerialMavConnection (+3 more)

### Community 39 - "ScreenRecordService"
Cohesion: 0.15
Nodes (13): NotificationType, ERROR, INFO, SUCCESS, WARNING, Context, Intent, ScreenRecordService (+5 more)

### Community 40 - "MotorHealthScreen"
Cohesion: 0.60
Nodes (5): EscIdRow(), NavHostController, LimitEditor(), MotorHealthScreen(), PendingChange

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
Cohesion: 0.18
Nodes (8): Modifier, NavController, LoginPage(), Modifier, NavController, MobileNumberField(), SignupPage(), AppStrings

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
Cohesion: 0.27
Nodes (6): FtpEntry, ByteArray, UByte, MavlinkFtpClient, Resp, FileTransferProtocol

### Community 54 - "LogUtils"
Cohesion: 0.10
Nodes (19): Context, LatLng, StateFlow, PhoneLocationProvider, rememberPhoneLocation(), GridSourceSelectionDialog(), KmlPolygonSelectionDialog(), MissionChoiceDialog() (+11 more)

### Community 55 - "FlightEntity"
Cohesion: 0.20
Nodes (3): FlightDao, Flow, FlightEntity

### Community 56 - "FenceAction"
Cohesion: 0.18
Nodes (8): FenceAction, ALWAYS_LAND, BRAKE, REPORT_ONLY, RTL, SMART_RTL, SMART_RTL_LAND, UInt

### Community 58 - "Log Replay Screen"
Cohesion: 0.25
Nodes (14): Context, shareFile(), shareLogFile(), shareRecording(), CrashBanner(), DisplayPrefsMenu(), formatT(), NavHostController (+6 more)

### Community 59 - ".handleBatteryVoltageFailsafe"
Cohesion: 0.10
Nodes (3): BatteryFsAction, Context, UInt

### Community 60 - "ArmingCheckState"
Cohesion: 0.29
Nodes (7): ArmingCheckSafetyDialog(), ArmingCheckState, HIDDEN, PROMPT_REBOOT, PROMPT_WRITE, WRITE_FAILED, WRITING

### Community 61 - ".request"
Cohesion: 0.33
Nodes (8): BroadcastReceiver, Context, Flow, Intent, UsbDevice, UsbManager, UsbSerialPermission, BroadcastReceiver

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
Cohesion: 0.22
Nodes (12): PairedDevice, UsbDeviceInfo, BluetoothConnectionContent(), ConnectionPage(), DeviceRow(), isPlausibleHost(), isUdpSelfTarget(), NavController (+4 more)

### Community 66 - "MissionItemInt"
Cohesion: 0.17
Nodes (5): Circle, FenceZone, Polygon, ReturnPoint, MissionItemInt

### Community 68 - "Backend Websocket Spec"
Cohesion: 0.16
Nodes (15): consumers.py (backend), Atomic Telemetry Writes + Safe Key Access (Issue F, MEDIUM), client_id Dedup (Issue E, MEDIUM), drone_uid_update Handler (Issue C, HIGH), Protocol Alignment: session_ack / STATUS_STARTED (Issue H), resume_mission_id field, session_start message handler, Authenticate the Socket (Issue A, CRITICAL) (+7 more)

### Community 69 - "KeyboardType"
Cohesion: 0.21
Nodes (13): Modifier, NavController, OtpVerificationPage(), BatteryMonitorScreen(), CalculatedCard(), NavController, LabeledNumberField(), SaveButton() (+5 more)

### Community 70 - "CameraTrackingState.kt"
Cohesion: 0.12
Nodes (15): CameraTrackingState, TrackingMode, NONE, POINT, RECTANGLE, TrackingStatus, ACTIVE, ERROR (+7 more)

### Community 72 - "android"
Cohesion: 0.36
Nodes (3): android, Callback, SurfaceTextureListener

### Community 74 - "SelectFlyingMethodScreen.kt"
Cohesion: 0.70
Nodes (4): Dp, NavController, SelectFlyingMethodScreen(), StyledFlyingMethodCard()

### Community 75 - "BarometerCalibrationViewModel"
Cohesion: 0.29
Nodes (5): BarometerCalibrationUiState, BarometerCalibrationViewModel, Job, StateFlow, ViewModel

### Community 76 - "OptionsScreen.kt"
Cohesion: 0.46
Nodes (7): ActionDropdown(), ActionRadioGroup(), NavHostController, NumericTextField(), OptionsScreen(), SectionCard(), SubLabel()

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
Cohesion: 0.21
Nodes (4): EventEntity, EventDao, Flow, TelemetryDao

### Community 83 - "Flight Log Exporter"
Cohesion: 0.32
Nodes (3): FlightExportData, FlightLogExporter, TlogEntry

### Community 84 - "TlogRepository"
Cohesion: 0.24
Nodes (3): TelemetryEntity, Flow, TlogRepository

### Community 85 - ".sendSignedV2"
Cohesion: 0.67
Nodes (3): T, UByte, UInt

### Community 86 - "VideoPlayerState"
Cohesion: 0.33
Nodes (6): VideoPlayerState, BUFFERING, ERROR, IDLE, LOADING, PLAYING

### Community 87 - "DataFlashParser"
Cohesion: 0.31
Nodes (3): DataFlashParser, ByteArray, ParseSummary

### Community 88 - "Log Analysis View Model"
Cohesion: 0.31
Nodes (8): AnalysisComplete, Error, AndroidViewModel, StateFlow, Loading, LogAnalysisUiState, LogAnalysisViewModel, Parsing

### Community 89 - "GcsMap"
Cohesion: 0.09
Nodes (29): GridGenerator, LatLng, GridMissionConverter, LatLng, MissionItemInt, UByte, GridSurveyParams, GridSurveyResult (+21 more)

### Community 90 - "BarometerCalibrationScreen.kt"
Cohesion: 0.70
Nodes (4): BarometerCalibrationScreen(), ImageVector, NavController, StatusIndicatorCard()

### Community 92 - "ParamLoginPage"
Cohesion: 0.83
Nodes (3): Modifier, NavController, ParamLoginPage()

### Community 95 - "LogsScreen.kt"
Cohesion: 0.27
Nodes (10): ExportFormat, CSV, JSON, TLOG, ActiveFlightCard(), FlightItem(), formatDuration(), Modifier (+2 more)

### Community 96 - "ParamManagementHomeScreen"
Cohesion: 0.83
Nodes (3): NavController, ParamManagementHomeScreen(), ParamNavItem

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
Cohesion: 0.10
Nodes (16): ConnectionType, BLUETOOTH, TCP, UDP, USB, BroadcastReceiver, Intent, Job (+8 more)

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

### Community 134 - "Shared View Model"
Cohesion: 0.67
Nodes (3): UserFlightMode, AUTOMATIC, MANUAL

### Community 157 - "MotorTestScreen.kt"
Cohesion: 0.52
Nodes (6): Modifier, NavController, MotorTestScreen(), MtCard(), MtInputDialog(), MtNumberField()

## Knowledge Gaps
- **250 isolated node(s):** `ErrorResponse`, `Error`, `AdminInfo`, `VehicleInfo`, `AUTHENTICATED` (+245 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 530 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **22 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `SharedViewModel` connect `SharedViewModel` to `BatteryMonitorViewModel`, `.sendHobbywingEscRequests`, `PreflightFailsafeDialog`, `MavlinkTelemetryRepository`, `FullParamListViewModel`, `ProximityOverlay.kt`, `CalibrationViewModel`, `SpraySettingsViewModel`, `Notification`, `Shared View Model`, `Compass Calibration Screen UI`, `FlightModeViewModel`, `RC Calibration Screen UI`, `TextToSpeechManager`, `ServoOutputViewModel`, `Level Calibration Screen UI`, `SessionManager`, `.connect`, `AnalyzeLogScreen.kt`, `AppNavGraph.kt`, `OptionsViewModel`, `MotorHealthScreen`, `LatLng`, `MotorTestViewModel`, `CalibrationsScreen`, `TelemetryState`, `AboutDroneViewModel`, `LogUtils`, `FenceAction`, `.clearGeofenceFromFC`, `.handleBatteryVoltageFailsafe`, `ArmingCheckState`, `AuthViewModel`, `ConnectionPage.kt`, `KeyboardType`, `CameraTrackingState.kt`, `.uploadGeofence`, `.sendCommandAck`, `SelectFlyingMethodScreen.kt`, `BarometerCalibrationViewModel`, `OptionsScreen.kt`, `Flow Sensor Calibration Screen`, `GcsMap`, `LogsScreen.kt`, `.write`, `Spray Calibration Screen`, `Sensor Settings Screen`, `SharedViewModel.kt`, `Shared View Model`, `SettingsScreen`, `.handleAltitudeFailsafe`, `Shared View Model`, `Shared View Model`?**
  _High betweenness centrality (0.373) - this node is a cross-community bridge._
- **Why does `MavlinkTelemetryRepository` connect `MavlinkTelemetryRepository` to `TelemetryRepository.kt`, `.sendHobbywingEscRequests`, `SharedViewModel`, `.authenticate`, `.sendCommand`, `MissionItemInt`, `.uploadGeofence`, `AnalyzeLogUiState`, `CameraProtocolManager`, `TelemetryState`, `CommandLong`, `VoltageFilter`, `Data.kt`, `.connect`, `AnalyzeLogScreen.kt`?**
  _High betweenness centrality (0.149) - this node is a cross-community bridge._
- **Why does `LogUtils` connect `LogUtils` to `BatteryMonitorViewModel`, `TelemetryRepository.kt`, `OptionsViewModel`, `FullParamListViewModel`, `ScreenRecordService`, `SpraySettingsViewModel`, `AnalyzeLogUiState`, `MotorTestViewModel`, `GCSApplication`, `TelemetryState`, `SharedViewModel.kt`, `FlightModeViewModel`, `MavlinkFtpClient`, `ServoOutputViewModel`, `DataFlashParser`, `Log Analysis View Model`, `Log Replay Screen`, `MissionTemplateEntity`?**
  _High betweenness centrality (0.106) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `MavlinkTelemetryRepository` (e.g. with `FlowRateFilter` and `VoltageFilter`) actually correct?**
  _`MavlinkTelemetryRepository` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 7 inferred relationships involving `Notification` (e.g. with `.arm()` and `.resumeMission()`) actually correct?**
  _`Notification` has 7 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ErrorResponse`, `Error`, `AdminInfo` to the rest of the system?**
  _250 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `.sendHobbywingEscRequests` be split into smaller, more focused modules?**
  _Cohesion score 0.13306451612903225 - nodes in this community are weakly interconnected._