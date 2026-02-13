package com.example.aerogcsclone.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.divpundir.mavlink.definitions.common.MissionItemInt
import com.google.android.gms.maps.model.LatLng

/**
 * Room entity for storing mission plan templates
 */
@Entity(tableName = "mission_templates")
@TypeConverters(MissionTemplateTypeConverters::class)
data class MissionTemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val projectName: String,
    val plotName: String,
    val waypoints: List<MissionItemInt>, // Will be converted to JSON using TypeConverter
    val waypointPositions: List<LatLng>, // Store LatLng positions separately for easier access
    val isGridSurvey: Boolean = false,
    val gridParameters: GridParameters? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Grid survey parameters for templates
 */
data class GridParameters(
    val lineSpacing: Float,
    val gridAngle: Float,
    val surveySpeed: Float,
    val surveyAltitude: Float,
    val surveyPolygon: List<LatLng>,
    val obstacles: List<List<LatLng>> = emptyList()
)
