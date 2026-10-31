# NordicTrack 1750 Android 5.1 console access

Reproduce the access setup tested on one NordicTrack Commercial 1750 with console
part **388129**: Android settings access, authenticated Wi-Fi ADB, and iFit
privileged mode that survives a normal power cycle. Last hardware verification:
2026-10-29; documentation and helper scripts prepared 2026-10-30.

**This was not a permanent Android root installation.** We used capabilities
already present in this firmware. No `su`, Magisk, boot image or system partition
was installed or modified. ADB initially ran as `uid=0(root)`; after reboot it ran
as `uid=2000(shell)`. Both Wi-Fi ADB and iFit privileged mode remained available.
iFit privileged mode, developer options and Android root are different things.

This is a record of one successful setup, not a universal 1750 jailbreak. The
scripts refuse changes on an unknown tablet build or Admin version. A matching
marketing name alone is insufficient. The new helpers have local automated tests;
they have **not** been rerun end to end on a freshly reset console. The underlying
commands and reboot outcome were verified in the original session.

## Optional setup APK

A [native setup APK](../../console/setup/README.md) now packages this workflow
with privileged-mode persistence and Wi-Fi ADB switches. A compact core build and
a local bundle with optional Termux/Preview installers are available. The original
commands were hardware-tested; execution from the new APK's own UID is still
unvalidated. Initial Android access, debugging and installer/RSA approvals remain
manual. The scripts below remain the verified underlying route.

## Tested hardware and software

| Item | Observed value | Evidence |
|---|---|---|
| Product | NordicTrack Commercial 1750 | Owner identification |
| Console part number | 388129 | Equipment info and board query |
| Android model | `MalataSamsungRoyalWolfSinglePartition` | Android settings / getprop |
| Android | 5.1.1, API 22 | Android settings / getprop |
| Firmware incremental build | `MGRSP_20210806` | getprop / equipment info |
| Kernel | 3.4.39 | About tablet photo |
| Console display | 800×480, 160 dpi | ADB display inventory |
| Android System WebView | 83.0.4103.120 | Runtime inventory |
| iFit | `com.ifit.standalone`, version 2.6.86, code 4458 | Extracted installed APK manifest; UI displays 2.6.86.4458 |
| iFit Admin | `com.ifit.eru`, version 2.2.20, code 1602 | Extracted installed APK manifest; UI displays 2.2.20.1602 |
| Arda | `com.ifit.arda`, version 1.0.26, code 43 | Extracted installed APK manifest |
| Brainboard / master library | 82.55 | Equipment info and read-only board query |
| Board identity | model 14116, part 388129, software 82, hardware 1, device address 4 | Read-only FitPro1 replies |
| Internal USB controller | `213c:0002`, ICON Fitness / ICON Generic HID | Android USB inventory |
| USB interface | One HID interface; interrupt IN `0x81`, OUT `0x02`, 64-byte packets | Android USB inventory |
| ADB authentication | `ro.adb.secure=1` | Before and after reboot |
| Initial system security props | `ro.secure=1`, `ro.debuggable=0`, `sys.adb.debug=1` | Startup report |
| Post-reboot root state | ADB `uid=2000(shell)`; `sys.adb.debug` empty | Reboot verification |

Read-only board limits were 0.8–19.31 km/h and −3–15% grade. With the key removed,
mode was 8 and target/actual speed and incline were zero. These replies do not
validate motor control, feedback while moving or cold-start initialization.
See [the protocol investigation](../../docs/ANDROID_5_PORT.md) for detail.

The model's year and retail NTL model suffix were not established. Part 388129 is
not a substitute for a complete retail treadmill model number.

## What you need

- Physical access to your own console. Stop workouts, step off, and remove the
  safety key before setup. These scripts send no board commands.
