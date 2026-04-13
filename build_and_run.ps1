# Build and Run Script for SampleGCS
Write-Host "========================================"
Write-Host "Building and Installing SampleGCS App" -ForegroundColor Cyan
Write-Host "========================================"

# Set Android SDK path
$androidSdk = "C:\Users\Sreenija\AppData\Local\Android\Sdk"
$platformTools = "$androidSdk\platform-tools"

# Add platform-tools to PATH for this session
$env:Path = "$platformTools;$env:Path"

# Set JAVA_HOME to Android Studio's embedded JDK
$possibleJdkPaths = @(
    "$env:LOCALAPPDATA\Android\AndroidStudio\jbr",
    "$env:LOCALAPPDATA\Android\Studio\jbr",
    "C:\Program Files\Android\Android Studio\jbr",
    "$env:ProgramFiles\Android\Android Studio\jbr"
)

$javaHome = $null
foreach ($path in $possibleJdkPaths) {
    if (Test-Path $path) {
        $javaHome = $path
        break
    }
}

if ($javaHome) {
    $env:JAVA_HOME = $javaHome
    Write-Host "✅ Using Android Studio's embedded JDK: $javaHome" -ForegroundColor Green
} else {
    Write-Host "⚠️ Android Studio JDK not found. Please ensure Android Studio is installed." -ForegroundColor Yellow
    Write-Host "⚠️ Trying to continue anyway..." -ForegroundColor Yellow
}

# Navigate to project directory
Set-Location "C:\Users\Sreenija\AndroidStudioProjects\SampleGCS"

# Check if device/emulator is connected
Write-Host "`nChecking for connected devices/emulators..." -ForegroundColor Yellow
$devices = & adb devices | Select-String "device$"

if ($devices.Count -eq 0) {
    Write-Host "❌ No emulator or device detected!" -ForegroundColor Red
    Write-Host ""
    Write-Host "Please do ONE of the following:" -ForegroundColor Yellow
    Write-Host "1. Start an Android emulator from Android Studio (AVD Manager)" -ForegroundColor Cyan
    Write-Host "2. Connect a physical Android device via USB with USB debugging enabled" -ForegroundColor Cyan
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "✅ Device/emulator found!" -ForegroundColor Green

# Clean and build debug APK
Write-Host "`nBuilding debug APK..." -ForegroundColor Yellow
& .\gradlew.bat clean assembleDebug

if ($LASTEXITCODE -ne 0) {
    Write-Host "`n❌ Build failed!" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "`n✅ Build successful!" -ForegroundColor Green

# Install APK on device/emulator
Write-Host "`nInstalling APK on device/emulator..." -ForegroundColor Yellow
& adb install -r app\build\outputs\apk\debug\app-debug.apk

if ($LASTEXITCODE -ne 0) {
    Write-Host "`n❌ Installation failed!" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "`n✅ Installation successful!" -ForegroundColor Green

# Launch the app
Write-Host "`nLaunching the app..." -ForegroundColor Yellow
& adb shell am start -n com.kft.gcs/com.example.kftgcs.MainActivity

Write-Host "`n✅ App launched successfully!" -ForegroundColor Green
Write-Host ""
Read-Host "Press Enter to exit"

