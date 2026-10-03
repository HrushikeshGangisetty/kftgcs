import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
//    alias(libs.plugins.google.gms.google.services)
    alias(libs.plugins.ksp)
}

// Load local.properties for API keys
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "com.example.kftgcs"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kft.gcs"
        minSdk = 26
        targetSdk = 36
        versionCode = 35
        versionName = "1.3.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inject Maps API key from local.properties into AndroidManifest.xml
        manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("MAPS_API_KEY") ?: ""

        // KFT authentication secret (hex-encoded 32-byte key, stored only in local.properties)
        buildConfigField(
            "String",
            "KFT_APP_SECRET",
            "\"${localProperties.getProperty("KFT_APP_SECRET") ?: ""}\""
        )
    }

    // White-label brand dimension. All code/UI/DB/API stay shared in main;
    // only applicationId, app_name and launcher icons differ per flavor.
    flavorDimensions += "brand"
    productFlavors {
        create("original") {
            dimension = "brand"
            // Current state — no overrides. Uses defaultConfig applicationId
            // and the app_name from src/main/res/values/strings.xml.
        }
        create("svd") {
            dimension = "brand"
            // applicationId stays com.svd.gcs — the app is already published on
            // Play under it. Only the displayed name changes.
            applicationId = "com.svd.gcs"
            // Override app name without touching main strings.xml.
            resValue("string", "app_name", "KGCS")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false

            // API Configuration for DEBUG builds
            // Uses local.properties values or defaults for development
            val debugApiUrl = localProperties.getProperty("DEBUG_API_URL") ?: "https://kftgcs.com"
            val debugServerIp = localProperties.getProperty("DEBUG_SERVER_IP") ?: "kftgcs.com"
            val debugServerPort = localProperties.getProperty("DEBUG_SERVER_PORT") ?: "443"

            buildConfigField("String", "API_BASE_URL", "\"$debugApiUrl\"")
            buildConfigField("String", "SERVER_IP", "\"$debugServerIp\"")
            buildConfigField("String", "SERVER_PORT", "\"$debugServerPort\"")
            buildConfigField("Boolean", "USE_PRODUCTION_SERVER", "false")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // API Configuration for RELEASE builds
            // IMPORTANT: Set PRODUCTION_API_URL in local.properties before release
            val productionApiUrl = localProperties.getProperty("PRODUCTION_API_URL") ?: "https://kftgcs.com"

            buildConfigField("String", "API_BASE_URL", "\"$productionApiUrl\"")
            buildConfigField("String", "SERVER_IP", "\"\"") // Not used in production
            buildConfigField("String", "SERVER_PORT", "\"\"") // Not used in production
            buildConfigField("Boolean", "USE_PRODUCTION_SERVER", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true  // Enable BuildConfig generation
    }
    lint {
        baseline = file("lint-baseline.xml")
    }
}
dependencies {
    // Core + lifecycle
    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.fragment:fragment:1.8.5") // lint: an older transitive fragment trips InvalidFragmentVersionForActivityResult

    // Compose BOM (manages versions automatically)
    implementation(platform(libs.androidx.compose.bom))
    implementation("com.google.android.gms:play-services-maps:19.2.0")

    implementation("com.google.maps.android:maps-compose:4.4.2")
    // SphericalUtil (polygon area, distances) — GridUtils, GcsMap, MainPage
    implementation("com.google.maps.android:android-maps-utils:3.8.2")

    // Compose UI
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)

    // Material3 (only one source, from libs)
    implementation(libs.androidx.material3)

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.2")

    // Material Icons Extended (choose one approach → using BOM-managed one)
    implementation("androidx.compose.material:material-icons-extended")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
dependencies {
    implementation(libs.androidx.runtime.livedata)

    // MAVLink message definitions (standard dialects like common.xml)
    implementation("com.divpundir.mavlink:definitions:1.2.8")


    // TCP connection client
    implementation("com.divpundir.mavlink:connection-tcp:1.2.8")

    // UDP connection (server = bind and wait, like Mission Planner / QGroundControl)
    implementation("com.divpundir.mavlink:connection-udp:1.2.8")


    // Coroutines adapter (recommended for Android)
    implementation("com.divpundir.mavlink:adapter-coroutines:1.2.8")

    // USB OTG serial transport (FTDI/CP210x/CH340/Prolific/CDC)
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")

    // Room database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Gson for JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")

    // OkHttp WebSocket for telemetry streaming
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ViewModel and LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")


    // Google Play Services Location for phone GPS in RC mode
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // AndroidX Security for EncryptedSharedPreferences (secure session storage)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Note: Using alpha version as it has better compatibility with newer Android versions
    // The stable 1.0.0 version has known issues with Android 12+

    // Timber for secure logging (only logs in debug builds)
    implementation("com.jakewharton.timber:timber:5.0.1")

    // WorkManager — background offline-sync worker
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Google Play In-App Updates — "new version available" prompt (FLEXIBLE flow)
    implementation(libs.play.app.update)

    // Media3 / ExoPlayer — live RTSP camera feed (SIYI A8 mini / ZT6 via MK15 air unit).
    // Android's built-in MediaPlayer cannot decode these streams; it connects and
    // then buffers forever, so ExoPlayer's RTSP source is required.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    implementation(libs.androidx.media3.ui)

    // Skydroid T12 video: no separate dependency needed. Confirmed (2026-09-22)
    // that the T12 does not expose USB Video Class — its video is decoded via
    // Android's built-in MediaCodec after being demuxed from the existing
    // usb-serial-for-android link (see T12SerialVideoSource.kt).
}
