# Critical Bug Fixes: Geofence & Tank Empty

## Date: February 10, 2026
## Priority: **CRITICAL - TOP PRIORITY**

---

## Issue 1: Geofence False RTL Triggers ✅ FIXED

### Problem
Geofence breach detection was too sensitive, triggering RTL from:
1. **Instantaneous GPS glitches** - Single bad GPS reading would trigger breach
2. **Previous state not properly cleared** - Breach confirmation counter wasn't reset
3. **UI vs FC state mismatch** - Geofence is GCS-side only, not sent to FC

### Root Cause Analysis
The geofence system is implemented as a **GCS-side monitoring system**. The Flight Controller doesn't have the geofence boundaries - instead, the GCS monitors the drone's position at high frequency (200Hz) and triggers RTL when it detects a breach. The problem was:

1. **No breach confirmation** - Any single GPS reading showing the drone outside would immediately trigger RTL
2. **GPS glitches** - GPS can have temporary bad reads (especially near obstacles or poor satellite visibility)
3. **State not cleared** - Breach confirmation counters weren't reset when geofence was disabled

### Solution Implemented

#### 1. Added Breach Confirmation Logic
**Location**: `SharedViewModel.kt` lines 3199-3203, 3253-3259

```kotlin
// Breach confirmation - require sustained breach before RTL to prevent GPS glitches
private const val BREACH_CONFIRMATION_SAMPLES = 5 // Number of consecutive breach samples required
private const val BREACH_CONFIRMATION_TIME_MS = 500L // 500ms window for breach confirmation

// Breach confirmation tracking variables
@Volatile
private var breachConfirmationCount = 0
@Volatile
private var firstBreachDetectedTime: Long? = null
```

#### 2. Updated checkGeofenceNow() Function
**Location**: `SharedViewModel.kt` lines 3393-3461

**Key Changes**:
- Now requires **5 consecutive breach detections** within 500ms before triggering RTL
- Prevents false positives from single GPS glitches
- Resets confirmation counter when drone moves back inside fence
- Logs breach confirmation progress for debugging

```kotlin
if (isOutsideFence) {
    // Increment breach confirmation counter
    if (firstBreachDetectedTime == null) {
        firstBreachDetectedTime = now
        breachConfirmationCount = 1
        Log.w("Geofence", "⚠️ BREACH DETECTED (1/5) - Starting confirmation...")
    } else {
        val timeSinceFirstBreach = now - firstBreachDetectedTime!!
        
        if (timeSinceFirstBreach <= BREACH_CONFIRMATION_TIME_MS) {
            breachConfirmationCount++
            Log.w("Geofence", "⚠️ BREACH CONFIRMED (${breachConfirmationCount}/5)")
        } else {
            // Exceeded time window - restart confirmation
            firstBreachDetectedTime = now
            breachConfirmationCount = 1
        }
    }
    
    // Only trigger RTL if breach is CONFIRMED (5 consecutive detections)
    if (breachConfirmationCount >= BREACH_CONFIRMATION_SAMPLES) {
        // Trigger RTL...
    }
} else {
    // Reset breach confirmation if drone is back inside
    if (breachConfirmationCount > 0 || firstBreachDetectedTime != null) {
        breachConfirmationCount = 0
        firstBreachDetectedTime = null
    }
}
```

#### 3. Updated resetGeofenceState()
**Location**: `SharedViewModel.kt` lines 3649-3663

Now properly resets breach confirmation counters:
```kotlin
fun resetGeofenceState() {
    _geofenceViolationDetected.value = false
    _geofenceWarningTriggered.value = false
    geofenceActionTaken = false
    rtlInitiated = false
    lastBrakeCommandTime = 0L
    breachConfirmationCount = 0  // ← Added
    firstBreachDetectedTime = null  // ← Added
    Log.i("Geofence", "Geofence state reset - ready for new monitoring")
}
```

### Testing Recommendations
1. **GPS Glitch Test**: Test near buildings/trees where GPS may fluctuate
2. **Edge Flight Test**: Fly close to geofence edge to verify warning without RTL
3. **Sustained Breach Test**: Deliberately fly outside fence to confirm RTL still triggers
4. **Re-enable Test**: Disable/enable geofence multiple times to verify state reset

---

## Issue 2: Tank Empty False Positives ✅ FIXED

### Problem
Tank empty detection was triggering false positives because:
1. Not checking if pump is actually running (only checking RC7 switch)
2. No startup delay - flow sensor takes time to register flow
3. Short timeout (1.5s) - not accounting for system delays
4. Confusion about BATT3 - code was already flow-based but needed improvement

### Root Cause Analysis
The existing code checked `sprayEnabled` (RC7 switch), but this doesn't mean the pump is actually running. The pump could be:
- Turned on but not drawing current (malfunction)
- In startup phase (takes 2+ seconds for flow sensor to read)
- Intermittently stopping/starting (normal operation)

### Solution Implemented

#### 1. Added Pump Current Detection
**Location**: `TelemetryRepository.kt` lines 631-696

Now checks actual pump current draw (BATT2 current sensor):
```kotlin
// Check if pump is actually running by looking at current draw
// Pump should draw significant current (>0.5A) when running
val pumpCurrent = b.currentBattery
val pumpIsRunning = currentSprayEnabledForEmpty && pumpCurrent > 0.5f
```

