# GhostIDE Android - build notes

v1.0.1 (Oct 19, 2026): BACKGROUND MODEL DOWNLOAD at Ryan's request
("the download to continue without me having to stay on the ghost
ide app"). Download moved out of the activity into DownloadService
(foreground service, dataSync type): persistent progress
notification with Cancel action, partial wakelock, resumable .part
(Range), exact-size gate, completion/error notifications that reopen
the app; MainActivity polls service statics when visible. New
permissions: FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC,
POST_NOTIFICATIONS (runtime-asked), WAKE_LOCK. APK 3,180,656 bytes,
md5 328db3dfd54109c3559894694d736a90, versionCode 2; SAME signer cert
as v1.0.0 (8ea8684a...) so it updates in place and any partial .part
download from v1.0.0 resumes in v1.0.1. Verified: badging,
manifest service element (exported=false, type dataSync), dex
contains the service, apksigner OK. Still NOT device-tested.

v1.0.0 (Oct 19, 2026), package com.ghostide.app, built with the house
manual recipe (build.sh: aapt2 + javac + d8 + zipalign + apksigner,
framework-only Java, no AndroidX/Gradle).

## What it is
The STANDALONE phone version of GhostIDE (Ryan redirected from a
WebView-to-PC wrapper: "I need a fully functional phone version").
Editor, files, chat, and autocomplete all run on the phone:
- Web IDE frontend (same bundle as desktop GhostIDE) in assets/www
- IdeServer.java: in-app HTTP server on 127.0.0.1:18763 implementing
  the desktop /api contract against app-private storage
- Engine: llama.cpp JNI bridge + arm64 libs REUSED from the MrGhosty
  on-device build (~/workspace/ghostbot/phone/native/libs/arm64-v8a,
  llama.cpp b11382). The Java bridge class MUST stay named
  com.ghostbot.app.LlamaBrain - the native symbols are bound to it.
- Model: Qwen2.5-Coder-1.5B-Instruct Q4_K_M, NOT in the APK. One-time
  in-app download (resumable, exact-size gated):
  986,048,800 bytes, SHA256
  f530705d447660a4336c329981af164b471b60b974b1d808d57e8ec9fe23b239
  (bartowski HF repo). Context 4096.

## Verified here
- APK 3,176,560 bytes, md5 009ad09a3f3729a39b581d3a6f3e0b2d; badging:
  com.ghostide.app v1.0.0, min SDK 24 / target 34, permissions exactly
  INTERNET + ACCESS_NETWORK_STATE, native-code arm64-v8a; all 6 libs
  and assets (www, help, playground) present; apksigner verifies
  (CN=GhostIDE Debug, cert SHA-256 8ea8684a...bec8bea).
- Keystore: ~/workspace/ghostide-apk/debug.keystore (alias/password
  "ghostide") - PRESERVE for updates.
- The phone model itself was downloaded and proven on the desktop
  llama.cpp here: chat produced a correct palindrome function
  (13.3 tok/s on 2 cores); /infill filled an is_palindrome stub and
  stopped exactly at the existing `return result` suffix line.

## NOT verified
- On-device execution: the sandbox cannot run arm64 code, so the
  first real on-phone generation test is Ryan's install (same status
  as MrGhosty v1.3). Java + native compile/link/packaging are proven;
  runtime behavior on his phone is not.
- Known soft spot: accepting a ghost-text suggestion needs a Tab key;
  most phone keyboards lack one (hardware keyboards work). A
  tap-to-accept affordance is a v1.1 candidate.

## v1.1.0 (Oct 19, 2026) - Phase 1
Gate 0 passed on Ryan's phone (background download worked, chat answer
~14 s and usable), so Phase 1 shipped the same night:
- 1.1 Tap-to-accept: ghost widget is tappable (mousedown handler,
  ignoreEvent false) + a green "Accept suggestion" chip in the status
  bar while a suggestion is showing.
- 1.2 Keyboard pass (partial): viewport interactive-widget=
  resizes-content, body height 100dvh, ghost text cursor:pointer.
- 1.3 Perf: infill max_tokens 64 -> 48 (settings-adjustable); native
  thread tuning deferred until more on-device data.
