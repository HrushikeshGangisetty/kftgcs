# Geofence and Mission Completion Fixes

## Date: February 13, 2026

## Summary
Fixed two critical issues:
1. **Geofence not stopping drone** - Implemented two-tier geofence system with BRAKE for warning and RTL for breach
2. **Mission completion popup timing** - Fixed to only show on disarm, not on mode change

---

## Issue 1: Geofence Not Stopping Drone

### Problem
- Drone was crossing geofence despite BRAKE commands being sent
- Logs showed spam of BRAKE commands every 30ms (way too frequent)
- RC input from pilot was overriding BRAKE/LOITER commands
- Drone continued moving even with geofence warnings

### Root Cause
1. **RC Override**: BRAKE and LOITER modes can be overridden by RC input in ArduPilot
2. **Command Spam**: Sending BRAKE every 30ms was flooding the flight controller
3. **Single-tier enforcement**: Only using BRAKE/LOITER wasn't strong enough

### Solution: Two-Tier Geofence System

Implemented a dual-zone geofence approach:

#### **INNER WARNING ZONE** (60% of buffer distance)
- Triggers when drone approaches fence at high speed (>2.5 m/s)
- Sends **ONE BRAKE command** to slow down
- Allows pilot to regain control after slowing
- Does NOT force RTL - gives pilot a chance to correct
- Located at `dynamicBuffer * 0.6`

#### **OUTER BREACH ZONE** (outside fence boundary)
- Triggers when drone actually crosses the fence
- Sends **BRAKE then RTL** (Return to Launch)
- RTL mode prevents RC override - pilot CANNOT push through
- Forces drone to return home
- Includes continuous enforcement loop

### Changes Made

#### 1. Reduced Command Spam
**File**: `SharedViewModel.kt`
```kotlin
// OLD: 30ms interval (too aggressive)
private const val BRAKE_COMMAND_INTERVAL_MS = 30L

// NEW: 2000ms interval (reasonable)
private const val BRAKE_COMMAND_INTERVAL_MS = 2000L
```

#### 2. Added Inner Warning Zone Function
**File**: `SharedViewModel.kt`

Added `sendSingleBrakeCommand()` function that:
- Sends BRAKE once (not repeatedly)
- Does NOT follow up with RTL
- Allows pilot to regain control
- Used for inner warning zone only

#### 3. Updated Outer Breach to Use RTL
**File**: `SharedViewModel.kt`

Modified `sendEmergencyBrakeAndLoiter()` to `sendEmergencyBrakeAndRTL()`:
- Still sends BRAKE first to stop immediately
- Then transitions to RTL instead of LOITER
- RTL prevents RC override better than LOITER
- Brings drone back to safe zone automatically

#### 4. Updated Pre-emptive Brake
**File**: `SharedViewModel.kt`

Modified `sendPreemptiveBrake()` to use RTL:
- Sends BRAKE to stop
- Transitions to RTL to return to safe zone
- Better enforcement than LOITER

#### 5. Updated Continuous Enforcement
**File**: `SharedViewModel.kt`

Changed `startContinuousLoiterEnforcement()` to `startContinuousRTLEnforcement()`:
- Sends RTL every 2 seconds (was 500ms)
- Prevents pilot from overriding during breach
- Stops after 2 minutes or when violation cleared

### How It Works

```
Drone Position          Action                  Mode
────────────────────────────────────────────────────────
Inside safe zone        Normal flight           AUTO/Manual
│
├─ 60% of buffer        INNER WARNING           BRAKE (once)
│  (dynamicBuffer*0.6)  High speed warning      └─ Slows down
│                       Sends ONE BRAKE          └─ Pilot can recover
│
├─ Buffer boundary      WARNING                 Continue monitoring
│  (dynamicBuffer)      Yellow UI border
│
└─ OUTSIDE FENCE        OUTER BREACH            BRAKE → RTL
   (distanceToFence=0)  CRITICAL VIOLATION      └─ Forces return
                        Continuous RTL          └─ Prevents RC override
                        enforcement
```

### Dynamic Buffer Calculation

The buffer distance adapts to drone speed and altitude:
- **Stationary**: 9.5m minimum buffer
- **2 m/s**: ~15m buffer
- **3 m/s**: ~18m buffer  
- **5 m/s**: ~27m buffer (capped at max)

Factors included:
- GPS uncertainty (3m)
- System latency distance
- Physics-based stopping distance
- Safety factor (2.0x)
- Wind margin
- High-speed multiplier
- Altitude factor

---

## Issue 2: Mission Completion Popup Showing on Re-arm

### Problem
- Mission completion popup was showing when drone **disarmed then re-armed**
- Should only show **once on disarm**, not again on re-arm
- Was triggering from mode change handler instead of disarm handler

