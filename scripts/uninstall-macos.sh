#!/bin/sh
set -eu
[ "$(id -u)" != 0 ] || { echo 'Run without sudo; macOS will request administrator access.' >&2; exit 1; }
driver_app="$HOME/Applications/X6 Driver.app"
[ -x "$driver_app/Contents/runtime/Contents/Home/bin/java" ] || { echo 'Installed X6 Driver.app was not found.' >&2; exit 1; }
# Only remove the service registered to this installation.
agent="$HOME/Library/LaunchAgents/dev.sbelx.x6driver.service.plist"
if [ -f "$agent" ]; then
    registered=$(/usr/libexec/PlistBuddy -c 'Print :ProgramArguments:0' "$agent")
    [ "$registered" = "$driver_app/Contents/MacOS/x6driver-service" ] || { echo 'The service is registered at a different path. Use its matching uninstaller.' >&2; exit 1; }
fi
exec "$driver_app/Contents/runtime/Contents/Home/bin/java" -cp "$driver_app/Contents/app/*" dev.sbelx.x6driver.service.SystemInstall disable "$driver_app"
