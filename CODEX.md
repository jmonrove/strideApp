# CODEX.md

Guidance for OpenAI Codex when working in this repo.

**Read [`CLAUDE.md`](CLAUDE.md) first: it is the canonical agent contract for ALL agents**
(safety and hardware rules, git rules, environment). If anything here conflicts with CLAUDE.md,
CLAUDE.md wins. Find the knowledge docs (architecture, coding standards, runbooks, module READMEs)
through [`docs/README.md`](docs/README.md).

## Codex-specific notes

### Tooling defaults
- Prefer `rg` for search and `rg --files` for file discovery. Add `-g '!**/build/**' -g
  '!**/.gradle/**' -g '!**/node_modules/**'`, because the ignored build trees contain generated
  copies of the READMEs.
- Use `apply_patch` for small, focused edits.
- Each `console/<module>` is its own Gradle root: `cd` into it before `./gradlew`.

### Sandbox and approvals
- The first Gradle or `npm ci` run downloads dependencies. If the network is restricted, ask
  before fetching anything.
- Reaching the console (`adb connect`, installs, connected tests) or the watch (USB) is a hardware
  action under rule 3 of CLAUDE.md. A sandbox denial is never a reason to try a different device or
  serial.

### Response style & editing hygiene
- Keep summaries concise and reference paths in backticks.
- Say which level of evidence you reached: built, unit-tested, emulator, or device (CLAUDE.md
  rule 4).
- Keep changes minimal, avoid reformatting unrelated code, and default to ASCII unless the file
  already uses Unicode.
