# PR Audit Checklist

The single source of truth for reviewing a change in this repo, used by humans and by
review agents. **Every rule should earn its place: add one when a real bug ships, so that class
of bug never ships twice.**

## Severity classification

- **Critical**: anything that could move the belt or deck unexpectedly, or weaken a stop. Also data
  loss, a committed secret or personal health data, a security hole, or a crash on the golden path.
  Blocks approval.
- **Major**: wrong results on a real input, a broken feature, a claim of evidence stronger than
  what was observed, or a convention break that will cause bugs (for example a new casing style,
  or a quantity with no unit, partway through a module). Blocks approval.
- **Minor**: a bug in a narrow case, missing edge handling, a small inconsistency. Decide case by
  case.
- **Suggestion**: an improvement that isn't required for approval.

## 1. Motor and deck safety (any change under `console/stride`, `console/motorbench`, `console/usbprobe`)

- [ ] SAFETY.md invariants hold: the safety key is the stop of record; incline is driven but speed
      is only suggested; automatic stops ramp down (2 km/h per second upstream) while STOP is
      immediate
- [ ] Every speed or incline request is clamped **in Kotlin** to the limits the board reported,
      never in HTML/JS, and nothing in Settings can raise those limits
- [ ] No new write or actuation path: `LockedUsbLink` still rejects physical writes before they
      are transmitted, the bench still refuses to arm a physical link, and `usbprobe` still sends
      only allowlisted read queries
- [ ] No motor-free module (`workoutlab`, `setup`, `sense2`) gained a USB/FitPro dependency, and
      `WorkoutEngine` / heart rate is not wired to any board adapter (D13, D14)
- [ ] An ACK is not treated as motion, and a simulated Stop is not presented as a hardware
      guarantee (D08)

## 2. Freshness and evidence

- [ ] Every health or sensor value carries its acquisition time. Arrival time never makes an old
      sample fresh, and replayed or retired-session input is rejected
- [ ] Display-only readings (`hr_display` / `sense2_exercise`) can't reach guidance or saved
      guidance history (D07)
- [ ] READMEs, comments and the validation snapshot claim no more than was observed: built, unit
      tests, emulator, or device. A device claim names the date and the build

## 3. Conventions & naming

- [ ] Naming and casing match [`coding-standards.md`](coding-standards.md) and the surrounding
      module, with **no new style introduced partway through a module**
- [ ] Physical quantities carry a unit suffix (`…Mph`, `…Ms`, `…Bpm`, `…Percent…`)
- [ ] Module boundaries match [`architecture.md`](architecture.md); each console app stays its
      own Gradle root and package

## 4. Platform compatibility

- [ ] Nothing above API 22 without a version guard; `targetSdk` stays at 28; lint still runs
- [ ] UI JS/CSS runs on Chromium 83 (no `replaceAll`, `.at()`, `:has()`, `inset`)
- [ ] The Fitbit SDK and CLI versions are unchanged, and watch code still builds for `rhea`

## 5. Secrets, privacy & security

- [ ] Nothing from CLAUDE.md rule 6 is in the diff: `local.properties`, keystores,
      `stride.conf`, broker or SDK credentials, IP addresses, boot IDs, serials, BPM or health
      values, vendor binaries or decompiled code
- [ ] No `mqtt.*` value can reach a published build (`release.yml` refuses those, so don't route
      around the refusal)
- [ ] LAN endpoints still require the bearer token, bound body size and concurrency, and allow no
      CORS
- [ ] The WebView still loads bundled assets only
- [ ] ADB helpers still require one explicit serial, preview before mutating, and save a baseline
      first
- [ ] Logs omit raw BPM, tokens and addresses
- [ ] No check returns the same value for "nothing found" and "couldn't look" (a poll vs. a
      measurement, an ACK vs. motion, a readback vs. a reboot). If a check was added, is the FALSE
      direction tested as well as the true one?

## 6. Error handling

- [ ] Failures surface rather than being swallowed: a stalled request shows as stalled, and a
      refused property write stops with no automatic escalation or retry
- [ ] Process or app restart starts idle. A running session is never reconstructed or resumed
      automatically

## 7. Documentation the change makes false

The obligation is **reconciliation against the diff**, not against the plan. A plan can overlook a
doc, so "the plan didn't list it" is not evidence the doc is fine. A doc the plan promised to update
is still stale until that edit is actually in the diff.

- [ ] No committed doc is left asserting something this change made untrue: a moved or renamed
      path, a changed command or flag, a renamed config key, a structure tree, or a count
- [ ] `docs/README.md` and the routing table in `CLAUDE.md` still match the docs that exist
- [ ] **Module README**: versionName/versionCode, build/test command, quoted test count, or
      validation section changed
- [ ] **`docs/architecture.md`**: the components table (versions, boundaries), runtime flows,
      decision record, or validation snapshot changed
- [ ] **`README.md` → "What still needs to be done manually"**: a manual setup step was added,
      removed or automated
- [ ] **`tools/android5/README.md` / `console/setup/README.md`**: an access command, compatibility
      guard (model, build, Admin version) or undo step changed
- [ ] **`docs/ANDROID_5_PORT.md`**: new port or board evidence. Add a dated section; don't rewrite
      earlier ones

**Factual staleness only.** A sentence the diff has made untrue is a finding. Prose you would have
written differently is not, a doc that never existed is not, and the voice or editorial content of a
document is out of scope entirely. Fixes are corrections (commands, paths, names, counts), never a
rewrite of how a document reads. Upstream-inherited docs are corrected only where the fork changed
the fact.

**Severity, so this section can actually block.** A doc that would now *mislead* someone following
it (a command that fails, a path that doesn't exist, a safety or validation claim that no longer
holds) is **Major**. Drift that misinforms without misleading, like a count or an out-of-date
example, is **Minor**.

## Review completion criteria (approval gate)

Minimum for `APPROVED`:

- [ ] No Critical or Major findings open
- [ ] The test and lint commands for every touched module pass (see
      [`runbooks/verify.md`](runbooks/verify.md)); for `console/stride`, the build plus
      `-PcompatLint`
- [ ] New logic has tests, or a stated reason why not. A safety or freshness check has tests in
      both directions
