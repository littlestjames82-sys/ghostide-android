# GhostIDE for Android 👻

The fully on-device edition of GhostIDE: a real AI code
editor that runs entirely on the phone. The editor, the
workspace, an in-app HTTP server, a llama.cpp engine
(JNI), a bundled Python interpreter, and a bundled git
all live in the app. Models download once into
app-private storage and then work offline forever.

**Your code never leaves the device. The AI lives here.**

- Package: `com.ghostide.app` · min SDK 24 · target SDK 34
  · arm64 only
- The web IDE is the SAME frontend bundle as the desktop
  `ghost-ide` repo (one behavior pack, `BEHAVIOR.md`
  there, so the two apps cannot drift). Things only a
  desktop can do explain themselves in plain words on
  the phone.

## What the phone build does

- Full editor: tabs, split view, find/replace, local
  history, drafts with recovery, import/export/share.
- On-device AI: chat with context modes (file, smart
  slice, project slice), agent mode with staged writes
  and diff review, autocomplete, inline edits.
- Model store (native Models screen): 0.5B Sprinter,
  1.5B Balanced, 3B Smarter, 7B Max. Downloads run in a
  foreground service (background, resumable, with
  progress) and are exact-size + SHA-256 verified;
  delete any model to reclaim its space.
- Run Python on the phone (bundled CPython 3.14 from
  Termux packages), with a persistent REPL and real
  syntax checking.
- Full git on the phone (bundled git 2.56): init,
  status, diff, stage, commit, branches, blame, log.
- Voice coding: system speech recognition into chat or
  the editor, plus spoken commands ("save file",
  "run it", "Ask Ghost ...").
- Project templates, sandboxed plugins, pair mode
  (join a desktop workspace from the Models screen,
  with conflict-safe sync), and optional encrypted
  backups in the same format as the desktop build.

## Build

Manual recipe (no Gradle): `build.sh` runs aapt2 +
javac + d8 + zipalign + apksigner from the Android SDK
build-tools, signing with the preserved debug keystore
so every build installs as an in-place update. See
`BUILD_NOTES.md` for the full story, including why the
JNI bridge class must stay named
`com.ghostbot.app.LlamaBrain`, how the Python and git
runtimes are bundled (`python-runtime/`), and the
per-version changelog.

Release builds (Play): `build-release.sh` produces a
signed `.aab` with the production upload key plus a
universal tester APK generated from the bundle. The
keystore and its password live outside this repo; see
`store/LISTING.md` for the launch checklist and gates.

Prerequisites not in this repo: the Android SDK
build-tools, a JDK 17, the llama `.so` libraries, and
bundletool for release builds.

MIT licensed. Built by Ghost Developer Studio, Oakdale,
Tennessee.
