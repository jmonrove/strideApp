# strideApp Coding Standards

The style detail. The agent contract (the rules) is in [`../CLAUDE.md`](../CLAUDE.md); this file is
the knowledge it references. Three languages live here: Kotlin (`console/`), JavaScript
(`wearables/sense2`, the bundled console UIs), and Python (`tools/android5`, `homeassistant/`,
`protocol/`). Each follows its own idiom, as set out below. Match the module you are in.

## Naming conventions

> **This section is non-optional.** Written-down casing rules are the cheapest way to prevent
> convention drift between agents, and they are what a reviewer enforces. Never introduce a new
> style partway through a module.

| Identifier | Convention | Example |
|---|---|---|
| Kotlin packages | `dev.stride.<module>`, one lowercase word | `dev.stride.lab`, `dev.stride.motorbench` |
| Kotlin classes / objects / files | PascalCase; the file is named after its main class | `WorkoutEngine.kt`, `FitProConnection.kt` |
| Kotlin functions / properties | camelCase | `acceptHeartRate()`, `canResumeManual` |
| Kotlin constants | UPPER_SNAKE_CASE `const val`, in a `companion object` | `MIN_SAMPLES`, `READ_TIMEOUT_MS` |
| Kotlin enum values | UPPER_SNAKE_CASE | `State.SENSOR_HOLD` |
| Kotlin tests | `<Class>Test.kt`; methods are camelCase behavior sentences | `staleSensorHoldsUntilExplicitResume()` |
| Logcat tags | `Stride` + PascalCase area | `Stride`, `StrideBoardRead`, `StrideHistory` |
| **Physical quantities** | **The unit is a suffix on the name**, in every language | `baseSpeedMph`, `decisionIntervalMs`, `inclineRatePercentPerSec`, `targetLowBpm`, `acquisitionAgeMs` |
| JS functions / variables | camelCase | `createDisplayRelay`, `inspectFrame` |
| JS files | kebab-case; tests are `<name>.test.mjs` | `display-relay.js`, `stride-core.js`, `protocol.test.mjs` |
| JSON fields (HTTP, watch messages) | camelCase, with the unit suffix | `sessionId`, `pollAgeMs`, `displayOnly` |
| Enumerated string values (kinds, sources, quality) | snake_case | `hr_display`, `sense2_exercise`, `acquisition_unknown` |
| HTTP endpoints | `/api/` + kebab-case | `/api/hr`, `/api/hr-display` |
| Python modules / functions / variables | snake_case; Home Assistant scripts start with `stride_` | `console_access.py`, `stride_reminders.py` |
| Python classes | PascalCase | `Access` |
| Python constants | UPPER_SNAKE_CASE at module level | `MODEL`, `MARKER`, `PROPS` |
| Python tests | `test_<module>.py`, `unittest` | `test_console_access.py` |
| Shell scripts | lowercase, kebab-case | `enable-wifi-adb.sh`, `grab-ifit.sh`, `run.sh` |
| Console UI documents | one lowercase word per interface | `cluster.html`, `pacer.html` |
| Android `versionName` | `<semver>-<role>` for the fork's modules; bump `versionCode` on every build that gets installed | `0.2.1-console-preview`, `0.1.0-simulator` |
| Docs | New docs in `docs/` use lowercase kebab-case (`coding-standards.md`); existing uppercase docs (`ANDROID_5_PORT.md`, `INSTALL.md`) keep their names; module docs are `README.md` | |

## Change-marker / comment format

No change-marker is required. Two habits carry over from upstream ([`../CONTRIBUTING.md`](../CONTRIBUTING.md)):

- **Comments explain why, not what.** The hard parts here are facts invisible in the code: "the
  board reports 0.00 at every speed", "this console boots at 2010", "AAPT expands `.gz` assets".
  Write those down next to the code that depends on them.
- **State the boundary in the code.** Classes that are deliberately limited say so in their KDoc
  (for example `/** Proposed setpoints only. This package has no USB or motor-command
  implementation. */`). Keep those lines true. When one stops being true, that is a design change
  under CLAUDE.md rule 2, not a comment edit.

