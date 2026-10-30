# STRIDE Workout Lab

An Android 5.1-compatible preview of four workout routines, a deterministic
heart-rate controller, and a paired phone interface. It is a separate APK
(`dev.stride.lab`) alongside STRIDE, so developing the phone/watch connection
cannot instantiate STRIDE's USB or motor-control code.

**This version never connects to the motor board.** MPH and incline values are
proposed setpoints in a simulation. It neither opens USB interfaces nor changes
iFit packages, launcher preferences, or reboot behavior. It contains no FitPro
transport. The original STRIDE motor driver remains in `../stride` for a later,
explicitly validated adapter.

## Build and validation

Set `ANDROID_HOME` to your Android SDK and `JAVA_HOME` to JDK 17 or 21:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

The standalone JVM tests exercise routine completion, bounds, rate limits,
sustained heart-rate adjustments, stale/off-wrist input, replay handling,
manual override, pause/stop, clock gaps and malformed HTTP. Local-socket
integration tests exercise bearer authentication and the phone/watch API.
The APK has also been installed and opened on an Android 5.1.1 NordicTrack 1750
console. Fourteen on-device API checks passed using synthetic heart-rate input:
authentication, routine inventory, fresh samples, explicit start, replay refusal,
stale-signal hold, stop and return to simulated input. This validates the preview
on that tablet; no motor connection or physical workout has been tested.

To install the preview APK and open its isolated activity:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.stride.lab/.WorkoutLabActivity
```

Opening the main STRIDE app instead is a different operation that may communicate
with the controller; the lab does not launch it.

## Native and phone preview

The lab begins with synthetic 120 BPM input and a zero speed proposal. After five
fresh readings spanning four seconds, press Start to run the selected 20-minute
preview. The four profiles are steady cardio, walking hills, effort/recovery
intervals, and easy recovery. The BPM slider changes synthetic input only.

The native screen shows the console's local address on port 8780 and a random
192-bit token. On a phone on the same Wi-Fi, visit `http://<console-ip>:8780/`
and enter the token. The phone page selects all four routines and durations,
shows the proposed MPH/grade, switches inputs, and provides pause, stop and
manual override. Tokens exist only for the current lab session, are sent in
an Authorization header, and are never put in a URL or saved by the page.

The local server is HTTP for this LAN prototype; it does not provide transport
encryption. Keep it on the local network, with no router port forwarding.
A future deployed system needs a reviewed pairing/transport design. Except
for the static pairing page, every endpoint requires the token. There is no
CORS allowance and the server is bounded to two workers, four queued clients,
8 KiB headers, 4 KiB bodies and three-second socket read timeouts.

Demo defaults are 2 MPH baseline, 0.8–3 MPH requested range, 0–4% grade and
110–130 BPM. These are software demonstration values, not a workout prescription
or verified limits for the user's motor board. Tests can configure the engine
constructor. A fresh native launch can override `baseSpeedMph`, `maxSpeedMph`,
`maxInclinePercent`, `targetLowBpm`, `targetHighBpm`, and `adaptiveSpeed` using
Android intent extras. For example, `--ef maxSpeedMph 2.5` supplies a floating
point limit. The native screen and `/status` report the active values.

The routine itself may propose phase-specific pace changes. `adaptiveSpeed`
(default false) controls whether heart-rate feedback may additionally trim that
pace. Grade adaptation remains enabled in the simulation.

## Sense 2 companion contract

Select **Use Sense 2 bridge** on the native screen (or PHONE in the phone page).
Incoming samples never select a sensor, start a routine, or resume it themselves.
Configure the companion to POST to:

```
http://<console-ip>:8780/api/hr
Authorization: Bearer <current pairing token>
Content-Type: application/json
```

Body:

```json
{
  "bpm": 120,
  "ageMs": 0,
  "source": "sense2",
  "sequence": 42,
  "sessionId": "watch-session-unique-123"
}
```

`bpm`, `ageMs`, and `sequence` must be JSON integers. Sample age must include
watch-to-phone delay and any phone buffering; receiving an old sample must not
make it look fresh. The receiver derives the sample time from its own monotonic
arrival clock minus this age; it does not compare independent device wall clocks.
Negative age (future data), implausible BPM, old sequence numbers and retired
session IDs are rejected. Validity is still dependent on the source honestly
reporting age; this API is not a cryptographic freshness attestation.

A session ID is 8–80 letters/digits, hyphens, underscores or colons. Generate a
new one on each watch app restart. Within it, sequence strictly increases. The
receiver keeps one current session and up to 64 retired IDs for this pairing.
A new session clears prior history and suspends running automation, requiring
fresh history and an explicit Resume. Old sessions can never become current
again. The server returns 200 with `accepted:true`, 409 for ignored/stale input,
400 for invalid input, or 401 when pairing is missing.

Other authenticated endpoints:

| Endpoint | Purpose |
|---|---|
| `GET /status` | Preview state, proposals, heart-rate freshness and limits |
| `GET /routines` | Routine IDs and display names |
| `POST /action` | Commands below, always JSON |

Actions: `start` with `routine` and integer `minutes` (1–120), `pause`, `resume`,
`stop`, `manual` with numeric `speedMph` and `inclinePercent`, `sensor` with
`source` equal to SIMULATED or PHONE, and `simulate` with integer `bpm`.

## Control behavior and next hardware boundary

- Five valid readings spanning at least four seconds are required before start.
- Ten recent readings are averaged. Changes are considered at most every ten
  seconds, with thirty seconds settling after an adaptive or phase change.
- Proposed speed changes are limited to 0.05 MPH/second and grade changes to
  0.1 percentage points/second, with configured bounds applied to target goals.
- Invalid, stale or off-wrist input suspends automation and holds its current
  proposal. Fresh data never automatically resumes the routine.
- Clock gaps over five seconds suspend automation; the engine never catches up
  a suspended app by jumping through a routine or its setpoints.
- Manual override remains in control until explicit Resume. Pausing sets the
  simulated speed proposal to zero and freezes time. Stop cannot auto-resume.

**Before a motor adapter is connected:** verify the user's actual controller
identity/protocol, real speed feedback, hardware safety-key behavior, physical
Stop/manual controls, bounded command delivery, and behavior after process or
phone failure. Intersect user limits with board-reported limits, and translate
simulation trajectories into the board's supported start/ramp/stop semantics.
The simulated ramp from zero can pass below a board's minimum moving speed;
these values must not be forwarded blindly as hardware commands. Stop and end
of preview are state transitions, not claims about physical braking behavior.

The physical safety key remains the independent emergency stop. This lab does
not establish that STRIDE can control the user's older board safely or reliably.
