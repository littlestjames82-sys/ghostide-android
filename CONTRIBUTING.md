# Contributing to GhostIDE

GhostIDE is built by Ghost Developer Studio (a solo
studio). Issues and discussion are welcome; patches
are reviewed against the same gates the studio's own
builds must pass.

## Before you start

- Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
  for how the app fits together and
  [docs/TESTING.md](docs/TESTING.md) for the gates.
- The web frontend is shared with the desktop edition
  and lives in the
  [ghost-ide](https://github.com/littlestjames82-sys/ghost-ide)
  repo — frontend changes are usually made there, so
  both editions move together.
- The Android build has **no Gradle**: see
  [BUILDING.md](BUILDING.md) for the manual recipe
  and its prerequisites (some binary inputs are not
  redistributed in this repo).

## Ground rules the codebase lives by

- **No silent drift between editions.** A server
  behavior exists on both servers or the difference
  is deliberate, documented, and stated in the UI.
- **Pins before processes.** Any value a client sends
  that reaches git, a shell, or the filesystem is
  validated or rebuilt server-side first.
- **Safe rendering by default.** New UI renders user
  data with text-safe APIs; the built bundle's
  HTML-sink count is census-pinned.
- **Honest refusals.** Where a build cannot do
  something, it says so in plain words (see the
  desktop-only test runner) instead of faking it.
- **Proof in layers.** State which layer proved your
  change: compile + harness pins, desktop-live
  fixture probes, or on-device. Do not claim a layer
  you did not run.

## Pull requests

- Keep the five version sources in agreement if you
  touch versions at all (`check_versions.py`).
- If you add or remove a route, a UI surface, or an
  agent verb, expect the census and pin harnesses in
  the desktop repo to need matching updates — that is
  the system working as designed.
- Describe what you verified, with the commands or
  fixtures you used.

## Reporting bugs

Use the bug report template. Device model, Android
version, GhostIDE version (Settings → About), and the
smallest reproduction you can manage help most.
Screenshots of the real app are gold — the repo has
no stock screenshots, on purpose.
