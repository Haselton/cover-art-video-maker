#!/usr/bin/env bash
set -euo pipefail

PACKAGE=com.haseltonmediagroup.coverartvideomaker
REMOTE=/sdcard/Android/data/$PACKAGE/files/regression
collect_diagnostics() {
  adb logcat -d > test-logcat.txt 2>&1 || true
  if [ ! -d test-exports ]; then
    adb pull "$REMOTE" test-exports || true
  fi
}
trap collect_diagnostics EXIT

# Calling instrumentation directly avoids Gradle's automatic uninstall, which
# removes app-specific files before FFmpeg can inspect the real exported MP4s.
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
timeout 600s adb shell am instrument -w -r \
  "$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee instrumentation-results.txt
# The adb process may return zero even when a JUnit test failed.
grep -Eq '^OK \(6 tests\)' instrumentation-results.txt
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' instrumentation-results.txt; then
  exit 1
fi
adb pull "$REMOTE" test-exports
for name in stereo_44100 stereo_48000 mono_48000 pcm24_48000 long_48000 aac_source aac_copy; do
  test -s "test-exports/$name.mp4"
done

# Launch the same application APK and retain a real UI screenshot and hierarchy.
adb shell am start -W -n "$PACKAGE/.MainActivity" | tee ui-launch.txt
grep -Eq '^Status: ok' ui-launch.txt
sleep 2
test -n "$(adb shell pidof "$PACKAGE" | tr -d '\r')"
adb shell uiautomator dump /sdcard/cover-ui.xml
adb pull /sdcard/cover-ui.xml ui-hierarchy.xml
adb exec-out screencap -p > ui-screenshot.png
