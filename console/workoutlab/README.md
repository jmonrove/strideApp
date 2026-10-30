# STRIDE console preview

An Android 5.1-compatible console APK with the approved STRIDE-style interface:
Home, four guided routines and setup, a manual walk, the live workout, sensor
pairing, saved summaries/history, and settings. The live workout switches between
a 400 m oval, the planned incline profile, and a recorded heart-rate chart.
The layout fits the NordicTrack tablet’s 800×480 landscape screen.

**This build does not connect to the motor board.** Speed, grade and distance are
simulated proposals from a real deterministic workout engine, rather than mockup
values. The separate package `dev.stride.lab` contains no FitPro transport, USB
permission, motor driver, fan command or boot receiver. The original motor app
remains in `../stride` pending physical validation. The launcher name is **STRIDE
Preview**, version **0.2.1-console-preview**.

## Build and validation

Set `ANDROID_HOME` to your Android SDK and `JAVA_HOME` to JDK 17 or 21:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

The JVM tests cover bounds, rates, heart-rate adaptation, freshness/replay
handling, explicit start/resume, manual-only sessions, pause/stop, clock gaps,
configuration changes, distance accounting, bounded source-separated HR history,
and the authenticated local HTTP API. Full lint runs without suppressing API
compatibility findings.

A device integration test drives the bundled display through its native bridge:

```sh
ANDROID_SERIAL=<console-ip>:5555 ./gradlew :app:connectedDebugAndroidTest
```

It checks setup/start, the three visualizations, manual override and resume,
manual-only operation without heart rate, settings and explicit history saves.
This test exercises **simulation only** and cannot instantiate a motor driver.
The test saves a synthetic session to verify persistence, then restores the prior history.

To install and open the APK:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.stride.lab/.WorkoutLabActivity
```

Opening the main STRIDE motor app is a different operation. This app neither
launches it nor changes iFit packages, launcher defaults, or reboot behavior.

## Console behavior

- New launches open Home with simulated 120 BPM input and zero speed proposal.
- Choose a routine and 15, 20, 30 or 45 minutes, then explicitly start the preview.
  Guided start needs five fresh readings spanning four seconds. Manual walk can
  start without a heart-rate signal.
- Speed/incline buttons take manual control; returning to guidance is explicit.
- Pause sets the speed proposal to zero and freezes session time. Manual resume
  restores manual control; guided resume needs fresh heart-rate history.
- Stop ends the session; it cannot auto-resume. A summary can be explicitly saved.
- Leaving the activity pauses the preview. App/process restart always starts idle;
  a previously running session is never reconstructed or resumed.
- Units, selected visualization, speed/incline caps, and pace-assistance choice
  persist on the tablet. Session limits can change only outside an active session.
- Up to 20 explicitly saved preview summaries remain in private app storage.
  They retain the input source, elapsed time, simulated distance, average/peak HR
  and a bounded chart series. Nothing is imported, uploaded or saved automatically.
  HR source/session changes clear the current HR summary to prevent mixing sources.
- A source returning after a dropout does not automatically resume guidance.

Default demonstration values are 2 MPH baseline, 0.8–3 MPH requested range,
0–4% grade and 110–130 BPM. These are software examples, not personalized targets.
Pace assistance defaults off. Guided routine phases still choose their own pace;
this setting separately controls additional heart-rate-driven pace adjustment.

The native code is authoritative. The local display contains no network client,
remote scripts or external frames. Only three immutable APK assets are served
under the app’s virtual `https://stride.local/` origin; other requests and
navigation are rejected, file/content access is disabled, and a restrictive CSP
is applied. The JavaScript interface accepts bounded, validated preview commands.

## Paired phone and Sense 2

Open **Sensors** on the console to see the current local address on port 8780 and
a random 192-bit session token. On the phone, open that address and enter the token.
The existing phone page selects routines/durations, shows proposals and freshness,
changes sensor input, and offers manual override/pause/stop.

