# Pause and Resume Functionality Enhancement

## Overview
This document describes the enhanced pause and resume functionality where:
1. Pause only works when in AUTO mission mode
2. When mode changes from AUTO to LOITER, a popup appears saying "Add Resume Here"
3. **The resume waypoint is captured AUTOMATICALLY** - user just clicks OK (no typing required)
4. When the user clicks OK, the resume point is stored and a modified mission is sent to the FC
5. When the mode changes back to AUTO, the mission automatically starts from the resume point

## Key Feature: Automatic Waypoint Capture
- The current waypoint is **automatically captured** when mode changes from AUTO to LOITER
- The waypoint is taken from `lastAutoWaypoint` (tracked during AUTO mode)
- Falls back to `currentWaypoint` if needed
- **User does NOT need to type any waypoint number** - just click OK to confirm

## Resume Mission Structure (Mid-Flight)
For a mid-flight resume, the mission structure is:
```
Seq 0: HOME (original home position)
Seq 1: Resume waypoint (where drone was paused)
Seq 2: Next waypoint
Seq 3: ...and so on
```

**IMPORTANT**: NO TAKEOFF command is included for mid-flight resume since the drone is already flying!

## Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           PAUSE & RESUME FLOW                                │
└─────────────────────────────────────────────────────────────────────────────┘

1. MISSION RUNNING IN AUTO MODE
   │
   ▼
2. USER CLICKS PAUSE BUTTON (or mode is changed externally)
   │
   ├── pauseMission() is called
   │   └── Sends LOITER mode command to FC
   │
   ▼
3. MODE CHANGES: AUTO → LOITER (detected by TelemetryRepository)
   │
   ├── onModeChangedToLoiterFromAuto(waypointNumber) is called
   │   ├── Shows "Add Resume Here" popup
   │   ├── Sets missionPaused = true
   │   └── Sets pausedAtWaypoint = currentWaypoint
   │
   ▼
4. "ADD RESUME HERE" POPUP SHOWN
   │
   ├── [CANCEL] → Dismiss popup, no action taken
   │
   └── [OK] → confirmAddResumeHere() is called
       │
       ├── Step 1: Check connection to FC
       ├── Step 2: Retrieve current mission from FC (getAllWaypoints)
       ├── Step 3: Filter waypoints (HOME + resume point onwards, NO TAKEOFF)
       ├── Step 4: Resequence waypoints (0, 1, 2, 3...)
       ├── Step 5: Validate sequence numbers
       ├── Step 6: Upload modified mission to FC (uploadMissionWithAck)
       ├── Step 7: Set current waypoint to 1
       └── Step 8: Set resumeMissionReady = true
   │
   ▼
5. WAITING FOR AUTO MODE
   │
   ├── User switches to AUTO mode (via RC or GCS)
   │
   ▼
6. MODE CHANGES: LOITER → AUTO (detected by TelemetryRepository)
   │
   ├── onModeChangedToAuto() is called
   │   ├── Checks if resumeMissionReady == true
   │   ├── Sends mission start command
   │   ├── Clears resumeMissionReady flag
   │   └── Clears missionPaused state
   │
   ▼
7. MISSION RESUMES FROM STORED WAYPOINT
```

## Files Modified

### 1. SharedViewModel.kt
**New State Variables:**
- `_showAddResumeHerePopup` - Controls visibility of "Add Resume Here" popup
- `_resumePointWaypoint` - Stores the waypoint number where mode changed
- `_resumeMissionReady` - Flag indicating modified mission is uploaded and ready

**New Functions:**
- `onModeChangedToLoiterFromAuto(waypointNumber)` - Called when AUTO → LOITER detected
- `confirmAddResumeHere(onProgress, onResult)` - Handles OK button click on popup
- `dismissAddResumeHerePopup()` - Handles Cancel/dismiss of popup
- `onModeChangedToAuto()` - Called when mode changes TO AUTO, starts mission if ready

**Modified Functions:**
- `pauseMission()` - Simplified to only send LOITER command, popup is now triggered by mode change detection

### 2. TelemetryRepository.kt
**Modified Mode Change Detection:**
- Added detection for AUTO → LOITER transition to trigger "Add Resume Here" popup
- Added detection for any mode → AUTO transition to trigger resume mission start

### 3. MainPage.kt
**New State Collections:**
- `showAddResumeHerePopup` - From SharedViewModel
- `resumePointWaypoint` - From SharedViewModel
- `showAddResumeProgressDialog` - Local state for progress dialog
- `addResumeProgressMessage` - Progress message string

**New UI Components:**
- "Add Resume Here" AlertDialog - Shown when mode changes from AUTO to LOITER
- Progress Dialog - Shown while preparing resume mission

## Key Behaviors

### Pause Button
- Only clickable when in AUTO mode
- Sends LOITER mode command
- Does NOT directly show popup (popup is triggered by mode change detection)

### Resume Button (Existing)
- Still available for manual resume with waypoint selection
- Uses the previous multi-step dialog flow
- Independent of the new automatic "Add Resume Here" flow

### Automatic Resume
- When "Add Resume Here" popup is confirmed, mission is uploaded to FC
- User can then switch to AUTO mode via any method (RC, GCS)
- Mission automatically starts from the resume point

## Error Handling
- If mission retrieval fails → Error toast shown
- If mission filtering fails → Error toast shown  
- If mission upload fails → Error toast shown
- If mission start fails → Error notification added

## Logging
All operations are logged with the following tags:
- `SharedVM` - General SharedViewModel operations
- `TelemetryRepo` - Mode change detection
- `ResumeMission` - Resume mission process

## Testing

### Test Case 1: Normal Pause/Resume Flow
1. Upload and start a mission (AUTO mode)
2. Click Pause button
3. Verify "Add Resume Here" popup appears
4. Click OK
5. Verify progress dialog shows
6. Verify "Resume point set" toast
7. Switch to AUTO mode (via RC or UI)
8. Verify mission resumes from correct waypoint

### Test Case 2: Cancel Pause
1. Start mission in AUTO mode
2. Click Pause button
3. Verify popup appears
4. Click Cancel
5. Verify no mission is uploaded
6. User remains in LOITER mode

### Test Case 3: External Mode Change
1. Start mission in AUTO mode
2. Change mode to LOITER via RC transmitter
3. Verify "Add Resume Here" popup appears
4. Click OK and resume

### Test Case 4: Use Manual Resume Button
1. Pause mission
2. Dismiss "Add Resume Here" popup
3. Click Resume button
4. Use manual waypoint selection
5. Verify mission resumes correctly

## Troubleshooting

### Issue: "Failed to clear mission after 2 attempts"
**Cause**: Race condition in ACK handling where the MISSION_CLEAR_ALL ACK was received before the code started waiting for it.

**Fix**: Changed from `MutableSharedFlow` to `CompletableDeferred` for ACK handling to avoid race conditions. The collector is now started BEFORE sending the command.

### Issue: Mission includes TAKEOFF causing issues
**Cause**: Old code was keeping TAKEOFF command for resume missions.

**Fix**: For mid-flight resume, we now skip ALL waypoints before the resume point (including TAKEOFF) since the drone is already flying. The mission structure is now:
- Seq 0: HOME
- Seq 1: Resume waypoint (first waypoint after pause point)
- Seq 2+: Remaining waypoints

### Log Messages to Look For
When resuming works correctly, you should see:
```
═══ Filtering Mission for Resume (Mid-Flight) ═══
NOTE: NO TAKEOFF - drone is already flying
...
✅ Mission cleared on attempt 1
...
✅ SUCCESS - Mission uploaded!
```

