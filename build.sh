#!/bin/bash
# Step 1 manual build (no Gradle daemon): aapt2 -> kotlinc -> d8 -> zipalign -> apksigner.
# Gradle project files are included too; on a normal dev machine `./gradlew assembleDebug` also works.
set -euo pipefail
TOOLS=/home/hatch/workspace/tools/android
export JAVA_HOME=$TOOLS/jdk17
export PATH=$JAVA_HOME/bin:$PATH
SDK=$TOOLS/sdk
BT=$SDK/build-tools/35.0.0
AJAR=$SDK/platforms/android-35/android.jar
cd "$(dirname "$0")"
OUT=app/build/manual
rm -rf "$OUT"; mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/dex"

# 1. Compile + link resources, generate R.java
$BT/aapt2 compile --dir app/src/main/res -o "$OUT/compiled/res.zip"
$BT/aapt2 link -o "$OUT/unsigned.apk" -I "$AJAR" \
  --manifest app/src/main/AndroidManifest.xml \
  --java "$OUT/compiled" --min-sdk-version 26 --target-sdk-version 35 \
  "$OUT/compiled/res.zip"

# 2. Compile Kotlin sources (+ generated R)
find app/src/main/java "$OUT/compiled" -name '*.kt' -o -name '*.java' > "$OUT/sources.txt"
$TOOLS/kotlinc/bin/kotlinc @"$OUT/sources.txt" -classpath "$AJAR" -d "$OUT/classes"

# 3. Dex (include kotlin-stdlib)
$BT/d8 --min-api 26 --lib "$AJAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class') $TOOLS/kotlinc/lib/kotlin-stdlib.jar

# 4. Add dex, align, sign with debug key
cp "$OUT/unsigned.apk" "$OUT/withdex.apk"
(cd "$OUT/dex" && zip -q "$OLDPWD/$OUT/withdex.apk" classes.dex)
$BT/zipalign -f 4 "$OUT/withdex.apk" "$OUT/aligned.apk"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out ReelBlocker-Step1.apk "$OUT/aligned.apk"
echo "Built: ReelBlocker-Step1.apk"