- 1.4 Import/export (native): menu Import file... (ACTION_OPEN_
  DOCUMENT, copies into workspace under its display name), Export
  file... (file list dialog -> ACTION_CREATE_DOCUMENT copy-out).
  NOTE: the manual toolchain cannot compile Java lambdas (javac vs
  LambdaMetafactory against android.jar) - use anonymous classes.
- 1.5 Rename/delete/new-folder: POST /api/file/rename, /api/file/
  delete, /api/dir on BOTH servers (desktop server.py tested live
  incl. escape + root-delete refusals); frontend per-file "..." menu;
  New button creates a folder when the name ends in /.
- 1.6 Settings: gear button in the IDE header - temperature slider,
  autocomplete on/off, infill token length; persisted in localStorage
  (ghostide-settings); model name/status shown from /api/health.
- 1.7 Autosave drafts: edits stash to localStorage (ghostide-draft:
  <path>) 3 s after typing stops; opening a file with a differing
  draft shows a Restore/Discard bar; Save clears the draft.
APK 3,184,752 bytes, md5 ca303491276e8d15715bf42d33b3bf7c,
versionCode 3, same signer (8ea8684a...) so it updates in place.
Desktop v0.1 gets the same frontend + endpoints (tested live).
NOT device-tested (no arm64 execution here): Ryan's install is the
first run of the new UI code paths.

## v1.2.0 (Oct 19, 2026) - Phase 2 (desktop-led)
Shared frontend gains: file TABS (per-file editor states, dirty dots,
close confirm), project find/replace panel, run pane, git panel,
workspace menu, chat context modes with token meter.
Android IdeServer: /api/search + /api/replace implemented natively;
GET /api/workspace returns "on-device"; run/git/context endpoints
return honest phone-build explanations the UI surfaces; format
returns formatted:false with reason. Desktop-only features light up
when the same UI runs against the desktop server (GhostSlice slices,
git, run, any-folder workspaces).
APK 3,188,846 bytes, md5 423d9532aab40c32b118aad776f832d1,
versionCode 4, same signer. Not device-tested.
Build gotcha: route() already declares q and query locals - new
locals there need distinct names (needle).

## v1.3.0 (Oct 19, 2026) - Phase 3
- 3.1 Model manager: Models.java variant table (1.5B + 3B), menu ->
  Models screen lists/downloads/switches/deletes. DownloadService
  parameterized by variant extra; downloads now SHA-256 VERIFIED
  after the exact-size gate (mismatch discards the file). 1.5B
  keeps its legacy .part name so old partials still resume.
  IdeServer modelName is now an instance field set from the active
  variant. Desktop got /api/model/info + /api/model/switch +
  Settings UI for the same job.
  3B facts (bartowski): 1,929,903,360 bytes, sha256 3da3afe6...460.
  The file was downloaded and hash-verified bit-exact here, and
  inference-proven on desktop llama.cpp (correct palindrome code,
  6.4 tok/s on the 2-core sandbox).
- 3.2 Behavior pack: web/src/behavior.js is the single source for
  system prompt + chat/infill/context defaults, both apps ship it.
  BEHAVIOR.md documents the native mirrors (CUT_MARKERS, Models).
- 3.3 LAN sync: desktop GET /api/sync/bundle + POST /api/sync/apply
  (escape-tested live); phone menu Sync pull/push with a PC address
  dialog (org.json payloads, text-extension allowlist, .. refused).
- 3.4 Chat history persists per workspace (localStorage keyed by
  workspace path; phone scope "on-device"), restored on return.
APK 3,197,038 bytes, md5 d92bdf0866c455a2488f24d1cc5c7df5,
versionCode 5, same signer. Not device-tested.

## v1.4.0 (Oct 19, 2026) - Tools Pack (Ryan: "load up this ide with tools")
Shared frontend: 🧰 Tools panel with AI quick actions (Explain /
Find+fix bugs / Write tests / Add docstrings - prompt templates over
the selection or file, through the normal chat), Preview (markdown
rendered, images via /api/raw, code as text), Check syntax, Duplicate
file, Snippets (localStorage, insert at cursor), TODO/FIXME scan
(click to jump), Project stats, Save slice as file, and a Terminal
pane (desktop shell in the workspace, history with up/down).
Desktop server: /api/terminal (30 s timeout, unsandboxed, stated in
the UI), /api/check (ast.parse / node --check / json), /api/todos,
/api/stats, /api/raw - all live-tested here.
Android: todos/stats/raw implemented natively; terminal and check
return plain-language desktop explanations.
APK 3,201,134 bytes, md5 0271e324b86082f4437a6b6003eb7d23,
versionCode 6, same signer. Not device-tested.

