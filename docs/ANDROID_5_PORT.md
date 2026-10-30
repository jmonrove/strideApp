# Android 5.1 compatibility work

This branch is an experimental port for an older NordicTrack 1750 console:
Android 5.1.1 (API 22), build MGRSP_20210806, reported part 388129 and
brainboard version 82.55. These values identify the user's console; they do
not establish compatibility with the FitPro protocol used by STRIDE.

## Changes

- Lower both console applications' minimum SDK to 22, keeping target SDK 28.
- Use the three-argument BLE GATT connection API before Android 6.
- Use media audio usage before Android 8, which added assistant audio usage.
- Start the USB probe with a scrollable USB inventory, tablet build and WebView
  user agent. Inventory does not open or claim USB interfaces. The optional
  input-reader button still requires USB permission. It never sends motor
  commands and now declines to detach an existing kernel driver.

## Validation

With ANDROID_HOME pointing to an Android SDK and JAVA_HOME to JDK 17 or 21:

```sh
cd console/usbprobe
./gradlew :app:assembleDebug :app:lintDebug
cd ../stride
./gradlew :app:assembleDebug :app:lintDebug -PcompatLint
```

Both builds and the checks above pass. APK manifest inspection confirms minimum
SDK 22; the probe's APK signature verifies for API 22. The USB probe has now been
installed and its inventory verified on the console. The full STRIDE motor app
has not been installed or run on this console.

The full app's compatibility lint profile checks NewApi and InlinedApi only.
Unfiltered lint also reports existing MissingPermission findings in Ftms.kt
relating to Bluetooth on newer Android versions; those are not suppressed by
the compatibility profile's existence. Do not interpret the compatibility run
as a full lint pass.

## Remaining device checks

1. Validate the probe's restricted identity/capability/status query path. Inventory
   confirms `213c:0002`, interrupt endpoints `81/02` with 64-byte packets, and
   WebView 83.0.4103.120. Opening inventory claims no interface.
2. Check the actual WebView against the app's JavaScript and CSS requirements.
   A successful native build does not validate the HTML user interface.
3. Confirm the motor-board transport and protocol before using the full app.
   Android 5 support alone does not establish motor-board compatibility.
4. Exercise Bluetooth and audio fallbacks on the console. They are statically
   API-checked, not yet validated on Android 5 hardware.

## ADB investigation

The console's extracted Settings code validates the Root Grant password
`rootaccess`, then broadcasts `malata.adb.root`. The handler in PhoneWindowManager
starts the console service, sets `sys.adb.debug=1`, and toggles `adb_enabled` off
and on. This handler does not set a network ADB port. The device already
reported sys.adb.debug=1, while network port 5555 refused the connection.

The startup report exposed a localhost-only ADB smart socket on port 5037, but
it returned device offline and was not usable as a transport. In a normal Termux
shell, this firmware accepted setting service.adb.tcp.port and
persist.adb.tcp.port to 5555 and restarting adbd through ctl.restart. Wi-Fi ADB
then connected after the user authorized this Mac. ro.adb.secure remains 1.

The installed iFit Admin 2.2.20.1602 recognizes /sdcard/.wolfDev and skips its
boot-time reset of debugging and privileged mode when that directory is present.
The marker /sdcard/.wolfDev/keepme has been created. After a user power cycle,
Wi-Fi ADB reconnected without another authorization prompt and the console
remained in privileged mode. The changed boot ID confirms a new boot;
persist.adb.tcp.port stayed 5555 while the transient service.adb.tcp.port cleared.
No factory reset, package removal, or motor-control operation has been performed.

Android USB inventory also confirms an ICON Generic HID controller with IDs
213c:0002 and 64-byte HID interrupt endpoints 0x81 and 0x02. Protocol behavior
and board identity remain unverified.

## Inspection of installed iFit 2.6.86 (build 4458)

The freshly pulled APK's SHA-256 matched the inspected copy exactly:
`2a012d40f43de2f2376457265a7c7d7c16ba4dc3cf3b9a40335a63a7ff9943ef`.
Static inspection unpacked 267 managed assemblies from the Xamarin Android
assembly store. The relevant layers are `Wolf.Android` (app setup and USB device
selection), `Shire.Core` (workout playback), `Sindarin.Core` (console abstraction),
`Sindarin.FitPro1.Core` (board protocol) and `Sindarin.Usb.Android` (USB transport).
Extracted vendor APKs, assemblies and decompiled source are kept outside this
repository. They were inspected, not executed or copied into STRIDE.

