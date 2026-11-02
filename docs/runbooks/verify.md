# Verification Runbook: strideApp

How a change in this repo is checked **end to end**: run the way it would actually run, not only
unit-tested. **An agent verifying a change reads this file** and
follows it. It writes a test plan from the change, runs it by the route below, records the
evidence, and restores state according to the data rules. It is an executable contract, not a
narrative.

`CLAUDE.md` still wins. Rules 3 (hardware actions), 4 (levels of evidence) and 6 (public repo)
bind every run, whether or not they are repeated here.

## At a glance

| | |
|---|---|
| **How the app is exercised** | (1) The touched module's test and lint commands. (2) Android UI on a local emulator at the console's 800×480 / 160 dpi. (3) Console HTML UIs in a desktop browser with the stubbed bridge. (4) The real console or watch, **only with the developer's OK for that run** |
| **Environment** | Local machine and emulator by default. The real console (`$STRIDE_DEVICE`) and watch only with approval. Never anything that drives the motor |
| **Test accounts / data** | None. The Lab generates a fresh pairing token on every launch, and simulated HR is built in. Fitbit SDK sign-in belongs to the developer: never automate it |
| **Writes data?** | Emulator: app-private storage only. Console: the Lab's connected test saves one synthetic session and then restores the prior history, but only when run with the leave-installed flag (Device check step 3); without the flag it deletes the app's data. Access/setup actions change device state (see Data safety) |

## Module checks (always)

Run from the module directory with JDK 17 or 21 and `ANDROID_HOME` set:

| Module | Command |
|---|---|
| `console/workoutlab` | `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` |
| `console/usbprobe` | `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` |
| `console/motorbench` | `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` |
| `console/setup` | `./gradlew :app:assembleCoreDebug :app:testCoreDebugUnitTest :app:lintCoreDebug`. The bundle variant needs `prepare_bundle.py` first; see its README |
| `console/stride` | `JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./gradlew :app:assembleDebug :app:lintDebug -PcompatLint`. `-PcompatLint` checks the API boundary only, so this is not a full lint pass. The module has no unit tests. Don't use `./run.sh build` here: `run.sh` exits when no device is attached, even for `build` |
| `wearables/sense2` | `npm ci && npm test && npm run build` |
| `tools/android5` | `python3 -m unittest discover -s tools/android5 -p 'test_*.py' -v` and `sh -n tools/android5/enable-wifi-adb.sh` (from the repo root) |

## Emulator UI check (Android modules)

The emulator is not the treadmill. No API 22 system image is installed, so this checks layout and
flow, not Android 5 behavior, WebView 83 rendering or USB.

1. `"$ANDROID_HOME/emulator/emulator" -avd AVapp_Analysis_API36 -no-snapshot-save` (run in the
   background).
2. `adb -s emulator-5554 wait-for-device`, then poll until
   `adb -s emulator-5554 shell getprop sys.boot_completed` prints `1`.
3. `adb -s emulator-5554 shell wm size 800x480` and `adb -s emulator-5554 shell wm density 160`
   to match the console.
4. From the module directory, `adb -s emulator-5554 install -r <apk>`. The APK is
   `app/build/outputs/apk/debug/app-debug.apk`, except in `console/setup`, where it is
   `app/build/outputs/apk/core/debug/app-core-debug.apk`.
5. Launch it: `adb -s emulator-5554 shell am start -n <package>/<activity>`, using
   `dev.stride.lab/.WorkoutLabActivity`, `dev.stride.usbprobe/.MainActivity`,
   `dev.stride.motorbench/.BenchActivity` or `dev.stride.setup/.SetupActivity`.
6. Drive the flow the change touches, taking a screenshot per step:
   `adb -s emulator-5554 exec-out screencap -p > "$(git rev-parse --show-toplevel)/../artifacts/<area>-<what>-emulator.png"`.
7. For `console/workoutlab`, the integration test also runs here:
   `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`. The Android Gradle
   Plugin uninstalls the app afterwards, which is fine on a disposable emulator.
8. Restore the display: `adb -s emulator-5554 shell wm size reset` and
   `adb -s emulator-5554 shell wm density reset`.

## Browser check (`console/stride` HTML interfaces)

