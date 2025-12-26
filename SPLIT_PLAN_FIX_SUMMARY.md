puo# Split Plan Feature - Bug Fix Summary

## Date: December 26, 2025

## Issues Reported
1. Split plan UI changes but mission upload still starts from the first waypoint
2. Start and end points not changing correctly
3. End point appearing at middle index, causing mission corruption

## Root Causes Identified

### 1. GridGenerator.kt - Line Index Mismatch (PRIMARY ISSUE)
**Problem:** The original code used the loop counter `i` as `lineIndex` for waypoints. However, not all lines from the loop intersect with the survey polygon. This caused a mismatch:

- Loop iterates: `i = 0, 1, 2, 3, 4, 5, ...`
- If lines 0-2 don't intersect polygon but 3-5 do:
  - Waypoints stored with `lineIndex = 3, 4, 5`
  - But `gridLines` array has indices `0, 1, 2`
  - `numLines = 3` (count of actual intersecting lines)

When splitting lines 0-1 (expecting first 2 lines), the filter `wp.lineIndex in 0..1` would find **no waypoints** because actual waypoints had `lineIndex = 3, 4, 5`.

**Fix:** Added `actualLineIndex` counter that only increments when a line actually intersects the polygon:
```kotlin
var actualLineIndex = 0
// ... inside loop ...
if (trimmedLine != null) {
    // Add waypoints with actualLineIndex
    waypoints.add(GridWaypoint(lineIndex = actualLineIndex, ...))
    actualLineIndex++ // Only increment for valid lines
}
```

### 2. PlanScreen.kt - Split Waypoints Not Re-indexed
**Problem:** When filtering waypoints for a split range (e.g., lines 5-10), the original `lineIndex` values were preserved. This meant:
- Split waypoints had `lineIndex = 5, 6, 7, 8, 9, 10`
- Mission converter expected `lineIndex = 0, 1, 2, 3, 4, 5` for proper line start/end detection

**Fix:** Added re-indexing logic in `generateSplitGridResult()`:
```kotlin
val reindexedWaypoints = filteredWaypoints.map { wp ->
    val newLineIndex = wp.lineIndex - startLineIndex
    wp.copy(lineIndex = newLineIndex)
}
```

## Files Modified

### 1. `app/src/main/java/com/example/aerogcsclone/grid/GridGenerator.kt`
- Added `actualLineIndex` counter for proper line indexing
- Changed boustrophedon pattern to use `actualLineIndex % 2` for consistent alternation
- Waypoints now use `lineIndex = actualLineIndex`

### 2. `app/src/main/java/com/example/aerogcsclone/uimain/PlanScreen.kt`
- Added re-indexing logic in `generateSplitGridResult()` to normalize line indices
- Added comprehensive debug logging for split calculation:
  - Total lines, start/end percentages
  - Calculated line indices
  - Filtered and reindexed waypoint counts
  - First few waypoints for verification

### 3. `app/src/main/java/com/example/aerogcsclone/grid/GridMissionConverter.kt`
- Added debug logging at mission conversion:
  - Input waypoint count and line count
  - First few waypoints with their line indices
  - Final mission item count

## Testing Checklist
- [ ] Create a survey polygon with irregular shape
- [ ] Generate grid mission (verify line indices are 0, 1, 2, ... consecutive)
- [ ] Enable Split Plan mode
- [ ] Adjust start slider to 30% → verify start line index updates
- [ ] Adjust end slider to 70% → verify end line index updates
- [ ] Verify UI shows correct start/end markers (green S, red E)
- [ ] Upload split mission
- [ ] Read back mission from FCU → verify waypoint count matches split
- [ ] Execute mission → verify drone follows only split portion

## Debug Logs to Check
When testing, check Logcat for these tags:
- `SplitPlan` - Split calculation and waypoint re-indexing
- `GridMissionConverter` - Mission item conversion

Example expected log output for a 10-line grid split from 30% to 70%:
```
D/SplitPlan: Split calculation: totalLines=10, startPercent=0.3, endPercent=0.7
D/SplitPlan: Line indices: startLineIndex=2, endLineIndex=6
D/SplitPlan: Filtered: 5 lines, 10 waypoints
D/SplitPlan: WP[0]: lineIndex=0, isStart=true, isEnd=false
D/SplitPlan: WP[1]: lineIndex=0, isStart=false, isEnd=true
I/SplitPlan: ✓ Generated split: 10 waypoints, 5 lines, 1234.5m
```

