#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
. ./scripts/java-env.sh
[ "$(uname -s)" = Darwin ] || { echo 'Build this package on macOS.' >&2; exit 1; }
[ "$(uname -m)" = arm64 ] || { echo 'This release targets Apple Silicon.' >&2; exit 1; }
./scripts/build-native.sh
mkdir -p build/system-print build/package-work build/dist
xcrun swiftc -O -module-cache-path build/swift-cache system-print/macos/backend.swift -o build/system-print/x6driver
codesign --force --sign - --identifier dev.sbelx.x6driver.cups build/system-print/x6driver
cupstestppd system-print/macos/X6-X6h.ppd
./gradlew --gradle-user-home "${X6DRIVER_GRADLE_HOME:-$PWD/.gradle}" -Dorg.gradle.java.home="$X6DRIVER_JDK" :print-service:installDist
package_work=$(mktemp -d "$PWD/build/package-work/release.XXXXXX")
mkdir -p "$package_work/input/system-print"
cp print-service/build/install/print-service/lib/*.jar "$package_work/input/"
cp build/native/x6driver-bluetooth "$package_work/input/"
cp build/system-print/x6driver system-print/macos/X6-X6h.ppd system-print/macos/install-root.sh "$package_work/input/system-print/"
"$X6DRIVER_JDK/bin/jlink" --add-modules java.base,java.desktop,java.logging,java.sql,java.xml,java.naming,java.scripting,java.management,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.net --strip-debug --no-man-pages --no-header-files --output "$package_work/runtime"
if [ -d 'build/dist/X6 Driver.app' ]; then mv 'build/dist/X6 Driver.app' "$package_work/previous.app"; fi
"$X6DRIVER_JDK/bin/jpackage" --type app-image --name 'X6 Driver' --app-version 1.1.0 --vendor 'X6' --description 'Local CUPS and Bluetooth driver for X6h' --input "$package_work/input" --main-jar print-service-1.1.0.jar --main-class dev.sbelx.x6driver.service.ServiceMain --runtime-image "$package_work/runtime" --dest build/dist --mac-package-identifier dev.sbelx.x6driver.driver --java-options '-Djava.awt.headless=true' --java-options '-Dx6driver.bridge=$APPDIR/x6driver-bluetooth' --java-options '-Dfile.encoding=UTF-8'
/usr/libexec/PlistBuddy -c 'Add :NSBluetoothAlwaysUsageDescription string X6 uses Bluetooth to connect to your printer and print documents.' 'build/dist/X6 Driver.app/Contents/Info.plist'
/usr/libexec/PlistBuddy -c 'Add :LSUIElement bool true' 'build/dist/X6 Driver.app/Contents/Info.plist'
cp system-print/macos/service-launcher.sh 'build/dist/X6 Driver.app/Contents/MacOS/x6driver-service'
chmod 755 'build/dist/X6 Driver.app/Contents/MacOS/x6driver-service'
codesign --force --deep --sign - 'build/dist/X6 Driver.app'
codesign --verify --deep --strict 'build/dist/X6 Driver.app'
echo "Built: $PWD/build/dist/X6 Driver.app"

cat > build/dist/install.sh <<'EOF'
#!/bin/sh
set -eu
bundle="$(cd "$(dirname "$0")" && pwd)/X6 Driver.app"
exec "$bundle/Contents/runtime/Contents/Home/bin/java" -cp "$bundle/Contents/app/*" dev.sbelx.x6driver.service.SystemInstall enable "$bundle"
EOF
cp scripts/driverctl.sh build/dist/driverctl.sh
cp scripts/uninstall-macos.sh build/dist/uninstall.sh
cp README.md build/dist/README.md
chmod 755 build/dist/*.sh
