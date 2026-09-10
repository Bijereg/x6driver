#!/bin/sh
# Use the user's installed Java; never download or silently select a different JDK.
if [ -z "${X6DRIVER_JDK:-}" ]; then
    X6DRIVER_JDK=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.home = //p')
fi
if [ ! -x "$X6DRIVER_JDK/bin/javac" ]; then
    echo 'Set X6DRIVER_JDK to an installed JDK 21.' >&2; exit 1
fi
case "$("$X6DRIVER_JDK/bin/java" -version 2>&1)" in
    *'version "21.'*) ;;
    *) echo 'X6 builds with the installed JDK 21. Set X6DRIVER_JDK explicitly.' >&2; exit 1 ;;
esac
export X6DRIVER_JDK
