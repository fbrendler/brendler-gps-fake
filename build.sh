#!/usr/bin/env bash
# Compila o APK sem Gradle (Linux / Ubuntu).
# Requisitos: openjdk, aapt2, dalvik-exchange (dx), zipalign, apksigner, android.jar (API 34)
# Uso: ANDROID_JAR=/caminho/android.jar ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR="${ANDROID_JAR:-/opt/plat/a34.jar}"
BT=/usr/lib/android-sdk/build-tools/debian
SRC=app/src/main
OUT=build
KEY=keystore/brendler.jks

rm -rf "$OUT" && mkdir -p "$OUT"/{res,gen,classes}

# 1) recursos (o Gradle usa "namespace"; aqui o pacote é injetado no manifesto)
#    + metadados do Google Play Services (no Gradle isso vem da dependência automaticamente)
GMS='<meta-data android:name="com.google.android.gms.version" android:value="@integer/google_play_services_version" /><activity android:name="com.google.android.gms.common.api.GoogleApiActivity" android:theme="@android:style/Theme.Translucent.NoTitleBar" android:exported="false" />'
sed -e 's#<manifest #<manifest package="com.brendler.gpsfake" #' \
    -e "s#</application>#$GMS</application>#" "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$BT/aapt2" compile --dir third_party/res/basement -o "$OUT/res_basement.zip"
"$BT/aapt2" compile --dir third_party/res/base -o "$OUT/res_base.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
  --manifest "$OUT/AndroidManifest.xml" -A "$SRC/assets" \
  --extra-packages com.google.android.gms.common:com.google.android.gms.base \
  --auto-add-overlay -R "$OUT/res_basement.zip" -R "$OUT/res_base.zip" \
  --java "$OUT/gen" --min-sdk-version 23 --target-sdk-version 34 \
  --version-code 6 --version-name 1.5 "$OUT/res.zip"

# 2) Java -> bytecode (Java 8)
javac -nowarn -source 8 -target 8 -encoding UTF-8 -bootclasspath "$ANDROID_JAR" -cp "$(ls third_party/jars/*.jar | tr '\n' :)" \
  -d "$OUT/classes" $(find "$SRC/java" "$OUT/gen" third_party/androidx-stubs -name '*.java') 2>&1 | grep -v "^warning" || true

# 3) bytecode -> dex
dalvik-exchange --dex --min-sdk-version=23 --output="$OUT/classes.dex" "$OUT/classes" third_party/jars/*.jar

# 4) empacota, alinha e assina
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q unsigned.apk classes.dex)
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f "$KEY" ]; then
  mkdir -p keystore
  keytool -genkeypair -keystore "$KEY" -alias brendler -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass brendler123 -keypass brendler123 \
    -dname "CN=Brendler, O=Brendler, C=BR"
fi
apksigner sign --ks "$KEY" --ks-pass pass:brendler123 --key-pass pass:brendler123 \
  --out "$OUT/BrendlerGPSFake.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/BrendlerGPSFake.apk" && echo "OK -> $OUT/BrendlerGPSFake.apk"
