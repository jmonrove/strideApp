# Sense 2 live heart-rate probe

This experimental clock face tests the user's Sense 2 before adapting workouts to
its readings. It is independent of the motor driver. It requests live heart-rate
access, displays BPM/status on the watch and sends individual sensor readings to
its companion inside the paired Fitbit/Google Health Android app.

The official npm beta SDK `@fitbit/sdk@6.2.0-pre.1` includes the `rhea` Sense 2
build target. The package builds with that SDK using device API 8.2.0 and companion
API 3.3.0, without proposed APIs. This is
not a claim that current Sense 2 firmware permits installation or supports every
sensor API. Fresh watch-side BPM is now verified in the user’s diagnostic
photos; continuous acquisition and watch-to-phone forwarding are not.

## Current validation (2026-10-30)

- Watch, companion and settings components build to `build/app.fba`.
- Sixty-two Node tests cover schema validation, delayed/stale readings, future clocks,
  off-wrist/paused state, duplicate heartbeats, retired sessions, stalled delivery,
  opt-in authenticated forwarding, missing watch permissions, missing wear
  detection, failure of wear detection while streaming, and repeated presence
  events preserving fresh BPM without restarting acquisition. Diagnostic tests also
  distinguish raw callbacks from valid data, reject absent/backwards timestamps,
  retain acquisition across transient wear changes without forwarding off-wrist
  input, and verify that display/timer events cannot freshen or restart readings.
- SDK developer login was attempted on 2026-10-29, but the browser authorization
  route showed 403 Forbidden and did not return an authorization callback.
- The phone's Developer Bridge was found under the Sense 2 device page and
  enabled after developer registration. General Settings / Services / Labs was
  the wrong location. Relay discovery still fails with no stored SDK auth token;
  enabling the phone bridge does not complete the Mac's SDK sign-in.
- With the watch connected and USB debugging enabled, macOS enumerates a Fitbit
  Sense 2 (`2687:fd13`). The beta SDK's USB/PULSE developer handshake succeeds.
  The watch reports family `rhea`, firmware `60.20001.220.31`, maximum API
  `10.1.0`, a 336×336 screen, and app installation/launch/screenshot support.
- The watch component was installed and launched over local USB using the
  unchanged SDK transport and sideload modules. A watch screenshot verifies the
  probe UI, `waiting for wear`, `Phone disconnected`, and zero samples while
  connected to its charger. That initial screenshot verified only UI startup. A later wrist test below
  establishes fresh watch-side BPM.
- The first device run exposed a missing `access_activity` permission required
  by BodyPresenceSensor. The corrected manifest requests it only for detecting
  wear state; the probe does not read steps, activity history, or daily goals.
  Missing permission/detection and unknown wear state now fail closed.
- The standard CLI starts cloud sign-in before USB discovery and the SDK token
  remains absent. The documented `www.fitbit.com/oauth2/authorize` endpoint was
  also tested with the SDK's original client, PKCE/state and token handler; it
  visibly returned 403 Forbidden. No callback/token was obtained. The endpoint
  test was stopped; no authentication protection was bypassed. The newer CLI
  1.8.0-pre.11 has the same authentication implementation and production relay
  configuration as pre.10, so it was not substituted as an unproven login fix.
- Gallery App Manager sign-in and developer registration succeeded. A private
  STRIDE HR Probe clockface draft has been created and the FBA attached. The
  authorized Publish Clockface action returned a generic HTTP 400, both with
  proposed APIs and explicit API versions, including a clean-page retry. No
  installation link or uploaded version was created. On 2026-10-30, the user
  authorized the corrected 0.1.1 rights warranty and a single private publication
  attempt. That attempt logged `ApolloError: Failed to fetch`. A subsequent
  read-only reload required renewing the existing Google sign-in; afterward the
  Gallery app list explicitly said "Waiting for at least one version to be
  uploaded." No published version or installation link was created. No second
  publish attempt was made. The page and console logs
  exposed no more specific validation reason; the cause is unresolved.
- Developer ID / App Cluster configuration is unnecessary because this probe
  does not request app-cluster storage. The proposed-API flag is disabled because
  none of the probe's imports require it; its removal did not fix publication.
