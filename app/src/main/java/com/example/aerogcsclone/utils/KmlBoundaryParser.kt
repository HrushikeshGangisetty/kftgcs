package com.example.aerogcsclone.utils

import android.util.Xml
import com.google.android.gms.maps.model.LatLng
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream

/**
 * Data class representing a parsed KML polygon with its name and boundary points.
 */
data class KmlPolygon(
    val name: String,
    val points: List<LatLng>
)

/**
 * Result of parsing a KML file containing all polygons found.
 */
data class KmlParseResult(
    val polygons: List<KmlPolygon>,
    val errorMessage: String? = null
)

/**
 * Parses a KML Input Stream to extract polygon boundaries.
 * Adheres to OGC KML 2.2 Standards.
 *
 * Supports:
 * - Single polygon extraction
 * - Multiple polygon extraction (user can select which to use)
 * - Nested Document/Folder structures
 * - Standard KML coordinate format (longitude,latitude,altitude)
 */
class KmlBoundaryParser {

    private val TAG = "KmlBoundaryParser"
    private val ns: String? = null // Namespace handling disabled for simple extraction

    /**
     * Parse KML file and return all polygons found.
     * @param inputStream The KML file input stream
     * @return KmlParseResult containing list of polygons and optional error message
     */
    fun parseAllPolygons(inputStream: InputStream): KmlParseResult {
        return try {
            inputStream.use { stream ->
                val parser: XmlPullParser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(stream, null)
                parser.nextTag()
                val polygons = readKml(parser)

                if (polygons.isEmpty()) {
                    KmlParseResult(
                        polygons = emptyList(),
                        errorMessage = "No valid polygons found in KML file"
                    )
                } else {
                    KmlParseResult(polygons = polygons)
                }
            }
        } catch (e: XmlPullParserException) {
            KmlParseResult(
                polygons = emptyList(),
                errorMessage = "Invalid KML format: ${e.message}"
            )
        } catch (e: IOException) {
            KmlParseResult(
                polygons = emptyList(),
                errorMessage = "Error reading file: ${e.message}"
            )
        } catch (e: Exception) {
            KmlParseResult(
                polygons = emptyList(),
                errorMessage = "Unexpected error: ${e.message}"
            )
        }
    }

    /**
     * Parse KML file and return only the first polygon found.
     * For backward compatibility with single-polygon workflows.
     */
    fun parseFirstPolygon(inputStream: InputStream): List<LatLng> {
        val result = parseAllPolygons(inputStream)
        return result.polygons.firstOrNull()?.points ?: emptyList()
    }

    private fun readKml(parser: XmlPullParser): List<KmlPolygon> {
        val polygons = mutableListOf<KmlPolygon>()

        parser.require(XmlPullParser.START_TAG, ns, "kml")

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue

            when (parser.name) {
                "Document", "Folder" -> polygons.addAll(readContainer(parser))
                "Placemark" -> {
                    val polygon = readPlacemark(parser)
                    if (polygon != null) polygons.add(polygon)
                }
                else -> skip(parser)
            }
        }
        return polygons
    }

    private fun readContainer(parser: XmlPullParser): List<KmlPolygon> {
        val polygons = mutableListOf<KmlPolygon>()

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue

            when (parser.name) {
                "Placemark" -> {
                    val polygon = readPlacemark(parser)
                    if (polygon != null) polygons.add(polygon)
                }
                "Folder", "Document" -> {
                    polygons.addAll(readContainer(parser))
                }
                else -> skip(parser)
            }
        }
        return polygons
    }

    private fun readPlacemark(parser: XmlPullParser): KmlPolygon? {
        var name = "Unnamed Polygon"
        var points: List<LatLng>? = null

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue

            when (parser.name) {
                "name" -> name = readText(parser)
                "Polygon" -> points = readPolygon(parser)
                else -> skip(parser)
            }
        }

        // Only return if we found a valid polygon with at least 3 points
        return if (points != null && points.size >= 3) {
            KmlPolygon(name = name, points = points)
        } else {
            null
        }
    }

    private fun readPolygon(parser: XmlPullParser): List<LatLng>? {
        var points: List<LatLng>? = null

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue

            // We only care about the outer boundary for the survey area
            if (parser.name == "outerBoundaryIs") {
                points = readLinearRingWrapper(parser)
            } else {
                skip(parser)
            }
        }
        return points
    }

    private fun readLinearRingWrapper(parser: XmlPullParser): List<LatLng>? {
        var points: List<LatLng>? = null

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue

            if (parser.name == "LinearRing") {
                points = readLinearRing(parser)
            } else {
                skip(parser)
            }
        }
        return points
    }

    private fun readLinearRing(parser: XmlPullParser): List<LatLng>? {
        var points: List<LatLng>? = null

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue

            if (parser.name == "coordinates") {
                points = readCoordinates(parser)
            } else {
                skip(parser)
            }
        }
        return points
    }

    private fun readCoordinates(parser: XmlPullParser): List<LatLng> {
        val coordinatesString = readText(parser)
        val latLngList = mutableListOf<LatLng>()

        // Regex split on whitespace (space, tab, newline)
        val tuples = coordinatesString.trim().split("\\s+".toRegex())

        for (tuple in tuples) {
            if (tuple.isBlank()) continue

            val parts = tuple.split(",")
            if (parts.size >= 2) {
                try {
                    // KML Spec: lon, lat, alt (longitude comes first!)
                    val lon = parts[0].trim().toDouble()
                    val lat = parts[1].trim().toDouble()

                    // Android Map Spec: lat, lon (latitude comes first!)
                    latLngList.add(LatLng(lat, lon))
                } catch (_: NumberFormatException) {
                    // Skipping malformed coordinate tuple
                }
            }
        }

        // Close the loop if not already closed (first and last point should be same)
        if (latLngList.size >= 3) {
            val first = latLngList.first()
            val last = latLngList.last()

            // Check if loop is already closed (within ~1m tolerance)
            val distance = calculateDistance(first, last)
            if (distance < 1.0) {
                // Remove the duplicate closing point to avoid issues
                latLngList.removeAt(latLngList.lastIndex)
            }
        }

        return latLngList
    }

    private fun readText(parser: XmlPullParser): String {
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text
            parser.nextTag()
        }
        return result
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) {
            throw IllegalStateException("Expected START_TAG, got ${parser.eventType}")
        }
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
            }
        }
    }

    /**
     * Calculate approximate distance between two LatLng points in meters.
     * Uses Haversine formula for accuracy.
     */
    private fun calculateDistance(p1: LatLng, p2: LatLng): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLon = Math.toRadians(p2.longitude - p1.longitude)
        val lat1 = Math.toRadians(p1.latitude)
        val lat2 = Math.toRadians(p2.latitude)

        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2) *
                kotlin.math.cos(lat1) * kotlin.math.cos(lat2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))

        return earthRadius * c
    }
}

