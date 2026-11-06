#!/bin/bash
# Ofrece la última APK de outputs/ por la red de casa para que PrisonMacro se
# actualice sola (⚙ Ajustes › Actualizar la app). Déjalo abierto mientras
# actualizas; ciérralo con Ctrl+C.
#
# Solo se publica la carpeta outputs/update (version.json + la APK), nada más.
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
printf '{"versionCode":%s,"versionName":"%s","apk":"%s","sha256":"%s","sizeBytes":%s}\n' "$code" "$name" "$file" "$sha" "$size" > outputs/update/version.json

ip="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "?")"
echo "Ofreciendo PrisonMacro $name ($file)"
echo "  · En BlueStacks de este ordenador, dirección: 10.0.2.2:$port"
echo "  · En otro equipo de tu red (PC, móvil):       $ip:$port"
echo "Deja esta ventana abierta mientras actualizas. Ctrl+C para cerrar."
exec python3 -m http.server "$port" --bind 0.0.0.0 --directory outputs/update
