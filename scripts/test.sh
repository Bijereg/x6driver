#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
. ./scripts/java-env.sh
exec ./gradlew --gradle-user-home "${X6DRIVER_GRADLE_HOME:-$PWD/.gradle}" -Dorg.gradle.java.home="$X6DRIVER_JDK" test
