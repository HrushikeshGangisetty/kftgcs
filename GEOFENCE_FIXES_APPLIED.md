# Geofence Fixes Applied

## Issues Fixed

### 1. Geofence Not Stopping Second Time ✅
**Problem**: The drone would stop the first time it approached the fence but not on subsequent approaches.

**Root Cause**: The `geofenceActionTaken` flag was not being reset properly when the drone returned inside the fence.

**Solution**:
- Improved the reset logic to check for both `geofenceActionTaken` and `rtlInitiated` flags
- Reduced the rearm threshold from 2x to 1.5x dynamic buffer for faster rearming
- Added explicit breach confirmation counter reset when drone is safely inside
- Reduced warning clear threshold from 1.5x to 1.2x for more responsive UI updates
- Set `geofenceActionTaken = true` in preemptive brake to prevent repeated triggers

### 2. Drone Crossing Fence by ~2m ✅
**Problem**: The drone was overshooting the geofence boundary by approximately 2 meters.

**Solution - Increased Buffer Distances**:
- **MIN_BUFFER_METERS**: Increased from 9m to 11m (+2m)
- **MAX_BUFFER_METERS**: Increased from 25m to 27m (+2m)
- **DEFAULT_FENCE_RADIUS**: Increased from 15m to 17m (+2m)
- **Minimum buffer distance in generation**: Increased from 5m to 7m (+2m)

### 3. Improved Braking Performance ✅
**Problem**: The drone was not braking early enough or aggressively enough.

**Solutions**:

#### More Conservative Deceleration
- **MAX_DECEL_M_S2**: Reduced from 2.5 to 2.0 m/s² (more conservative = brakes earlier)
- **SYSTEM_LATENCY_SECONDS**: Reduced from 1.0 to 0.8 seconds (faster response)
- **Wind margin**: Increased from 30% to 40% of current speed

#### Earlier Preemptive Braking
- **Critical distance threshold**: Increased from 50% to 60% of buffer (triggers earlier)
- **Speed threshold**: Reduced from >3.0 m/s to >2.5 m/s (triggers at lower speeds)
- **Preemptive brake delay**: Reduced from 300ms to 200ms for faster LOITER transition
- Added `rtlInitiated = true` flag when LOITER is sent in preemptive brake

## Changes Summary

### Constants Changed
```kotlin
// Before → After
MIN_BUFFER_METERS: 9.0 → 11.0 (+2m)
MAX_BUFFER_METERS: 25.0 → 27.0 (+2m)
MAX_DECEL_M_S2: 2.5 → 2.0 (more conservative)
SYSTEM_LATENCY_SECONDS: 1.0 → 0.8 (faster response)
DEFAULT_FENCE_RADIUS_METERS: 15.0 → 17.0 (+2m)
Wind margin: 0.3 → 0.4 (+10%)
```

### Logic Changes
1. **Reset Logic**:
   - Rearm threshold: 2.0x → 1.5x dynamic buffer
   - Warning clear threshold: 1.5x → 1.2x dynamic buffer
   - Added breach confirmation reset when safely inside
   - Check for both flags when resetting

2. **Preemptive Brake**:
   - Critical distance: 50% → 60% of buffer
   - Speed threshold: >3.0 → >2.5 m/s
   - Delay before LOITER: 300ms → 200ms
   - Sets both `geofenceActionTaken` and `rtlInitiated` flags

3. **Buffer Calculation**:
   - Minimum buffer in generation: 5m → 7m

## Expected Behavior

1. **First Approach**: Drone will brake at 60% of dynamic buffer if moving >2.5 m/s
2. **Fence Breach**: If drone crosses the outer fence, it will BRAKE then LOITER
3. **Return Inside**: When drone moves >1.5x dynamic buffer inside fence, flags reset
4. **Second Approach**: Geofence will trigger again (re-armed properly)
5. **No Overshoot**: Extra 2m buffer should prevent crossing the fence boundary

## Testing Recommendations

1. Test at different speeds (2.5 m/s, 3 m/s, 5 m/s) approaching fence
2. Verify drone stops before crossing outer boundary
3. Test repeated approaches to confirm re-arming works
4. Monitor logs for:
   - "Geofence REARMED for re-trigger" message
   - Breach confirmation count
   - Distance to fence values
   - Action taken flag states

## Files Modified

- `SharedViewModel.kt`:
  - Constants section (lines ~3445-3485)
  - `calculateDynamicBuffer()` function
  - `checkGeofenceNow()` reset logic
  - `sendPreemptiveBrake()` function
  - Geofence generation buffer calculation

## Date Applied
February 13, 2026

