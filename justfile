# Justfile for a-music — Android music player (libmpv audio-only via JNI)
#
# Requires: adb on PATH, Android SDK (ANDROID_HOME), NDK installed, and a
# prebuilt audio-only libmpv.so at app/src/main/prebuilt/<abi>/libmpv.so
# (see gradle.properties `mpv.dir`).
#
# Recipes:
#   just build              Build the debug APK
#   just install            Install to any online device (builds first)
#   just install phone      Install to the phone
#   just install tablet     Install to the tablet
#   just devices            List connected ADB devices

set shell := ["pwsh", "-NoProfile", "-NonInteractive", "-Command"]

# Build the debug APK.
build:
    if (Test-Path ./gradlew.bat) { ./gradlew.bat assembleDebug } else { ./gradlew assembleDebug }

# List connected ADB devices (debug helper).
devices:
    adb devices -l

# Install to a device. `target` is one of: "" (any), "phone", "tablet".
# Builds first, then resolves the serial and runs `adb install -r`.
# Override auto-detection with env vars:
#   TABLET_SERIAL=abc123 just install tablet
#   PHONE_SERIAL=def456  just install phone
install target="": build
    pwsh ./scripts/install.ps1 "{{target}}"
