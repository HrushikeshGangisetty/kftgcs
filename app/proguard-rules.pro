# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Preserve line numbers so RELEASE crash stack traces (Play Console → Android
# vitals, and our local crash logs) point at real line numbers instead of
# being unreadable after R8 obfuscation. The matching mapping.txt that R8
# generates is auto-bundled into the AAB and uploaded to Play Console, which
# deobfuscates the traces for you.
-keepattributes SourceFile,LineNumberTable

# Hide the original source file name (replace with "SourceFile") while still
# keeping the line numbers above.
-renamesourcefileattribute SourceFile

# ============================================
# GSON Serialization: Keep data class field names
# Without this, R8/ProGuard obfuscates field names (e.g., "email" -> "a")
# causing the backend to receive unrecognized JSON keys and return
# "error: fields required"
# ============================================

# Keep ALL API request/response model classes (preserves field names for Gson)
# Using a wildcard so that any new models added to this package are automatically covered.
-keep class com.example.kftgcs.api.** { *; }

# Param Management uses a SEPARATE auth API (ParamAuthApiService) whose request/
# response models (ParamLoginRequest/Response, ParamUser, etc.) live in this
# package and are (de)serialized with Gson via reflection. These are only ever
# instantiated through Gson's Unsafe allocator, so in R8 full mode (release/AAB)
# R8 sees "no real instances", prunes/optimizes the fields, and deserialization
# returns nulls or throws — even though the @SerializedName member rule is present.
# Debug works only because isMinifyEnabled=false. Keep the package fully so the
# Param login (and other Gson models here) behave identically in release builds.
-keep class com.example.kftgcs.parammanagement.** { *; }

# Keep Gson TypeToken and related classes
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Keep all classes that use @SerializedName annotation
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ============================================
# OkHttp: Keep OkHttp and Okio classes
# ============================================
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ============================================
# Timber: Keep Timber logging framework
# ============================================
-dontwarn timber.log.**

# ============================================
# Room Database: Keep all entities, DAOs, and TypeConverters
# R8 obfuscation renames field names, which breaks Gson
# serialization/deserialization used by Room TypeConverters.
# This caused crashes on physical devices (release builds)
# when saving mission templates.
# ============================================
-keep class com.example.kftgcs.database.** { *; }
-keep class com.example.kftgcs.database.obstacle.** { *; }
-keep class com.example.kftgcs.database.tlog.** { *; }

# ============================================
# MAVLink: Keep MAVLink library classes
# MissionItemInt and MavEnumValue are serialized via Gson
# reflection in MissionTemplateTypeConverters.
# ============================================
-keep class com.divpundir.mavlink.** { *; }
-keep class io.dronefleet.mavlink.** { *; }

# MAVLink connection transports (TCP/UDP/Bluetooth/USB). These are instantiated
# only through the MavConnectionProvider interface, and BufferedMavConnection is
# driven via okio Source/Sink adapters, so R8 full mode sees few direct
# references. Keep the transport package intact so the UDP DatagramSocket
# adapters (UdpMavConnection's nested UdpInputStream/UdpOutputStream) and their
# okio bridges survive shrinking.
-keep class com.example.kftgcs.telemetry.connections.** { *; }

# kotlinx-atomicfu backs BufferedMavConnection's sequence counter and locks.
# It is transitively pulled in by the MAVLink adapter and is not referenced
# directly by app code.
-dontwarn kotlinx.atomicfu.**
-keep class kotlinx.atomicfu.** { *; }

# ============================================
# App data model classes used with Gson serialization
# ============================================
-keep class com.example.kftgcs.obstacle.** { *; }
-keep class com.example.kftgcs.repository.** { *; }
-keep class com.example.kftgcs.viewmodel.MissionTemplateUiState { *; }

# ============================================
# Kotlin: Keep reflection and metadata
# Required for Gson deserialization of Kotlin data classes
# and MAVLink's reflection-based message handling
# ============================================
-keep class kotlin.reflect.** { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers class * {
    @kotlin.Metadata *;
}

# ============================================
# Google Maps: Keep LatLng and model classes
# Used in Gson serialization of waypoint positions
# ============================================
-keep class com.google.android.gms.maps.model.** { *; }

# ============================================
# BuildConfig: fields read via reflection (Class.forName + getField) in
# ApiService.kt — API_BASE_URL, SERVER_IP, SERVER_PORT. R8 inlines/strips
# string constants that are never referenced directly, so getField() would
# throw NoSuchFieldException in release and the app would fall back to wrong
# server config. Keep the class and its fields.
# ============================================
-keep class com.example.kftgcs.BuildConfig { *; }

# ============================================
# PRODUCTION BUILD: Remove Android Log statements
#
# NOTE: -assumenosideeffects tells R8 the call itself is side-effect free, so it
# removes the call AND any argument expression whose only use was that call.
# Verified (2026-09-08) that no Log.* argument in this app performs work that
# must still happen in release: the two candidates, WebSocketManager's
# resolveDroneUid() and dao.countPending(), are both pure reads, and the
# telemetrySendCount++ increment sits on its own statement outside the call.
# Re-check this if you ever inline a mutation into a Log argument.
#
# Log.e/wtf are deliberately NOT stripped: real errors should still reach
# logcat and Play Console crash breadcrumbs on production devices. LogUtils
# already gates its own e() behind BuildConfig.DEBUG for routine noise, so
# what remains here is genuine error reporting worth keeping.
# ============================================
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** i(...);
    public static *** v(...);
    public static *** w(...);
}