- The phone companion has not been installed. Do not label the whole path live until
  a companion-side `HR_PROBE` log records fresh sensor samples on actual hardware.
- In the first wrist test, the user reported 22 sensor events but a current
  `waiting` / `--` display. This is not verified fresh BPM. The presence callback
  restarted HR acquisition and cleared display state even for repeated unchanged
  on-wrist events. The correction starts sensing only on a transition onto the
  wrist; a regression test preserves a fresh reading across repeated events.
  Corrected build `0ea56b0356d71976` was installed and its launch acknowledged;
  a screenshot verifies `off wrist` and zero samples on its charger. The first
  corrected install returned success but its launch timed out, followed by an
  `App not installed` reply on a fresh connection. A subsequent bounded install
  and launch succeeded. The cause of that intermittent installation failure is
  unresolved; no automatic retry loop is used.

## Diagnostic revisions 0.1.2–0.1.4

The second wrist test still showed `waiting` / `--`: 13 counted readings in
roughly two minutes, with progress apparently tied to waking/tapping the screen.
That does not establish usable BPM or continuous screen-off acquisition.

The diagnostic revision adds a visible version label and separate counters for
all HR callbacks, unique advancing sensor timestamps, and eligible BPM readings.
It shows raw HR/type, whether a sensor timestamp exists, confirmed wear state,
wear-state changes, sensor start count, the last valid BPM with its age, display
wake count, timer ticks and wall-clock runtime. Last-valid/raw diagnostics never
substitute for current eligible BPM and are not added to outbound frames or logs.

After the first confirmed on-wrist start, transient off-wrist reports immediately
invalidate eligible streaming data but no longer repeatedly stop/start the optical
acquisition during this diagnostic. Events received off wrist remain diagnostic
only. Returning to confirmed wear requires a new advancing sensor reading. Explicit
Pause or a wear-detection error still stops acquisition. This is a test of whether
wear-state flapping starves acquisition, not a claim that it caused the observed
failure. Real workout automation still cannot use unknown/off-wrist/stale input.

The app requests `appTimeoutEnabled=false` while unpaused and restores timeout
when paused. It observes ordinary display-change events and does not force the
screen awake, request AOD privileges, or assume screen-off execution is available.
Fitbit documents that AMOLED devices ignore `display.autoOff=false`; repeatedly
waking the screen would not establish a reliable background stream. Timer counts
versus runtime are intended to measure actual suspension on this firmware.

The 0.1.2 diagnostic screen opened but stopped rendering partway through. USB
exception notifications in 0.1.3 reported `TypeError: Expected a function` at
`messaging.peerSocket.readyState` during local rendering/startup. Separately, a
watch-side runtime feature log confirmed `Number.isFinite=undefined`. In the
original probe, that numeric helper was called after incrementing the sensor
counter and before setting status/BPM, explaining how an event count could grow
while the display stayed at `waiting`.

Revision 0.1.4 uses strict primitive finite-number checks and isolates all optional
phone readiness, send and listener operations. Native phone exceptions cannot
interrupt local sensor callbacks, rendering or timer installation. New tests
model a missing Number helper and exceptions in phone readiness/bind/send. The
USB helper records this probe's exception stacks and install/launch UUID/build
metadata; it does not log raw HR or other apps' messages.

All 25 tests and the FBA build pass. Build `00d2e094daff1fcb` was installed and
its launch acknowledged over USB. A watch screenshot verifies DIAG 0.1.4, off
wrist, wear=no, starts=0 and 8 timer ticks over 8 seconds while on its charger.
This verifies running local callbacks and rendering, not actual BPM or screen-off
acquisition. The next wrist test and the phone companion remain unresolved.

## Watch-side BPM checkpoint (2026-10-30)

Two user-supplied DIAG 0.1.4 photos verify on-wrist numeric HR with an advancing
sensor timestamp, a new unique/valid reading, and correct transitions between
`stale` and `ok`. No biometric values or wrist photos are included in this repo.
The sensor start count remained one; confirmed wear did not change between the
photos. Timer callbacks advanced with elapsed wall time rather than stopping
when the display slept. This establishes watch-side acquisition/rendering and
stale detection, not sensor accuracy or end-to-end delivery.

