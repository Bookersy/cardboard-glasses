#!/usr/bin/env bash
# Builds StereoCam.apk without Gradle, using Ubuntu's android-sdk packages:
#   apt-get install android-sdk-build-tools android-sdk-platform-23 apksigner zipalign dalvik-exchange zip
set -euo pipefail
cd "$(dirname "$0")"

BT=/usr/lib/android-sdk/build-tools/29.0.3
JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
DX=/usr/lib/android-sdk/build-tools/debian/dx
SRC=app/src/main
OUT=build
KS=stereocam.keystore

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/gen"

"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" "$OUT/res.zip" --manifest "$SRC/AndroidManifest.xml" \
  --min-sdk-version 24 --target-sdk-version 34 --version-code 7 --version-name 1.6 \
  -o "$OUT/base.apk"

javac -source 8 -target 8 -bootclasspath "$JAR" -d "$OUT/classes" -Xlint:-options \
  $(find "$SRC/java" -name '*.java')

"$DX" --dex --min-sdk-version=24 --output="$OUT/classes.dex" "$OUT/classes"

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias stereocam -storepass stereocam -keypass stereocam \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=StereoCam" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias stereocam --ks-pass pass:stereocam --key-pass pass:stereocam \
  --out "$OUT/StereoCam.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/StereoCam.apk"
echo "Built $OUT/StereoCam.apk"