No live Sense 2 connection is implied by selecting phone input. The receiver
shows waiting/stale until valid fresh samples actually arrive. The watch probe
and its remaining installation/streaming status are documented in
[`../../wearables/sense2/README.md`](../../wearables/sense2/README.md).

The current prototype uses HTTP on the local LAN. It is not encrypted; do not
expose it through router forwarding or a public tunnel. Tokens are generated per
app launch, sent only in Authorization headers, and never embedded in URLs or
saved by the phone page. Other than the static pairing page, endpoints require
a token. There is no CORS allowance. The bounded server uses two workers, four
queued clients, 8 KiB headers, 4 KiB bodies and three-second socket read timeouts.

Configure the Sense 2 companion, after verifying watch-to-phone delivery, with:

```text
http://<console-ip>:8780/api/hr
Authorization: Bearer <current pairing token>
Content-Type: application/json
```

Body:

```json
{"bpm":120,"ageMs":0,"source":"sense2","sequence":42,"sessionId":"watch-session-123"}
```

`bpm`, `ageMs` and `sequence` must be JSON integers. Age includes watch-to-phone
and buffering delay. Negative age, implausible BPM, duplicate/old sequences,
retired sessions and stale input are rejected. Arrival cannot freshen an old sample.
The source must honestly report age; this is not a cryptographic freshness proof.
A session ID has 8–80 letters/digits, hyphens, underscores or colons. A new session
clears input history and suspends running guidance until fresh data and explicit
Resume. Up to 64 retired IDs are kept for the current pairing session.

Authenticated endpoints:

| Endpoint | Purpose |
|---|---|
| `GET /status` | Engine state, proposals, freshness, targets, limits and current metrics |
| `GET /routines` | Routine IDs, names and phase profiles |
| `POST /api/hr` | Accept a Sense 2 companion reading (200 accepted, 409 stale/ignored) |
| `POST /action` | Validated preview commands below |

Actions use JSON: `start` with `routine` and integer `minutes` (1–120),
`startManual` with `minutes`, `pause`, `resume`, `resumeManual`, `stop`,
`manual` with numeric `speedMph` and `inclinePercent`, `sensor` with `source`
SIMULATED or PHONE, `simulate` with integer `bpm`, and `configure` with numeric
`maxSpeedMph`, `maxInclinePercent` and boolean `adaptiveSpeed`.
The native display uses the same validation. Presentation preferences and history
saves are native-only actions; the HTTP token grants no app settings/file access.

## Physical control boundary

Ten recent HR readings are averaged. Adaptation is considered at most every ten
seconds with thirty seconds settling after a phase/load change. Proposed ramps
are bounded at 0.05 MPH/s and 0.1 grade percentage points/s. Stale/invalid input
holds guidance; controller clock gaps do not jump through elapsed time or distance.

Before connecting a motor adapter, validate cold-start reads, independent moving
speed/incline feedback, safety-key and physical Stop behavior, board-supported
start/ramp/stop semantics, and process/phone failure behavior. User caps must be
intersected with live board limits. The simulation’s ramp passes below minimum
moving speed and must never be forwarded blindly as hardware commands. Its Stop
state is not a claim about physical braking.

## Display-only exercise HR

Version 0.2.1 adds authenticated `POST /api/hr-display` for Sense 2 exercise
statistics with unknown acquisition age. Its JSON contract requires
`source: "sense2_exercise"`, `displayOnly: true`, `quality: "acquisition_unknown"`,
`acquisitionAgeMs: null`, `pollAgeMs`, a positive sequence, session ID, status,
and integer BPM (or null for inactive/off-wrist/paused/unavailable statuses).
The receiver's separate display cache has no engine reference, expires old
polls after five seconds, and rejects replays and retired sessions. It does not
change the current control sensor, guide a workout, or enter saved HR history.
The phone page and Sensors screen label it as display only. Neither an accepted
poll nor a changing BPM establishes acquisition freshness.

Build, 36 JVM tests and full lint pass. Physical display/relay delivery for this
version remains pending because Wi-Fi ADB did not respond at 192.168.86.64:5555
and the Fitbit companion has not yet been installed. No motor transport is linked.
