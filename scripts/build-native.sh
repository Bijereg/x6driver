#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/native build/swift-cache
xcrun swiftc -O -module-cache-path build/swift-cache -framework CoreBluetooth -framework IOBluetooth -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker transport-macos/src/main/swift/Info.plist transport-macos/src/main/swift/main.swift -o build/native/x6driver-bluetooth
codesign --force --sign - --identifier dev.sbelx.x6driver.bluetooth build/native/x6driver-bluetooth
