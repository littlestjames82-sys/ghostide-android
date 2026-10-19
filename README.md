# GhostIDE for Android 👻

The fully on-device edition of GhostIDE: a real AI code editor that
runs entirely on the phone. The editor, the file workspace, an
in-app HTTP server, and a llama.cpp engine (JNI) all live in the
app; the model (Qwen2.5-Coder 1.5B or 3B, GGUF) downloads once into
app-private storage and then works offline forever.

**Your code never leaves the device. The AI lives here.**

- Package: `com.ghostide.app` · min SDK 24 · target SDK 34 · arm64 only
- The web IDE is the SAME frontend bundle as the desktop `ghost-ide`
  repo (one behavior pack, `BEHAVIOR.md` there, so the apps cannot
  drift). Desktop-only features (git, run, GhostSlice slices) explain
  themselves in plain words on the phone; tabs, find/replace,
  drafts, import/export, and LAN sync work natively.
- Model manager (menu -> Models): download / switch / delete the
  1.5B (940 MB, ~1.5 GB RAM) or the 3B (1.93 GB, ~2.6 GB RAM, best on
  6 GB+ phones). Downloads run in a foreground service (background,
  resumable, notification progress) and are exact-size + SHA-256
  verified (hashes in `Models.java`, checked against Hugging Face).

## Build

Manual recipe (no Gradle): `build.sh` runs aapt2 + javac + d8 +
zipalign + apksigner from the Android SDK build-tools. See
`BUILD_NOTES.md` for the full story, including why the Java JNI
bridge class must stay named `com.ghostbot.app.LlamaBrain`.

Prerequisites not in this repo:
- Android SDK + JDK 17
- The prebuilt arm64 llama.cpp libraries (`libllama`, `libggml*`,
  `libghost_llama`, `libomp`), expected at
  `~/workspace/ghostbot/phone/native/libs/arm64-v8a/` - they come
  from the studio's on-device llama.cpp build (llama.cpp b11382).
- A signing keystore (the build script points at the studio's local
  one; use your own for your own builds - never commit a keystore).

## Layout
- `app/src/main/java/com/ghostide/app/` - MainActivity (screens,
  menus, import/export, LAN sync), DownloadService (background model
  downloads), IdeServer (the in-app /api server), Models (the model
  matrix), Json (tiny JSON helper)
- `app/src/main/java/com/ghostbot/app/LlamaBrain.java` - JNI bridge
  (class name is load-bearing for the native symbols)
- `app/src/main/assets/www/` - the bundled web IDE
- `app/src/main/assets/help.html` - in-app help
