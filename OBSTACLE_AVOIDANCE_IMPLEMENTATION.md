# Obstacle Avoidance Implementation Guide

> **Status: ✅ VERIFIED & FIXED** - Bug fixed on December 26, 2025
> 
> **Bug Fixed**: The processed waypoints with obstacle avoidance were not being published to SharedViewModel after upload. Fixed by using `processedGridResult` instead of `gridToUpload`.

## Overview

This document describes the obstacle avoidance feature implemented in the GCS app. Users can define no-fly zones (obstacles) on the map, and the drone will automatically route around them during mission upload.

## Features

### 1. Add Obstacle Button
- Located in the **left sidebar** on the Plan Screen
- Icon: Block (🚫) icon
- When active, the button turns **red** to indicate obstacle-adding mode

### 2. Adding Obstacles
1. Tap the **Add Obstacle** button in the left sidebar
2. A helper card appears at the top: "🚧 Adding Obstacle: Tap X more point(s) on map"
3. Tap **4 points** on the map to define the obstacle boundary (polygon)
4. After 4 points are added, the obstacle is automatically saved
5. The obstacle area is displayed with a **semi-transparent red fill**

### 3. Obstacle Display
- **Red semi-transparent fill** (35% opacity)
- **Red boundary outline**
- **"X" marker** at the centroid when not selected
- When selected, shows **draggable markers** at each vertex

### 4. Managing Obstacles
- **Click** on an obstacle to select it
- **Drag vertices** to adjust the obstacle shape (when in edit mode)
- **Delete** selected obstacle using the delete button in the info card
- Info card shows: "🚧 X Obstacle Zone(s) - Drone will auto-route around"

### 5. Obstacle Avoidance During Upload
When uploading a mission:
1. The system checks if any waypoint paths cross defined obstacles
2. If intersections are found, intermediate waypoints are inserted
3. The drone routes around obstacle boundaries with a **5-meter buffer**
4. A toast message confirms: "Path modified to avoid X obstacle(s)"

## Technical Implementation

### Files Modified/Created

1. **`utils/ObstaclePathPlanner.kt`** (NEW)
   - Path planning algorithms for obstacle avoidance
   - Functions:
     - `processWaypointsAroundObstacles()` - Main entry point
     - `lineIntersectsPolygon()` - Checks if path crosses obstacle
     - `isPointInsidePolygon()` - Ray casting algorithm
     - `findRouteAroundObstacle()` - Generates avoidance waypoints

2. **`uimain/GcsMap.kt`** (MODIFIED)
   - Added obstacle rendering parameters
   - Renders obstacle polygons with red fill
   - Supports obstacle point dragging
   - Shows in-progress obstacle while adding

3. **`uimain/PlanScreen.kt`** (MODIFIED)
   - Added obstacle state management
   - Added "Add Obstacle" button to left sidebar
   - Updated map click handler for obstacle mode
   - Integrated obstacle processing into mission upload
   - Added obstacle info card and helper text

### State Variables (PlanScreen)

```kotlin
// List of obstacle zones (each is a list of 4+ points)
var obstacles by remember { mutableStateOf<List<List<LatLng>>>(emptyList()) }

// Flag for obstacle-adding mode
var isAddingObstacle by remember { mutableStateOf(false) }

// Current obstacle points being added
var currentObstaclePoints by remember { mutableStateOf<List<LatLng>>(emptyList()) }

// Selected obstacle for editing/deletion
var selectedObstacleIndex by remember { mutableStateOf<Int?>(null) }
```

### Obstacle Path Planning Algorithm

1. **Line-Polygon Intersection**: For each path segment, check if it crosses any obstacle using:
   - Point-in-polygon test (ray casting)
   - Line segment intersection test

2. **Route Generation**: When intersection found:
   - Expand obstacle polygon by buffer distance (5m)
   - Find entry/exit vertices closest to start/end
   - Generate both clockwise and counter-clockwise routes
   - Choose shorter path

3. **Waypoint Insertion**: Insert avoidance waypoints between original waypoints that would cross obstacles

## Usage Flow

1. **Create Mission Plan** (waypoints or grid survey)
2. **Add Obstacles** (optional)
   - Tap "Add Obstacle" button
   - Tap 4 points on map to define boundary
   - Repeat for additional obstacles
3. **Save Mission**
4. **Upload Mission**
   - System automatically processes path around obstacles
   - Modified mission uploaded to drone

## Limitations

- Obstacles require exactly 4 points (quadrilateral)
- Obstacles are not persisted between sessions (in-memory only)
- Buffer distance is fixed at 5 meters
- Complex obstacle arrangements may not find optimal paths

## Future Enhancements

- [ ] Support for polygons with more than 4 points
- [ ] Persist obstacles with mission templates
- [ ] Adjustable buffer distance
- [ ] Visual preview of modified path before upload
- [ ] A* or RRT path planning for complex scenarios
- [ ] Obstacle elevation/3D support

