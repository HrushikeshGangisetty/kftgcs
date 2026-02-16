# Mission Upload Progress & Obstacle Save Fixes

## Summary
Fixed two critical issues in the application:
1. **Mission upload progress bar not updating** - Progress bar remained at 0% until upload completed
2. **Obstacles not being saved with plot templates** - Obstacle data was lost when saving templates

---

## Issue 1: Mission Upload Progress Bar Not Updating

### Problem
During mission upload, logs showed progress updates but the UI progress bar remained at 0% until the upload completed at 100%.

### Root Cause
The `uploadMissionWithAck()` function in `TelemetryRepository.kt` was sending mission items to the flight controller but not reporting incremental progress back to the ViewModel and UI layer.

### Solution

#### 1. Updated `TelemetryRepository.kt`
**File**: `app/src/main/java/com/example/aerogcsclone/telemetry/TelemetryRepository.kt`

Added `onProgress` callback parameter to the `uploadMissionWithAck()` function:

```kotlin
suspend fun uploadMissionWithAck(
    missionItems: List<MissionItemInt>, 
    timeoutMs: Long = 45000,
    onProgress: ((currentItem: Int, totalItems: Int) -> Unit)? = null
): Boolean {
```

Added progress callback invocation when each mission item is sent:

```kotlin
connection.trySendUnsignedV2(gcsSystemId, gcsComponentId, item)
sentSeqs.add(seq)

// Report progress to caller
onProgress?.invoke(seq + 1, missionItems.size)
```

#### 2. Updated `SharedViewModel.kt`
**File**: `app/src/main/java/com/example/aerogcsclone/telemetry/SharedViewModel.kt`

Modified the `uploadMissionWithAck()` call in `uploadMission()` function to provide progress callback:

```kotlin
val success = repo?.uploadMissionWithAck(
    missionItems = missionItems,
    onProgress = { currentItem, totalItems ->
        _missionUploadProgress.value = MissionUploadProgress(
            stage = "Uploading",
            currentItem = currentItem,
            totalItems = totalItems,
            message = "Uploading waypoint $currentItem of $totalItems..."
        )
    }
) ?: false
```

### Result
- Progress bar now updates smoothly during mission upload
- Shows current waypoint being uploaded (e.g., "Uploading waypoint 15 of 50...")
- Progress percentage updates incrementally (0% → 2% → 4% ... → 100%)
- Better user feedback during the upload process

---

## Issue 2: Obstacles Not Being Saved with Plot Templates

### Problem
When saving a plot template with obstacles defined, the obstacle data was not persisted to the database. When loading the template later, obstacles were missing.

### Root Cause
The `GridParameters` data class did not include an `obstacles` field, so obstacle information was never stored in the database.

### Solution

#### 1. Updated `MissionTemplateEntity.kt`
**File**: `app/src/main/java/com/example/aerogcsclone/database/MissionTemplateEntity.kt`

Added `obstacles` field to `GridParameters` data class:

```kotlin
data class GridParameters(
    val lineSpacing: Float,
    val gridAngle: Float,
    val surveySpeed: Float,
    val surveyAltitude: Float,
    val surveyPolygon: List<LatLng>,
    val obstacles: List<List<LatLng>> = emptyList()  // NEW FIELD
)
```

#### 2. Updated `PlanScreen.kt` - Save Operation
**File**: `app/src/main/java/com/example/aerogcsclone/uimain/PlanScreen.kt`

Modified the template save operation to include obstacles:

```kotlin
val currentGridParams = if (isGridSurveyMode) {
    GridParameters(
        lineSpacing = lineSpacing,
        gridAngle = gridAngle,
        surveySpeed = surveySpeed,
        surveyAltitude = surveyAltitude,
        surveyPolygon = surveyPolygon,
        obstacles = obstacles  // NOW INCLUDED
    )
} else null
```

#### 3. Updated `PlanScreen.kt` - Load Operation
**File**: `app/src/main/java/com/example/aerogcsclone/uimain/PlanScreen.kt`

Modified the template load operation to restore obstacles:

```kotlin
if (template.isGridSurvey && template.gridParameters != null) {
    isGridSurveyMode = true
    showGridControls = true
    isGridGenerated = true
    isPlotDefinitionMode = false

    val gridParams = template.gridParameters
    lineSpacing = gridParams.lineSpacing
    gridAngle = gridParams.gridAngle
    surveySpeed = gridParams.surveySpeed
    surveyAltitude = gridParams.surveyAltitude
    surveyPolygon = gridParams.surveyPolygon
    obstacles = gridParams.obstacles  // NOW RESTORED

    if (surveyPolygon.size >= 3) {
        regenerateGrid()
    }
}
```

### Result
- Obstacles are now saved when saving plot templates
- Obstacles are restored when loading plot templates
- Grid regeneration respects loaded obstacles
- Complete plot configuration (including obstacles) is preserved

---

## Testing Recommendations

### Test Mission Upload Progress
1. Create a mission with 20+ waypoints
2. Click "Upload to Drone"
3. Observe the progress dialog:
   - Progress bar should animate smoothly from 0% to 100%
   - Progress percentage should update incrementally
   - Message should show "Uploading waypoint X of Y..."
   - Current item count should increment

### Test Obstacle Saving
1. Enter grid survey mode
2. Define a survey polygon (4+ points)
3. Add one or more obstacles
4. Generate grid
5. Save the plot template
6. Close and reopen the app
7. Load the saved template
8. Verify:
   - Survey polygon is restored
   - All obstacles are restored
   - Grid is regenerated with obstacles
   - Waypoints avoid obstacle zones

---

## Files Modified

1. **app/src/main/java/com/example/aerogcsclone/database/MissionTemplateEntity.kt**
   - Added `obstacles` field to `GridParameters`

2. **app/src/main/java/com/example/aerogcsclone/uimain/PlanScreen.kt**
   - Updated save operation to include obstacles
   - Updated load operation to restore obstacles

3. **app/src/main/java/com/example/aerogcsclone/telemetry/TelemetryRepository.kt**
   - Added `onProgress` callback parameter to `uploadMissionWithAck()`
   - Invoke callback when each mission item is sent

4. **app/src/main/java/com/example/aerogcsclone/telemetry/SharedViewModel.kt**
   - Updated `uploadMission()` to provide progress callback
   - Progress callback updates `_missionUploadProgress` state

---

## Impact Analysis

### Backward Compatibility
- **GridParameters**: Added default value `emptyList()` for obstacles field, so existing saved templates without obstacles will load correctly
- **uploadMissionWithAck**: Made `onProgress` an optional parameter with default `null`, so existing calls (e.g., in resume mission) continue to work without modification

### Performance
- Minimal impact: Progress callback is invoked once per waypoint sent (typically 50-100ms apart)
- No blocking operations in callback
- StateFlow update is efficient and thread-safe

### UI/UX Improvements
- Better user feedback during mission upload
- Complete plot template persistence (including obstacles)
- Reduced user frustration from "frozen" progress indicators

---

## Date
February 13, 2026