`WolfUsbDevice` selects vendor ID 8508 (`213c`) and product IDs 2 and 3.
`UsbProduct` assigns product 2 to FitPro1 and product 3 to FitPro2;
`PlatformWolfAppSetup.CreateConsole` selects the corresponding factory.
The observed console controller is `213c:0002`, so the installed app's own
selection logic identifies it as FitPro1. This is stronger evidence than matching
the NordicTrack product name, but does not validate live command behavior.

The FitPro1 implementation agrees with STRIDE on command framing, a maximum
64-byte message, additive checksum, ReadWriteData command 2, and 11 inspected
field IDs. In particular:

| Field | ID | Encoding / access |
|---|---:|---|
| Target speed | 0 | uint16 little endian, km/h multiplied by 100; writable |
| Target grade | 1 | signed int16 little endian, percent multiplied by 100; writable |
| Actual speed | 16 | same speed encoding; read only |
| Actual incline | 17 | same grade encoding; read only |
| Maximum / minimum grade | 27 / 28 | grade encoding; read only |
| Maximum / minimum speed | 30 / 31 | speed encoding; read only |

The wire units agree, but quantization differs: iFit's speed/grade converters
truncate the scaled value toward zero, while STRIDE's general field encoder
rounds to nearest. Arbitrary fractional inputs can therefore differ by one raw
unit (0.01 km/h or 0.01% grade); this is not a byte-for-byte equivalence claim.

The remaining matched IDs are workout mode (12), RPM (5), and elapsed time (20).
`ReadWriteDataCmd` builds sorted write and read masks, waits for a response, and
updates read fields from the response. Its write-success path also updates the
local cache with requested values: an acknowledged target is not an independent
measurement of belt speed or grade. Actual fields must be validated on this board;
STRIDE's upstream notes describe actual speed returning zero on its author's board.

iFit discovers supported fields/commands and device limits before normal use.
Its startup includes an unlock exchange for newer software versions and writes
start-request/idle-lockout settings. The whole initialization routine therefore
must not be treated as a read-only identification probe. iFit polls selected
supported fields in a queued loop with a default 100 ms delay after each cycle;
this is not a guarantee of a 10 Hz effective sample rate. Its exported IPC methods
examined in the main app cover information and administration, not a ready
speed/incline API.

The installed app also contains an ActivePulse heart-rate training service. Its
workout scale checks call a backend service, so the inspected client does not
establish a complete offline heart-rate control algorithm for reuse.

### Separate Arda package

`com.ifit.arda` 1.0.26 (build 43) was also pulled and its seven DEX files checked
against the full APK. It includes a Kotlin FitPro1 implementation and generated
GlassOS gRPC definitions, including:

- ConsoleService: GetConsole, GetConsoleState and state subscriptions.
- SpeedService: CanRead, CanWrite, GetSpeed, SetSpeed and SpeedSubscription.
- InclineService: CanRead, CanWrite, GetIncline, SetIncline and InclineSubscription.
- WorkoutService: StartNewWorkout, Pause, Resume and Stop.

Its inspected gRPC client targets plaintext `localhost:54321`. Read-only inspection
of the console's IPv4 and IPv6 TCP tables found no listener on that port. Generated
client definitions do not establish that a server is installed, enabled or usable
on this firmware. No RPC was invoked and no server was enabled. Some Arda methods
could not be fully decompiled; its wider activation/control flow remains unverified.
This is an alternative integration lead, not a working control API on this console.

### Restricted board-read probe

The USB probe now has an isolated query codec and bounded metadata/status plan,
with 16 passing JVM tests and a passing full lint/build on API 22-compatible code.
Inventory and the query screen run on the physical console. Android USB permission
was granted without selecting a default USB handler. A successful live identity
and status snapshot has not yet been obtained.

The first non-forced claim was refused. Temporarily closing the main iFit app
did not release the `usbfs` claim; closing Arda did. With the safety key still out,
the probe then claimed the interface without force. Its first identity query got
an unexpected command response, and it closed without sending control requests.
The original diagnostic did not record the reply header, so the reason is unknown.
The next version includes a bounded input-only drain and header diagnostics.

Linux subsequently reattached `usbhid`, blocking another non-forced claim. An
unchecked per-run option can permit detachment only when sysfs confirms `usbhid`
on the sole matching controller; other or unknown owners are rejected. That
additional mode remains pending an explicitly approved device test. No connect,
unlock, mode/speed/incline write, calibration, reset or firmware operation was sent.

Next validation should isolate identity/capability/status reads from initialization
writes, then establish limits, safety-key/start behavior and independent feedback
before connecting the Lab's proposals to physical actuation. This inspection sent
no board commands and did not launch the STRIDE motor driver.
