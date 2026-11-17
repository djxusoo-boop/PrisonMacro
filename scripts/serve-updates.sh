#!/bin/bash
# Ofrece la última APK de outputs/ por la red de casa para que PrisonMacro se
# actualice sola (⚙ Ajustes › Actualizar la app). Déjalo abierto mientras
# actualizas; ciérralo con Ctrl+C.
#
# Solo se publica la carpeta outputs/update (version.json, la APK y macros.json).
# También recibe macros enviadas desde el iPhone o Android (scripts/macro_server.py).
set -euo pipefail
cd "$(dirname "$0")/.."
port="${1:-8765}"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
aapt="$sdk/build-tools/36.0.0/aapt"

apk="$(ls -t outputs/PrisonMacro-*.apk 2>/dev/null | head -1 || true)"
if [[ -z "$apk" ]]; then echo "No hay ninguna APK en outputs/. Genera una con: bash scripts/build-apk.sh" >&2; exit 1; fi

badging="$("$aapt" dump badging "$apk" | awk 'NR==1')"
code="$(sed -E "s/.*versionCode='([0-9]+)'.*/\1/" <<<"$badging")"
name="$(sed -E "s/.*versionName='([^']+)'.*/\1/" <<<"$badging")"
file="$(basename "$apk")"
sha="$(shasum -a 256 "$apk" | cut -d' ' -f1)"
size="$(stat -f%z "$apk")"

rm -rf outputs/update && mkdir -p outputs/update
cp "$apk" "outputs/update/$file"
macro="$(ls -t outputs/macros/*.json 2>/dev/null | head -1 || true)"
if [[ -n "$macro" ]]; then cp "$macro" outputs/update/macros.json; echo "Macro para enviar: $(basename "$macro")"; fi
printf '{"versionCode":%s,"versionName":"%s","apk":"%s","sha256":"%s","sizeBytes":%s}\n' "$code" "$name" "$file" "$sha" "$size" > outputs/update/version.json

ip="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "?")"
echo "Ofreciendo PrisonMacro $name ($file)"
echo "  · En BlueStacks de este ordenador, dirección: 10.0.2.2:$port"
echo "  · En otro equipo de tu red (PC, móvil):       $ip:$port"
echo "  · El iPhone lo encuentra solo (misma wifi)."
echo "Deja esta ventana abierta mientras actualizas o pasas macros. Ctrl+C para cerrar."
# Se anuncia en la wifi para que el iPhone lo encuentre sin escribir la dirección.
dns-sd -R "PrisonMacro en $(scutil --get ComputerName 2>/dev/null || hostname)" _prisonmacro._tcp local "$port" >/dev/null 2>&1 &
announce=$!
trap 'kill $announce 2>/dev/null' EXIT INT TERM
python3 scripts/macro_server.py "$port"
