#!/usr/bin/env bash
# Builds StereoCam.apk without Gradle.
#
# Toolchain, picked automatically:
#  - A full Android SDK in $ANDROID_HOME (platform 34+, build-tools 34+), as on GitHub's runners.
#    Uses d8, and can include ARCore for 6DoF: set ARCORE_AAR=/path/to/core-x.y.z.aar
#  - Otherwise Ubuntu's packages (no 6DoF):
#      apt-get install android-sdk-build-tools android-sdk-platform-23 apksigner zipalign dalvik-exchange zip
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=14
VERSION_NAME=3.0
PKG=com.stereocam.app
SRC=app/src/main
OUT=build
KS=stereocam.keystore
ARCORE_AAR=${ARCORE_AAR:-}

PLATFORM=""
if [ -n "${ANDROID_HOME:-}" ]; then
  PLATFORM=$(ls -d "$ANDROID_HOME"/platforms/android-3[4-9] 2>/dev/null | sort -V | tail -1 || true)
  BTDIR=$(ls -d "$ANDROID_HOME"/build-tools/3[4-9]* 2>/dev/null | sort -V | tail -1 || true)
fi
if [ -n "$PLATFORM" ] && [ -n "${BTDIR:-}" ] && [ -x "$BTDIR/d8" ]; then
  BT=$BTDIR
  JAR=$PLATFORM/android.jar
  DEXER=d8
else
  BT=/usr/lib/android-sdk/build-tools/29.0.3
  JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
  DEXER=dx
fi
echo "Toolchain: $BT, $JAR ($DEXER)"

if [ -n "$ARCORE_AAR" ] && [ "$DEXER" != d8 ]; then
  echo "ARCore needs a full Android SDK (d8); building without it." >&2
  ARCORE_AAR=""
fi

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dex" "$OUT/apkroot"

MANIFEST="$SRC/AndroidManifest.xml"
JAVA_DIRS=("$SRC/java")
CLASSPATH=""
RES_INPUTS=()
LINK_EXTRA=()

if [ -n "$ARCORE_AAR" ]; then
  echo "Including ARCore from $ARCORE_AAR"
  mkdir -p "$OUT/arcore"
  unzip -q -o "$ARCORE_AAR" -d "$OUT/arcore"
  python3 tools/merge_manifest.py "$MANIFEST" "$OUT/arcore/AndroidManifest.xml" "$PKG" > "$OUT/AndroidManifest.xml"
  MANIFEST="$OUT/AndroidManifest.xml"
  JAVA_DIRS+=("app/src/arcore/java")
  CLASSPATH="$OUT/arcore/classes.jar"
  if [ -d "$OUT/arcore/res" ] && [ -n "$(ls -A "$OUT/arcore/res")" ]; then
    "$BT/aapt2" compile --dir "$OUT/arcore/res" -o "$OUT/arcore-res.zip"
    RES_INPUTS+=("$OUT/arcore-res.zip")
    LINK_EXTRA+=(--extra-packages com.google.ar.core)
  fi
  if [ -d "$OUT/arcore/jni" ]; then
    mkdir -p "$OUT/apkroot/lib"
    cp -r "$OUT/arcore/jni/." "$OUT/apkroot/lib/"
  fi
fi

"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" "${RES_INPUTS[@]}" "$OUT/res.zip" --manifest "$MANIFEST" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$OUT/gen" "${LINK_EXTRA[@]}" -o "$OUT/base.apk"

CP_ARGS=()
[ -n "$CLASSPATH" ] && CP_ARGS=(-cp "$CLASSPATH")
javac -source 8 -target 8 -bootclasspath "$JAR" "${CP_ARGS[@]}" -d "$OUT/classes" -Xlint:-options \
  $(find "${JAVA_DIRS[@]}" "$OUT/gen" -name '*.java')

if [ "$DEXER" = d8 ]; then
  (cd "$OUT/classes" && jar cf ../classes.jar .)
  DEX_INPUTS=("$OUT/classes.jar")
  [ -n "$CLASSPATH" ] && DEX_INPUTS+=("$CLASSPATH")
  "$BT/d8" --release --min-api 24 --lib "$JAR" --output "$OUT/dex" "${DEX_INPUTS[@]}"
else
  /usr/lib/android-sdk/build-tools/debian/dx --dex --min-sdk-version=24 --output="$OUT/dex/classes.dex" "$OUT/classes"
fi

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes*.dex)
if [ -d "$OUT/apkroot/lib" ]; then
  (cd "$OUT/apkroot" && zip -q -0 -r ../unsigned.apk lib)
fi
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KS" ]; then
  echo "No $KS found: making a new signing key (this APK won't install over one signed with another key)."
  keytool -genkeypair -keystore "$KS" -alias stereocam -storepass stereocam -keypass stereocam \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=StereoCam" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias stereocam --ks-pass pass:stereocam --key-pass pass:stereocam \
  --out "$OUT/StereoCam.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/StereoCam.apk"
echo "Built $OUT/StereoCam.apk ($VERSION_NAME${ARCORE_AAR:+, with ARCore 6DoF})"
