# X6/X6h Driver for macOS

Print photos and PDFs on a Bluetooth thermal printer using **⌘P**.
Tested on **macOS 26.5.1, Apple Silicon, X6h**. X6 compatibility has not been verified yet.

## Install

**Turn on your printer and keep it near your Mac before installing.**

Download the archive from Releases and extract it. Open Terminal in the extracted folder:

```sh
./install.sh
```

Java is included. The installer requests administrator access, creates the **X6/X6h**
print queue, and starts the service automatically at login. Run it without `sudo`.
The package is ad-hoc signed, without Apple notarization; another Mac may request permission to run it.

The installer searches for nearby X6/X6h printers and connects automatically when
only one is found. If several are available, choose a number from the list.
Allow Bluetooth access when macOS asks. Your selection is saved.
If no printer is found, check its power and Bluetooth permissions, then retry.

## Print

Open a photo or PDF, press **⌘P**, and select **X6/X6h**.
Under **Printer Options → Printer Features**, choose Photo / Text / Black and White,
Light / Normal / Dark density, and contrast from 0.8 to 1.5. Defaults: Photo, Dark, 1.15.
Use the standard controls for copies, page ranges, and orientation.

Outer white margins are cropped while preserving proportions. The printable width is
**48.768 mm (384 dots)** on a 56.2 mm roll. Actual length depends on content and paper feed,
so it may differ from the preview. Blank pages are skipped.

Jobs wait while the printer is off. Cancel through the system print queue;
buffered lines may still print. Interrupted jobs are not automatically reprinted.
`SENT` means data was transferred, not that the printer confirmed physical completion.

## Update or uninstall

Finish pending jobs, then run `./install.sh` from the new release to update.
Run `./uninstall.sh` to remove the print queue and automatic startup. History and
`~/Applications/X6 Driver.app` remain on disk; remove them separately if needed.

## Build from source

Requires JDK 21, Xcode Command Line Tools, and internet access to download dependencies:

```sh
export X6DRIVER_JDK="$(/usr/libexec/java_home -v 21)"
./scripts/test.sh
./scripts/package-macos.sh
```

Output: `build/dist`. Install with `./scripts/install-macos.sh`, follow the printer setup prompts. Build artifacts are excluded from Git.
Java packages and macOS component identifiers use the `dev.sbelx.x6driver` namespace.


