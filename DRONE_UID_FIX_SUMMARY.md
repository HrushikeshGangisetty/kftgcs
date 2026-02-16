# Vehicle ID (Drone UID) Fix Summary

## Problem
The vehicle ID was always stored as `SITL_DRONE_001` in the database, even when using a real flight controller. This happened because:

1. The WebSocket connection starts when the mission begins
2. At that moment, the AUTOPILOT_VERSION message from the FC might not have been received yet
3. The `droneUid` was still blank, so the fallback value `SITL_DRONE_001` was used in `session_start`
4. The backend stored this fallback value as the vehicle ID

## Solution
Implemented a two-part fix:

### Part 1: Android Client (WebSocketManager.kt)
Added a custom setter for `droneUid` that automatically sends an update to the backend when the real drone UID is received:

```kotlin
var droneUid: String = ""
    set(value) {
        val oldValue = field
        field = value
        // If droneUid was updated while connected, send update to backend
        if (value.isNotBlank() && oldValue != value && isConnected && missionId != null) {
            sendDroneUidUpdate(value)
        }
    }
```

Added new function `sendDroneUidUpdate()`:
```kotlin
private fun sendDroneUidUpdate(realDroneUid: String) {
    val msg = JSONObject().apply {
        put("type", "drone_uid_update")
        put("mission_id", missionId)
        put("drone_uid", realDroneUid)
    }
    webSocket.send(msg.toString())
}
```

### Part 2: Django Backend (consumers.py)
Added handler for `drone_uid_update` message type that:
1. Creates a new Vehicle record with the real drone UID (or retrieves existing)
2. Updates the Mission to reference the new Vehicle
3. Updates the session to use the new Vehicle for subsequent telemetry

## Flow
1. Mission starts → WebSocket connects → `session_start` sent with fallback `SITL_DRONE_001`
2. AUTOPILOT_VERSION received from FC → `droneUid` set on SharedViewModel
3. `droneUid` propagated to `wsManager.droneUid` via MainActivity telemetryState collector
4. Custom setter detects change → `sendDroneUidUpdate()` called
5. Backend receives `drone_uid_update` → Updates Vehicle and Mission records
6. All subsequent telemetry saved with correct vehicle ID

## Files Modified
- `WebSocketManager.kt` - Added custom setter and `sendDroneUidUpdate()` function
- `BACKEND_CONSUMERS_WITH_CROP_TYPE.py` - Added `drone_uid_update` message handler

## Backend Deployment
Remember to update the backend `consumers.py` file with the new `drone_uid_update` handler before testing.

## Testing
1. Connect to a real flight controller
2. Start a mission
3. Check logs for:
   - `📤🔥 Sent drone_uid_update: mission=..., droneUid=...`
   - Backend: `✅ Vehicle ID updated: SITL_DRONE_001 → <real_uid>`
4. Verify in database that the mission has the correct vehicle_id

