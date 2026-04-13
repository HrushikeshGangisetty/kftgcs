# Quick Start Emulator and Run App
Write-Host "========================================"
Write-Host "SampleGCS - Quick Start" -ForegroundColor Cyan
Write-Host "========================================"

# Set Android SDK path
$androidSdk = "C:\Users\Sreenija\AppData\Local\Android\Sdk"
$emulatorPath = "$androidSdk\emulator\emulator.exe"
$platformTools = "$androidSdk\platform-tools"

# Add to PATH
$env:Path = "$androidSdk\emulator;$platformTools;$env:Path"

Write-Host "`nStep 1: Checking for available emulators..." -ForegroundColor Yellow

# List available emulators
if (Test-Path $emulatorPath) {
    $avds = & $emulatorPath -list-avds

    if ($avds) {
        Write-Host "✅ Found the following emulators:" -ForegroundColor Green
        $avds | ForEach-Object { Write-Host "   - $_" -ForegroundColor Cyan }

        Write-Host "`nStep 2: Starting the first emulator..." -ForegroundColor Yellow
        $firstAvd = $avds[0]
        Write-Host "Starting: $firstAvd" -ForegroundColor Cyan

        # Start emulator in background
        Start-Process -FilePath $emulatorPath -ArgumentList "-avd", $firstAvd, "-no-snapshot-load" -WindowStyle Normal

        Write-Host "✅ Emulator is starting (this may take 30-60 seconds)..." -ForegroundColor Green
        Write-Host ""
        Write-Host "Waiting for emulator to boot..." -ForegroundColor Yellow

        # Wait for device to be online
        $timeout = 120
        $elapsed = 0
        while ($elapsed -lt $timeout) {
            Start-Sleep -Seconds 2
            $elapsed += 2
            $bootComplete = & adb shell getprop sys.boot_completed 2>$null
            if ($bootComplete -eq "1") {
                Write-Host "✅ Emulator is ready!" -ForegroundColor Green
                break
            }
            Write-Host "." -NoNewline
        }

        if ($elapsed -ge $timeout) {
            Write-Host "`n⚠️ Emulator is taking longer than expected..." -ForegroundColor Yellow
            Write-Host "Continuing anyway..." -ForegroundColor Yellow
        }

        Write-Host "`n`nStep 3: Building and installing app..." -ForegroundColor Yellow
        Write-Host "This will now run the build_and_run.ps1 script..." -ForegroundColor Cyan
        Write-Host ""

        # Run the main build script
        & "$PSScriptRoot\build_and_run.ps1"

    } else {
        Write-Host "❌ No emulators found!" -ForegroundColor Red
        Write-Host ""
        Write-Host "Please create an emulator using Android Studio:" -ForegroundColor Yellow
        Write-Host "1. Open Android Studio" -ForegroundColor Cyan
        Write-Host "2. Go to Tools > Device Manager" -ForegroundColor Cyan
        Write-Host "3. Click 'Create Device' and follow the wizard" -ForegroundColor Cyan
        Write-Host ""
    }
} else {
    Write-Host "❌ Android SDK Emulator not found at: $emulatorPath" -ForegroundColor Red
    Write-Host ""
    Write-Host "Please ensure Android Studio and the Android SDK are properly installed." -ForegroundColor Yellow
    Write-Host ""
}

Read-Host "Press Enter to exit"

