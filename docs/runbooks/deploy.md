# Deploy Runbook: strideApp

How a change in this repo ships. **An agent shipping a change reads this file and follows
the Ceremony below step by step**, so it is an executable contract, not a narrative. Keep it accurate: a wrong
step here runs the wrong git operation on a public repo.

`CLAUDE.md` still wins. This file supplies the *route*: branches, order, and what makes a change
ship. It cannot authorise skipping the rails that apply everywhere (staging by explicit path, the
confirmation gates, no force-push, no history rewrite). If a step here appears to conflict with
`CLAUDE.md`, `CLAUDE.md` is correct and this file needs fixing.

**Shipping is a git push. It installs nothing.** Installing a build on the treadmill console or the
Sense 2 watch is a separate hardware action that needs the developer's OK each time
(CLAUDE.md rule 3). See "Device installs" below. It is never a step of this ceremony.

## At a glance

| | |
|---|---|
| **What makes a change live** | `git push origin android-5-compat`, which publishes the fork branch on `jmonrove/strideApp` (a public repo) |
| **Integration branch** | None. Routine work is committed straight onto `android-5-compat`, and its history is linear, with no merges. Risky work starts on a short-lived feature branch (see Merge style) |
| **Production branch** | `android-5-compat` on `origin`. `main` stays at the upstream fork point `d9e1b53` and is not shipped to |
| **Production push** | `git push origin android-5-compat` |
| **Merge style** | Fast-forward only: `git merge --ff-only <feature-branch>` into `android-5-compat`, never a merge commit. Use a feature branch for work that could move the belt or deck, or that connects heart rate to workload (D13, D14), and for anything else the developer wants kept off the main line until it is tested |
| **Deploy is reversible?** | Yes, with `git revert` and a new push. Never by rewriting history |

## Ceremony

1. Run `git rev-parse --abbrev-ref HEAD`.
   - If it prints `android-5-compat`, go to step 2.
   - If it prints a feature branch, run step 3's checks on that branch first. Only when they pass,
     run `git checkout android-5-compat`, then `git merge --ff-only <feature-branch>`. If git
     refuses to fast-forward, **stop and ask**. Don't create a merge commit, and don't rebase a
     branch that has been pushed.
   - If it prints anything else (`main`, or `HEAD` for a detached checkout), **stop and ask**.
2. `git status --short` must show nothing staged or modified that is not part of this ship. Check
   that `git diff origin/android-5-compat --stat` contains nothing from CLAUDE.md rule 6
   (`local.properties`, keystores, `stride.conf`, addresses, health data, vendor binaries).
3. Run the test and lint commands for every module the outgoing commits touch, from
   [`verify.md`](verify.md) § "Module checks". All must pass before you go on. If step 1 just ran
   them on this exact commit, you don't need to run them again.
4. `git fetch origin`, then `git merge-base --is-ancestor origin/android-5-compat HEAD`. It must
   exit 0. If it doesn't, the remote has moved: **stop and ask**. Never force-push.
5. `git push origin android-5-compat`. **This is the deploy.**

## Verification

- `git fetch origin && test "$(git rev-parse origin/android-5-compat)" = "$(git rev-parse HEAD)"`
  must succeed.
- `git ls-remote --tags origin` lists no tag that this ship created. Only the inherited `v0.7.0` and
  `v0.8.0` should be there.

## Rollback

- `git revert <sha>` on `android-5-compat`, then run this ceremony again. Never use `reset` +
  force-push.
- A build already installed on the console or watch is not rolled back by git. Reinstalling a
  previous build is a device install (below) and needs the developer's OK.

## Device installs (not part of this ceremony)

These happen only when the developer asks, at that moment, with the developer present:

- **Console (motor-free packages only: `dev.stride.lab`, `.usbprobe`, `.motorbench`, `.setup`):**
  `adb -s "$STRIDE_DEVICE" install -r <module>/app/build/outputs/apk/<variant>/<apk>`, then the
  `am start` line from the module README. Never install `dev.stride.hud`.
- **Watch:** from `wearables/sense2`, run `npm run usb -- install`, then `npm run usb -- launch`.
- Afterwards, record the build, date and outcome in the module README and the validation snapshot
  in `docs/architecture.md`. Put the evidence file in `../artifacts/`.

## Do not

- Never push to `upstream` (keranm/strideApp) or open a PR there unless the developer asks.
- Never push or move `main`, on `origin` or anywhere else.
- Never push a `v*` tag. `.github/workflows/release.yml` fires on `v*` and would try to build,
  sign and publish the upstream motor app (`dev.stride.hud`) as a GitHub release.
- Never force-push or rewrite pushed history.
- Never create a merge commit on `android-5-compat`. Feature branches come back fast-forward only.
- Never treat a successful push as hardware validation (CLAUDE.md rule 4).

---

*Route lives here; rules live in [`../../CLAUDE.md`](../../CLAUDE.md). An agent reads it on
every ship. If it had to stop and ask you something, the answer belongs in this file.*
