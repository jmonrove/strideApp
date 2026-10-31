# STRIDE Console Setup APK

Android 5.1/API 22 setup wizard for the tested Malata NordicTrack 1750 console.
Package `dev.stride.setup`, version `0.1.0`. It bundles the documented access
procedure into a local native UI. No motor transport or workout driver is present.

## Two switches

- **Keep privileged mode after reboot:** on explicit Apply, create
  `/sdcard/.wolfDev/keepme` and send the version-specific Admin privileged-mode
  broadcast. The marker prevents the inspected Admin version's boot reset.
  Turning it off removes only empty marker directories that this app created;
  preexisting markers are preserved. It does not force an immediate exit from
  the current privileged-mode session.
- **Wi-Fi ADB debugging · TCP 5555:** on Apply, set and read back the persistent
  and current TCP ports, then restart only adbd. Turning it off clears both ports
  and requests the same restart; a computer connection may close. Computer-key
  authentication remains required. The checked state reports the persistent
  property; it is not an independent probe that a TCP listener is reachable.

The UI never writes settings on launch, refresh, or programmatic switch updates.
Each switch has an explanation and Apply/Cancel dialog. Unknown devices remain
inspect-only. Before the first change it saves a private first baseline without
overwriting it. Backup view, Developer options, the full setup guide, and an
explicit changed-boot verification button are included. It never reboots itself,
installs root, disables/uninstalls iFit, changes HOME, claims USB or sends board
commands. There is no boot receiver, remote command endpoint or cloud service.

## What is confirmed and what is pending

The underlying Termux commands, marker and Admin request were verified on the
original device, including persistence through a real power cycle. See the
[complete setup record](../../tools/android5/README.md) for hardware versions,
manual first-entry steps and reversal details.

**This new APK has not been tested on the physical treadmill.** It executes fixed
system commands from its own app UID. Firmware permissions may differ from
Termux's UID, so successful execution there does not establish that this APK can
write the same properties. A denied write stops without escalation or retry.
Compatibility requires all of:

- model `MalataSamsungRoyalWolfSinglePartition`, incremental build `MGRSP_20210806`;
- Android API 22, secure ADB `ro.adb.secure=1`, USB debugging manually enabled;
- iFit Admin package version **2.2.20**, version code **1602**.

Initial iFit privileged-mode entry/challenge response, enabling USB debugging,
Android installer approval, and the computer RSA approval still require you.
The vendor Root Grant screen is documented but never invoked by this app.
Persistent iFit privileged mode is not permanent Android root.

## Core and bundle

**Core** (~850 KB debug): the native setup UI, guide and original shell script.
Termux is not required by the direct native path. It contains no third-party APK.

**Bundle** (~81 MB debug): the same UI plus independent optional installer payloads:

- Unchanged Termux `0.119.0-beta.3` code 1022, `apt-android-5` universal APK,
  SHA-256 `bad6b661128baeb57e4c05dca5cc2a1f18feaee8b594abdbe99ca4e54e7f05d6`.
- Optional motor-free STRIDE Preview `0.2.1-console-preview`, code 3,
  package `dev.stride.lab`.
- Termux app source archive for that tag, GPL text and upstream license notices.

Install buttons copy a payload to private cache, verify SHA-256, and hand one
read-granted content URI to Android's installer. They never install silently or
remove an app to overcome a signature conflict. The provider is nonexported and
allows only fixed payload names; it cannot share the private baseline. The source
archive can be shared separately. There is no runtime payload download.

If direct Wi-Fi setup is denied, install/open the optional Termux package and tap
**Copy Termux fallback**. Paste the copied self-contained shell block in Termux,
review it and press Enter. It runs the exact firmware-guarded bootstrap script
already documented in `tools/android5`; no HTTP download is needed. Its baseline
is saved separately under Termux's home. It enables TCP ADB only. Use this app's
privileged-mode switch or the host helper separately for the marker/request.

Termux is a separate GPLv3-only program with upstream exceptions, not a library
linked into STRIDE. Payload binaries and generated source archives stay in an
ignored local build directory. Before publicly distributing a Termux-containing
binary, retain its notices and provide corresponding sources for the application
and its bundled bootstrap packages. The included Termux app archive/source links
are not a completed audit of every bootstrap package's source obligations. The
core APK avoids redistribution of Termux. See the
[official pinned release](https://github.com/termux/termux-app/releases/tag/v0.119.0-beta.3)
and [Termux packages source/recipes](https://github.com/termux/termux-packages).

## Build

JDK 17 or 21, Android SDK platform/build tools 34, Python 3.8+ for payload staging.
From this directory:

```sh
./gradlew :app:assembleCoreDebug :app:testCoreDebugUnitTest :app:lintCoreDebug
```

To prepare a local bundle, optionally build the motor-free preview first:

```sh
(cd ../workoutlab && ./gradlew :app:assembleDebug)
python3 prepare_bundle.py \
  --preview ../workoutlab/app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleBundleDebug :app:lintBundleDebug
python3 verify_package.py app/build/outputs/apk/bundle/debug/app-bundle-debug.apk
```

Staging downloads only pinned official Termux artifacts/sources, checks hashes,
and uses SDK `aapt` to validate the package/version/code. You can instead pass
`--termux /path/to/already-downloaded.apk`; the same pinned hash is mandatory.
Omit `--preview` to leave out that optional payload. `ANDROID_HOME` or
`--android-sdk` selects the SDK used for validation.

The Gradle wrapper copies the current guide and shell script into generated
assets, avoiding a second hand-maintained script. Core is buildable without
staging any payload. Bundle builds require the staged Termux APK, source and
license files and fail if they are missing; the Preview payload is optional. Local payloads and
Gradle output are gitignored, and no runtime payload or debug signing key is
committed. Use your own maintained signing key before a public release.

Install only when ready to test on your own tablet:

```sh
adb -s "$STRIDE_DEVICE" install -r app/build/outputs/apk/core/debug/app-core-debug.apk
adb -s "$STRIDE_DEVICE" shell am start -n dev.stride.setup/.SetupActivity
```

An APK install alone changes no ADB/privileged-mode settings. Inspect first, then
apply one switch at a time with the belt stopped and safety key removed. Verify
readbacks, computer authorization, and finally an operator-performed power cycle.
If the device refuses a step, preserve the exact message and do not bypass guards.

## Validation

Both variants assemble and lint with zero errors. Twelve JVM policy tests cover
version/authentication/manual-debugging guards, marker ownership/symlinks, reboot
requirements, port readback before restart, and clearing the port before restart.
The host/shell helper suite remains separate. Signature verification targets API 22.

No physical setup mutation or third-party installation has been performed from
this APK. Emulator UI inspection verifies rendering/inspect-only behavior on an
unsupported API, not Android 5 property permissions or the device installer flow.

Removing the setup app does not undo the external marker or persistent ADB
properties. Save a copy of the selectable baseline text before uninstalling; its
private backup is removed by uninstall. Use the documented undo steps first.
