#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
jdk="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
android_jar="$sdk/platforms/${ANDROID_PLATFORM:-android-37.0}/android.jar"
stage="$(mktemp -d "${TMPDIR:-/tmp}/nine-play-tests.XXXXXX")"
trap 'rm -rf "$stage"' EXIT
sources="$root/app/src/main/java/com/nova5bike/dashboard"

node --check "$root/app/src/main/assets/map.js"
node "$root/tests/map.test.js"
"$jdk/bin/javac" -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
    -classpath "$android_jar" -d "$stage" "$sources/GpsSpeedReading.java" \
    "$sources/RideSensors.java" "$sources/VibrationReading.java" "$sources/RideLog.java" \
    "$sources/BikeBluetooth.java" "$sources/AppleBle.java" "$sources/IPhoneServices.java" "$root/tests/BluetoothInstallTest.java" "$root/tests/MediaMetadataTest.java" \
    "$sources/NavigationPlan.java" "$root/tests/NavigationPlanTest.java" \
    "$sources/HeadingReading.java" "$sources/HeadingFrame.java" "$sources/RouteSamples.java" "$sources/VolumeKeys.java" \
    "$root/tests/HeadingReadingTest.java" "$root/tests/RouteSamplesTest.java" "$root/tests/VolumeKeysTest.java" \
    "$sources/ChartAnimation.java" "$root/tests/RideReadingsTest.java" "$root/tests/DisplayTransitionsTest.java" \
    "$sources/LandscapeRotation.java" "$sources/ReadingColumns.java" "$sources/LocationAccuracyReading.java" \
    "$root/tests/DashboardDisplayTest.java" "$sources/WeatherReading.java" "$root/tests/WeatherReadingTest.java"
"$jdk/bin/java" -classpath "$stage:$android_jar" com.nova5bike.dashboard.RideReadingsTest
"$jdk/bin/java" -classpath "$stage:$android_jar" com.nova5bike.dashboard.DisplayTransitionsTest
"$jdk/bin/java" -classpath "$stage:$android_jar" com.nova5bike.dashboard.DashboardDisplayTest
"$jdk/bin/java" -classpath "$stage:$android_jar" com.nova5bike.dashboard.WeatherReadingTest

"$jdk/bin/java" -classpath "$stage:$android_jar" com.nova5bike.dashboard.HeadingReadingTest
"$jdk/bin/java" -classpath "$stage:$android_jar" com.nova5bike.dashboard.RouteSamplesTest
"$jdk/bin/java" -classpath "$stage:$android_jar" com.nova5bike.dashboard.NavigationPlanTest
"$jdk/bin/java" -classpath "$stage:$android_jar" com.nova5bike.dashboard.BluetoothInstallTest
"$jdk/bin/java" -classpath "$stage:$android_jar" com.nova5bike.dashboard.MediaMetadataTest
"$jdk/bin/java" -classpath "$stage:$android_jar" com.nova5bike.dashboard.VolumeKeysTest
