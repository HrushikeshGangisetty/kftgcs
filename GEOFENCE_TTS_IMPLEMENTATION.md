# Geofence TTS Notifications Implementation

## Overview
Added Text-to-Speech (TTS) announcements for geofence events to provide immediate audio feedback to pilots when geofence boundaries are approached or breached.

## Implementation Details

### File Modified
**`SharedViewModel.kt`** - Geofence monitoring and notification system

### Features Added

#### 1. Geofence Warning TTS
**Trigger**: When drone is inside geofence but approaching the boundary (within buffer zone)
**Location**: Line ~3563 (after warning notification is added)
**Message**: `"Geofence Breached, brake enabled"`
**Behavior**: 
- Plays once per warning event (prevents spam)
- Only triggers when `_geofenceWarningTriggered` changes to `true`
- Accompanies the notification panel warning: "⚠️ Warning: Approaching geofence boundary"

#### 2. Critical Geofence Breach TTS  
**Trigger**: When drone is confirmed to be outside the geofence boundary
**Location**: Line ~3524 (when emergency actions are initiated)
**Message**: `"Critical geofence breach! Emergency RTL activated!"`
**Behavior**:
- Plays once when hard breach is first confirmed
- Only triggers on `geofenceActionTaken = false` → `true` transition
- Accompanies emergency brake and RTL activation

## Code Changes

### Warning TTS Addition
```kotlin
// Only notify once per warning event
addNotification(
    Notification(
        message = "⚠️ Warning: Approaching geofence boundary",
        type = NotificationType.WARNING
    )
)

// 🔊 TTS Announcement for geofence warning
speak("Geofence Breached, brake enabled")
```

### Critical Breach TTS Addition  
```kotlin
Log.e("Geofence", "🚨🚨🚨 CONFIRMED BREACH! Outside fence - EMERGENCY BRAKE + RTL! 🚨🚨🚨")
Log.e("Geofence", "Position: $droneLat, $droneLon")

// 🔊 TTS Announcement for critical geofence violation
speak("Critical geofence breach! Emergency RTL activated!")

// Send BRAKE then RTL - this will set rtlInitiated = true
sendEmergencyBrakeAndRTLBurst()
```

## Geofence Event Flow

### Scenario 1: Approaching Boundary (Warning)
1. **Drone position**: Inside fence, within warning buffer
2. **Visual**: Notification panel shows "⚠️ Warning: Approaching geofence boundary"  
3. **Audio**: TTS plays "Geofence Breached, brake enabled"
4. **Action**: UI warning indicators activate (yellow borders, etc.)
5. **Pilot response**: Manual corrective action expected

### Scenario 2: Outside Boundary (Critical Breach)
1. **Drone position**: Outside fence boundary (confirmed over multiple samples)
2. **Visual**: Critical notifications and UI indicators
3. **Audio**: TTS plays "Critical geofence breach! Emergency RTL activated!"
4. **Action**: Automatic emergency brake → RTL sequence initiated
5. **Pilot response**: Monitor RTL execution, prepare for manual takeover if needed

## TTS Integration

### Existing TTS System
Uses the existing `speak()` function in SharedViewModel:
```kotlin
fun speak(text: String) {
    ttsManager?.speak(text)
}
```

### Benefits
- **Immediate audio feedback** - pilots get instant notification even when not looking at screen
- **Hands-free awareness** - critical for safety during flight operations  
- **Clear messaging** - distinct messages for warning vs critical situations
- **Non-intrusive** - uses existing TTS system, no additional audio conflicts

## Safety Considerations

### Warning Phase
- **Message clarity**: "Geofence Breached, brake enabled" - clear, concise, actionable
- **Single announcement**: Prevents audio spam during boundary oscillation
- **Pilot awareness**: Gives time for manual correction before automatic intervention

### Critical Phase  
- **Urgency conveyed**: "Critical geofence breach! Emergency RTL activated!"
- **Status update**: Confirms automatic safety systems are engaged
- **Pilot preparation**: Alerts pilot to monitor RTL progress and prepare for manual override

## Testing Guidelines

### Warning TTS Test
1. Set up geofence boundary
2. Fly drone toward boundary (stay inside)
3. **Expected**: When approaching buffer zone
   - Notification appears in panel
   - TTS announces: "Geofence Breached, brake enabled"
   - No automatic flight mode changes

### Critical Breach TTS Test  
1. Set up geofence boundary
2. Fly drone outside boundary
3. **Expected**: After breach confirmation
   - Critical notifications appear
   - TTS announces: "Critical geofence breach! Emergency RTL activated!"
   - Drone automatically brakes and initiates RTL

### Repeatability Test
1. Trigger warning, return to safe zone
2. Trigger warning again
3. **Expected**: TTS plays again (geofence system rearms)

## Dependencies
- **Existing TTS Manager**: Uses SharedViewModel's `ttsManager?.speak()`
- **Geofence Logic**: Integrates with existing geofence monitoring system
- **Notification System**: Works alongside visual notification panel

## User Experience
- **Clear audio cues** for different severity levels
- **Immediate feedback** without requiring visual attention
- **Consistent messaging** with existing notification text
- **No interference** with existing flight operations

The implementation provides critical safety feedback through audio announcements while maintaining integration with the existing geofence monitoring and TTS systems.