- A computer with Python **3.8+** and Android SDK **Platform Tools** (`adb`).
  See [Google's ADB documentation](https://developer.android.com/tools/adb).
- Console and computer on the same trusted LAN, with client-to-client connectivity.
  Do not expose TCP 5555 through internet port forwarding. This legacy TCP mode
  retains computer-key authentication; it is not modern Android wireless pairing.
- A way to install a local terminal app on the tablet. Our working app was Termux
  **0.119.0-beta.3**, version code **1022**, **apt-android-5 universal** variant,
  minimum API 21. The normal Android 7+ variant will not work here.
- For building the preview: JDK 17 or 21, Android SDK platform 34/build tools, and
  the repo's Gradle wrapper. Building is separate from access setup.

No factory reset, package uninstall/disable, alternate launcher or firmware update
was needed. Do **not** run `tools/unchain.sh` for this route: it changes packages,
launcher and potentially installs the original motor app. Those were not our steps.

## 1. Reach Android settings

The blue iFit **Secret Settings** opened by repeatedly tapping Equipment Info is
an app diagnostic menu. Its development/mock-device/cache/crash controls are not
Android ADB switches; leave them alone. The Malata engineering menu and TangoC
app are hardware/touchscreen diagnostics, not a network-ADB setup route.

We entered iFit's separate **Privileged Mode** challenge screen, entered a response
code, then reached the Android launcher/settings. The upstream documented gesture
is ten taps on a blank area, wait seven seconds, then ten more taps. The exact tap
cadence was not independently recorded in our session. The photographed challenge
screen directed the owner to support; use a response issued for your current
challenge. No response code is hardcoded or generated by these scripts. We did
not verify or automate any third-party response-code calculator.

From the Android launcher, open the gear **Settings**, then:

1. About tablet → tap Build number seven times if Developer options is hidden.
2. Developer options → main switch On → **USB debugging On**.
3. Join your LAN and read the current tablet IPv4 address under Wi-Fi / Status.

The computer-authorization prompt will come later. **Wi-Fi verbose logging does
not enable network ADB.** Enabling USB debugging alone did not open TCP 5555 on
this console.

Our exposed full-size USB port did not enumerate the tablet as an ADB device with
USB-A to USB-C cables. It appears to serve the host/accessory role; this is not
proof of every internal connector's role. HDMI did not provide an ADB route.
No cable modification or console disassembly was used. We bootstrapped over LAN.

### Optional vendor Root Grant

This exact Settings build includes Developer options → **Root Grant** → an Input
box. Static inspection of the installed Settings APK found the value
`rootaccess`, which broadcasts `malata.adb.root`. The matching framework receiver
starts the console service, sets `sys.adb.debug=1`, and toggles debugging off/on.
It does **not** set a TCP port or install `su`.

In our session `sys.adb.debug` was already 1 when inspected; we did not establish
that entering this value was required. Treat this as a firmware-specific option,
not a universal root password or a persistent-root guarantee. Root Grant is a
local manual action; the scripts do not invoke it or change ADB authentication.
After any such action, recheck USB debugging before continuing.

## 2. Install the Android 5 Termux variant

On the computer, get the **apt-android-5** build from the
[official Termux 0.119.0-beta.3 release](https://github.com/termux/termux-app/releases/tag/v0.119.0-beta.3).
The [direct universal APK](https://github.com/termux/termux-app/releases/download/v0.119.0-beta.3/termux-app_v0.119.0-beta.3+apt-android-5-github-debug_universal.apk)
is the variant used here. Our installed APK had SHA-256:

```text
bad6b661128baeb57e4c05dca5cc2a1f18feaee8b594abdbe99ca4e54e7f05d6
```

This digest records our local APK, not a promise about future/replaced downloads.
The official release distinguishes Android 5/6 from Android 7+ packages. Termux
reported a legacy environment with unmaintained packages; package upgrades are
not needed for this bootstrap. No rooting package is required.

The tablet's old browser had certificate failures and queued downloads. We
worked around this by downloading on the computer, then serving **only a staging
directory** over local HTTP. Substitute your computer's LAN IPv4 address for
`192.0.2.20` below; it is an example address, not a discovered host.

From the repository root, put the downloaded APK at `tools/android5/local/termux.apk`
and stage these two files:

```sh
mkdir -p tools/android5/local/http
cp tools/android5/local/termux.apk tools/android5/local/http/termux.apk
cp tools/android5/enable-wifi-adb.sh tools/android5/local/http/enable-wifi-adb.sh
python3 -m http.server 8766 --bind 192.0.2.20 --directory tools/android5/local/http
```

Open `http://192.0.2.20:8766/termux.apk` in the **tablet's browser**, download,
allow installation from unknown sources if prompted, install, and launch Termux.
The HTTP server must remain running until the script has also downloaded. Keep
this on your trusted LAN; HTTP does not protect the download in transit. Compare
the downloaded script with the repository before running it. Stop the server with
Ctrl+C afterward. The staging directory is gitignored; do not serve the repo or
home directory, which can contain private data.

## 3. Bootstrap authenticated TCP ADB from Termux

In **Termux on the treadmill**, substitute the same host address and run:

```sh
curl -fS http://192.0.2.20:8766/enable-wifi-adb.sh -o "$HOME/enable-wifi-adb.sh"
cat "$HOME/enable-wifi-adb.sh"
/system/bin/sh "$HOME/enable-wifi-adb.sh" --check
/system/bin/sh "$HOME/enable-wifi-adb.sh" --apply
```

The script checks the exact model, API, incremental build and `ro.adb.secure=1`.
It preserves the first prior TCP-property snapshot in `~/stride-adb-before.txt`,
sets both port properties, verifies their values, and restarts only `adbd`:

```sh
/system/bin/setprop persist.adb.tcp.port 5555
/system/bin/setprop service.adb.tcp.port 5555
/system/bin/setprop ctl.restart adbd
```

These operations worked from the ordinary Termux app UID on this firmware; no
`su` invocation was needed. If a property write is denied, stop: do not assume
that another build has these permissions. The helper performs no automatic
rollback of a partially accepted property change; the saved values and local
Termux shell let you inspect/undo it.

We originally hit missing `/system/bin/sed`, `/system/bin/head`, `/system/bin/tr`
and `app_process` errors when mixing old Android tools with Termux's environment.
The helper uses Android's shell with an explicit system PATH and empty LD_PRELOAD;
it does not depend on those missing tools. Downloading a script first also avoids
confusion about `| bash` versus `| sh`. Do not pipe an unreviewed network response
directly into a shell.

`command -v su` in Termux found a wrapper, but `su -c id` returned **No su program
found**. That result is expected; the wrapper does not establish root access.
The localhost-only port 5037 found during research returned an offline device
and was not our working transport.

## 4. Connect and authorize the computer

Back on the **computer**, set the console's actual address, not the HTTP server's
address. `192.0.2.10` below is another example placeholder:

```sh
export STRIDE_DEVICE=192.0.2.10:5555
adb connect "$STRIDE_DEVICE"
adb devices -l
```

On the tablet, accept **Allow USB debugging?**, check **Always allow from this
computer**, and tap Allow. The prompt uses that wording even for this TCP
connection. Re-run `adb devices -l` until this exact serial is `device`.
Do not copy or distribute the computer's private `adbkey` or disable authentication.
This is legacy `adb connect`, not Android 11+'s QR/`adb pair` workflow.

Read the console state without changing it:

```sh
python3 tools/android5/console_access.py --serial "$STRIDE_DEVICE" inspect
```

Only an explicit serial is accepted. The helper never selects the first attached
phone/emulator automatically. It prints a bounded report: identity/security/ADB
properties, package versions, marker existence, shell UID, boot ID and display.
It does not collect accounts, Wi-Fi passwords, app data or the full property dump.
The report includes your local address and boot ID; redact them before sharing.

## 5. Keep privileged mode and ADB across reboot

Preview the changes first (no writes):

```sh
python3 tools/android5/console_access.py --serial "$STRIDE_DEVICE" configure
```

Apply with a new baseline file:

```sh
python3 tools/android5/console_access.py --serial "$STRIDE_DEVICE" configure \
  --apply --backup tools/android5/local/before-setup.json
```

The helper requires the tested tablet profile and **Admin version 2.2.20, code
1602**. It exclusively creates the baseline before any mutation and refuses to
overwrite an existing file. The baseline includes the boot ID for the next step.
It then executes only:

```sh
adb -s "$STRIDE_DEVICE" shell mkdir -p /sdcard/.wolfDev/keepme
adb -s "$STRIDE_DEVICE" shell am broadcast \
  -a com.ifit.eru.PRIVILEGEDMODE \
  -n com.ifit.eru/com.ifit.eru.receivers.PrivilegedModeReceiver \
  --ez PRIVILEGED_KEY true
adb -s "$STRIDE_DEVICE" shell setprop persist.adb.tcp.port 5555
```

`keepme` is an empty **directory**, as created in our session. It is the presence
of the parent `/sdcard/.wolfDev` that this Admin build checks. Static inspection
found that Admin's boot receiver skips resetting debugging and privileged mode
when that path exists. The explicit broadcast requests privileged mode now;
the marker prevents the normal boot reset on this inspected version.
A successful broadcast return alone does not prove the visible state changed.

The host helper does not restart ADB, reboot, disable Admin, uninstall iFit,
replace HOME, change screen sleep policy or touch the motor board. It reports
marker/property readback; verify the Android launcher/settings remain accessible.

## 6. Verify a real reboot

With the belt stopped and safety key out, power the treadmill off and on normally.
Once Android is fully back, reconnect using the current tablet IP:

```sh
adb connect "$STRIDE_DEVICE"
python3 tools/android5/console_access.py --serial "$STRIDE_DEVICE" verify-reboot \
  --baseline tools/android5/local/before-setup.json
```

Checks: a different boot ID, boot completed, persistent TCP port 5555, secure ADB,
debugging/developer options still enabled, and marker still present. The UI must
also be checked manually: the owner confirmed privileged mode survived without
another response code. Our test reconnected without another RSA approval prompt.
The transient `service.adb.tcp.port` cleared but `persist.adb.tcp.port` stayed 5555.
Root was **not** required for the pass condition and did not persist.

A DHCP address change needs a new `STRIDE_DEVICE`. For this comparison the script
requires the same baseline serial; after independently identifying the tablet,
update the serial in a **copy** of the baseline. A router DHCP reservation can
avoid this; no router configuration was changed in our session.

Do not describe persistence as verified until a real boot changes the boot ID.
New firmware/Admin versions may change or remove this behavior.

## 7. Build/install the preview separately

The setup scripts deliberately install no application. To reproduce the current
motor-free console preview, from the repo root with JAVA_HOME/ANDROID_HOME set:

```sh
(cd console/workoutlab && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug)
adb -s "$STRIDE_DEVICE" install -r console/workoutlab/app/build/outputs/apk/debug/app-debug.apk
adb -s "$STRIDE_DEVICE" shell am start -n dev.stride.lab/.WorkoutLabActivity
```

See [Workout Lab](../../console/workoutlab/README.md) for screens and local-phone
pairing. Version 0.2.0 was installed/visually tested; 0.2.1 is prepared but has not
been installed on the physical console as of this guide. Debug builds are bench
artifacts; this is not a signed production-release distribution workflow.

The [Motor Bench SIM](../../console/motorbench/README.md) is another isolated APK,
with **all physical writes disabled**. The original `console/stride` motor app
has not been installed or validated on this console. The
[USB probe](../../console/usbprobe/README.md) is a separate diagnostic; any interface
claim/read test requires its own physical setup. Do not detach a driver or stop
iFit/Arda automatically during access setup. In our later read-only session Arda
held USB; a subsequently attached `usbhid` driver required a separately approved
per-run probe option. That investigation is not part of this bootstrap.

## Undo and recovery

Keep Termux and physical Android settings access available before disabling Wi-Fi
ADB. The scripts never reboot automatically.

To preview removing only the empty marker directories created after your host
baseline (existing markers are preserved):

```sh
python3 tools/android5/console_access.py --serial "$STRIDE_DEVICE" remove-marker \
  --baseline tools/android5/local/before-setup.json
# Repeat with --apply after reviewing the plan.
```

Removal uses `rmdir`, never recursive deletion. If contents have appeared, inspect
them manually. This removes persistence markers; it does not change the current
iFit privileged-mode setting. Exit privileged mode in the manufacturer's UI, or
on this exact Admin build explicitly send the same receiver `PRIVILEGED_KEY false`
if you want it disabled now. That latter action was not needed in our setup and
has not been physically validated as a recovery flow.

To disable legacy TCP ADB, preview and then apply **last**:

```sh
python3 tools/android5/console_access.py --serial "$STRIDE_DEVICE" disable-wifi
python3 tools/android5/console_access.py --serial "$STRIDE_DEVICE" disable-wifi --apply
```

It clears both port properties and requests an adbd restart. This may disconnect
before the host gets a result; the helper does not treat a dropped connection as
proof of success. Check locally in Termux:

```sh
/system/bin/getprop persist.adb.tcp.port
/system/bin/getprop service.adb.tcp.port
```

Both should be empty. To restore earlier nonempty ports instead, use the values
in Termux's original `~/stride-adb-before.txt`; the host baseline was captured
*after* bootstrapping network access. Do not blindly restore a host baseline as
if it were the pre-bootstrap state. USB debugging/authorization are retained;
turn them off/revoke keys manually in Developer options if desired.

If TCP is unavailable, the tablet-local reversal is:

```sh
/system/bin/setprop persist.adb.tcp.port ''
/system/bin/setprop service.adb.tcp.port ''
/system/bin/setprop ctl.restart adbd
```

Factory reset is not part of this guide and was not required. Package data,
manufacturer apps, the boot image and system partition were retained throughout.

## Troubleshooting and evidence limits

| Symptom | What to check |
|---|---|
| Download remains queued / certificate error | Use the Android 5 APK and trusted-LAN staging server; do not disable TLS checks globally. |
| `adb connect` refused | USB debugging alone does not set a TCP port; check local property readbacks, Wi-Fi IP, and LAN isolation. |
| `unauthorized` | Accept the tablet RSA prompt; do not alter `ro.adb.secure`. |
| `offline` | Reconnect the explicit TCP serial and check the tablet; the discovered localhost 5037 path was unusable. |
| `setprop` denied / guard refuses | This firmware-specific route is not established for your device; do not force through the guard. |
| `app_process` not found in Termux | Use system PATH and empty LD_PRELOAD with `/system/bin/sh`; see the helper. |
| Root Grant opens just an Input box | Expected for this vendor UI; it is separate from iFit challenge/response privileged mode. |
| Privileged mode resets after reboot | Confirm marker, Admin build and genuinely changed boot ID; newer builds are outside this test. |
| ADB UID changed from root to shell | Observed and expected here; it does not invalidate privileged-mode or ADB persistence. |

No photos with personal data, vendor APKs/decompiled sources, account credentials,
ADB private keys or per-household addresses are redistributed. The account-free
hardware findings and summarized static inspection are documented in
[ANDROID_5_PORT.md](../../docs/ANDROID_5_PORT.md). The installed iFit APK inspected
had SHA-256 `2a012d40f43de2f2376457265a7c7d7c16ba4dc3cf3b9a40335a63a7ff9943ef`;
that is a reproducibility identifier, not a download or redistributed vendor code.

## Test the helpers locally

```sh
python3 -m unittest discover -s tools/android5 -p 'test_*.py' -v
sh -n tools/android5/enable-wifi-adb.sh
```

These tests use fake ADB/system-tool responses. They do not connect to a treadmill,
restart adbd, or validate permissions on a different firmware. Submit the bounded,
redacted `inspect` report and precise failing step for another hardware revision;
do not generalize compatibility from the retail model name.

Current result: **20 tests pass**, covering read-only previews, exact build/Admin
guards, explicit device selection, backup-before-write/no overwrite, denied port
writes, preserving preexisting markers, rejecting marker symlinks, nonempty/silent
directory-removal failures, shell quoting, and real changed-boot requirements.
