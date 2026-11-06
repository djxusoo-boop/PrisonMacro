#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in .tools/jdk-*/Contents/Home; do
    if [[ -x "$candidate/bin/java" ]]; then export JAVA_HOME="$PWD/$candidate"; break; fi
  done
fi
: "${JAVA_HOME:?Configura JAVA_HOME con un JDK 21}"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
buildtools="$sdk/build-tools/36.0.0"
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug -Pandroid.injected.testOnly=false --console=plain
apk="app/build/outputs/apk/debug/app-debug.apk"
"$buildtools/apksigner" verify --verbose "$apk"
badging="$("$buildtools/aapt" dump badging "$apk")"
if [[ "$badging" == *"testOnly"* ]]; then
  echo "ERROR: APK solo para pruebas. No se publica." >&2
  exit 1
fi
unzip -t "$apk" >/dev/null
mkdir -p outputs
cp "$apk" outputs/PrisonMacro-0.3.18.apk
shasum -a 256 outputs/PrisonMacro-0.3.18.apk
echo "APK verificado: outputs/PrisonMacro-0.3.18.apk"