Open `console/stride/app/src/main/assets/ui/<name>.html` directly (for example `cluster.html`).
Add `?screen=welcome|summary|paused|…`, or press the number keys to jump between states. A stubbed
bridge supplies fake data. A desktop browser is far newer than Chromium 83, so also check the diff
against the Chromium 83 limits in [`../coding-standards.md`](../coding-standards.md).

## Device check (only when the developer approves this run)

Preconditions: the developer is present, no workout is running, and for anything that opens the
board, nobody is on the belt and the safety key is removed. Motor-free packages only.

1. `adb connect "$STRIDE_DEVICE"`, then confirm that `adb -s "$STRIDE_DEVICE" shell getprop
   ro.product.model` prints `MalataSamsungRoyalWolfSinglePartition`. If it prints anything else,
   stop.
2. `adb -s "$STRIDE_DEVICE" install -r <apk>` (the APK path from emulator step 4), then
   `adb -s "$STRIDE_DEVICE" shell am start -n <package>/<activity>`, using an activity from
   emulator step 5.
3. For `console/workoutlab`: `ANDROID_SERIAL="$STRIDE_DEVICE" ./gradlew
   :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`.
   **The flag is mandatory on the console.** Without it, the Android Gradle Plugin 8.5.2
   uninstalls `dev.stride.lab` after the run, deleting its saved settings and history. Afterwards,
   remove the leftover test package: `adb -s "$STRIDE_DEVICE" uninstall dev.stride.lab.test`.
4. Screenshot: `adb -s "$STRIDE_DEVICE" exec-out screencap -p >
   "$(git rev-parse --show-toplevel)/../artifacts/<area>-<what>-<version>.png"`.
5. Watch (from `wearables/sense2`): `npm run usb -- install`, `npm run usb -- launch`,
   `npm run usb -- screenshot /absolute/path/to/artifacts/<name>.png`.

## Data safety

- **Before:** for a console run, note the installed version
  (`adb -s "$STRIDE_DEVICE" shell dumpsys package <package> | grep versionName`) so it can be
  reinstalled. Access or setup changes are **never** part of routine verification. Each one runs
  only as its own explicitly approved task. The privileged-mode marker and the TCP ADB properties
  go through `tools/android5/console_access.py`, which needs authorized ADB already. Only
  `configure --apply` saves a baseline (into `tools/android5/local/`) before it mutates anything.
  `remove-marker` needs that baseline (`--baseline <file>`). `disable-wifi` saves nothing, so record
  the state first with `inspect --output tools/android5/local/<name>.json`. The vendor
  USB-debugging hook has no host helper. It exists only as the setup APK's **Enable USB
  debugging** action, which the developer applies on the console, as
  [`../../console/setup/README.md`](../../console/setup/README.md) describes.
- **After:** on the console, confirm that `dev.stride.lab` is still installed with its prior saved
  history intact, and that `dev.stride.lab.test` is uninstalled. Reset the
  emulator's `wm` overrides. Undo any access change with the steps in
  [`../../tools/android5/README.md`](../../tools/android5/README.md) unless the developer says to
  keep it.
- **Never:** install or launch `dev.stride.hud` on the console; send board commands beyond
  `usbprobe`'s allowlisted reads (its driver-detach claim mode needs its own approval); run
  `tools/unchain.sh`; reboot the console yourself; sign in to the Fitbit SDK on the developer's
  behalf.

## Evidence

- Write evidence to the `artifacts/` folder beside the repo (never committed). Write the path as
  `"$(git rev-parse --show-toplevel)/../artifacts/…"` so that it resolves the same from any
  directory. Name files `<area>-<what>[-<version>].{png,json,txt}`, for example
  `stride-workout-lab.png` or `lab-smoke-test.json`. A JSON record lists the device, whether the run was preview-only, and the
  checks performed.
- The report states the level each claim reached (built, unit tests, emulator, device) and the
  build that was tested.
- Watch and HR screenshots show biometric values. They stay in `artifacts/`. When repo docs
  quote a result, they quote counts and timings, not BPM.

---

*Route lives here; rules live in [`../../CLAUDE.md`](../../CLAUDE.md).*