**Commit messages:** a sentence-case, imperative subject with no type prefix ("Add guarded vendor
USB debugging enable action…"). Put the reasoning in the body when it isn't obvious; upstream's log
shows the shape.

## Formatting

No formatter or linter config is checked in (no ktlint, detekt, Prettier or Black). Match the file.

- **Indentation:** 4 spaces in Kotlin and Python; 2 spaces in JS. No tabs.
- **Line length:** no enforced limit. Most code stays near 100–120 characters; don't reflow
  existing lines.
- **Kotlin:** compact, with expression-bodied functions and small `data class`es. `@Synchronized`
  marks the public entry points of state machines (`WorkoutEngine`). Validate configuration in
  `init { require(...) }`.
- **Doc comments:** KDoc `/** */` on classes and non-obvious functions; a module docstring at the
  top of each Python script; a block comment at the top of each console UI JS file explaining what
  it owns.

## Platform constraints (these fail on the device, not in the build)

- **Android API 22 minimum, `targetSdk 28` on purpose.** `BootReceiver` starts an activity from a
  background broadcast (API 29 closed this), and the Bluetooth permissions are the pre-31 set. Keep
  `ExpiredTargetSdkVersion` disabled with its reason. Check the API boundary with
  `./gradlew :app:lintDebug` (`-PcompatLint` in `console/stride` checks only `NewApi`/`InlinedApi`,
  so it is not a full lint pass).
- **No AndroidX** in `console/stride`. It uses framework APIs and the plain Java Paho MQTT client.
- **The console WebView is Chromium 83 (2020).** No `String.prototype.replaceAll`, no
  `Array.prototype.at`, no CSS `:has()`, no `inset`. Use explicit positional properties and flex
  spacing. A successful native build does not validate the HTML.
- **WebViews load bundled assets only** (`https://stride.local/` in the Lab). No remote navigation,
  no file or content access, no external requests.
- **Fitbit SDK versions are pinned** (`@fitbit/sdk` 6.2.0-pre.1, CLI 1.8.0-pre.10). Watch code
  targets `rhea` only.

## Security & error handling

- **Secrets come from `local.properties`**, which is gitignored. They are read in
  `app/build.gradle.kts` and baked into `BuildConfig` only as a seed for the settings the user
  stores. A fresh clone must build an app that can't reach any broker. That is the intended failure.
- **Debug builds are `debuggable`** and turn on the WebView DevTools socket. That is acceptable on
  a bench but not on a console that stays installed (see `console/stride/run.sh`).
- **LAN HTTP is bearer-token authenticated, not encrypted.** Operational endpoints require the
  per-launch token and allow no CORS. Bound the body size and the concurrency.
- **ADB helpers take one explicit serial** and quote remote commands with `shlex`. `subprocess`
  never uses a local shell. Preview mutations by default and save a baseline before changing
  anything.
- **Logs omit raw BPM**, tokens and addresses.

## A check must not return the same answer for "nothing" and "broken"

A check that cannot tell *no result* apart from *could not look* carries **no information**, and it
fails in the worst possible way: it keeps running, keeps reporting, and is believed.

Instances from this repo, where the same trap surfaces as a hardware or freshness question:

- **A recent poll does not prove a recent heart-rate measurement.** Exercise statistics returned 53
  valid polls out of 58 while not one sensor reading qualified as fresh. Changing BPM values do not
  establish acquisition timestamps. Hence `acquisitionAgeMs=null` and `displayOnly=true`.
- **An ACK of a target is not motion.** Vendor cache updates are not evidence of speed or grade
  (D08). Measure actual values independently.
- **A property readback does not prove a reboot.** A marker or `persist.*` value can read back
  correctly on the same boot. Only a changed boot ID shows a new boot, and visible privileged mode
  has to be checked separately.
- **A sensor or HTTP error is not "no data".** A stalled or failed request must show as stalled or
  failed (the companion reports `receiver: stalled`), never as an empty reading.

Generic instances: `$(cmd)` in shell is empty both when the command fails and when it legitimately
prints nothing; `grep -c` exits 1 on a count of zero, which under `set -e` kills the caller on the
legitimate answer "none".

Two rules follow, and people skip the second:

1. **Separate the states at the point of the call:** an exit code, a sentinel, a distinct return,
   or a nullable age. Don't infer them from an empty value.
2. **Test BOTH directions.** Build the true case and the false case, and make the test fail loudly
   if the probe itself breaks. The freshness tests in `WorkoutEngineTest` and
   `HeartRateIngressTest` show the pattern: fresh, stale, replayed, and from a retired session.

## Pre-commit checklist

- [ ] Names follow the table above, including the unit suffix on every physical quantity
- [ ] Tests for the touched module pass (commands in [`runbooks/verify.md`](runbooks/verify.md))
- [ ] Lint passes for Android modules, and still covers API 22
- [ ] UI JS/CSS uses nothing newer than Chromium 83
- [ ] No `Log.d`/`console.log` left behind that prints BPM, tokens or addresses
- [ ] Nothing from CLAUDE.md rule 6 is staged (`git status` + `git diff --cached --stat`)
- [ ] No check conflates "nothing found" with "couldn't look" (see the section above)
- [ ] The status docs this change makes false are updated (see "Keeping status docs true" in
      `../CLAUDE.md`)

---

*The agent contract (rules) is in `../CLAUDE.md`; this document is the style detail it references.*