The photos' runtime/age counters imply roughly 14 seconds between the two latest
HR events (rounded counters). Total runtime includes time on the charger, so
sample count divided by total runtime is not a valid on-wrist sampling-rate
estimate. A longer measurement is still needed. The requested 1 Hz rate is not
being treated as a measured delivery guarantee; Fitbit's SensorOptions reference
explicitly says actual frequency depends on driver/hardware constraints.

The current five-second freshness limit and fresh-history start gate must stay
unchanged. These sparse observations do not establish an input suitable for
adaptive motion control. The next comparison is acquisition while an exercise
session is active, if the firmware permits returning to the probe while it runs.
Starting an exercise creates an activity record; no exercise has been started
programmatically or requested automatically by this probe. The Exercise API also
automatically persists sessions to the Fitbit Activity Log, so testing that API
requires an explicit user start and the exercise permission.

The display still reports the phone disconnected. SDK OAuth/Gallery installation
blocks for the companion remain; watch-local BPM is not a verified phone stream.

## Integrated exercise test 0.1.5 (installed; recording test pending)

The user confirmed that the probe could be revisited only after ending the native
Fitbit workout. The completed-workout photo therefore does not establish HR
cadence during exercise. A separate native exercise screen cannot currently supply
a concurrent comparison for this probe.

Revision 0.1.5 adds an optional exercise session inside the probe using
`access_exercise`. This is a new permission to create Fitbit Activity Log records;
the user explicitly approved installation with this permission. Nothing starts at app launch.
In 0.1.5–0.1.6, the native exercise module was loaded only after an on-watch Start
and Confirm sequence. Revision 0.1.7 also performs a guarded, read-only state
inspection at launch; starting still requires Start and Confirm while wear is
confirmed and HR is unpaused. The confirmation
states that it records a 60-second Treadmill test. GPS and autopause are disabled.
The 0.1.8 device screenshot subsequently confirmed a delayed native `started`
state after a user request. Correct end-to-end start/stop timing and faster HR
cadence remain unverified. Unsupported modules/permissions leave passive HR usable.

The current controller requests stop after 60 seconds from the original request
when its timer runs, on explicit Stop, on HR pause/wear loss, or on native pause.
It waits for native `stopped` confirmation rather than assuming stop is synchronous.
Unload cleanup is best effort and synchronous;
a killed/suspended runtime cannot guarantee a timely stop. If stop cannot be
confirmed, the probe retains ownership, blocks replacement starts and instructs
the user to end the exercise in Fitbit. It never stops an exercise already active
before the test and never automatically resumes an exercise. Starting an exercise
may sync its activity record to the paired Fitbit account.

The test displays remaining time, eligible unique HR-reading count and maximum
inter-reading/start/end gap. It retains timing/counters only; biometric values
are not added to diagnostic logs. The existing five-second freshness limit and
the phone receiver's start/resume gates remain unchanged. This is a capability
and cadence test, not an actuation test or a guarantee of continuous data.

All 43 tests and the FBA build pass, including explicit confirmation/expiry,
permission gating, foreign-workout protection, native failures, clock changes,
auto-stop, repeated actions, local HR surviving unsupported APIs, and unload.
The generated watch script keeps `require("exercise")` inside a guarded loader.
A separate exercise-test artifact is prepared; the previous passive FBA is
preserved outside the repo for rollback. No exercise was started by these tests
on the physical watch; unit tests use a fake native exercise object.

## Repeat the local USB watch test

From this directory, with the watch on the Mac-connected charger and watch-side
USB debugging enabled:

```sh
npm ci
npm test
npm run build
npm run usb -- info
npm run usb -- install
npm run usb -- launch
npm run usb -- screenshot /absolute/path/to/new-watch-screenshot.png
```

