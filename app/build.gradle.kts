plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.gms.google.services)
    id("kotlin-kapt")
}

android {
    namespace = "com.example.aerogcsclone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.aerogcsclone"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }
}
dependencies {
    // Core + lifecycle
    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose BOM (manages versions automatically)
    implementation(platform(libs.androidx.compose.bom))
    implementation("com.google.android.gms:play-services-maps:19.2.0")

    // Core Maps SDK (version will be managed by the BOM)
//    implementation("com.google.android.gms:play-services-maps")
    implementation("com.google.maps.android:maps-compose:4.4.2") // You had 4.4.2, which is good.

    // Maps Utils (for clustering, GeoJSON, KML, heatmaps, etc.)
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
    implementation(libs.firebase.auth)

    // MAVLink message definitions (standard dialects like common.xml)
    implementation("com.divpundir.mavlink:definitions:1.2.8")


    // TCP connection client
    implementation("com.divpundir.mavlink:connection-tcp:1.2.8")


    // Coroutines adapter (recommended for Android)
    implementation("com.divpundir.mavlink:adapter-coroutines:1.2.8")

    // Room database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Gson for JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")

    // OkHttp WebSocket for telemetry streaming
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ViewModel and LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // MAVLink Java library (example, adjust if you use a different one)
    implementation("io.dronefleet.mavlink:mavlink:1.0.7")

    // Accompanist System UI Controller for status bar control
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.34.0")

    implementation(platform("com.google.firebase:firebase-bom:33.13.0"))
    implementation ("androidx.navigation:navigation-compose:2.7.6")
    implementation ("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation ("com.google.firebase:firebase-auth-ktx:22.3.1")
    implementation ("com.google.android.gms:play-services-auth:20.7.0")
}
