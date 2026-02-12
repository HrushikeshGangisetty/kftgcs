# Geofence Clear and Upload Fix - Complete Implementation

## Issue Identified
The geofence state wasn't being cleared properly when setting a new geofence. The Flight Controller was retaining old fence points, causing the drone to stop at old fence locations even after setting a new geofence boundary.

## Root Cause Analysis
1. **App was only sending `FENCE_ENABLE=1`** - This enabled the fence but didn't upload actual fence points
2. **No fence point upload** - The geofence polygon was only stored in the app, never sent to Flight Controller
3. **No fence clearing** - Old fence points remained in FC memory when new fence was set
4. **FC used stale data** - Flight Controller enforced whatever fence was previously configured

## Solution Implementation

### 1. SharedViewModel.kt - Added Comprehensive Geofence Upload

#### New Function: `uploadGeofenceToFC(polygon: List<LatLng>)`
**Location**: Line ~1765 (after validatePolygonContainsPoints)

```kotlin
private suspend fun uploadGeofenceToFC(polygon: List<LatLng>) {
    // STEP 1: Clear existing fence (FENCE_TOTAL=0)
    setParameter("FENCE_TOTAL", 0f, timeoutMs = 5000L)
    
    // STEP 2: Create fence mission items
    val fenceItems = polygon.mapIndexed { index, point ->
        MissionItemInt(
            command = MavCmd.NAV_FENCE_POLYGON_VERTEX_INCLUSION,
            missionType = MavMissionType.FENCE,
            x = (point.latitude * 1e7).toInt(),
            y = (point.longitude * 1e7).toInt(),
            // ... other parameters
        )
    }
    
    // STEP 3: Upload fence items to FC
    val success = repo?.uploadFenceItems(fenceItems) ?: false
    
    // STEP 4: Set FENCE_TOTAL to number of points
    if (success) {
        setParameter("FENCE_TOTAL", polygon.size.toFloat())
    }
}
```

#### Integration Points
- **`updateGeofencePolygon()`**: Automatically uploads when geofence is generated
- **`updateGeofencePolygonManually()`**: Uploads when user drags geofence points
- **Both scenarios**: Ensures FC always has current fence data

### 2. TelemetryRepository.kt - Added Fence Upload Infrastructure

#### New Function: `uploadFenceItems(fenceItems: List<MissionItemInt>)`
**Location**: Line ~2165 (after uploadMissionWithAck)

```kotlin
suspend fun uploadFenceItems(fenceItems: List<MissionItemInt>): Boolean {
    // Step 1: Send mission count with FENCE mission type
    val missionCount = MissionCount(
        count = fenceItems.size.toUShort(),
        missionType = MavMissionType.FENCE
    )
    connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, missionCount)
    
    // Step 2: Handle mission requests and send fence items
    // Step 3: Wait for mission acknowledgment
    // Returns true if successful, false otherwise
}
```

## MAVLink Protocol Implementation

### Fence Setup Sequence
1. **`MISSION_COUNT`** with `mission_type=FENCE` - Tell FC how many fence points to expect
2. **Wait for `MISSION_REQUEST`** - FC requests each fence point by sequence number
3. **Send `MISSION_ITEM_INT`** - Send each fence point as mission item with fence type
4. **Wait for `MISSION_ACK`** - FC confirms fence upload completion
5. **Set `FENCE_TOTAL` parameter** - Set total number of active fence points
6. **Set `FENCE_ENABLE=1`** - Activate the fence system

### Fence Clearing Sequence
1. **Set `FENCE_TOTAL=0`** - Clear all existing fence points from FC memory
2. **Upload new fence points** - Follow the setup sequence above

## Key Features

### Automatic Fence Upload
- **On geofence enable**: Uploads initial fence based on waypoints/polygon
- **On polygon update**: Re-uploads when fence shape changes
- **On manual adjustment**: Uploads when user drags fence points

### Comprehensive Error Handling
- **Timeout protection**: 30-second timeout for fence upload operations
- **Validation checks**: Ensures FCU is connected and fence items are valid
- **User feedback**: Notifications for success/failure of fence operations
- **Fallback handling**: Graceful failure if FC doesn't respond

