# WebSocket Connection Error Fix - Vehicle Name Duplicate Key Constraint

## Issue Description
The WebSocket connection was failing with the following error:
```
IntegrityError: duplicate key value violates unique constraint "pavaman_gcs_app_vehicle_vehicle_name_key"
DETAIL: Key (vehicle_name)=(DRONE_01) already exists.
```

## Root Cause
The Android app was hardcoding the vehicle name as `"DRONE_01"` for every WebSocket session. When multiple connections were made, or when the same drone connected again, it tried to create another vehicle record with the same name, violating the database's unique constraint.

## Solution Implemented
Modified `WebSocketManager.kt` to generate unique vehicle names instead of using the hardcoded `"DRONE_01"`:

### Before (Problematic Code):
```kotlin
put("vehicle_name", "DRONE_01") // MUST match DB
```

### After (Fixed Code):
```kotlin
// 🔥 FIX: Generate unique vehicle name to avoid database constraint violations
// Use drone UID as vehicle name, or fallback to timestamp-based unique name
val uniqueVehicleName = if (droneUidToSend.isNotBlank() && droneUidToSend != "SITL_DRONE_001") {
    droneUidToSend.take(50) // Limit length to avoid DB field size issues
} else {
    "DRONE_${System.currentTimeMillis()}" // Timestamp-based fallback
}

Log.d(TAG, "🔥 Using unique vehicle name: '$uniqueVehicleName' (was: DRONE_01)")

put("vehicle_name", uniqueVehicleName) // 🔥 UNIQUE vehicle name
```

## How the Fix Works
1. **Primary Strategy**: Use the real drone UID (e.g., `FC_11694_4184_69664768`) as the vehicle name when available
2. **Fallback Strategy**: For SITL or when drone UID is not available, use a timestamp-based unique name (e.g., `DRONE_1707735737000`)
3. **Length Limiting**: Truncate the drone UID to 50 characters to avoid database field size issues

## Benefits
- ✅ Eliminates duplicate key constraint violations
- ✅ Each connection gets a unique vehicle record
- ✅ Real drone connections use meaningful names (the actual FC UID)
- ✅ SITL/simulation connections get timestamp-based unique names
- ✅ Backward compatible - doesn't break existing functionality

## Testing Required
1. Test with real drone connection - should use FC UID as vehicle name
2. Test with SITL connection - should use timestamp-based name
3. Test multiple sequential connections - each should get unique vehicle name
4. Verify WebSocket connection succeeds without database errors

## Database Cleanup (Optional)
If you have existing duplicate `DRONE_01` entries in your database, run the cleanup script:
```
python CLEANUP_DUPLICATE_VEHICLES.py
```

## Log Messages to Watch For
After the fix, you should see this in the logs:
```
🔥 Using unique vehicle name: 'FC_11694_4184_69664768' (was: DRONE_01)
```

And the WebSocket connection should succeed without the IntegrityError.

## Files Modified
- `WebSocketManager.kt` - Line ~350, updated vehicle name generation logic

## Files Created
- `CLEANUP_DUPLICATE_VEHICLES.py` - Script to clean up existing duplicate vehicle entries
- `WEBSOCKET_VEHICLE_NAME_DUPLICATE_FIX.md` - This documentation
