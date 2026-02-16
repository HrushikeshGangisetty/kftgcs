# Drone UID Extraction Fix - Complete Implementation

## Issue Identified
The app was always sending `SITL_DRONE_001` as the drone UID even when connected to a real drone because the extracted `droneUid` from `TelemetryRepository` was never being passed to `WebSocketManager`.

## Root Cause
1. **TelemetryRepository** was correctly extracting `droneUid` from OpenDroneID messages and storing it in telemetry state
2. **WebSocketManager** had a `droneUid` property that was never being populated
3. **Missing Link**: No connection between TelemetryRepository's extracted droneUid and WebSocketManager's droneUid property

## Changes Made

### 1. TelemetryRepository.kt - Added WebSocketManager Update
**Location**: Line ~1467 (after droneUid extraction from OpenDroneID)

```kotlin
// 🔥 CRITICAL FIX: Update WebSocketManager with real drone UID
try {
    val wsManager = WebSocketManager.getInstance()
    wsManager.droneUid = droneIdentifier.serialNumber
    Log.i("TelemetryRepo", "✅ Updated WebSocketManager with droneUid: '${droneIdentifier.serialNumber}'")
} catch (e: Exception) {
    Log.e("TelemetryRepo", "❌ Failed to update WebSocketManager droneUid", e)
}
```

### 2. WebSocketManager.kt - Enhanced droneUid Property Setter
**Location**: Line ~162 (droneUid property setter)

```kotlin
var droneUid: String = ""
    set(value) {
        val oldValue = field
        field = value
        
        // 🔥 LOG ALL DRONE UID UPDATES
        Log.e("WS_DRONE_UID", "🔥 DRONE UID UPDATED:")
        Log.e("WS_DRONE_UID", "   Old: '$oldValue'")
        Log.e("WS_DRONE_UID", "   New: '$value'")
        Log.e("WS_DRONE_UID", "   IsConnected: $isConnected")
        Log.e("WS_DRONE_UID", "   MissionId: $missionId")
        
        // Send update to backend if connected
        if (value.isNotBlank() && oldValue != value && isConnected && missionId != null) {
            Log.e("WS_DRONE_UID", "   📤 Sending drone_uid_update to backend")
            sendDroneUidUpdate(value)
        }
    }
```

### 3. WebSocketManager.kt - Connection Status Logging
**Location**: Line ~264 (connect method)

```kotlin
// 🔥 IMMEDIATE DRONE UID STATUS CHECK
val currentDroneUid = resolveDroneUid()
val isRealDrone = droneUid.isNotBlank()
Log.e("WS_DRONE_UID", "🔥 DRONE UID STATUS ON CONNECT:")
Log.e("WS_DRONE_UID", "   Raw droneUid: '$droneUid'")
Log.e("WS_DRONE_UID", "   Resolved droneUid: '$currentDroneUid'")
Log.e("WS_DRONE_UID", "   Is Real Drone: $isRealDrone")
Log.e("WS_DRONE_UID", "   ${if (isRealDrone) "✅ USING REAL DRONE UID" else "⚠️ USING FALLBACK (SITL_DRONE_001)"}")
```

### 4. WebSocketManager.kt - Session Start Verification Logging
**Location**: Line ~355 (sendSessionStart method)

```kotlin
// 🔥 EXTRA DRONE UID VERIFICATION
Log.e("WS_DRONE_UID", "🔥 SESSION_START DRONE UID VERIFICATION:")
Log.e("WS_DRONE_UID", "   Raw droneUid field: '$droneUid'")
Log.e("WS_DRONE_UID", "   droneUidToSend: '$droneUidToSend'")
Log.e("WS_DRONE_UID", "   Is fallback: $isFallback")
Log.e("WS_DRONE_UID", "   Final payload drone_uid: '${sessionStart.getString("drone_uid")}'")
```

## How to Test & Verify

### Step 1: Connect to Real Drone
1. Connect the app to a real drone (not SITL)
2. Watch the logs for `WS_DRONE_UID` tags

### Step 2: Expected Log Sequence (Real Drone)
```
TelemetryRepo: ✅ Extracted Drone ID: [REAL_SERIAL] (Type: ...)
TelemetryRepo: ✅ Updated WebSocketManager with droneUid: '[REAL_SERIAL]'
WS_DRONE_UID: 🔥 DRONE UID UPDATED:
WS_DRONE_UID:    Old: ''
WS_DRONE_UID:    New: '[REAL_SERIAL]'
WS_DRONE_UID: 🔥 DRONE UID STATUS ON CONNECT:
WS_DRONE_UID:    Raw droneUid: '[REAL_SERIAL]'
WS_DRONE_UID:    Resolved droneUid: '[REAL_SERIAL]'
WS_DRONE_UID:    Is Real Drone: true
WS_DRONE_UID:    ✅ USING REAL DRONE UID
WS_DRONE_UID: 🔥 SESSION_START DRONE UID VERIFICATION:
WS_DRONE_UID:    Final payload drone_uid: '[REAL_SERIAL]'
```

### Step 3: Expected Log Sequence (SITL/No Drone)
```
WS_DRONE_UID: 🔥 DRONE UID STATUS ON CONNECT:
WS_DRONE_UID:    Raw droneUid: ''
WS_DRONE_UID:    Resolved droneUid: 'SITL_DRONE_001'
WS_DRONE_UID:    Is Real Drone: false
WS_DRONE_UID:    ⚠️ USING FALLBACK (SITL_DRONE_001)
WS_DRONE_UID: 🔥 SESSION_START DRONE UID VERIFICATION:
WS_DRONE_UID:    Final payload drone_uid: 'SITL_DRONE_001'
```

## Key Log Tags to Monitor
- **`WS_DRONE_UID`**: All drone UID related logging
- **`TelemetryRepo`**: Drone ID extraction from OpenDroneID messages
- **`WS_DEBUG`**: WebSocket payload and connection details

## Files Modified
1. `TelemetryRepository.kt` - Added WebSocketManager.droneUid update
2. `WebSocketManager.kt` - Enhanced logging for droneUid tracking

## Impact
- ✅ Real drone UIDs will now be properly extracted and sent to backend
- ✅ SITL fallback still works for simulator testing
- ✅ Comprehensive logging for debugging and verification
- ✅ Automatic backend updates when drone UID becomes available during session

## Testing Checklist
- [ ] Connect to real drone and verify real UID is logged and sent
- [ ] Connect to SITL and verify fallback UID is used
- [ ] Check backend receives correct drone_uid in session_start
- [ ] Verify drone_uid_update is sent if UID becomes available after connection
