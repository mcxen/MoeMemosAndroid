#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

cd "$ROOT"
./gradlew assembleDebug
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"

if ! adb devices | grep -qE 'device$'; then
  echo "No device; starting emulator MoeMemos_API34..."
  nohup emulator -avd MoeMemos_API34 -netdelay none -netspeed full >/tmp/emulator-moememos.log 2>&1 &
  adb wait-for-device
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
fi

adb install -r "$APK"
adb shell am start -n me.mudkip.moememos/.MainActivity
echo "Installed and launched. APK: $APK"
