# install.ps1 <target>
#   target: "" (any online device) | "phone" | "tablet"
#
# Builds are NOT handled here or by the `just install` recipe — build with
# `just build` first; this script only resolves the target device and runs
# `adb install` on the APK already in app/build/outputs.
param(
    [string]$Target = ""
)

$ErrorActionPreference = 'Stop'

$tabletSerial = $env:TABLET_SERIAL
$phoneSerial = $env:PHONE_SERIAL

$APK = "app/build/outputs/apk/debug/app-debug.apk"
if (-not (Test-Path $APK)) {
    Write-Error "error: $APK not found — run `just build` first"
    exit 1
}

# Get online device serials
$devicesOutput = adb devices 2>$null
$serials = $devicesOutput | Select-String -Pattern '^\S+\s+device$' | ForEach-Object {
    ($_ -split '\s+')[0]
}

if ($serials.Count -eq 0) {
    Write-Error "error: no ADB devices connected (see: adb devices)"
    exit 1
}

# Check if device is tablet
function Test-IsTablet {
    param([string]$Serial)
    $ch = (adb -s $Serial shell getprop ro.build.characteristics 2>$null) -replace '[\r\n]', ''
    return $ch -match 'tablet'
}

$serial = ""

if ($Target -eq "tablet" -and $tabletSerial) {
    $serial = $tabletSerial
} elseif ($Target -eq "phone" -and $phoneSerial) {
    $serial = $phoneSerial
} elseif ($Target -and $Target -ne "phone" -and $Target -ne "tablet") {
    Write-Error "error: unknown target `"$Target`" (use: phone | tablet | <empty>)"
    exit 1
} else {
    # Auto-detect from build characteristics
    foreach ($s in $serials) {
        if ($Target -eq "tablet") {
            if (Test-IsTablet $s) { $serial = $s; break }
        } elseif ($Target -eq "phone") {
            if (-not (Test-IsTablet $s)) { $serial = $s; break }
        } else {
            $serial = $s; break  # no target: just take the first online device
        }
    }
}

if (-not $serial) {
    Write-Error "error: no $Target device found among: $($serials -join ', ')"
    Write-Host "hint: set TABLET_SERIAL / PHONE_SERIAL env vars, or connect the device" -ForegroundColor Cyan
    exit 1
}

if ((adb -s $serial get-state 2>$null) -notmatch 'device') {
    Write-Error "error: device $serial is not online"
    exit 1
}

Write-Host ">> Installing $(Split-Path $APK -Leaf) to $serial ($Target)"
adb -s $serial install -r -t $APK
