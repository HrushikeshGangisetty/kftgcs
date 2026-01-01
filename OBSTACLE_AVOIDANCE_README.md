# Obstacle Avoidance Grid Survey System

## Overview

This document describes the obstacle avoidance implementation for grid survey missions in the AeroGCS application. The system generates flight paths that automatically avoid defined obstacle zones while maintaining efficient survey coverage.

## Features

### 1. Grid Generation with Obstacle Avoidance
- **Boustrophedon Pattern**: Grid lines follow a back-and-forth (lawnmower) pattern for efficient coverage
- **Obstacle Detection**: Grid lines that intersect obstacles are automatically split
- **Minimum Buffer**: 3 meters safety buffer from obstacles (configurable up to 5m)
- **Visual Feedback**: Grid lines are not shown inside obstacle areas

### 2. Visual Feedback
- **Grid Lines (Green)**: Show the survey path that avoids obstacles
- **Obstacle Zones (Red)**: Semi-transparent polygons showing no-fly zones
- **Distance Labels**: Display edge lengths on obstacle boundaries (red-themed)
- **Outer Fence (Yellow)**: Safety buffer zone outside the geofence (2m)
- **Main Map Display**: Obstacles are visible on the main page after mission upload

### 3. UI Behavior
- **Plan Screen**: X marker visible at obstacle center for selection/editing
- **Main Page**: No X marker - obstacles displayed as read-only polygons
- **Distance Labels**: Red-themed distance labels on all obstacle edges

## Technical Implementation

### GridGenerator.kt

Key features:
1. **Minimum 3m buffer**: `effectiveBuffer = maxOf(params.obstacleBoundary.toDouble(), 3.0)`
2. **500-point sampling**: High-resolution line sampling for accurate obstacle detection
3. **Segment-based processing**: Lines split at obstacle boundaries
4. **Boustrophedon pattern**: Efficient back-and-forth flying pattern

### Key Algorithms

#### Line Splitting (`splitLineAroundObstacles`)
- Samples 500 points along each line
- Checks each point against original AND expanded obstacles
- Creates segments only from points OUTSIDE obstacles
- Minimum segment length: 1 meter

#### Polygon Expansion (`expandPolygonEdgeBased`)  
- Expands obstacles outward by buffer distance
- Uses edge perpendiculars for accurate expansion
- Handles corners with miter joint calculation

### Waypoint Sequence

```
Line 1: →→→→→→→→→
Line 2:     ←←←←←[OBSTACLE]←←←←
                 ↑ (no waypoints in obstacle)
Line 3: →→→→→→→→→→→→→→→→→→→→→→→
```

## Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `lineSpacing` | 30m | Distance between grid lines |
| `obstacleBoundary` | 2m | Buffer from obstacles (min 3m enforced) |
| `indentation` | 0m | Buffer from survey boundary |

## Files Modified

1. **GridGenerator.kt** - Obstacle avoidance grid generation
2. **GcsMap.kt** - `obstacleEditingEnabled` parameter, distance labels
3. **MainPage.kt** - Obstacles display from SharedViewModel
4. **PlanScreen.kt** - `obstacleEditingEnabled=true` for editing
5. **SharedViewModel.kt** - `obstacles` storage and `setObstacles()`

## Troubleshooting

### Grid lines still go through obstacle
- The minimum buffer is 3 meters - increase if needed
- Ensure obstacle polygon has at least 3 vertices
- Regenerate grid after adding/modifying obstacles

### X marker shows on main page
- Fixed: `obstacleEditingEnabled` defaults to `false`
- X marker only shows in PlanScreen with `obstacleEditingEnabled=true`
