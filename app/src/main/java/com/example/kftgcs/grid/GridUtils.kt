package com.example.kftgcs.grid

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil
import java.util.Locale
import kotlin.math.*

/**
 * Utility functions for grid calculations
 */
object GridUtils {

    /**
     * Calculate distance between two points using Haversine formula
     * @param a First point
     * @param b Second point
     * @return Distance in meters
     */
    fun haversineDistance(a: LatLng, b: LatLng): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)

        val aHarv = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(aHarv), sqrt(1 - aHarv))
        return R * c
    }

    /**
     * Move a point by dx, dy meters (approximate for small distances)
     * @param point Original point
     * @param dx Distance to move east (meters)
     * @param dy Distance to move north (meters)
     * @return New point
     */
    fun moveLatLng(point: LatLng, dx: Double, dy: Double): LatLng {
        val dLat = dy / 111111.0 // Approximate meters per degree latitude
        val dLng = dx / (111111.0 * cos(Math.toRadians(point.latitude)))
        return LatLng(point.latitude + dLat, point.longitude + dLng)
    }

    /**
     * Calculate the bearing (angle) from point A to point B
     * @param from Starting point
     * @param to Ending point
     * @return Bearing in degrees (0-360, where 0 = North)
     */
    fun calculateBearing(from: LatLng, to: LatLng): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLng = Math.toRadians(to.longitude - from.longitude)

        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)

        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360) % 360
    }

    /**
     * Find the centroid (center) of a polygon
     * @param polygon List of points defining the polygon
     * @return Center point
     */
    fun calculatePolygonCenter(polygon: List<LatLng>): LatLng {
        var lat = 0.0
        var lng = 0.0

        polygon.forEach { point ->
            lat += point.latitude
            lng += point.longitude
        }

        return LatLng(lat / polygon.size, lng / polygon.size)
    }

    /**
     * Calculate the bounding box of a polygon
     * @param polygon List of points
     * @return Pair of (southwest corner, northeast corner)
     */
    fun calculateBoundingBox(polygon: List<LatLng>): Pair<LatLng, LatLng> {
        val minLat = polygon.minOf { it.latitude }
        val maxLat = polygon.maxOf { it.latitude }
        val minLng = polygon.minOf { it.longitude }
        val maxLng = polygon.maxOf { it.longitude }

        return Pair(
            LatLng(minLat, minLng), // Southwest
            LatLng(maxLat, maxLng)  // Northeast
        )
    }

    /**
     * Find the angle of the longest side of a polygon (for auto grid angle)
     * @param polygon List of points
     * @return Angle in degrees
     */
    fun getAngleOfLongestSide(polygon: List<LatLng>): Double {
        if (polygon.size < 2) return 0.0

        var maxDistance = 0.0
        var longestSideAngle = 0.0

        for (i in polygon.indices) {
            val current = polygon[i]
            val next = polygon[(i + 1) % polygon.size]
            val distance = haversineDistance(current, next)

            if (distance > maxDistance) {
                maxDistance = distance
                longestSideAngle = calculateBearing(current, next)
            }
        }

        return longestSideAngle
    }

    /**
     * Check if a point is inside a polygon using ray casting algorithm
     * @param point Point to check
     * @param polygon Polygon vertices
     * @return True if point is inside polygon
     */
    fun isPointInPolygon(point: LatLng, polygon: List<LatLng>): Boolean {
        if (polygon.size < 3) return false

        var inside = false
        var j = polygon.size - 1

        for (i in polygon.indices) {
            val xi = polygon[i].longitude
            val yi = polygon[i].latitude
            val xj = polygon[j].longitude
            val yj = polygon[j].latitude

            if (((yi > point.latitude) != (yj > point.latitude)) &&
                (point.longitude < (xj - xi) * (point.latitude - yi) / (yj - yi) + xi)) {
                inside = !inside
            }
            j = i
        }

        return inside
    }

    /**
     * Calculate the area of a polygon in square meters using SphericalUtil.
     * @param polygon List of points
     * @return Area in square meters
     */
    fun calculatePolygonArea(polygon: List<LatLng>): Double {
        if (polygon.size < 3) return 0.0
        return SphericalUtil.computeArea(polygon)
    }

    /**
     * Calculates and formats the area of a polygon into acres.
     * @param polygon The list of LatLng points defining the polygon.
     * @return A formatted string representing the area in acres.
     */
    fun calculateAndFormatPolygonArea(polygon: List<LatLng>): String {
        if (polygon.size < 3) return "0 acres"

        val areaInSqMeters = SphericalUtil.computeArea(polygon)
        val areaInSqFeet = areaInSqMeters * 10.7639

        // Conversion constant
        val ft2PerAcre = 43560.0

        // Convert to acres and format
        val areaInAcres = areaInSqFeet / ft2PerAcre
        return String.format(Locale.US, "%.2f acres", areaInAcres)
    }

    /** Exact square metres in one acre (1 acre = 4046.856 m²). Single source of truth. */
    const val SQ_METERS_PER_ACRE = 4046.856

    /**
     * Geodesic area of a plot polygon in acres. Numeric counterpart of
     * [calculateAndFormatPolygonArea], for use in flight/telemetry math.
     * @return acres, or 0.0 for a degenerate polygon (< 3 points).
     */
    fun polygonAreaAcres(polygon: List<LatLng>): Double {
        if (polygon.size < 3) return 0.0
        return SphericalUtil.computeArea(polygon) / SQ_METERS_PER_ACRE
    }

    /**
     * Swept-path area in acres: the strip covered by travelling [distanceMeters]
     * with an effective swath of [swathMeters]. Used for "sprayed acres".
     * Using the mission line spacing as the swath keeps adjacent lanes non-overlapping,
     * which avoids double-counting coverage.
     */
    fun sweptAcres(distanceMeters: Double, swathMeters: Double): Double {
        if (distanceMeters <= 0.0 || swathMeters <= 0.0) return 0.0
        return distanceMeters * swathMeters / SQ_METERS_PER_ACRE
    }

    /**
     * Shrink a polygon inward by a specified distance (indentation/padding)
     * This creates a safe zone by moving all edges inward
     * @param polygon Original polygon vertices
     * @param distance Distance to shrink inward in meters
     * @return Shrunk polygon, or original if shrinking fails
     */
    fun shrinkPolygon(polygon: List<LatLng>, distance: Float): List<LatLng> {
        if (polygon.size < 3 || distance <= 0) return polygon

        val center = calculatePolygonCenter(polygon)
        val shrunkPolygon = mutableListOf<LatLng>()

        for (point in polygon) {
            // Calculate direction from point to center
            val bearing = calculateBearing(point, center)

            // Move point toward center by the specified distance
            val newPoint = movePointByBearing(point, bearing, distance.toDouble())
            shrunkPolygon.add(newPoint)
        }

        // Validate the shrunk polygon is still valid (has positive area)
        val originalArea = calculatePolygonArea(polygon)
        val shrunkArea = calculatePolygonArea(shrunkPolygon)

        // If shrunk polygon is too small or inverted, return a smaller shrinkage
        return if (shrunkArea > 0 && shrunkArea < originalArea) {
            shrunkPolygon
        } else {
            polygon // Return original if shrinking would invert polygon
        }
    }

    /**
     * Move a point by a given bearing and distance
     * @param point Starting point
     * @param bearing Direction in degrees (0 = North, 90 = East)
     * @param distance Distance in meters
     * @return New point
     */
    fun movePointByBearing(point: LatLng, bearing: Double, distance: Double): LatLng {
        val bearingRad = Math.toRadians(bearing)
        val dx = distance * sin(bearingRad)
        val dy = distance * cos(bearingRad)
        return moveLatLng(point, dx, dy)
    }

    /**
     * Order polygon points in clockwise order around centroid
     * This fixes the "hourglass" rendering issue when points are added in random order
     * @param points List of polygon points in any order
     * @return List of points ordered clockwise
     */
    fun orderPointsClockwise(points: List<LatLng>): List<LatLng> {
        if (points.size < 3) return points

        // Calculate centroid
        val centroid = calculatePolygonCenter(points)

        // Sort points by angle from centroid (clockwise)
        return points.sortedBy { point ->
            // Calculate angle from centroid to point
            val dx = point.longitude - centroid.longitude
            val dy = point.latitude - centroid.latitude
            // atan2 returns angle in radians, negate for clockwise order
            -atan2(dy, dx)
        }
    }
}
