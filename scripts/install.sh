#!/usr/bin/env bash
# install.sh <target>
#   target: "" (any online device) | "phone" | "tablet"
#
# Builds are handled by the `just install` recipe (which depends on `build`);
# this script only resolves the target device and runs `adb install`.
set -euo pipefail

target="${1:-}"
tablet_serial="${TABLET_SERIAL:-}"
phone_serial="${PHONE_SERIAL:-}"

APK=app/build/outputs/apk/debug/app-debug.apk
if [ ! -f "$APK" ]; then
    echo "error: $APK not found — run \`just build\` first" >&2
    exit 1
fi

# Online device serials
mapfile -t SERIALS < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ "${#SERIALS[@]}" -eq 0 ]; then
    echo "error: no ADB devices connected (see: adb devices)" >&2
    exit 1
fi

# A tablet reports "tablet" in ro.build.characteristics; phones do not.
is_tablet() {
    local ch
    ch=$(adb -s "$1" shell getprop ro.build.characteristics 2>/dev/null | tr -d '\r')
    echo "$ch" | grep -qi "tablet"
}

SERIAL=""
if [ "$target" = "tablet" ] && [ -n "$tablet_serial" ]; then
    SERIAL="$tablet_serial"
elif [ "$target" = "phone" ] && [ -n "$phone_serial" ]; then
    SERIAL="$phone_serial"
elif [ -n "$target" ] && [ "$target" != "phone" ] && [ "$target" != "tablet" ]; then
    echo "error: unknown target \"$target\" (use: phone | tablet | <empty>)" >&2
    exit 1
else
    # Auto-detect from build characteristics
    for s in "${SERIALS[@]}"; do
        if [ "$target" = "tablet" ]; then
            if is_tablet "$s"; then SERIAL="$s"; break; fi
        elif [ "$target" = "phone" ]; then
            if ! is_tablet "$s"; then SERIAL="$s"; break; fi
        else
            SERIAL="$s"; break   # no target: just take the first online device
        fi
    done
fi

if [ -z "$SERIAL" ]; then
    echo "error: no $target device found among: ${SERIALS[*]}" >&2
    echo "hint: set TABLET_SERIAL / PHONE_SERIAL env vars, or connect the device" >&2
    exit 1
fi

if ! adb -s "$SERIAL" get-state 2>/dev/null | grep -q device; then
    echo "error: device $SERIAL is not online" >&2
    exit 1
fi

echo ">> Installing $(basename "$APK") to $SERIAL ($target)"
adb -s "$SERIAL" install -r -t "$APK"
