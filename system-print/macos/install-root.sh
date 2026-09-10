#!/bin/sh
set -eu
stage=$1
owner_uid=$2
mode=$3
case "$owner_uid" in ''|*[!0-9]*) exit 1;; esac
[ "$(id -u)" = 0 ]
if [ "$mode" = enable ]; then
    install -o root -g wheel -m 700 "$stage/x6driver" /usr/libexec/cups/backend/x6driver
    install -d -o root -g wheel -m 755 /Library/Printers/X6
    install -o root -g wheel -m 644 "$stage/X6-X6h.ppd" /Library/Printers/X6/X6-X6h.ppd
    /usr/sbin/lpadmin -p X6_X6h -D 'X6/X6h' -E -v "x6driver:/$owner_uid" -P /Library/Printers/X6/X6-X6h.ppd -o printer-is-shared=false -o printer-error-policy=abort-job
elif [ "$mode" = disable ]; then
    if /usr/bin/lpstat -p X6_X6h >/dev/null 2>&1; then /usr/sbin/lpadmin -x X6_X6h; fi
    /bin/rm -f /usr/libexec/cups/backend/x6driver /Library/Printers/X6/X6-X6h.ppd
else
    exit 1
fi