### Root Cause
In `TelemetryRepository.kt`, there were TWO places setting `missionCompleted = true`:
1. **Line ~980**: Mode change from Auto (WRONG - causes re-trigger on re-arm)
2. **Line ~1050**: On disarm (CORRECT - should be only place)

### Solution
Removed `missionCompleted = true` from the mode change handler. Mission completion should ONLY be set on disarm.

### Changes Made

#### Updated Mode Change Handler
**File**: `TelemetryRepository.kt` (Line ~970-1000)

**BEFORE**:
```kotlin
} else {
    // Mode changed from Auto
    if (!isPaused && !state.value.missionCompleted) {
        if ((lastElapsed ?: 0L) > 0L) {
            _state.update { it.copy(
                isMissionActive = false,
                missionElapsedSec = null, 
                missionCompleted = true,  // ❌ WRONG - causes popup on re-arm
                lastMissionElapsedSec = lastElapsed
            )}
            // Send mission summary...
        }
    }
}
```

**AFTER**:
```kotlin
} else {
    // Mode changed from Auto to something else
    // DON'T set missionCompleted here - that should only happen on disarm
    if (!isPaused && !state.value.missionCompleted) {
        if ((lastElapsed ?: 0L) > 0L) {
            _state.update { it.copy(
                isMissionActive = false, 
                missionElapsedSec = null,
                // NO missionCompleted = true here!
                lastMissionElapsedSec = lastElapsed
            )}
            // Send PAUSED status instead
            wsManager.sendMissionStatus(MISSION_STATUS_PAUSED)
        }
    }
}
```

#### Disarm Handler Remains Unchanged
**File**: `TelemetryRepository.kt` (Line ~1040-1080)

The disarm handler correctly sets `missionCompleted = true` and sends mission summary:
```kotlin
} else if (lastArmed == true && !armed) {
    // Drone disarmed - this is the ONLY place missionCompleted should be set
    if ((lastElapsed ?: 0L) > 0L && !isPaused && !alreadyCompleted) {
        _state.update { it.copy(
            isMissionActive = false,
            missionElapsedSec = null,
            missionCompleted = true,  // ✅ CORRECT - only on disarm
            lastMissionElapsedSec = lastElapsed
        )}
        // Send mission summary...
    }
}
```

### How It Works Now

```
Event Sequence              missionCompleted    Popup Shown
──────────────────────────────────────────────────────────────
Mission starts in AUTO      false               No
Mode changes AUTO→LOITER    false               No (paused status)
Mode changes LOITER→MANUAL  false               No
Drone DISARMS              true                 YES ✅
Drone RE-ARMS              true (unchanged)     NO ✅ (already handled)
```

---

## Testing Recommendations

### Test Geofence Two-Tier System
1. Upload a mission with geofence enabled
2. **Test Inner Warning**:
   - Fly drone at 3-4 m/s toward fence
   - At ~60% of buffer distance, should:
     - Get ONE BRAKE command
     - Drone slows down
     - Pilot can regain control
     - No RTL triggered
3. **Test Outer Breach**:
   - Continue flying toward fence
   - If crosses fence boundary:
     - BRAKE sent immediately
     - RTL mode activated
     - Drone returns to launch
     - RC input does NOT override

4. **Verify No Spam**:
   - Check logs - BRAKE commands should be 2 seconds apart
   - No flood of commands every 30ms

### Test Mission Completion Popup
1. Upload and start a mission
2. Let mission complete
3. Drone should auto-disarm or manually disarm
4. **Verify**: Popup shows mission statistics
5. Click OK on popup
6. **Re-arm the drone**
7. **Verify**: Popup does NOT show again

---

## Files Modified

### SharedViewModel.kt
1. Changed `BRAKE_COMMAND_INTERVAL_MS` from 30ms to 2000ms
2. Updated geofence logic to two-tier system
3. Added `sendSingleBrakeCommand()` for inner warning
4. Changed `sendEmergencyBrakeAndLoiter()` to use RTL
5. Changed `sendPreemptiveBrake()` to use RTL
6. Renamed `startContinuousLoiterEnforcement()` to `startContinuousRTLEnforcement()`

### TelemetryRepository.kt
1. Removed `missionCompleted = true` from mode change handler
2. Changed to send PAUSED status instead of ENDED
3. Removed orphaned mission summary code from mode change
4. Kept mission completion logic ONLY in disarm handler

---

## Key Improvements

### Geofence
✅ Two-tier system: warning + breach zones  
✅ Reduced command spam (30ms → 2000ms)  
✅ RTL prevents RC override  
✅ Inner warning allows pilot recovery  
✅ Outer breach forces return home  
✅ Dynamic buffer based on speed  

### Mission Completion
✅ Popup only shows once on disarm  
✅ Does not re-trigger on re-arm  
✅ Proper state management  
✅ Correct mission status sent to backend  

---

## Date
February 13, 2026

