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

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ============================================
# GSON Serialization: Keep data class field names
# Without this, R8/ProGuard obfuscates field names (e.g., "email" -> "a")
# causing the backend to receive unrecognized JSON keys and return
# "error: fields required"
# ============================================

# Keep all API request/response model classes (preserves field names for Gson)
-keep class com.example.kftgcs.api.PilotRegisterRequest { *; }
-keep class com.example.kftgcs.api.PilotLoginRequest { *; }
-keep class com.example.kftgcs.api.PilotLogoutRequest { *; }
-keep class com.example.kftgcs.api.VerifyOtpRequest { *; }
-keep class com.example.kftgcs.api.ResendOtpRequest { *; }
-keep class com.example.kftgcs.api.PilotRegisterResponse { *; }
-keep class com.example.kftgcs.api.PilotLoginResponse { *; }
-keep class com.example.kftgcs.api.MessageResponse { *; }
-keep class com.example.kftgcs.api.ErrorResponse { *; }

# Keep Gson TypeToken and related classes
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

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
# PRODUCTION BUILD: Remove all Android Log statements
# This strips out all Log.d, Log.i, Log.v, Log.w, and Log.e calls
# in release builds to improve performance and reduce log spam
# ============================================
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** i(...);
    public static *** v(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}

# ============================================
# MAVLink Libraries: Keep all MAVLink classes
# These use reflection and serialization internally.
# Without these rules, R8 strips/obfuscates them causing
# RuntimeException on TCP/Bluetooth connect.
# ============================================

# divpundir MAVLink library (definitions, TCP connection, coroutines adapter)
-keep class com.divpundir.mavlink.** { *; }
-keepclassmembers class com.divpundir.mavlink.** { *; }
-dontwarn com.divpundir.mavlink.**

# dronefleet MAVLink library
-keep class io.dronefleet.mavlink.** { *; }
-keepclassmembers class io.dronefleet.mavlink.** { *; }
-dontwarn io.dronefleet.mavlink.**

# ============================================
# Kotlin Coroutines: Prevent stripping of coroutine internals
# ============================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ============================================
# App Telemetry: Keep telemetry data classes and enums
# These are used with reflection and flow serialization
# ============================================
-keep class com.example.kftgcs.Telemetry.** { *; }
-keep class com.example.kftgcs.telemetry.** { *; }
-keep enum com.example.kftgcs.telemetry.ConnectionType { *; }