## v1.5.0 (Oct 19, 2026) - Phase 6 (roadmap items)
Shared frontend only - no new endpoints needed:
- SPLIT VIEW (the deferred Phase 2.5 item): second editor pane,
  plain editor (autocomplete stays in the main pane); edits sync
  back into the tab store on save/close; undo history resets when a
  file changes panes (accepted trade, documented in code + plan).
- Quick Open palette (Ctrl+K), Outline (functions/classes, tap to
  jump), Go to line.
- Chat: Stop button while streaming (AbortController), Clear chat.
- Settings: word wrap (Compartment reconfigure, both panes),
  editor text size (CSS var), Short answers mode (512-token cap,
  the battery/heat knob), autocomplete pause setting.
- Status bar shows doc lines/chars; files over 200 KB warn before
  opening (the plan's big-file guard); file menu gains Copy path,
  Download file, Open in split pane.
APK 3,205,231 bytes, md5 f850b47a8191f7db6310542a8e88e529,
versionCode 7, same signer. Not device-tested.

## v1.6.0 (Oct 19, 2026) - Phase 7 (editor + app)
Editor (shared frontend): Find in file (CodeMirror search panel),
Fold/Unfold all, text transforms (sort lines, dedupe lines, trim
trailing spaces, toggle case on selection), Quick Open shows
recent files first (🕘), git log in the Git panel, workspace .zip
download (desktop), chat Regenerate + Export chat as .md file.
Desktop server: GET /api/git/log, GET /api/export-zip (dot paths
skipped, 50 MB cap) - live-tested.
App (native): Share file - menu entry + file chooser sending via
GhostShareProvider, a framework ContentProvider (exported=false,
grantUriPermissions) with canonical-path containment, read-only.
Zip tool on the phone explains Export/Share instead.
APK 3,205,233 bytes, md5 0a291408f73be565ac66178375bdd6de,
versionCode 8, same signer. Not device-tested - the share provider
is the newest native surface; first real share is Ryan's test.

## v1.6.1 HOTFIX (Oct 19, 2026) - the dead-IDE bug
Ryan reported v1.6.0 (and v1.5.0): app opens to the IDE but no
files load and no taps work. ROOT CAUSE (frontend): Phase 6 put
`const wrapComp = new Compartment()` AFTER `new EditorView(...)`,
but baseExtensions() runs during view creation and calls
wrapComp.of(...) - temporal dead zone, the bundle died at startup
before any wiring or boot code. Total, silent death on every
device; invisible to parse checks and HTTP smoke tests because
nothing here executes the JS.
FIX: wrapComp/wrapExt moved above view creation. Proven with a
node DOM-stub evaluation harness: old bundle dies inside
baseExtensions (".of" of undefined); fixed bundle sails past it
(stops only on a stub artifact, document.defaultView).
Also shipped: startup error banner + boot watchdog in index.html
(any future startup crash prints its message in a red bar), and
native stuck-model self-healing (loadMarker pref + automatic
fallback to the 1.5B if a load never completes).
APK 3,209,328 bytes, md5 9755932372ff5fd72b2ebcae06179539,
versionCode 9, same signer.

## v1.6.2 HOTFIX (Oct 19, 2026) - the dead dialogs
Ryan: "+ New / folder does nothing." ROOT CAUSE (native):
MainActivity set a WebViewClient but NO WebChromeClient, so
Android silently swallowed the page's alert()/confirm()/
prompt() (confirm -> false, prompt -> null). Every dialog-driven
flow was dead on the phone: new file/folder (prompt), rename,
delete (confirm), go-to-line, duplicate, replace-all confirm,
clear chat, big-file open confirm, workspace prompt - and every
error alert was invisible too. Desktop browsers show these
natively, which is why endpoint tests never caught it; Gate 0's
proven flows (download, chat) use no dialogs.
FIX: WebChromeClient in ensureWebView bridging onJsAlert /
onJsConfirm / onJsPrompt to native AlertDialogs (EditText for
prompt). Verified present in the dex. Frontend unchanged.
APK 3,209,328 bytes, md5 ca2e3cdd5fd30b1a819f9e35eed5bf3f,
versionCode 10, same signer.
