# Sense 2 live heart-rate probe

This experimental clock face tests the user's Sense 2 before adapting workouts to
its readings. It is independent of the motor driver. It requests live heart-rate
access, displays BPM/status on the watch and sends individual sensor readings to
its companion inside the paired Fitbit/Google Health Android app.

The official npm beta SDK `@fitbit/sdk@6.2.0-pre.1` includes the `rhea` Sense 2
build target. The package builds with that SDK using device API 8.2.0 and companion
API 3.3.0, without proposed APIs. This is
not a claim that current Sense 2 firmware permits installation or supports every
sensor API. No live watch reading has been verified yet.

## Current validation

- Watch, companion and settings components build to `build/app.fba`.
- Ten Node tests cover schema validation, delayed/stale readings, future clocks,
  off-wrist/paused state, duplicate heartbeats, retired sessions, stalled delivery,
  and opt-in authenticated forwarding.
- SDK developer login was attempted on 2026-10-29, but the browser authorization
  route showed 403 Forbidden and did not return an authorization callback.
- The phone's Developer Bridge was found under the Sense 2 device page and
  enabled after developer registration. General Settings / Services / Labs was
  the wrong location. Relay discovery still fails with no stored SDK auth token;
  enabling the phone bridge does not complete the Mac's SDK sign-in.
- Local USB discovery returned no watch hosts. A watch-side USB debug test is
  pending because its charger was unavailable. The newer CLI 1.8.0-pre.11 was
  inspected: it has the same authentication implementation and production relay
  configuration as pre.10, so it was not substituted as an unproven login fix.
- Gallery App Manager sign-in and developer registration succeeded. A private
  STRIDE HR Probe clockface draft has been created and the FBA attached. The
  authorized Publish Clockface action returned a generic HTTP 400, both with
  proposed APIs and explicit API versions, including a clean-page retry. No
  installation link or uploaded version was created. The page and console logs
  exposed no more specific validation reason; the cause is unresolved.
- Developer ID / App Cluster configuration is unnecessary because this probe
  does not request app-cluster storage. The proposed-API flag is disabled because
  none of the probe's imports require it; its removal did not fix publication.
- The watch has not been installed or tested. Do not label readings live until
  a companion-side `HR_PROBE` log records fresh sensor samples on actual hardware.

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

After installation, approve heart-rate access on the watch. Keep this clock face
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

- [Official heart-rate guide](https://dev.fitbit.com/build/guides/sensors/heart-rate/)
- [Official messaging guide](https://dev.fitbit.com/build/guides/communications/messaging/)
- [Official publishing guide](https://dev.fitbit.com/build/guides/publishing/)
- [Google support developer-mode instructions](https://support.google.com/googlehealth/thread/433273147/how-to-enable-developer-mode?hl=en)
- [Beta Sense 2 SDK investigation](https://github.com/yeohongred/fitbit-versa4-sense2-sdk)
- [Experimental developer bridge workflow](https://github.com/cmengler/fitbit-app-versa4)

The probe's source is newly written under the parent project's Apache-2.0 license.
The references are not a guarantee of current device compatibility.
