#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
project="$root_dir/ios/PrisonMacro/PrisonMacro.xcodeproj"
derived="$root_dir/ios/build/ConnectedDeviceDerivedData"
device_id="${1:-}"

if [[ -z "$device_id" ]]; then
  device_id="$(xcrun devicectl list devices | awk '$1 == "iphone" && $4 ~ /^[A-F0-9-]{8,}$/ { print $4; exit }')"
fi

if [[ -z "$device_id" ]]; then
  echo "No se ha encontrado un iPhone disponible. Conéctalo y vuelve a intentarlo." >&2
  exit 1
fi

echo "Compilando PrisonMacro para $device_id..."
xcodebuild \
  -project "$project" \
  -scheme PrisonMacro \
  -destination "id=$device_id" \
  -derivedDataPath "$derived" \
  -allowProvisioningUpdates \
  -allowProvisioningDeviceRegistration \
  build

app="$derived/Build/Products/Debug-iphoneos/PrisonMacro.app"
if [[ ! -d "$app" ]]; then
  echo "La compilación no produjo $app" >&2
  exit 1
fi

echo "Instalando en el iPhone..."
xcrun devicectl device install app --device "$device_id" "$app"
echo "PrisonMacro instalada en $device_id"
