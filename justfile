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

# ---------------------------------------------------------------------------
# Release pipeline (signed APK → GitHub Release → Obtainium permanent link)
#
#   just release            Build signed release APK -> dist/amusic.apk (+ archive) + self-check
#   just release-verify     Only self-check the APK already in dist/
#   just release-bump       versionCode+1, versionName last segment+1
#   just release-publish    Tag + GitHub Release (fixed asset name) + verify download link
#
# Obtainium tracks the permanent direct link:
#   https://github.com/PT123123/a-music/releases/latest/download/amusic.apk
# Requires: keystore.properties (gitignored) for a signed build, and `gh` auth for publish.

# Build a signed release APK and self-check it.
release:
    pwsh ./scripts/release.ps1 build

# Only self-check the APK already in dist/ (no build).
release-verify:
    pwsh ./scripts/release.ps1 verify

# Bump versionCode + versionName for the next release, then re-run `just release`.
release-bump:
    pwsh ./scripts/release.ps1 bump

# Tag, push a GitHub Release with a fixed asset name, and verify the download link.
release-publish:
    pwsh ./scripts/release.ps1 publish
