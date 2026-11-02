# CLAUDE.md

Agent guide for **strideApp**, the `jmonrove/strideApp` fork of
[keranm/strideApp](https://github.com/keranm/strideApp). **This is the canonical contract for every
coding agent.** `AGENTS.md` and `CODEX.md` defer here. When instructions conflict, this file wins.

strideApp replaces the iFit software on a NordicTrack treadmill console. This fork (branch
`android-5-compat`) ports it to one Android 5.1 / API 22 console and adds separate modules that
cannot drive the motor: Kotlin/Android apps under `console/` (each one its own Gradle root), a Fitbit
Sense 2 heart-rate probe in JavaScript under `wearables/sense2`, and Python access helpers under
`tools/android5`. **The fact to know first: physical motor control and the live
watch → phone → console heart-rate path are not validated, and the modules are kept apart on
purpose until they are.**

**How the docs work: the rules that bind an agent live in this file. The conventions the code must
satisfy, and all other knowledge, live in `docs/` and the module READMEs.** Read this file fully,
then pull in only what your task needs via the routing table below.

## Non-negotiable rules

1. **This software moves a belt and a deck with a person on them.** Before changing anything that
   can reach the motor board, read [`SAFETY.md`](SAFETY.md) and the "If you touch anything that
   moves the belt" section of [`CONTRIBUTING.md`](CONTRIBUTING.md). That code is `console/stride`
   (`FitPro*.kt`, `Session.kt`, `Plan.kt`, `Ftms.kt`), `console/motorbench`, and the
   query/claim code in `console/usbprobe`. Keep their invariants: the safety key is the stop of
   record; incline is driven but speed is only ever suggested; every request is clamped in Kotlin
   to the limits the board reports; automatic stops ramp down, while STOP acts immediately.
2. **Never weaken a motor isolation boundary unless the developer explicitly asks.** Don't remove or
   bypass `LockedUsbLink`'s rejection of physical writes, and don't add a runtime unlock to
   `console/motorbench`. Don't give `console/workoutlab`, `console/setup` or `wearables/sense2` a
   USB/FitPro transport. Don't connect `WorkoutEngine` proposals or heart rate to any board adapter
   (decisions D09, D13 and D14 in [`docs/architecture.md`](docs/architecture.md)). Don't add write or
   actuation commands to `console/usbprobe`.
3. **Every action against the real console or watch needs the developer's OK, every time, with the
   developer present.** That covers `adb install`, `am start`, `setprop`, broadcasts,
   `connectedDebugAndroidTest`, reboots and Fitbit USB installs. Before you run it, say what you
   will run and on which serial. Always pass an explicit serial (`adb -s "$STRIDE_DEVICE"`) and
   never act on whatever happens to be attached. Never install the motor-capable `dev.stride.hud`
   app on the console. Never run `tools/unchain.sh` on the Android 5 console: it disables the iFit
   packages, which this access route deliberately keeps (D03).
4. **Keep the levels of evidence separate.** "Builds", "unit tests pass", "works on the emulator"
   and "verified on the treadmill or watch" are four different claims. Never write a stronger claim
   than you observed, whether in code comments, a README, the validation snapshot or your report.
5. **Never pass along a number without its age.** Heart-rate and health values carry their
   acquisition time. A poll, an ACK or a property readback does not prove a fresh measurement or
   real motion. Display-only readings (`hr_display` / `sense2_exercise`) must never become
   guidance input (D07).
6. **This repo is public.** Never commit any of these: `local.properties`; keystores (`*.jks`,
   `*.keystore`, `*.p12`); `homeassistant/stride.conf`; broker credentials; Fitbit or SDK tokens;
   console IP addresses, boot IDs or serials; heart-rate or health values, or screenshots showing
   them; extracted iFit or vendor binaries, or decompiled vendor code. Device evidence goes in
   `../artifacts/`, outside the repo. Per-device baselines stay in the ignored `local/` directories.
7. **Git: commit only when asked, and stage by explicit path, never with `git add -A`.** Ship only
   by following [`docs/runbooks/deploy.md`](docs/runbooks/deploy.md). Never push to `upstream`
   (keranm). Never push or move `main`. Never push a `v*` tag, because that fires
   `.github/workflows/release.yml`. Never force-push. Routine work is committed on
   `android-5-compat`. Work that could move the belt or deck, or that connects heart rate to
   workload (D13, D14), starts on a short-lived feature branch and comes back with
   `git merge --ff-only`, as the runbook describes.
8. **Inherited upstream material stays upstream's.** The lower half of `README.md`, plus
   `SAFETY.md`, `CONTRIBUTING.md`, `docs/INSTALL.md` and `docs/RELEASE_NOTES.md`, describe keranm's
   machine. Don't present their claims as this fork's results; write fork status in the fork's own
   docs.

## Find what you need

| Your task involves… | Read first |
|---|---|
| The system map: modules, flows, stack, build, decisions, validation snapshot | [`docs/architecture.md`](docs/architecture.md) |
| Style, naming (unit suffixes), Android 5 / WebView 83 limits, pre-commit | [`docs/coding-standards.md`](docs/coding-standards.md) |
| Reviewing a change | [`docs/pr-audit-checklist.md`](docs/pr-audit-checklist.md) |
| Shipping a change | [`docs/runbooks/deploy.md`](docs/runbooks/deploy.md) |
| Checking a change end to end: tests, emulator, console | [`docs/runbooks/verify.md`](docs/runbooks/verify.md) |
| Anything that moves the belt or deck | [`SAFETY.md`](SAFETY.md) + [`CONTRIBUTING.md`](CONTRIBUTING.md) |
| Android 5 port status, ADB/iFit findings, board-query evidence | [`docs/ANDROID_5_PORT.md`](docs/ANDROID_5_PORT.md) |
| Console access: Wi-Fi ADB, privileged mode, undo steps | [`tools/android5/README.md`](tools/android5/README.md) |
| The FitPro serial protocol | [`protocol/FITPRO_PROTOCOL.md`](protocol/FITPRO_PROTOCOL.md) |
| One module | Its README: [`console/workoutlab`](console/workoutlab/README.md) · [`console/usbprobe`](console/usbprobe/README.md) · [`console/motorbench`](console/motorbench/README.md) · [`console/setup`](console/setup/README.md) · [`wearables/sense2`](wearables/sense2/README.md). `console/stride` has none; use `CONTRIBUTING.md` |
| Upstream install, Home Assistant, releases | [`docs/INSTALL.md`](docs/INSTALL.md) · [`homeassistant/stride.conf.example`](homeassistant/stride.conf.example) · [`docs/RELEASE_NOTES.md`](docs/RELEASE_NOTES.md) |
| Why something was built the way it was | The decision record in [`docs/architecture.md`](docs/architecture.md); [`docs/plans/`](docs/plans/) (dated records: don't edit) |

## Keeping status docs true

Fork status is quoted in several places, and a change that moves it must update all of them in the
same change:

- **New device or watch evidence** (or a failed attempt) → the module README's validation section,
  the validation snapshot in `docs/architecture.md`, and `docs/ANDROID_5_PORT.md` if it concerns the
  port. Date the entry. The evidence file goes to `../artifacts/`.
- **Changed a `versionName` / `versionCode`** → the module README and the components table in
  `docs/architecture.md`.
- **Changed a manual setup step** → "What still needs to be done manually" in `README.md`,
  `tools/android5/README.md` and `console/setup/README.md`.
- **Added or removed tests** → the test count quoted in the module README and in the validation
  snapshot.

## Local environment

- **JDK / Android:** JDK 17 or 21 (`/usr/libexec/java_home -v 17`); Android SDK at `$ANDROID_HOME`
  (default `~/Library/Android/sdk`); compileSdk 34, minSdk 22, targetSdk 28. Each `console/<module>`
  is its own Gradle root, so run `./gradlew` from inside it. For `console/stride`, use `./run.sh
  build`: it sets `JAVA_HOME`, and calling `./gradlew` there directly tends to fail with a
  misleading error.
- **Node:** `wearables/sense2`: `npm ci`, `npm test` (`node --test`), `npm run build` →
  `build/app.fba`. The Fitbit SDK is pinned (`@fitbit/sdk` 6.2.0-pre.1, `@fitbit/sdk-cli`
  1.8.0-pre.10); don't bump it.
- **Python:** 3.8+, standard library only. `python3 -m unittest discover -s tools/android5 -p
  'test_*.py' -v`.
- **Tests:** each module has its own command; see the table in
  [`docs/runbooks/verify.md`](docs/runbooks/verify.md). `console/stride` has no unit tests; its
  check is a build plus `-PcompatLint`.
- **Database:** none. Console state lives in app-private storage; the Home Assistant scripts read
  `homeassistant/stride.conf`.
- **Device:** set `STRIDE_DEVICE=<ip>:5555` for `run.sh` and the access helper, and `ANDROID_SERIAL`
  for Gradle connected tests. Never hardcode an address.
- **Build/deploy:** `git push origin android-5-compat` is the ship. Installing on the console or
  watch is a separate step, gated by rule 3. See
  [`docs/runbooks/deploy.md`](docs/runbooks/deploy.md).
- **Verify:** [`docs/runbooks/verify.md`](docs/runbooks/verify.md). Tests and the emulator by
  default; the console only with the developer's OK for that run.