`tools/usb-probe.cjs` loads the pinned SDK's `USBDebugHost`, `HostConnection`,
`sideload.app` and screenshot helper. It accepts exactly one discoverable Fitbit
USB debug host and retains the SDK's package/device compatibility checks. It
never signs in, modifies the SDK, extracts an account token, or calls a cloud
endpoint. It installs **only the watch component**, then launches it. There is
no phone companion role on this USB host. Operations stop after 90 seconds,
release the USB connection, and do not retry. Existing screenshot files are not
overwritten. `launch` does not reinstall and can diagnose whether the probe is
still installed. Error messages include the build ID because the watch may replay
errors from an earlier build when reconnecting.

To test sensing, remove the watch from its charger, wear it, and keep the probe
face active. Check for `ok`, a BPM value and an increasing sensor count. This
disconnects the USB debugger, so watch-visible readings are only watch-side
evidence. The phone remains a separate installation/transport checkpoint.

## Build

```sh
npm ci
npm test
npm run build
```

`npm run debug` starts the Fitbit SDK developer shell. `FITBIT_QA_COMMANDS=1`
enables the beta shell's `hosts` command for discovering the developer hosts.
The CLI expects an interactive login using the same account as the watch.
Do not put Fitbit credentials or tokens in this repository.

This CLI predates current npm dependency releases. The AppAuth override to 1.2.8
preserves the module paths the CLI imports; later AppAuth packages moved them.
Native developer-tool dependencies keytar and usb have narrowly pinned install
script approvals. Their permissions do not grant this probe access to health data.
The legacy toolchain has npm audit findings; it is a local build/development tool,
not a web service or production backend. Avoid automatic major dependency upgrades
that silently change the beta SDK target behavior.

## First hardware test

Use the phone app already paired to the Sense 2 (Fitbit, called Google Health in
newer documentation). Open the Sense 2 device page, then System / Developer Menu;
Developer Bridge was found and enabled there on this user's phone. This is
separate from the app's general Settings / Services / Labs menu.
Some experimental workflows also require the watch on its USB charger with
Developer Bridge / USB debugging enabled in watch settings. These options and the
private app-installation route must be verified on the installed versions.

After installation, approve requested access if prompted. Keep this clock face
active and the paired phone app open for the first test. It does not promise to
keep running when the built-in Exercise app replaces it. Watch screen-off and
phone-background behavior are part of the test, not established capabilities.

The large BPM value is local watch evidence. The watch's `Phone received #N`
status and companion console lines such as `HR_PROBE { ... }` establish that a
new sample reached the phone. Check changing sample sequence/timestamps, repeated
BPM values, loss on removing the watch, and reconnection behavior. A cached BPM
repeated in heartbeats never increments the sensor sequence.

## Optional forwarding to Workout Lab

No upload destination is built in. Before configuring one, verify watch-to-phone
readings through the SDK console. The live BPM values are not logged to disk by
this project or sent to a public relay.

In the clock face's phone settings, configure:

- Lab HR endpoint: `http://<treadmill-ip>:8780/api/hr`
- Lab pairing token: the token displayed by STRIDE Workout Lab on the console

Select PHONE input in the Lab. Incoming data never starts or resumes a routine.
The companion POSTs `{bpm, ageMs, source:"sense2", sequence, sessionId}` with an
Authorization bearer header. It forwards at most one copy of each sensor reading,
keeps only the newest pending sample, and refuses samples older than five seconds.
A watch clock more than two seconds ahead of the phone is rejected; the clocks
must be synchronized. Sample age depends on watch event timing, including the
SDK's sensor behavior; this is not a measured guarantee of sensor latency.

The companion receives raw sensor timestamps to detect repeated cached readings,
but its observedAt timestamp represents the watch reading event, not independently
verified acquisition time. Sensor acquisition delay must be measured before motor
control. A stalled fetch stops new requests from accumulating; no old backlog is
played on recovery. Off-wrist/paused notifications clear queued readings.

The Lab is HTTP on the local LAN for this prototype. Depending on the paired phone
app version, Android cleartext-network policy or CORS may block companion fetch;
this transport is not yet tested on the Pixel. No public tunnel, certificate bypass,
or cloud health-data upload has been configured. A reliable stream, secure
transport and controller verification remain prerequisites for physical adaptation.

## References

