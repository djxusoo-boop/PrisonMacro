#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
serial="${1:?Uso: bash scripts/test-device.sh emulator-5560}"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
adb="$sdk/platform-tools/adb"
# A newly launched emulator may exist but still be offline or booting.
# Bound the wait so a missing device never leaves unattended tests hanging.
boot_ready=false
for ((attempt=0; attempt<30; attempt++)); do
  boot_state="$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [[ "$boot_state" == 1 ]]; then boot_ready=true; break; fi
  sleep 2
done
if [[ "$boot_ready" != true ]]; then
  echo "El dispositivo $serial no terminó de arrancar en 60 segundos. No se instaló ni ejecutó nada." >&2
  exit 1
fi
model="$("$adb" -s "$serial" shell getprop ro.product.model | tr -d '\r')"
if [[ "$model" != sdk_gphone* ]]; then
  echo "Solo se permite esta batería de gestos en el emulador de laboratorio sdk_gphone, no en BlueStacks ni teléfonos personales." >&2
  exit 1
fi
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in .tools/jdk-*/Contents/Home; do
    if [[ -x "$candidate/bin/java" ]]; then export JAVA_HOME="$PWD/$candidate"; break; fi
  done
fi
bash scripts/build-apk.sh
./gradlew :app:assembleDebugAndroidTest --console=plain
"$adb" -s "$serial" install -r outputs/PrisonMacro-0.3.18.apk
"$adb" -s "$serial" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$adb" -s "$serial" logcat -c
# On API 37 ADB may return a non-zero transport code after the runner has
# already written its successful final callback. Keep the code as evidence,
# then decide from the runner's own complete result below.
set +e
"$adb" -s "$serial" shell am instrument -w es.macrostudio.test/androidx.test.runner.AndroidJUnitRunner | tee outputs/device-tests.txt
instrument_exit=${PIPESTATUS[0]}
set -e
# Android 37 can stop the runner immediately after its final test and omit the
# traditional "OK (N tests)" line. Verify all expected @Test callbacks in the
# device TestRunner log as a fallback, while still rejecting any failure.
runner_log="$("$adb" -s "$serial" logcat -d -v brief TestRunner:I '*:S' || true)"
printf '%s\n' "$runner_log" > outputs/device-tests-runner.log
expected="$(rg -c '@Test' app/src/androidTest/java | awk -F: '{total += $2} END {print total+0}')"
finished="$(printf '%s\n' "$runner_log" | rg -c 'finished: .*\(es\.macrostudio\.' || true)"
passed=false
reported="$finished"
if grep -Eq '^OK \([0-9]+ tests\)' outputs/device-tests.txt; then
  passed=true
  reported="$(sed -nE 's/^OK \(([0-9]+) tests\)$/\1/p' outputs/device-tests.txt | tail -1)"
fi
if [[ "$finished" -eq "$expected" ]] && ! printf '%s\n' "$runner_log" | rg -qi 'failed:|failure|exception|fatal'; then passed=true; fi
# am instrument can exit 0 even when JUnit fails. Validate the actual report
# and its complete device-side callbacks.
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|Error in ' outputs/device-tests.txt || [[ "$passed" != true ]]; then
  echo "Esperados: $expected; finalizados: $finished" >&2
  echo "Código de transporte ADB: $instrument_exit" >&2
  echo "Pruebas instrumentadas fallidas o incompletas" >&2
  exit 1
fi
echo "Regresión de dispositivo completada ($reported pruebas)"
