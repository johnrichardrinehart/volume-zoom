# shellcheck shell=bash
# Sign Volume Zoom with the release key (see volume-zoom-sign), install it on
# the device that adb selects and enable its accessibility service. Set
# ANDROID_SERIAL to select one of several devices.

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

volume-zoom-sign "$VOLUME_ZOOM_UNSIGNED_APK" "$workdir/volume-zoom.apk"
adb install -r "$workdir/volume-zoom.apk"

service=dev.johnrinehart.volumezoom/.ZoomService
enabled="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
case ":$enabled:" in
*":$service:"*) ;;
":null:" | "::") adb shell settings put secure enabled_accessibility_services "$service" ;;
*) adb shell settings put secure enabled_accessibility_services "$enabled:$service" ;;
esac
adb shell settings put secure accessibility_enabled 1