- [Official Appbit timeout reference](https://dev.fitbit.com/build/reference/device-api/appbit/)
- [Official sensor frequency reference](https://dev.fitbit.com/build/reference/device-api/sensors/)
- [Official Exercise API reference](https://dev.fitbit.com/build/reference/device-api/exercise/)
- [Official display reference](https://dev.fitbit.com/build/reference/device-api/display/)
- [Official heart-rate guide](https://dev.fitbit.com/build/guides/sensors/heart-rate/)
- [Official permission guide](https://dev.fitbit.com/build/guides/permissions/)
- [Official OAuth authorization endpoint](https://dev.fitbit.com/build/reference/web-api/authorization/authorize/)
- [Official messaging guide](https://dev.fitbit.com/build/guides/communications/messaging/)
- [Official publishing guide](https://dev.fitbit.com/build/guides/publishing/)
- [Google support developer-mode instructions](https://support.google.com/googlehealth/thread/433273147/how-to-enable-developer-mode?hl=en)
- [Beta Sense 2 SDK investigation](https://github.com/yeohongred/fitbit-versa4-sense2-sdk)
- [Experimental developer bridge workflow](https://github.com/cmengler/fitbit-app-versa4)

The probe's source is newly written under the parent project's Apache-2.0 license.
The references are not a guarantee of current device compatibility.

Installation checkpoint: after the user approved the new permission, SDK USB
installation and launch acknowledged build `0d41e117a9cb174c`. A watch screenshot
verifies DIAG 0.1.5, eight timer callbacks over eight seconds, zero HR events on
the charger, and the idle “Start 60s test” control with its recording notice.
No exercise was started remotely. The user will initiate the two-step recording
and report its result from the same probe screen.

### Touch correction 0.1.6

The user reported that neither Start nor Pause responded. The 0.1.5 SVG omitted
`pointer-events="visible"` on both button backgrounds and text labels; Fitbit
requires this opt-in for native click events. Version 0.1.6 enables all four
targets and shows a tap count beside the phone status after a handler runs.
The prior Node UI mocks called handlers directly, so they could not detect
disabled native hit testing. A resource regression check now verifies the opt-in,
and a label-handler test verifies visible tap feedback, wear gating, and the
separate confirmation press. The 45 tests and FBA build pass. Physical touch and
the native Exercise API still require a user test on the watch.

Reference: [Fitbit UI events guide](https://dev.fitbit.com/build/guides/user-interface/javascript/#events).

USB installation and launch acknowledged 0.1.6 build `0b414e309bf578a9`.
A device screenshot verifies the new version and idle controls. No recording
was started remotely; physical tap verification remains pending.

### Exercise state diagnosis 0.1.7

The user registered six taps in 0.1.6. A screenshot showed the pre-start message
“Another Fitbit exercise may be active,” but the user confirmed none was active.
That revision grouped any value other than `stopped` into one message; the exact
state from that attempt was not captured. Revision 0.1.7 distinguishes a reported
`started`/`paused` state from an unsupported value and displays the latter's
primitive value/type. Unknown states still block recording.

A guarded, read-only state inspection now runs at launch and reports metadata
only to USB logs. It never starts, stops, resumes, or adopts an exercise. After
the first installation's launch timed out and a fresh launch returned “App not
installed,” one bounded reinstall succeeded. SDK install and launch acknowledged
build `0255c9b194333496`; its log reported `exercise state=stopped; valid=true`.
This confirms the current state, not the cause of the earlier rejection. The
48 tests cover unknown-state diagnostics and read-only inspection preserving
confirmation, ownership, passive HR, and foreign-exercise protection. The
on-watch Start/Confirm recording test is still pending.

### Start failure diagnostics 0.1.8

The user reached “Exercise start failed; no automatic retry” in 0.1.7. That
message did not distinguish an exception in the native `start()` call from a
call that returned without immediately reporting `started`. It establishes a
failed verification, not the native reason or that no short record was created.

Revision 0.1.8 preserves the failure stage, exception name/message (up to 96
characters), and native state. After failure, three sensor-detail rows instead
show these diagnostics; the primary HR display and freshness checks keep
working. Diagnostics survive removal from the wrist and are cleared only by
the next explicit Start attempt. Error-state ticks observe the latest native
state without retrying start or adopting/stopping a later exercise. The
existing explicit confirmation and synchronous start verification are unchanged.
No sensor values enter the diagnostics. Fifty tests pass, including native
exceptions, unconfirmed returns, read-only follow-up, and visible errors
surviving off-wrist events. At this checkpoint native recording was unverified;
the next screenshot below establishes a delayed native start.

### Delayed native lifecycle correction 0.1.9

The user reported `Start failed: Unconfirmed/stopped`. A USB screenshot of 0.1.8
showed both the captured immediate `stopped` result and the later current state
`started`. The native call had taken effect asynchronously. The old controller
had dropped ownership and its stop timer before that transition; its failure
message was incorrect, and its 60-second bound did not cover this case. The user
was told to close the probe and end the test recording if shown in Fitbit.

Version 0.1.9 retains the request through `starting`, `active`, and `stopping`,
polling native state once per timer callback. Optional state-change events are
counted but never make native writes inside the callback. The recording deadline
remains 60 seconds from the original request, not 60 additional seconds after
confirmation. Stop is sent once, and ownership is released only after a confirmed
start is followed by a confirmed `stopped` state. The UI shows waiting, cancel,
countdown, and stop-confirmation states explicitly.

A pending request cancelled by the user, wear loss, HR pause, clock rollback, or
a 10-second start timeout remains guarded: if it starts later while the probe is
alive, it receives a stop request, and no replacement start is allowed meanwhile.
After a stop failure/timeout, the app observes further state without repeating
stop writes and directs the user to Fitbit. Closing or terminating the runtime
while a native request is pending cannot guarantee that a later transition will
be handled; this is still a diagnostic requiring on-watch verification.

All 56 tests pass, including delayed start/stop, cancellation before a delayed
start, timeout followed by a very late start, no overlapping replacement calls,
and the complete delayed lifecycle through the watch UI. Existing foreign-workout,
wear, freshness, permission, and no-auto-start checks remain covered.

Installation checkpoint: after the first launch timed out and a fresh launch
reported “App not installed,” one bounded reinstall and launch acknowledged
0.1.9 build `031f1de14c509a59`. Its read-only startup log reported `stopped`.
No exercise was started remotely. Corrected on-watch lifecycle verification
remains pending; unit-test results do not establish physical stop timing.

### Completed wrist test and statistics comparison 0.1.10

After the user completed the 0.1.9 test and reconnected USB, a screenshot showed
`Test complete: 5 readings, max gap 33s`. Reaching this completion path requires
an observed native start followed by `stopped`; it confirms that the corrected
lifecycle completed on this run, though exact native stop latency was not
measured. The whole-runtime counters showed eight events, eight unique/eligible
readings, one sensor start, and 129 timer callbacks over 132 seconds. That runtime
includes time outside the test and cannot be used to calculate its sampling rate.
No raw biometric values or screenshots are checked into the repository.

Five eligible readings and a 33-second maximum gap do not establish a usable
stream for the controller's five-second freshness requirement. Revision 0.1.10
compares the sensor channel with read-only `exercise.stats` during another
explicitly started test. It counts valid native-stat polls, BPM value changes,
advancing exercise time, unavailable/error reads, and longest same-value/wait
interval. The completion screen freezes these counters alongside sensor count,
sensor maximum gap, native state, state-event count, and start-confirmation delay.

ExerciseStats has no HR acquisition timestamp. A poll or BPM value change is
therefore only comparison evidence; an unchanged BPM may also represent genuine
new measurements. Neither polls nor changes enter HR frames, refresh observation
timestamps, advance sensor sequence, or count as eligible sensor readings. Native
stats errors cannot interrupt the existing start/stop lifecycle or passive HR.
All 62 tests and the FBA build pass, including isolation of the statistics channel
and freezing comparison counters after completion. No new permissions are added.

The SDK acknowledged 0.1.10 build `00d2b5977e6914a8` after one bounded reinstall
following the recurring first-launch timeout/“App not installed” behavior. Launch
and a watch screenshot verify the new idle UI, and the read-only native state is
`stopped`. The comparison test still requires the user’s on-watch Start/Confirm.
