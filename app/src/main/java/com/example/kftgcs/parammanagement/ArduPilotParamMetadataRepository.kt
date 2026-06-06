package com.example.kftgcs.parammanagement

import android.content.Context
import com.example.kftgcs.utils.LogUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * Repository that downloads, parses, and caches ArduPilot parameter metadata.
 *
 * Metadata source: Official ArduPilot parameter definition files
 * https://autotest.ardupilot.org/Parameters/{vehicle}/apm.pdef.xml
 *
 * This gives us description, default value, options (enum values),
 * range, units, and increment for every parameter — the same data
 * that Mission Planner uses.
 */
object ArduPilotParamMetadataRepository {

    private const val TAG = "ParamMetaRepo"

    /** URLs for different vehicle types */
    private const val COPTER_URL =
        "https://autotest.ardupilot.org/Parameters/ArduCopter/apm.pdef.xml"
    private const val PLANE_URL =
        "https://autotest.ardupilot.org/Parameters/ArduPlane/apm.pdef.xml"
    private const val ROVER_URL =
        "https://autotest.ardupilot.org/Parameters/Rover/apm.pdef.xml"

    private const val CACHE_FILE_NAME = "ardupilot_param_metadata.json"
    /** Full metadata snapshot bundled in app assets (works offline, in the field). */
    private const val ASSET_FILE_NAME = "ardupilot_param_metadata.json"
    private const val CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000  // 7 days

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** In-memory cache so we only parse once per app session */
    @Volatile
    private var memoryCache: Map<String, ParamMeta>? = null

    // ─────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────

    /**
     * Load parameter metadata.
     * Priority: memory cache → disk cache (if fresh) → network download → fallback hardcoded.
     */
    suspend fun loadMetadata(context: Context): Map<String, ParamMeta> {
        // 1. Memory cache
        memoryCache?.let {
            LogUtils.d(TAG, "📋 Using in-memory metadata cache (${it.size} params)")
            return it
        }

        // 2. Disk cache (written by a previous network refresh)
        val diskCache = loadFromDiskCache(context)
        if (diskCache != null) {
            LogUtils.d(TAG, "📋 Loaded metadata from disk cache (${diskCache.size} params)")
            memoryCache = diskCache
            return diskCache
        }

        // 3. Bundled asset — full snapshot, always available offline
        val bundled = loadFromAssets(context)
        if (bundled != null) {
            LogUtils.d(TAG, "📋 Loaded metadata from bundled asset (${bundled.size} params)")
            memoryCache = bundled
            return bundled
        }

        // 4. Download from network (only if the asset is somehow unavailable)
        val downloaded = downloadAndParse(context)
        if (downloaded != null) {
            LogUtils.d(TAG, "📋 Downloaded metadata from ArduPilot (${downloaded.size} params)")
            memoryCache = downloaded
            return downloaded
        }

        // 5. Fallback to hardcoded
        LogUtils.d(TAG, "📋 Using fallback hardcoded metadata (${FALLBACK_PARAM_METADATA.size} params)")
        return FALLBACK_PARAM_METADATA
    }

    /**
     * Force refresh metadata from the network, ignoring cache.
     * Returns true if successful.
     */
    suspend fun refreshMetadata(context: Context): Boolean {
        val downloaded = downloadAndParse(context)
        if (downloaded != null) {
            memoryCache = downloaded
            LogUtils.d(TAG, "🔄 Refreshed metadata from ArduPilot (${downloaded.size} params)")
            return true
        }
        return false
    }

    /**
     * Check if metadata has been loaded (memory or disk cache exists).
     */
    fun isMetadataLoaded(): Boolean = memoryCache != null

