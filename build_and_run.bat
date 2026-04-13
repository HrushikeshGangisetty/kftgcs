@echo off
REM Build and Run Script for SampleGCS

echo ========================================
echo Building and Installing SampleGCS App
echo ========================================

REM Set Android SDK path
set ANDROID_SDK=C:\Users\Sreenija\AppData\Local\Android\Sdk

REM Add platform-tools and build-tools to PATH
set PATH=%ANDROID_SDK%\platform-tools;%ANDROID_SDK%\build-tools;%PATH%

REM Check if device/emulator is connected
echo.
echo Checking for connected devices/emulators...
adb devices

REM Wait for device
adb wait-for-device

REM Navigate to project directory
cd /d "%~dp0"

REM Use gradlew with Android Studio's embedded JDK (if available)
set JAVA_HOME=%LOCALAPPDATA%\Android\AndroidStudio\jbr

REM Clean and build debug APK
echo.
echo Building debug APK...
call gradlew.bat clean assembleDebug

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ❌ Build failed!
    pause
    exit /b 1
)

echo.
echo ✅ Build successful!

REM Install APK on device/emulator
echo.
echo Installing APK on device/emulator...
adb install -r app\build\outputs\apk\debug\app-debug.apk

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ❌ Installation failed!
    pause
    exit /b 1
)

echo.
echo ✅ Installation successful!

REM Launch the app
echo.
echo Launching the app...
adb shell am start -n com.kft.gcs/com.example.kftgcs.MainActivity

echo.
echo ✅ App launched successfully!
echo.
pause