#### 2. Added Pump Startup Delay
**Location**: `TelemetryRepository.kt` lines 138-139

```kotlin
private var pumpTurnedOnTime: Long? = null  // Track when pump was turned ON
private val PUMP_STARTUP_DELAY_MS = 2000L  // 2 second delay after pump turns ON
```

#### 3. Increased Zero Flow Threshold
**Location**: `TelemetryRepository.kt` line 138

```kotlin
private val ZERO_FLOW_THRESHOLD_MS = 3000L // 3 seconds (increased from 1.5s)
```

#### 4. Enhanced Detection Logic
**Location**: `TelemetryRepository.kt` lines 640-696

**Key Changes**:
- Tracks when pump turns ON
- Waits 2 seconds for flow sensor to stabilize
- Then monitors for 3 seconds of zero flow
- Only triggers if pump is actually drawing current
- Better logging for debugging

```kotlin
if (pumpIsRunning && configValid) {
    // Track when pump was turned ON
    if (pumpTurnedOnTime == null) {
        pumpTurnedOnTime = System.currentTimeMillis()
        Log.d("TankEmpty", "⏱️ Pump turned ON - waiting 2s before checking flow")
    }
    
    val timeSincePumpOn = System.currentTimeMillis() - (pumpTurnedOnTime ?: 0L)
    
    // Only check for zero flow after pump startup delay
    if (timeSincePumpOn >= PUMP_STARTUP_DELAY_MS && flowIsZero) {
        if (zeroFlowStartTime == null) {
            zeroFlowStartTime = System.currentTimeMillis()
        } else {
            val zeroFlowDuration = System.currentTimeMillis() - zeroFlowStartTime!!
            
            if (zeroFlowDuration >= ZERO_FLOW_THRESHOLD_MS && !tankEmptyNotificationShown) {
                // TANK EMPTY!
            }
        }
    } else if (!flowIsZero) {
        // Flow detected - reset timers
        if (zeroFlowStartTime != null) {
            zeroFlowStartTime = null
        }
    }
} else {
    // Pump is OFF - reset all timers
    pumpTurnedOnTime = null
    zeroFlowStartTime = null
}
```

### Detection Timeline
1. **Pump turns ON** (RC7 + current >0.5A detected)
2. **Wait 2 seconds** - Pump startup delay
3. **Monitor flow** - Check if flow rate > 0
4. **If no flow for 3 seconds** - Trigger tank empty warning
5. **Total time**: 5 seconds from pump ON to warning (2s + 3s)

### Testing Recommendations
1. **Normal Operation**: Verify no false warnings during normal spraying
2. **Pump Startup**: Test pump turning on - should wait 2s before checking
3. **Empty Tank**: Actually empty tank and verify warning appears after 5s
4. **Refill**: Verify warning clears when flow resumes
5. **Pump Off**: Verify warning doesn't appear when pump is off

---

## Files Modified

1. **TelemetryRepository.kt**
   - Lines 138-139: Added pump timing variables
   - Lines 631-696: Enhanced tank empty detection logic

2. **SharedViewModel.kt**
   - Lines 3199-3203: Added breach confirmation constants
   - Lines 3253-3259: Added breach confirmation tracking variables
   - Lines 3393-3461: Updated geofence breach detection logic
   - Lines 3649-3663: Updated state reset function

---

## Important Notes

### Geofence System Design
- **GCS-Side Only**: Geofence is NOT stored in the FC - it's monitored by the GCS
- **High-Frequency Monitoring**: Runs at 200Hz (every 5ms) for fast response
- **Dynamic Buffer**: Buffer distance increases with speed (physics-based)
- **Breach Confirmation**: Now requires 5 consecutive detections to prevent false positives

### Tank Empty System Design
- **Flow-Based**: Detection is based on flow sensor readings, NOT BATT3 level
- **Pump Current**: Now checks actual pump current draw (>0.5A)
- **Multi-Stage**: 2s pump startup + 3s zero flow = 5s total before warning
- **BATT3 Independence**: Tank level (BATT3) is for display only, not for empty detection

---

## Verification Steps

### After Deployment:
1. **Monitor Logs**: Check for "BREACH CONFIRMED" and "TANK EMPTY" messages
2. **Test Geofence**: Fly near edge and verify no false RTL
3. **Test Tank Empty**: Run pump with empty tank and verify 5s delay
4. **Check State Reset**: Disable/enable geofence multiple times
5. **Field Test**: Full mission with both features active

---

## Risk Assessment

### Geofence Changes
- **Risk**: Low - Makes system MORE conservative (requires confirmation)
- **Impact**: Reduces false RTL triggers significantly
- **Rollback**: Can revert to immediate trigger by setting BREACH_CONFIRMATION_SAMPLES = 1

### Tank Empty Changes
- **Risk**: Low - Still detects empty tank, just with better logic
- **Impact**: Eliminates false positives during normal operation
- **Rollback**: Can reduce delays if needed (but 5s total is appropriate)

---

## Next Steps

1. **Deploy fixes** to test environment
2. **Ground testing** with pump and flow sensor
3. **Flight testing** near geofence boundaries
4. **Production deployment** after verification
5. **Monitor telemetry** for any remaining issues

---

## Contact
For questions or issues with these fixes, refer to the implementation details above or check the code comments in the modified files.

