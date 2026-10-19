# G9 scoping: running Python on the phone

Goal: the sample project runs on-device and prints its
expected output. This study was written before the build.

## Options considered

1. **Chaquopy (Gradle plugin + runtime AAR).** The standard
   answer, but this build is manual (aapt2 + javac + d8) and
   the sandbox JVM has no Maven access, so the plugin route
   is out. Hand-wiring its AARs is possible but means
   adopting a large opaque runtime whose Java API drives
   everything, and its licensing/attribution terms need a
   separate review.
2. **BeeWare Python-Android-support.** A full CPython built
   for embedding via JNI inside the app process. Needs a C
   launcher and their Gradle template conventions; in-process
   embedding also means a crashing script can take the IDE
   down with it.
3. **Cross-compile CPython with the NDK.** Most control,
   most work: NDK download plus a finicky cross build, for
   the same interpreter others already build well.
4. **A Python-in-JS engine in the WebView (Brython class).**
   Zero native risk, but a subset dialect: no real files,
   partial stdlib, imports over HTTP. Scripts that "run"
   there can still fail on a real interpreter. Rejected as
   the main route; it would teach wrong expectations.
5. **Bundled Termux CPython, executed as a child process.**
   Termux publishes a complete, current CPython (3.14.8 at
   build time) for aarch64 Android as .deb packages with a
   small dependency closure (17 packages, 10 MB). Run it the
   way the platform allows: the interpreter and libraries
   live in nativeLibraryDir, a separate process executes
   scripts, the IDE just captures stdio.

## Chosen design (option 5)

- `python-runtime/assemble.py` downloads nothing itself; it
  reads the extracted Termux tree in `rootfs/` (the 17 debs
  from packages.termux.dev, closure resolved from the repo's
  own Packages index) and stages:
  - `stage/lib/`: the interpreter renamed `libpythonbin.so`,
    the transitive DT_NEEDED closure (one file per SONAME),
    and all 75 `lib-dynload` extension modules under their
    literal names. **Every file name ends in `.so`** - the
    Android installer only extracts lib entries that do, so
    versioned SONAMEs (`libcrypto.so.3`) are renamed
    (`libcrypto.so`) and every NEEDED/SONAME string in the
    staged set is patched to match (rewrites confined to
    each file's `.dynstr`).
  - `stage/assets/pyhome.zip`: the pure-Python stdlib.
- On device, `IdeServer` unpacks the stdlib once to
  `filesDir/pyhome` and runs scripts with:
  `PYTHONHOME=filesDir/pyhome`, `PYTHONPATH=nativeLibraryDir`
  (this is how the import system finds the extension
  modules), `LD_LIBRARY_PATH=nativeLibraryDir`,
  `PYTHONDONTWRITEBYTECODE=1`, stdin closed (input() gets
  EOF instead of hanging), 20 s timeout, output capped like
  the desktop (32 KB stdout / 8 KB stderr). Response shape
  is byte-identical to the desktop `/api/run`.
- The same runtime upgrades `/api/check` and
  `/api/check-content` for Python from "skipped" to real.

## Size cost

APK 3.2 MB -> 14.5 MB (+11.3 MB: ~8.4 MB compressed runtime
libraries, 2.9 MB stdlib zip). Install size grows by the
unpacked stdlib (~13 MB) plus the extracted libraries
(~21 MB) - call it ~35 MB of storage for on-device Python.

## Known limits and risks

- **First real execution test is a physical phone.** The
  sandbox is x86; assembly correctness is verified here
  (dependency closure re-checked over the patched files,
  names, APK contents) but no ARM code runs until install.
  If process start fails on-device, `/api/run` answers with
  the OS error instead of pretending, and nothing else in
  the app is affected (separate process, lazy unpack).
- Scripts run as the app: same files access as the IDE
  itself, no network permission beyond the app's own.
- `ctypes` can load system libraries; acceptable for a
  developer tool running the user's own code.
- Curses/terminfo-dependent programs will not render (there
  is no terminal; that is G10's problem, not G9's).
- OpenSSL legacy providers/engines are not shipped; the
  default provider is built in, so `ssl`, `hashlib` and
  `https` via urllib work, but exotic engine setups won't.

## Fallback

If the exec route fails on real hardware, the fallback is
option 1 (hand-wire Chaquopy AARs) reusing the same
`/api/run` contract, or the standing kill criterion: the
phone stays a LAN thin client for run/test/debug and the
desktop keeps the runtime roles.
