# Mission Planner-Style Geofence Migration Summary

## Migration Date: February 13, 2026

## Overview

This document summarizes the migration from GCS-based geofence enforcement to ArduPilot's native fence system (Mission Planner approach).

### Key Changes

#### Architecture Change
- **OLD**: GCS enforced geofence at ~5-10Hz by checking GPS position and sending BRAKE/LOITER commands
- **NEW**: FC (Flight Controller) enforces fence autonomously at 400Hz. GCS only uploads and monitors.

#### Benefits of New Approach
1. **Works even if GCS disconnects** - FC has fence in memory
2. **400Hz enforcement** vs ~5Hz from GCS - much faster response
3. **Native ArduPilot FENCE_ACTION** - configurable breach response
4. **Supports multiple zone types** - polygons, circles, exclusion zones
5. **Inner/Outer fence support** - "donut" shaped safe zones

---

## Files Changed

### 1. NEW: `app/src/main/java/com/example/aerogcsclone/fence/FenceTypes.kt`

New data classes for fence configuration:
- `FenceZone` (sealed class) - Polygon, Circle, ReturnPoint
- `FenceConfiguration` - Complete fence setup
- `FenceAction` (enum) - REPORT_ONLY, RTL, HOLD, GUIDED, BRAKE, SMART_RTL
- `FenceStatus` - Runtime fence state from FC

### 2. MODIFIED: `TelemetryRepository.kt`

Added new geofence management functions:
- `uploadGeofence(configuration: FenceConfiguration)` - Upload fence to FC
- `enableFence(enable: Boolean)` - Enable/disable fence
- `downloadGeofence()` - Download existing fence from FC
- `clearGeofenceFromFC()` - Clear fence from FC
- `startFenceMonitoring()` - Monitor SYS_STATUS for breach flags
- `fenceStatus` StateFlow - Runtime fence status

Internal helpers:
- `convertFenceToMissionItems()` - Convert zones to MAVLink items
- `uploadFenceItemsInternal()` - Handle MAVLink mission protocol for FENCE type
- `configureFenceParameters()` - Set FENCE_ACTION, FENCE_MARGIN, etc.
- `setFenceParameter()` - Set individual fence parameters
- `requestFenceItemsFromFcu()` - Download fence items
- `convertMissionItemsToFence()` - Convert MAVLink items back to zones

### 3. MODIFIED: `SharedViewModel.kt`

#### Removed (Old GCS-Based Enforcement):
- `geofenceMonitorJob` - Background monitoring job
- `startGeofenceMonitor()` - High-frequency polling function
- `checkGeofenceNow()` - GPS check function
- `calculateDynamicBuffer()` - Speed-based buffer calculation
- `sendBrakeCommandImmediate()` - Direct brake command
- `sendSingleBrakeCommand()` - Warning zone brake
- `sendPreemptiveBrake()` - Preemptive brake
- `sendEmergencyBrakeAndLoiter()` - Emergency stop
- `startContinuousLoiterEnforcement()` - Continuous enforcement
- `handleReturnToSafeZone()` - Return detection
- All GCS-based monitoring constants (MIN_BUFFER_METERS, MAX_BUFFER_METERS, etc.)

#### Added (New FC-Based System):
- `fenceConfiguration` StateFlow - Current fence config
- `fenceStatus` StateFlow - FC fence status
- `startFenceStatusMonitoring()` - Monitor FC's breach detection
- `getCurrentFenceAction()` - Get action string for display
- `uploadGeofence()` - Upload polygon fence with all options
- `uploadCircularGeofence()` - Upload circular fence
- `downloadGeofence()` - Download from FC
- `setFenceEnabled()` - Enable/disable fence
- `clearGeofenceFromFC()` - Clear fence

---

## Usage Examples

### Upload Polygon Fence (Outer Boundary)
```kotlin
viewModel.uploadGeofence(
    outerBoundary = listOf(
        LatLng(40.7128, -74.0060),
        LatLng(40.7138, -74.0060),
        LatLng(40.7138, -74.0050),
        LatLng(40.7128, -74.0050)
    ),
    action = FenceAction.BRAKE,
    margin = 3.0f,
    altitudeMax = 120f
)
```

### Upload "Donut" Fence (Outer + Inner Boundary)
```kotlin
viewModel.uploadGeofence(
    outerBoundary = outerPolygon,      // Inclusion - must stay inside
    innerBoundary = buildingPolygon,   // Exclusion - must stay outside
    action = FenceAction.BRAKE
)
```

### Upload Circular Fence
```kotlin
viewModel.uploadCircularGeofence(
    center = homeLocation,
    radiusMeters = 200f,
    isInclusion = true,  // Must stay inside
    action = FenceAction.RTL
)
```

### Multiple Exclusion Zones
```kotlin
viewModel.uploadGeofence(
    outerBoundary = fieldBoundary,
    exclusionZones = listOf(building1, building2, trees),
    action = FenceAction.BRAKE
)
```

---

## Fence Actions Reference

| Action | Value | Description |
|--------|-------|-------------|
| REPORT_ONLY | 0 | Just report breach, no action |
| RTL | 1 | Return to launch |
| HOLD | 2 | Hold position (LOITER) |
| GUIDED | 3 | Switch to GUIDED mode |
| BRAKE | 4 | Emergency brake (recommended for spray drones) |
| SMART_RTL | 5 | SmartRTL - retrace path home |

---

## MAVLink Protocol Used

- `MavMissionType.FENCE` - Mission type for fence items
- `MavCmd.NAV_FENCE_POLYGON_VERTEX_INCLUSION` - Polygon inclusion vertex
- `MavCmd.NAV_FENCE_POLYGON_VERTEX_EXCLUSION` - Polygon exclusion vertex
- `MavCmd.NAV_FENCE_CIRCLE_INCLUSION` - Circle inclusion
- `MavCmd.NAV_FENCE_CIRCLE_EXCLUSION` - Circle exclusion
- `MavCmd.NAV_FENCE_RETURN_POINT` - Return point for breaches

### Parameters Set
- `FENCE_ENABLE` - Enable/disable fence (1/0)
- `FENCE_ACTION` - Action on breach (0-5)
- `FENCE_MARGIN` - Safety margin in meters
- `FENCE_TYPE` - Bitfield (1=altitude, 2=circle, 4=polygon)
- `FENCE_ALT_MAX` - Maximum altitude limit
- `FENCE_ALT_MIN` - Minimum altitude limit

---

## Backward Compatibility

The following StateFlows are maintained for backward compatibility with existing UI:
- `geofenceEnabled` - Still works
- `geofencePolygon` - Still used for map display
- `geofenceViolationDetected` - Now mirrors fenceStatus.breached
- `geofenceWarningTriggered` - Still works for UI warnings
- `isGeofenceTriggeringModeChange` - Still works for popup prevention

---

## Testing Checklist

- [ ] Upload polygon fence and verify FC stores it
- [ ] Test breach detection - FC should handle automatically
- [ ] Verify BRAKE action stops drone immediately
- [ ] Test RTL action returns drone to home
- [ ] Test altitude fence limits
- [ ] Test exclusion zones (inner boundaries)
- [ ] Verify fence persists after GCS disconnect
- [ ] Download existing fence from FC
- [ ] Clear fence from FC
- [ ] Enable/disable fence toggle

---

## Notes

1. The fence is stored in FC memory and persists even if GCS disconnects
2. FC monitors fence at 400Hz - much faster than any GCS-based solution
3. BRAKE action (4) is recommended for spray drones - stops immediately
4. The UI still shows the fence polygon for visual reference
5. Breach notifications come from FC's SYS_STATUS messages