### Smart Notifications
```kotlin
// Success notification
"✅ Geofence uploaded to FC (X points)"

// Error notifications  
"❌ Failed to upload geofence to FC"
"❌ Geofence upload error: [details]"
```

## Protocol Details

### Fence Mission Item Structure
```kotlin
MissionItemInt(
    targetSystem = 1u,
    targetComponent = 1u,
    seq = index.toUShort(),                    // Point sequence number
    frame = MavFrame.GLOBAL,                   // Global coordinate frame
    command = MavCmd.NAV_FENCE_POLYGON_VERTEX_INCLUSION,  // Fence vertex command
    current = if (index == 0) 1u else 0u,     // First point is "current"
    autocontinue = 1u,
    param1 = polygon.size.toFloat(),           // Total vertices (in first point)
    x = (point.latitude * 1e7).toInt(),       // Latitude * 1e7
    y = (point.longitude * 1e7).toInt(),      // Longitude * 1e7
    z = 0f,                                    // Altitude (not used for polygon fence)
    missionType = MavMissionType.FENCE         // FENCE mission type
)
```

### Parameter Management
- **`FENCE_TOTAL`**: Total number of fence points in FC memory
- **`FENCE_ENABLE`**: Enable/disable fence system (0=off, 1=on)

## Testing Procedures

### Before Fix Testing (Reproduce Issue)
1. Set initial geofence polygon
2. Enable geofence, fly mission
3. Change geofence to different area  
4. **Expected Issue**: Drone stops at old fence locations
5. **Root Cause**: FC still has old fence points

### After Fix Testing (Verify Solution)
1. Set initial geofence polygon
2. **Verify**: Check logs for "✅ Geofence uploaded to FC" 
3. Enable geofence, test boundaries
4. Change geofence to different area
5. **Verify**: Check logs for fence clearing and new upload
6. **Expected**: Drone respects new fence boundaries only

### Log Monitoring
```
🔥 Starting geofence upload to FC: X points
📤 Clearing existing fence points (FENCE_TOTAL=0)
✅ Existing fence cleared successfully
📤 Uploading X fence points to FC
✅ Fence points uploaded successfully  
📤 Setting FENCE_TOTAL=X
✅ FENCE_TOTAL=X confirmed
✅ Geofence uploaded to FC (X points)
```

## Error Scenarios Handled

### FCU Not Connected
- **Detection**: `!state.value.fcuDetected`
- **Action**: Skip fence upload, log error
- **User feedback**: No fence upload notification

### Upload Timeout  
- **Detection**: 30-second timeout on fence upload
- **Action**: Cancel operation, return false
- **User feedback**: "❌ Fence upload timeout" notification

### FC Rejection
- **Detection**: `MISSION_ACK` with error result
- **Action**: Log specific error, return false
- **User feedback**: "❌ Failed to upload geofence to FC"

### Invalid Parameters
- **Detection**: Invalid coordinates, insufficient points
- **Action**: Skip upload, log validation error
- **User feedback**: Error notification with details

## Integration Points

### UI Integration
- **GcsMap.kt**: Continues to display fence visualization
- **No UI changes needed**: Fence upload happens transparently
- **Status indicators**: Users see success/error notifications

### Existing Geofence System
- **Monitoring continues**: Existing fence breach detection unchanged  
- **Enhanced enforcement**: FC now has correct fence boundaries
- **Improved reliability**: No more stale fence data issues

## Performance Considerations

### Minimal Overhead
- **Upload only when needed**: Not on every geofence check
- **Efficient protocol**: Uses existing MAVLink infrastructure  
- **Background operation**: Non-blocking fence upload

### Resource Usage
- **Memory**: Small temporary fence item list during upload
- **Network**: ~100 bytes per fence point + protocol overhead
- **CPU**: Minimal - leverages existing mission upload code

The implementation ensures that whenever a new geofence is set, the Flight Controller receives the correct fence points, eliminating the issue of drones stopping at old fence locations.
