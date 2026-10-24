#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../android"

# The emulator action runs script lines in separate shells. Keep this flow in
# one process so status handling and conditional screenshot capture stay intact.
test_status=0
./gradlew --no-daemon -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true connectedDebugAndroidTest || test_status=$?
mkdir -p app/build/reports/ui
adb pull /sdcard/Android/data/ke.co.pichalite.preview04/files/ui/. app/build/reports/ui/ || true
adb pull /sdcard/Android/data/ke.co.pichalite.preview04/files/wrapped-preview/. app/build/reports/ui/wrapped/ || true
if [ "$test_status" -ne 0 ]; then
    adb logcat -d -t 500
    exit "$test_status"
fi
adb install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
adb shell am start -n ke.co.pichalite.preview04/ke.co.pichalite.MainActivity
sleep 3
adb exec-out screencap -p > app/build/reports/ui/studio.png
