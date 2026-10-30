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
SDK 22; the probe's APK signature verifies for API 22. Neither application has
yet been installed or run on this console.

The full app's compatibility lint profile checks NewApi and InlinedApi only.
Unfiltered lint also reports existing MissingPermission findings in Ftms.kt
relating to Bluetooth on newer Android versions; those are not suppressed by
the compatibility profile's existence. Do not interpret the compatibility run
as a full lint pass.

## Remaining device checks

1. Install and open the probe. Record the USB inventory and WebView version.
   Do not press its optional input-reader button for the initial inventory.
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
