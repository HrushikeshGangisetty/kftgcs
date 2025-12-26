package com.example.aerogcsclone.grid

import com.google.android.gms.maps.model.LatLng

/**
 * Represents a waypoint in a grid survey mission
 */
data class GridWaypoint(
    val position: LatLng,
    val altitude: Float,
    val speed: Float? = null,
    val isLineStart: Boolean = false,
    val isLineEnd: Boolean = false,
    val lineIndex: Int = 0
)

/**
 * Grid survey parameters
 */
data class GridSurveyParams(
    val lineSpacing: Float = 30f,     // meters
    val gridAngle: Float = 0f,        // degrees from north
    val speed: Float = 10f,           // m/s
    val altitude: Float = 10f,        // meters
    val includeSpeedCommands: Boolean = true,
    val holdNosePosition: Boolean = false,  // Hold yaw throughout mission for battery efficiency
    val indentation: Float = 0f       // meters - padding from polygon boundary (safe zone)
)

/**
 * Result of grid generation
 */
data class GridSurveyResult(
    val waypoints: List<GridWaypoint>,
    val gridLines: List<Pair<LatLng, LatLng>>, // For visualization
    val totalDistance: Double, // meters
    val estimatedTime: Double, // seconds
    val numLines: Int,
    val polygonArea: String
)