    /**
     * Clear all caches (memory + disk).
     */
    suspend fun clearCache(context: Context) {
        memoryCache = null
        withContext(Dispatchers.IO) {
            getCacheFile(context).delete()
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Network download
    // ─────────────────────────────────────────────────────────────────

    private suspend fun downloadAndParse(
        context: Context,
        url: String = COPTER_URL
    ): Map<String, ParamMeta>? = withContext(Dispatchers.IO) {
        try {
            LogUtils.d(TAG, "⬇️ Downloading parameter metadata from $url")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "KFT-GCS/1.0")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                LogUtils.e(TAG, "❌ Download failed: HTTP ${response.code}")
                return@withContext null
            }

            val xmlBody = response.body?.string()
            if (xmlBody.isNullOrBlank()) {
                LogUtils.e(TAG, "❌ Empty response body")
                return@withContext null
            }

            LogUtils.d(TAG, "📥 Downloaded ${xmlBody.length} bytes, parsing XML…")

            val parsed = parseXml(xmlBody)
            if (parsed.isNotEmpty()) {
                // Merge with fallback (downloaded data wins for overlapping keys)
                val merged = FALLBACK_PARAM_METADATA.toMutableMap()
                merged.putAll(parsed)

                // Save to disk cache
                saveToDiskCache(context, merged)
                LogUtils.d(TAG, "✅ Parsed ${parsed.size} params, merged total: ${merged.size}")
                return@withContext merged
            }

            LogUtils.e(TAG, "❌ XML parsing returned empty result")
            null
        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Failed to download/parse metadata", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // XML Parsing (apm.pdef.xml format)
    // ─────────────────────────────────────────────────────────────────

    private fun parseXml(xmlContent: String): Map<String, ParamMeta> {
        val result = mutableMapOf<String, ParamMeta>()

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlContent))

            var eventType = parser.eventType
            var currentParamName: String? = null
            var currentDescription = ""
            var currentDefault = ""
            var currentRange = ""
            var currentUnits = ""
            var currentIncrement = ""
            var currentBitmask = mutableMapOf<Int, String>()
            var currentOptions = mutableMapOf<Int, String>()
            var inValues = false
            var inBitmask = false
            var currentFieldName = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "param" -> {
                                // Start of a parameter definition
                                currentParamName = parser.getAttributeValue(null, "name")
                                currentDescription = parser.getAttributeValue(null, "documentation") ?: ""
                                currentDefault = ""
                                currentRange = ""
                                currentUnits = ""
                                currentIncrement = ""
                                currentOptions = mutableMapOf()
                                currentBitmask = mutableMapOf()
                                inValues = false
                                inBitmask = false
                            }
                            "field" -> {
                                currentFieldName = parser.getAttributeValue(null, "name") ?: ""
                            }
                            "values" -> {
                                inValues = true
                            }
                            "value" -> {
                                if (inValues) {
                                    val code = parser.getAttributeValue(null, "code")
                                    val codeInt = code?.toIntOrNull()
                                    if (codeInt != null) {
                                        val text = parser.nextText() ?: ""
                                        currentOptions[codeInt] = text
                                    }
                                }
                            }
                            "bitmask" -> {
                                inBitmask = true
                            }
                            "bit" -> {
                                if (inBitmask) {
                                    val code = parser.getAttributeValue(null, "code")
                                    val codeInt = code?.toIntOrNull()
                                    if (codeInt != null) {
                                        val text = parser.nextText() ?: ""
                                        currentBitmask[codeInt] = text
                                    }
                                }
                            }
                        }
                    }

                    XmlPullParser.TEXT -> {
                        if (currentFieldName.isNotEmpty() && currentParamName != null) {
                            val text = parser.text?.trim() ?: ""
                            when (currentFieldName.lowercase()) {
                                "range" -> currentRange = text
                                "units" -> currentUnits = text
                                "increment" -> currentIncrement = text
                                "default" -> currentDefault = text
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "field" -> {
                                currentFieldName = ""
                            }
                            "values" -> {
                                inValues = false
                            }
                            "bitmask" -> {
                                inBitmask = false
                            }
                            "param" -> {
                                // End of a parameter definition — store it
                                currentParamName?.let { name ->
                                    // Clean up param name (remove vehicle prefix like "ArduCopter:")
                                    val cleanName = if (name.contains(":")) {
                                        name.substringAfter(":")
                                    } else {
                                        name
                                    }

                                    // Use bitmask as options if no values/options present
                                    val finalOptions = if (currentOptions.isNotEmpty()) {
                                        currentOptions.toMap()
                                    } else if (currentBitmask.isNotEmpty()) {
                                        currentBitmask.toMap()
                                    } else {
                                        emptyMap()
                                    }

                                    result[cleanName] = ParamMeta(
                                        description = currentDescription,
                                        defaultValue = currentDefault,
                                        options = finalOptions,
                                        range = currentRange,
                                        units = currentUnits
                                    )
                                }
                                currentParamName = null
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ XML parsing error", e)
        }

        return result
    }

    // ─────────────────────────────────────────────────────────────────
    // Disk cache (JSON file in app's internal storage)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Load the full metadata snapshot bundled in app assets.
     * Same JSON shape as the disk cache, so it deserializes with the same TypeToken.
     */
    private suspend fun loadFromAssets(context: Context): Map<String, ParamMeta>? =
        withContext(Dispatchers.IO) {
            try {
                val json = context.assets.open(ASSET_FILE_NAME)
                    .bufferedReader().use { it.readText() }
                val type = object : TypeToken<Map<String, ParamMeta>>() {}.type
                val parsed: Map<String, ParamMeta> = gson.fromJson(json, type)
                if (parsed.isNotEmpty()) {
                    // Merge over hardcoded fallback so nothing is ever lost
                    val merged = FALLBACK_PARAM_METADATA.toMutableMap()
                    merged.putAll(parsed)
                    merged
                } else {
                    null
                }
            } catch (e: Exception) {
                LogUtils.e(TAG, "❌ Failed to load bundled metadata asset", e)
                null
            }
        }

    private fun getCacheFile(context: Context): File =
        File(context.filesDir, CACHE_FILE_NAME)

    private suspend fun loadFromDiskCache(context: Context): Map<String, ParamMeta>? =
        withContext(Dispatchers.IO) {
            try {
                val cacheFile = getCacheFile(context)
                if (!cacheFile.exists()) return@withContext null

                // Check age
                val age = System.currentTimeMillis() - cacheFile.lastModified()
                if (age > CACHE_MAX_AGE_MS) {
                    LogUtils.d(TAG, "📁 Disk cache expired (${age / 1000 / 3600}h old)")
                    return@withContext null
                }

                val json = cacheFile.readText()
                val type = object : TypeToken<Map<String, ParamMeta>>() {}.type
                val cached: Map<String, ParamMeta> = gson.fromJson(json, type)

                if (cached.isNotEmpty()) cached else null
            } catch (e: Exception) {
                LogUtils.e(TAG, "❌ Failed to read disk cache", e)
                null
            }
        }

    private suspend fun saveToDiskCache(context: Context, data: Map<String, ParamMeta>) {
        withContext(Dispatchers.IO) {
            try {
                val json = gson.toJson(data)
                getCacheFile(context).writeText(json)
                LogUtils.d(TAG, "💾 Saved metadata cache (${data.size} params)")
            } catch (e: Exception) {
                LogUtils.e(TAG, "❌ Failed to save disk cache", e)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Fallback hardcoded metadata (used when offline with no cache)
// ─────────────────────────────────────────────────────────────────────

internal val FALLBACK_PARAM_METADATA: Map<String, ParamMeta> by lazy {
    mapOf(
        "ARMING_CHECK" to ParamMeta("Checks before arming", "1", mapOf(0 to "Disabled", 1 to "All")),
        "ARMING_REQUIRE" to ParamMeta("Arming requirements", "1", mapOf(0 to "Disabled", 1 to "Throttle Down", 2 to "RC Pattern")),
        "FRAME_CLASS" to ParamMeta("Frame class", "0", mapOf(0 to "Undefined", 1 to "Quad", 2 to "Hexa", 3 to "Octo", 4 to "OctoQuad", 5 to "Y6", 6 to "Heli", 7 to "Tri")),
        "FRAME_TYPE" to ParamMeta("Frame type", "1", mapOf(0 to "Plus", 1 to "X", 2 to "V", 3 to "H", 4 to "V-Tail", 5 to "A-Tail", 12 to "BetaFlightX", 13 to "DJI X")),
        "BATT_MONITOR" to ParamMeta("Battery monitor", "0", mapOf(0 to "Disabled", 3 to "Analog V", 4 to "Analog V+A")),
        "BATT_CAPACITY" to ParamMeta("Battery capacity mAh", "3300"),
        "BATT_ARM_VOLT" to ParamMeta("Min arm voltage", "0"),
        "BATT_LOW_VOLT" to ParamMeta("Low battery voltage", "10.5"),
        "BATT_CRT_VOLT" to ParamMeta("Critical battery voltage", "10.1"),
        "BATT2_MONITOR" to ParamMeta("Battery 2 monitor", "0", mapOf(0 to "Disabled", 3 to "Analog V", 4 to "Analog V+A", 11 to "Flow Sensor")),
        "FENCE_ENABLE" to ParamMeta("Geofence", "0", mapOf(0 to "Disabled", 1 to "Enabled")),
        "FENCE_TYPE" to ParamMeta("Geofence type bitmask", "7"),
        "FENCE_ACTION" to ParamMeta("Fence breach action", "1", mapOf(0 to "Report Only", 1 to "RTL/Land", 2 to "Always Land", 3 to "SmartRTL/RTL", 4 to "Brake/Land")),
        "FENCE_ALT_MAX" to ParamMeta("Max altitude fence (m)", "100"),
        "FENCE_RADIUS" to ParamMeta("Circular fence radius (m)", "300"),
        "FENCE_MARGIN" to ParamMeta("Fence margin (m)", "2"),
        "PILOT_SPEED_UP" to ParamMeta("Max climb rate cm/s", "250"),
        "PILOT_SPEED_DN" to ParamMeta("Max descent cm/s", "0"),
        "PILOT_ACCEL_Z" to ParamMeta("Vertical accel cm/s²", "250"),
        "PILOT_THR_BHV" to ParamMeta("Throttle behaviour", "0"),
        "WPNAV_SPEED" to ParamMeta("WP horiz speed cm/s", "500"),
        "WPNAV_SPEED_UP" to ParamMeta("WP climb speed cm/s", "250"),
        "WPNAV_SPEED_DN" to ParamMeta("WP descent speed cm/s", "150"),
        "WPNAV_ACCEL" to ParamMeta("WP horiz accel cm/s²", "250"),
        "WPNAV_RADIUS" to ParamMeta("WP acceptance radius cm", "200"),
        "RCMAP_ROLL" to ParamMeta("RC roll channel", "1"),
        "RCMAP_PITCH" to ParamMeta("RC pitch channel", "2"),
        "RCMAP_THROTTLE" to ParamMeta("RC throttle channel", "3"),
        "RCMAP_YAW" to ParamMeta("RC yaw channel", "4"),
        "RC_SPEED" to ParamMeta("ESC update rate Hz", "490"),
        "SERIAL0_BAUD" to ParamMeta("Serial0 baud", "115"),
        "SERIAL0_PROTOCOL" to ParamMeta("Serial0 protocol", "2", mapOf(-1 to "None", 1 to "MAVLink1", 2 to "MAVLink2")),
        "GPS_TYPE" to ParamMeta("GPS type", "1", mapOf(0 to "None", 1 to "Auto", 2 to "uBlox", 5 to "NMEA", 9 to "SBF")),
        "INS_GYRO_FILTER" to ParamMeta("Gyro LPF Hz", "20"),
        "INS_ACCEL_FILTER" to ParamMeta("Accel LPF Hz", "20"),
        "ATC_RAT_RLL_P" to ParamMeta("Roll rate P", "0.135"),
        "ATC_RAT_RLL_I" to ParamMeta("Roll rate I", "0.135"),
        "ATC_RAT_RLL_D" to ParamMeta("Roll rate D", "0.004"),
        "ATC_RAT_PIT_P" to ParamMeta("Pitch rate P", "0.135"),
        "ATC_RAT_PIT_I" to ParamMeta("Pitch rate I", "0.135"),
        "ATC_RAT_PIT_D" to ParamMeta("Pitch rate D", "0.004"),
        "ATC_RAT_YAW_P" to ParamMeta("Yaw rate P", "0.18"),
        "ATC_RAT_YAW_I" to ParamMeta("Yaw rate I", "0.018"),
        "ATC_RAT_YAW_D" to ParamMeta("Yaw rate D", "0"),
        "LAND_SPEED" to ParamMeta("Land speed cm/s", "50"),
        "LAND_SPEED_HIGH" to ParamMeta("Land speed high cm/s", "0"),
        "LAND_ALT_LOW" to ParamMeta("Land alt low cm", "1000"),
        "RTL_ALT" to ParamMeta("RTL altitude cm", "1500"),
        "RTL_SPEED" to ParamMeta("RTL speed cm/s", "0"),
        "RTL_ALT_FINAL" to ParamMeta("RTL final alt cm", "0"),
        "RTL_LOIT_TIME" to ParamMeta("RTL loiter time ms", "5000"),
        "FS_THR_ENABLE" to ParamMeta("Throttle failsafe", "1", mapOf(0 to "Disabled", 1 to "RTL", 2 to "Continue Auto", 3 to "Land")),
        "FS_THR_VALUE" to ParamMeta("Throttle FS PWM", "975"),
        "FS_GCS_ENABLE" to ParamMeta("GCS failsafe", "1", mapOf(0 to "Disabled", 1 to "RTL", 2 to "Continue Auto", 3 to "SmartRTL/RTL", 4 to "SmartRTL/Land", 5 to "Land")),
        "FS_BATT_ENABLE" to ParamMeta("Battery failsafe", "0", mapOf(0 to "Disabled", 1 to "Land", 2 to "RTL", 3 to "SmartRTL/RTL", 4 to "SmartRTL/Land")),
        "FS_EKF_ACTION" to ParamMeta("EKF failsafe", "1", mapOf(1 to "Land", 2 to "AltHold", 3 to "Land (even Stab)")),
        "MOT_SPIN_ARM" to ParamMeta("Motor spin armed", "0.1"),
        "MOT_SPIN_MIN" to ParamMeta("Motor spin min", "0.15"),
        "MOT_SPIN_MAX" to ParamMeta("Motor spin max", "0.95"),
        "MOT_BAT_VOLT_MAX" to ParamMeta("Batt volt comp max", "0"),
        "MOT_BAT_VOLT_MIN" to ParamMeta("Batt volt comp min", "0"),
        "MOT_THST_EXPO" to ParamMeta("Thrust expo", "0.65"),
        "SYSID_THISMAV" to ParamMeta("System ID", "1"),
        "SYSID_MYGCS" to ParamMeta("GCS system ID", "255"),
        "LOG_BITMASK" to ParamMeta("Logging bitmask", "176126"),
        "LOG_BACKEND_TYPE" to ParamMeta("Log backend", "1", mapOf(0 to "None", 1 to "File", 2 to "MAVLink", 3 to "Both")),
        "AHRS_EKF_TYPE" to ParamMeta("EKF type", "3", mapOf(2 to "EKF2", 3 to "EKF3", 11 to "ExtAHRS")),
        "SPRAY_ENABLE" to ParamMeta("Sprayer", "0", mapOf(0 to "Disabled", 1 to "Enabled")),
        "SPRAY_PUMP_RATE" to ParamMeta("Pump rate %", "10"),
        "SPRAY_SPINNER" to ParamMeta("Spinner speed", "3500"),
        "SPRAY_SPEED_MIN" to ParamMeta("Min spray speed cm/s", "100"),
        "EK3_SRC1_POSXY" to ParamMeta("EKF3 pos XY", "1", mapOf(0 to "None", 1 to "GPS", 2 to "Beacon", 3 to "OptFlow", 6 to "ExtNav")),
        "EK3_SRC1_VELXY" to ParamMeta("EKF3 vel XY", "1", mapOf(0 to "None", 1 to "GPS", 2 to "Beacon", 3 to "OptFlow", 5 to "InertialNav", 6 to "ExtNav")),
        "EK3_SRC1_POSZ" to ParamMeta("EKF3 pos Z", "1", mapOf(0 to "None", 1 to "Baro", 2 to "RangeFinder", 3 to "GPS", 4 to "Beacon", 6 to "ExtNav")),
        "TERRAIN_ENABLE" to ParamMeta("Terrain following", "1", mapOf(0 to "Disabled", 1 to "Enabled")),
        "COMPASS_USE" to ParamMeta("Compass 1", "1", mapOf(0 to "Disabled", 1 to "Enabled")),
        "COMPASS_USE2" to ParamMeta("Compass 2", "1", mapOf(0 to "Disabled", 1 to "Enabled")),
        "COMPASS_USE3" to ParamMeta("Compass 3", "1", mapOf(0 to "Disabled", 1 to "Enabled")),
        "COMPASS_AUTODEC" to ParamMeta("Auto declination", "1", mapOf(0 to "Disabled", 1 to "Enabled")),
        // Flight modes
        "FLTMODE1" to ParamMeta("Flight mode 1", "0", mapOf(0 to "Stabilize", 1 to "Acro", 2 to "AltHold", 3 to "Auto", 4 to "Guided", 5 to "Loiter", 6 to "RTL", 7 to "Circle", 9 to "Land", 11 to "Drift", 13 to "Sport", 14 to "Flip", 15 to "AutoTune", 16 to "PosHold", 17 to "Brake", 18 to "Throw", 19 to "Avoid_ADSB", 20 to "Guided_NoGPS", 21 to "SmartRTL", 22 to "FlowHold", 23 to "Follow", 24 to "ZigZag", 25 to "SystemID", 26 to "Heli_Autorotate", 27 to "Auto RTL")),
        "FLTMODE2" to ParamMeta("Flight mode 2", "0", mapOf(0 to "Stabilize", 1 to "Acro", 2 to "AltHold", 3 to "Auto", 4 to "Guided", 5 to "Loiter", 6 to "RTL", 7 to "Circle", 9 to "Land", 11 to "Drift", 13 to "Sport", 14 to "Flip", 15 to "AutoTune", 16 to "PosHold", 17 to "Brake", 18 to "Throw", 19 to "Avoid_ADSB", 20 to "Guided_NoGPS", 21 to "SmartRTL", 22 to "FlowHold", 23 to "Follow", 24 to "ZigZag", 25 to "SystemID", 26 to "Heli_Autorotate", 27 to "Auto RTL")),
        "FLTMODE3" to ParamMeta("Flight mode 3", "0", mapOf(0 to "Stabilize", 1 to "Acro", 2 to "AltHold", 3 to "Auto", 4 to "Guided", 5 to "Loiter", 6 to "RTL", 7 to "Circle", 9 to "Land", 11 to "Drift", 13 to "Sport", 14 to "Flip", 15 to "AutoTune", 16 to "PosHold", 17 to "Brake", 18 to "Throw", 19 to "Avoid_ADSB", 20 to "Guided_NoGPS", 21 to "SmartRTL", 22 to "FlowHold", 23 to "Follow", 24 to "ZigZag", 25 to "SystemID", 26 to "Heli_Autorotate", 27 to "Auto RTL")),
        "FLTMODE4" to ParamMeta("Flight mode 4", "0", mapOf(0 to "Stabilize", 1 to "Acro", 2 to "AltHold", 3 to "Auto", 4 to "Guided", 5 to "Loiter", 6 to "RTL", 7 to "Circle", 9 to "Land", 11 to "Drift", 13 to "Sport", 14 to "Flip", 15 to "AutoTune", 16 to "PosHold", 17 to "Brake", 18 to "Throw", 19 to "Avoid_ADSB", 20 to "Guided_NoGPS", 21 to "SmartRTL", 22 to "FlowHold", 23 to "Follow", 24 to "ZigZag", 25 to "SystemID", 26 to "Heli_Autorotate", 27 to "Auto RTL")),
        "FLTMODE5" to ParamMeta("Flight mode 5", "0", mapOf(0 to "Stabilize", 1 to "Acro", 2 to "AltHold", 3 to "Auto", 4 to "Guided", 5 to "Loiter", 6 to "RTL", 7 to "Circle", 9 to "Land", 11 to "Drift", 13 to "Sport", 14 to "Flip", 15 to "AutoTune", 16 to "PosHold", 17 to "Brake", 18 to "Throw", 19 to "Avoid_ADSB", 20 to "Guided_NoGPS", 21 to "SmartRTL", 22 to "FlowHold", 23 to "Follow", 24 to "ZigZag", 25 to "SystemID", 26 to "Heli_Autorotate", 27 to "Auto RTL")),
        "FLTMODE6" to ParamMeta("Flight mode 6", "0", mapOf(0 to "Stabilize", 1 to "Acro", 2 to "AltHold", 3 to "Auto", 4 to "Guided", 5 to "Loiter", 6 to "RTL", 7 to "Circle", 9 to "Land", 11 to "Drift", 13 to "Sport", 14 to "Flip", 15 to "AutoTune", 16 to "PosHold", 17 to "Brake", 18 to "Throw", 19 to "Avoid_ADSB", 20 to "Guided_NoGPS", 21 to "SmartRTL", 22 to "FlowHold", 23 to "Follow", 24 to "ZigZag", 25 to "SystemID", 26 to "Heli_Autorotate", 27 to "Auto RTL")),
    )
}

