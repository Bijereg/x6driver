#!/bin/sh
set -eu
contents=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec "$contents/runtime/Contents/Home/bin/java" -Djava.awt.headless=true -Dfile.encoding=UTF-8 "-Dx6driver.bridge=$contents/app/x6driver-bluetooth" -cp "$contents/app/*" dev.sbelx.x6driver.service.ServiceMain
