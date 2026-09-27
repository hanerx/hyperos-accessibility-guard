#!/bin/bash
# Installs and configures NoScroll Guard on a connected phone, building it first
# unless you hand it a prebuilt APK.
#
#   ./install.sh                       build from source, then install
#   ./install.sh --apk path/to.apk     install a downloaded release APK
#
# Requires on the phone (Developer options, both need a Mi account):
#   - USB debugging (Security settings)  — to grant permissions
#   - Install via USB                    — to install over ADB
set -e

PKG=io.github.haku4130.noscrollguard
APK=app/build/outputs/apk/debug/app-debug.apk
BUILD=yes

case "$1" in
  --apk)
    [ -n "$2" ] || { echo "--apk needs a path to an APK"; exit 1; }
    [ -f "$2" ] || { echo "No such file: $2"; exit 1; }
    APK="$2"
    BUILD=no
    ;;
  '') ;;
  *) echo "Unknown option: $1 (expected --apk PATH)"; exit 1 ;;
esac

if [ "$BUILD" = yes ]; then
  export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
  export PATH="$JAVA_HOME/bin:$PATH"
  export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
fi

if ! adb devices | grep -q "device$"; then
  echo "No device connected (check the cable and pick 'File transfer' mode)"
  exit 1
fi

if [ "$BUILD" = yes ]; then
  echo "=== 1. Build ==="
  ./gradlew assembleDebug -q
else
  echo "=== 1. Build === skipped, installing $APK"
fi

echo "=== 2. Install ==="
adb install -r "$APK"

echo "=== 3. Permissions ==="
for P in WRITE_SECURE_SETTINGS PACKAGE_USAGE_STATS DUMP GET_APP_OPS_STATS; do
  adb shell pm grant $PKG android.permission.$P 2>/dev/null && echo "  $P granted" || echo "  $P NOT granted"
done

echo "=== 4. Background activity start ==="
# Not for drawing overlays: this app-op is what lets the guard reopen the guarded app
# after a repair. Without it the launch is silently dropped.
#
# HyperOS resets this app-op to its default a second or so after every install or
# update (com.miui.securitycenter handles the install and applies its policy). Setting
# it right after `adb install` loses that race silently, so wait, set, and check it held.
sleep 5
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
sleep 3
if adb shell cmd appops get $PKG SYSTEM_ALERT_WINDOW | grep -q "allow"; then
  echo "  SYSTEM_ALERT_WINDOW allowed"
else
  echo "  WARNING: SYSTEM_ALERT_WINDOW was reset — run: adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow"
fi

# Off by default since Android 13, and without it every warning the guard raises is dropped.
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS && echo "  POST_NOTIFICATIONS granted"

echo "=== 5. Battery whitelist ==="
adb shell dumpsys deviceidle whitelist +$PKG

echo "=== 6. Start ==="
# The service is exported=false, so start it through the activity.
adb shell am start -n $PKG/.ui.MainActivity > /dev/null
sleep 4

echo "=== 7. Verify ==="
adb shell dumpsys package $PKG 2>/dev/null | grep -E "WRITE_SECURE_SETTINGS: granted|PACKAGE_USAGE_STATS: granted|POST_NOTIFICATIONS: granted"
adb shell cmd appops get $PKG SYSTEM_ALERT_WINDOW
if adb shell pidof $PKG > /dev/null; then echo "  guard is running"; else echo "  WARNING: process did not come up"; fi

echo
echo "One manual step remains on the phone:"
echo "  Security -> Permissions -> Autostart -> enable NoScroll Guard"
