# GhostIDE Android — Architecture

How the app is put together, and why. Everything here
describes the current release (3.10.0).

## One app, four layers

```
┌────────────────────────────────────────────────────┐
│ WebView frontend (assets/www)                      │
│ The SAME bundle as the desktop edition: editor,    │
│ chat, agent UI, git panel, panels for every tool.  │
├────────────────────────────────────────────────────┤
│ IdeServer (Java, loopback HTTP on 127.0.0.1)       │
│ 130+ routes: files, git, search, eco tools, MCP,   │
│ verify, backups, pairing, health.                  │
├──────────────┬───────────────┬─────────────────────┤
│ llama.cpp    │ CPython 3.14  │ bundled git 2.56    │
│ via JNI      │ runtime       │ runtime             │
│ (LlamaBrain) │               │                     │
├──────────────┴───────────────┴─────────────────────┤
│ Ecosystem bundle (eco.zip, generation e13)         │
│ 29 Ghost tools, extracted to filesDir on first run │
└────────────────────────────────────────────────────┘
```

## The frontend is shared, not ported

The editor UI is a single CodeMirror 6 bundle built from
the desktop repo's `web/src` and shipped in both editions.
A behavior pack (`BEHAVIOR.md` in the desktop repo) records
the contract between the UI and whichever server is behind
it. Consequences:

- A feature lands in both editions in the same cycle, or
  the phone build explains — in the UI itself — why it is
  desktop-only (the test-runner panel is the standing
  example).
- The two servers are separate implementations of one API:
  Python (`server.py`, desktop) and Java (`IdeServer.java`,
  phone). Route parity is enforced by a census harness
  (144 desktop / 134 Java route literals at 3.10.0; the
  delta is desktop-only surface the phone refuses
  explicitly rather than omitting silently).

## The Java server

`IdeServer.java` plus focused companions:

- **GitSupport** — every git invocation goes through one
  argument builder. Routes never interpolate client input
  into a command line; names and refs are validated or
  rebuilt server-side (see SECURITY.md).
- **PairHub** — desktop pairing: token issue/expiry,
  viewer vs editor roles, conflict-safe sync.
- **BackupService / BackupCrypto / ZipGuard** — staged
  backup restore with validation and rollback;
  AES-256-GCM with PBKDF2-HMAC-SHA256; archive traversal
  and duplicate-entry defense.
- **DownloadService / Models** — the model store: a
  foreground service downloads GGUF models resumably into
  app-private storage, verified by exact size and SHA-256.
- **PathCheck** — workspace containment for file paths.

## Inference

`LlamaBrain.java` (package `com.ghostbot.app` — the name is
load-bearing for the JNI bridge and must not be renamed)
wraps llama.cpp. Models are Qwen2.5-Coder GGUF builds at
0.5B / 1.5B / 3B / 7B. Inference is fully on-device; the
IDE works in airplane mode once a model is present.

## The ecosystem bundle

The 29 Ghost tools are pure-Python (stdlib-first) packages.
The desktop build tool (`eco_bundle_gate.py` in the
desktop repo) runs **every tool's own test suite**, then
`build.sh` zips the passing trees into the APK with a
generation stamp (`e13`). On first run the app extracts
the bundle to `filesDir/eco` and executes the real tools
against the workspace via the bundled CPython — the same
code paths, and the same outputs, as the tools' own CLIs.
If a tool's tests fail, the build fails: the bundle cannot
drift ahead of its proofs.

## Pair mode

A phone can join a desktop workspace (or a desktop can
serve a phone): the joining side presents a pairing token;
tokens expire after 12 hours; viewers are read-only
(POST is refused except heartbeat). Sync is conflict-safe:
diverged files surface as conflicts, never silent
overwrites.

## State on the device

- Workspace files: user-picked directory.
- App-private: models, the extracted eco bundle, pairing
  state, approve-queue data (`.ghostide/` inside the
  workspace), replay trails (`.ghostide/replay/`).
- Editor state (open tabs, cursor positions, drafts,
  settings) lives in the WebView's local storage and is
  versioned with the session format.

## Build shape

No Gradle. `build.sh` drives the SDK build-tools directly
(aapt2 → javac → d8 → zipalign → apksigner) in framework-
only Java — no AndroidX, no lambdas in server code, so the
toolchain stays small and the build stays reproducible
from a clean checkout plus the documented prerequisites.
