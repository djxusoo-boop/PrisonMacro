#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
project="$root_dir/ios/PrisonMacro/PrisonMacro.xcodeproj"
derived="$root_dir/ios/build/ConnectedDeviceDerivedData"
device_id="${1:-}"

if [[ -z "$device_id" ]]; then
  # Primer iPhone físico, aunque su nombre tenga espacios (p. ej. «iphone xuso»).
  device_id="$(xcrun devicectl list devices | grep -E 'iPhone[0-9]+,[0-9]+' | grep -v simulated | grep -oE '[0-9A-F]{8}-[0-9A-F]{16}' | head -1 || true)"
fi

if [[ -z "$device_id" ]]; then
  echo "No se ha encontrado un iPhone disponible. Conéctalo y vuelve a intentarlo." >&2
  exit 1
fi

echo "Compilando PrisonMacro para $device_id..."
xcodebuild \
  -project "$project" \
  -scheme PrisonMacro \
  -configuration Release \
  -destination "id=$device_id" \
  -derivedDataPath "$derived" \
  -allowProvisioningUpdates \
  -allowProvisioningDeviceRegistration \
  build

app="$derived/Build/Products/Release-iphoneos/PrisonMacro.app"
if [[ ! -d "$app" ]]; then
  echo "La compilación no produjo $app" >&2
  exit 1
fi

echo "Instalando en el iPhone..."
xcrun devicectl device install app --device "$device_id" "$app"
echo "PrisonMacro instalada en $device_id"
