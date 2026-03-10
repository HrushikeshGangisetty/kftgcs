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

