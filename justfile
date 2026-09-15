# Justfile for a-music — Android music player (libmpv audio-only via JNI)
#
# Requires: adb on PATH, Android SDK (ANDROID_HOME), NDK installed, and a
# prebuilt audio-only libmpv.so at app/src/main/prebuilt/<abi>/libmpv.so
# (see gradle.properties `mpv.dir`).
#
# Recipes:
#   just build              Build the debug APK
#   just install            Install the existing APK to any online device
#   just install phone      Install the existing APK to the phone
#   just install tablet     Install the existing APK to the tablet
#   just devices            List connected ADB devices
#
# `just install` never builds: run `just build` first, then install as often as you like.
# Device auto-detection can be overridden with TABLET_SERIAL=abc123 / PHONE_SERIAL=def456.

set shell := ["pwsh", "-NoProfile", "-NonInteractive", "-Command"]

# Build the debug APK.
build:
    if (Test-Path ./gradlew.bat) { ./gradlew.bat assembleDebug } else { ./gradlew assembleDebug }

# List connected ADB devices (debug helper).
devices:
    adb devices -l

# Install the APK already in app/build/outputs to a device — never builds.
install target="":
    pwsh ./scripts/install.ps1 "{{target}}"
