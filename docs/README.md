# strideApp Documentation

Start here to find the right document. **The rules for agents live in
[`../CLAUDE.md`](../CLAUDE.md).** That file is canonical and is always read first; everything below
is the knowledge it routes to.

## Fork knowledge

| Document | Read it when… |
|---|---|
| [`architecture.md`](architecture.md) | You need the system map: modules, runtime flows, stack, build, configuration, decision record, validation snapshot |
| [`coding-standards.md`](coding-standards.md) | You're writing or reviewing code: naming, unit suffixes, platform limits, pre-commit |
| [`pr-audit-checklist.md`](pr-audit-checklist.md) | You're reviewing a change before it ships |
| [`runbooks/deploy.md`](runbooks/deploy.md) | You're shipping a change: branches, order, what makes it live |
| [`runbooks/verify.md`](runbooks/verify.md) | You're checking a change end to end: tests, emulator, device |
| [`ANDROID_5_PORT.md`](ANDROID_5_PORT.md) | You need the Android 5 port log: ADB and iFit findings, board-query evidence (dated sections) |
| [`plans/`](plans/) | You want history: dated specs and plans (point-in-time records, so don't edit them) |

## Module and procedure docs

| Document | Read it when… |
|---|---|
| [`../tools/android5/README.md`](../tools/android5/README.md) | Console access: Wi-Fi ADB, privileged mode, reboot check, undo |
| [`../console/workoutlab/README.md`](../console/workoutlab/README.md) | STRIDE Preview: workout engine, console UI, phone page, HR receivers |
| [`../console/usbprobe/README.md`](../console/usbprobe/README.md) | Read-only board identification and the bounded query path |
| [`../console/motorbench/README.md`](../console/motorbench/README.md) | Motor simulator, the locked USB adapter, outstanding physical work |
| [`../console/setup/README.md`](../console/setup/README.md) | Setup APK: access switches, USB-debugging hook, Termux bundle |
| [`../wearables/sense2/README.md`](../wearables/sense2/README.md) | Sense 2 HR probe: build, USB install, test log |
| [`../protocol/FITPRO_PROTOCOL.md`](../protocol/FITPRO_PROTOCOL.md) | The FitPro serial protocol |

## Inherited from upstream (keranm/strideApp)

These describe the upstream author's machine. They are not validation results for this fork.

| Document | Read it when… |
|---|---|
| [`../SAFETY.md`](../SAFETY.md) | Before touching anything that moves the belt or deck |
| [`../CONTRIBUTING.md`](../CONTRIBUTING.md) | Setting up `console/stride`; the belt-safety rules; the HTML UI preview |
| [`INSTALL.md`](INSTALL.md) | The upstream install procedure (not this fork's Android 5 route) |
| [`RELEASE_NOTES.md`](RELEASE_NOTES.md) | Upstream v0.8.0 notes, which `release.yml` attaches to a release |
| [`GALLERY.md`](GALLERY.md) | Screenshots of the five upstream interfaces |
