package com.example.kftgcs.grid

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridGeneratorReverseTest {
    // ~55 m square with an obstacle in the middle, so detour (transition) points are covered too.
    private val field = listOf(
        LatLng(17.0000, 78.0000), LatLng(17.0000, 78.0005),
        LatLng(17.0005, 78.0005), LatLng(17.0005, 78.0000)
    )
    private val obstacle = listOf(
        LatLng(17.0002, 78.0002), LatLng(17.0002, 78.0003),
        LatLng(17.0003, 78.0003), LatLng(17.0003, 78.0002)
    )

    @Test
    fun reverseFliesTheSamePathBackwards() {
        val params = GridSurveyParams(lineSpacing = 5f, gridAngle = 90f, obstacles = listOf(obstacle))
        val forward = GridGenerator().generateGridSurvey(field, params)
        val reversed = GridGenerator().generateGridSurvey(field, params.copy(reverse = true))

        assertTrue(forward.waypoints.size >= 4)
        assertEquals(forward.waypoints.map { it.position }.reversed(), reversed.waypoints.map { it.position })
        assertEquals(forward.gridLines.reversed().map { Pair(it.second, it.first) }, reversed.gridLines)
        assertEquals(forward.totalDistance, reversed.totalDistance, 0.0)

        // Still a well-formed mission: starts on a line start at line 0, ends on a line end,
        // and each line is a start followed by an end with the index never going backwards.
        assertTrue(reversed.waypoints.first().isLineStart)
        assertEquals(0, reversed.waypoints.first().lineIndex)
        assertTrue(reversed.waypoints.last().isLineEnd)
        val lines = reversed.waypoints.filterNot { it.isTransition }
        lines.chunked(2).forEachIndexed { i, (start, end) ->
            assertTrue(start.isLineStart && end.isLineEnd)
            assertEquals(i, start.lineIndex)
            assertEquals(i, end.lineIndex)
        }
        reversed.waypoints.zipWithNext { a, b -> assertTrue(a.lineIndex <= b.lineIndex) }
    }
}
