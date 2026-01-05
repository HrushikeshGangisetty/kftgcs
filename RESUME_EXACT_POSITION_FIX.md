# Resume Mission - Exact Position Fix

## Issue
When the drone was paused between waypoints (e.g., between waypoint 3 and 4), it would resume from the **end of the line segment** (waypoint 4) instead of resuming from its **exact GPS position** where it was paused.

### Example Scenario (Before Fix)
- Drone is flying from waypoint 3 to waypoint 4
- User pauses the drone when it's at the **middle** of the line segment
- On resume, drone goes directly to waypoint 4 (skipping the paused position)

### Expected Behavior (After Fix)
- Drone resumes from its exact paused GPS position
- Then continues to waypoint 4, then 5, etc.

## Solution
When filtering waypoints for resume, we now **insert the drone's paused GPS location as the first navigation waypoint** after HOME. This ensures the drone first goes to (or acknowledges) its paused position, then continues to the target waypoint and beyond.

### New Mission Structure for Resume
```
Original Mission:
  0: HOME
  1: TAKEOFF
  2: WP1
  3: WP2
  4: WP3 ← (drone was heading here when paused)
  5: WP4
  6: RTL

Resume Mission (paused between WP2 and WP3):
  0: HOME
  1: RESUME_LOCATION ← (inserted - drone's exact GPS position when paused)
  2: WP3 ← (original target waypoint)
  3: WP4
  4: RTL
```

## Changes Made

### 1. TelemetryRepository.kt - `filterWaypointsForResume()`

Updated function signature to accept resume GPS coordinates:

```kotlin
suspend fun filterWaypointsForResume(
    allWaypoints: List<MissionItemInt>,
    resumeWaypointSeq: Int,
    resumeLatitude: Double? = null,    // NEW - paused latitude
    resumeLongitude: Double? = null,   // NEW - paused longitude
    resumeAltitude: Float? = null      // NEW - optional altitude override
): List<MissionItemInt>
```

The function now:
1. Keeps HOME (seq 0)
2. **Inserts a new waypoint at the paused GPS location** right after HOME
3. Keeps the target waypoint and all subsequent waypoints
4. Skips all waypoints before the target waypoint

### 2. SharedViewModel.kt - Multiple Functions Updated

Updated the following functions to pass the resume location:

#### `processResumePoint()`
```kotlin
val resumeLocation = _resumePointLocation.value
val filtered = repo?.filterWaypointsForResume(
    allWaypoints, 
    waypointNumber,
    resumeLatitude = resumeLocation?.latitude,
    resumeLongitude = resumeLocation?.longitude
)
```

#### `confirmAddResumeHere()`
```kotlin
val resumeLocation = _resumePointLocation.value
val filtered = repo?.filterWaypointsForResume(
    allWaypoints, 
    resumeWaypoint,
    resumeLatitude = resumeLocation?.latitude,
    resumeLongitude = resumeLocation?.longitude
)
```

#### `resumeMissionComplete()`
```kotlin
val resumeLocation = _resumePointLocation.value
val filtered = repo?.filterWaypointsForResume(
    allWaypoints, 
    resumeWaypointNumber,
    resumeLatitude = resumeLocation?.latitude,
    resumeLongitude = resumeLocation?.longitude
)
```

## How Resume Location is Captured

The resume location is captured when the drone pauses (AUTO → LOITER mode change):

1. `TelemetryRepository` detects mode change from AUTO to LOITER
2. It calls `sharedViewModel.onModeChangedToLoiterFromAuto(waypointNumber)`
3. `SharedViewModel.onModeChangedToLoiterFromAuto()` captures the drone's current GPS:
   ```kotlin
   val currentLat = _telemetryState.value.latitude
   val currentLon = _telemetryState.value.longitude
   _pendingResumeLocation = LatLng(currentLat, currentLon)
   ```
4. When user confirms the resume popup, the location is set:
   ```kotlin
   _resumePointLocation.value = _pendingResumeLocation
   ```
5. This location is then used when filtering waypoints for resume

## Testing

1. **Upload a mission** with multiple waypoints
2. **Start AUTO mode** and let the drone fly
3. **Pause the drone** (switch to LOITER) while it's between two waypoints
4. **Confirm the resume popup** ("Add Resume Here")
5. **Resume the mission** (switch to AUTO)
6. **Verify** the drone:
   - First acknowledges/passes through its paused position
   - Then continues to the original target waypoint
   - Then completes the rest of the mission

## Log Messages

Look for these log messages to verify the fix is working:

```
ResumeMission: ═══ Filtering Mission for Resume (Mid-Flight) ═══
ResumeMission: Resume location: lat=XX.XXXXX, lon=YY.YYYYY, alt=null
ResumeMission: ✅ INSERTING Resume Location WP: lat=XX.XXXXX, lon=YY.YYYYY, alt=ZZ.Z
```

## Files Modified
- `app/src/main/java/com/example/aerogcsclone/telemetry/TelemetryRepository.kt`
- `app/src/main/java/com/example/aerogcsclone/telemetry/SharedViewModel.kt`
- `app/src/main/java/com/example/aerogcsclone/uiflyingmethod/SelectFlyingMethodScreen.kt`

---

# Mission Data Preservation During Disconnect (Battery Change)

## Issue
When the drone mission was paused and the user disconnected to change batteries, the mission data (grid lines, waypoints, resume point) would be cleared when returning to the SelectFlyingMethodScreen.

## Solution
Modified the app to preserve mission data when the mission is in a paused state:

### 1. SelectFlyingMethodScreen.kt
Added check to NOT clear mission data when mission is paused:

```kotlin
LaunchedEffect(Unit) {
    val missionPaused = telemetryState.missionPaused
    val hasResumePoint = resumePointLocation != null
    
    if (missionPaused || hasResumePoint) {
        Log.i("SelectFlyingMethod", "Mission is paused or has resume point - NOT clearing mission data")
    } else {
        sharedViewModel.clearMissionFromMap()
    }
}
```

### 2. SharedViewModel.kt - `cancelConnection()`
Modified to preserve mission pause state when disconnecting:

```kotlin
suspend fun cancelConnection() {
    // ... close connection ...
    
    // Preserve mission pause state when disconnecting (e.g., for battery change)
    val currentState = _telemetryState.value
    val wasPaused = currentState.missionPaused
    val pausedAtWp = currentState.pausedAtWaypoint
    
    if (wasPaused) {
        Log.i("SharedVM", "Preserving mission pause state during disconnect")
        _telemetryState.value = TelemetryState(
            missionPaused = true,
            pausedAtWaypoint = pausedAtWp
        )
    } else {
        _telemetryState.value = TelemetryState()
    }
}
```

## What Gets Preserved
When disconnecting while mission is paused:
- ✅ Grid lines on map (`_gridLines`)
- ✅ Survey polygon (`_surveyPolygon`)
- ✅ Uploaded waypoints (`_uploadedWaypoints`)
- ✅ Resume point location (R marker) (`_resumePointLocation`)
- ✅ Mission paused state (`missionPaused`)
- ✅ Paused waypoint number (`pausedAtWaypoint`)

## Testing Battery Change Scenario
1. Upload a mission and start AUTO mode
2. Pause the mission (switch to LOITER)
3. Confirm the "Add Resume Here" popup
4. Go to Connection tab and disconnect
5. Change batteries on the drone
6. Reconnect to the drone
7. Verify:
   - Grid lines are still visible on map
   - Resume point (R marker) is still visible
   - Resume button is available
8. Resume the mission

