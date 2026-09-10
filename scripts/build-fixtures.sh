#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/system-print build/swift-cache
xcrun swiftc -module-cache-path build/swift-cache system-print/macos/photo-fixtures.swift -o build/system-print/photo-fixtures
build/system-print/photo-fixtures build/system-print
xcrun swiftc -module-cache-path build/swift-cache system-print/macos/probe-document.swift -o build/system-print/probe-document
build/system-print/probe-document build/system-print/three-pages.pdf
