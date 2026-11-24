#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")" && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
jdk="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
platform="${ANDROID_PLATFORM:-android-37.0}"
version="${ANDROID_BUILD_TOOLS:-36.0.0}"
tools="$sdk/build-tools/$version"
android_jar="$sdk/platforms/$platform/android.jar"
out="$root/build"
app="$root/app/src/main"
keystore="${NINEPLAY_KEYSTORE:-${NOVA_BIKE_KEYSTORE:-$HOME/.android/debug.keystore}}"

export JAVA_HOME="$jdk"
export PATH="$jdk/bin:$PATH"

for required in "$android_jar" "$tools/aapt2" "$tools/d8" "$jdk/bin/javac" "$keystore"; do
    if [[ ! -r "$required" ]]; then
        printf 'Missing build input: %s\n' "$required" >&2
        exit 1
    fi
done

mkdir -p "$out"
stage="$(mktemp -d "$out/.build.XXXXXX")"
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/gen" "$stage/classes" "$stage/dex"
cp -R "$app/assets" "$stage/assets"
config="${NINEPLAY_CONFIG:-$root/services.local.json}"
legacy_config="${NINEPLAY_AMAP_CONFIG:-${NOVA_BIKE_AMAP_CONFIG:-$root/amap.local.json}}"
if [[ -n "${NINEPLAY_CONFIG:-}" || ( -z "${NINEPLAY_AMAP_CONFIG:-${NOVA_BIKE_AMAP_CONFIG:-}}" && -f "$config" ) ]]; then
    [[ -f "$config" ]] || { printf 'Missing service configuration: %s\n' "$config" >&2; exit 1; }
    cp "$config" "$stage/assets/services.json"
elif [[ -f "$legacy_config" ]]; then
    cp "$legacy_config" "$stage/assets/amap.json"
fi
"$tools/aapt2" compile --dir "$app/res" -o "$stage/res.zip"
"$tools/aapt2" link -I "$android_jar" --manifest "$app/AndroidManifest.xml" \
    --version-code 137 --version-name 1.0.0 -A "$stage/assets" \
    --java "$stage/gen" -o "$stage/unsigned.apk" "$stage/res.zip"

sources=("$app"/java/com/nova5bike/dashboard/*.java "$stage"/gen/com/nova5bike/dashboard/R.java)
"$jdk/bin/javac" -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
    -classpath "$android_jar" -d "$stage/classes" "${sources[@]}"
"$jdk/bin/jar" cf "$stage/classes.jar" -C "$stage/classes" .
"$tools/d8" --min-api 26 --lib "$android_jar" --output "$stage/dex" "$stage/classes.jar"
(cd "$stage/dex" && zip -q "$stage/unsigned.apk" classes.dex)
"$tools/zipalign" -p 4 "$stage/unsigned.apk" "$stage/aligned.apk"
"$tools/apksigner" sign --ks "$keystore" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android \
    --out "$stage/nine-play.apk" "$stage/aligned.apk"
"$tools/apksigner" verify "$stage/nine-play.apk"
mv "$stage/nine-play.apk" "$out/nine-play.apk"
printf '%s\n' "$out/nine-play.apk"
