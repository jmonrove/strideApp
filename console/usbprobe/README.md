# Console inventory and bounded board reads

Android 5.1-compatible diagnostic, independent of STRIDE's motor driver. Opening
the app shows USB inventory and WebView information without claiming an interface.
The original passive input-report reader remains available separately.

**Identify board / read status** opens an additional query screen. Nothing is sent
until **Read once** is pressed. For a bench test, stop the workout, step off the
belt and remove the safety key first. The Android USB permission prompt grants
access to the controller; the app does not set itself as the default USB handler
or launch automatically on attachment.

The query path has its own restricted codec. It can construct only DeviceInfo,
SupportedCommands, SupportedDevices, SystemInfo, VersionInfo, and ReadWriteData
requests whose write section is empty. Status fields are restricted to target /
actual speed and grade, RPM, mode and min/max limits. Optional commands and fields
are queried only when advertised by the board. Basic ReadWriteData is a core
protocol command and need not appear in the board's optional-command list, matching
iFit's QueueManager policy. Serial numbers and pulse values
are omitted from reports.

There are no builders for connect/disconnect, unlock, workout mode changes,
speed/incline writes, calibration, reset, bootloader, firmware or 0xFF flush packets.
A bounded input-only drain consumes up to eight pending reports before the first
query. A continuously busy input, partial write, timeout, bad checksum, unrelated
reply or non-success status stops the test without retrying or unlocking.

The default interface claim uses `force=false`. A separate unchecked option permits
temporary kernel HID detachment for one run. Even with that option selected, a
forced claim is attempted only after a normal claim fails and sysfs identifies
exactly one matching ICON controller with driver `usbhid`. Unknown drivers and
`usbfs` ownership are refused. The interface is released and connection closed on
completion or when leaving the screen; driver reattachment should be checked on
the tested console. This option must be explicitly approved for the bench test.

## Build and verification

```sh
ANDROID_HOME=/path/to/android/sdk JAVA_HOME=/path/to/jdk ./gradlew \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Seventeen JVM tests cover every nonempty subset of the ten status fields on each
allowed address, request allowlists, corrupted/mismatched replies, signed units,
capability filtering, blocked replies, bounded draining, partial writes, and the
opt-in driver-claim fallback. These test software behavior; device support is
recorded separately in the Android 5 port notes.

Install `app/build/outputs/apk/debug/app-debug.apk` and open
`dev.stride.usbprobe/.MainActivity`. Query reports use logcat tag `StrideBoardRead`.
Inventory/passive input uses `StrideProbe`. Do not run simultaneous controller
clients; temporarily closing the installed iFit apps may be needed for the test.
