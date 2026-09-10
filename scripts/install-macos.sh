#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
[ "$(id -u)" != 0 ] || { echo 'Run as your normal macOS user, without sudo. macOS will request administrator access separately.' >&2; exit 1; }
source_app="$PWD/build/dist/X6 Driver.app"
[ -x "$source_app/Contents/MacOS/x6driver-service" ] || { echo 'First run ./scripts/package-macos.sh' >&2; exit 1; }
exec "$source_app/Contents/runtime/Contents/Home/bin/java" -cp "$source_app/Contents/app/*" dev.sbelx.x6driver.service.SystemInstall enable "$source_app"
