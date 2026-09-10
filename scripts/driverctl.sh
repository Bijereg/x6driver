#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
[ "$#" -gt 0 ] || { echo 'Usage: driverctl.sh status | scan | connect UUID NAME | cancel JOB_UUID' >&2; exit 1; }
driver_app="$HOME/Applications/X6 Driver.app"
if [ ! -x "$driver_app/Contents/runtime/Contents/Home/bin/java" ]; then driver_app="$PWD/build/dist/X6 Driver.app"; fi
[ -x "$driver_app/Contents/runtime/Contents/Home/bin/java" ] || { echo 'Build the driver first: ./scripts/package-macos.sh' >&2; exit 1; }
exec "$driver_app/Contents/runtime/Contents/Home/bin/java" -cp "$driver_app/Contents/app/*" dev.sbelx.x6driver.service.ServiceCtl "$@"
