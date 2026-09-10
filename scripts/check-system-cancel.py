#!/usr/bin/env python3
"""Physical acceptance check: prints a few rows, then cancels via CUPS."""
import json
import pathlib
import re
import subprocess
import sys
import time

app = pathlib.Path.home() / "Applications/X6 Driver.app/Contents"
ctl = [str(app / "runtime/Contents/Home/bin/java"), "-cp", str(app / "app/*"), "dev.sbelx.x6driver.service.ServiceCtl"]
fixture = pathlib.Path(__file__).resolve().parent.parent / "build/system-print/photo-portrait.pdf"
title = "X6 cancellation check " + str(time.time_ns())
output = subprocess.check_output(["/usr/bin/lp", "-d", "X6_X6h", "-t", title, str(fixture)], text=True)
match = re.search(r"X6_X6h-\d+", output)
if not match:
    raise RuntimeError(output)
cups_id = match.group()
cancelled = False
deadline = time.monotonic() + 30
try:
    while time.monotonic() < deadline:
        snapshot = json.loads(subprocess.check_output(ctl + ["status"], text=True))
        entry = next((e for e in snapshot["jobs"] if e["title"] == title), None)
        if entry and entry["state"] == "SENDING" and not cancelled:
            subprocess.run(["/usr/bin/cancel", cups_id], check=True)
            cancelled = True
        if entry and entry["state"] in ("SENT", "COMPLETED", "CANCELLED", "FAILED", "INTERRUPTED"):
            print(cups_id, entry["state"], entry["message"])
            if entry["state"] != "CANCELLED":
                raise RuntimeError("Job was not cancelled during transfer")
            sys.exit(0)
        time.sleep(0.15)
    raise TimeoutError("Cancellation check did not finish")
finally:
    if not cancelled:
        subprocess.run(["/usr/bin/cancel", cups_id], check=False)
