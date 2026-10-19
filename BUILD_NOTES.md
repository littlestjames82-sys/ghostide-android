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

## v1.7.0 (Oct 19, 2026) - Phase 8: VS Code / Cursor round
Shared frontend + desktop endpoints, no native changes (native
carries the v1.6.1 self-healing + v1.6.2 dialog bridge).
- Command palette (Ctrl+Shift+P / Tools): 27 commands over every
  existing action.
- VS Code keymap pack in both panes: Mod-/ comment toggle,
  Alt+arrows move line, Shift+Alt+Down copy line, Mod+Shift+K
  delete line, Mod+D select next occurrence.
- Autosave (off / after 2 s pause) + trim-trailing-spaces-on-save
  settings.
- Cursor-style inline AI edit: instruction bar over the selection
  or whole file -> review panel -> Apply/Discard. One-shot replies
  via a collectChat helper (also powers the commit writer).
- Problems panel: /api/check now returns line + message (line =
  LAST traceback match; verified line 4 on a planted error);
  tap a problem to jump.
- Status bar TODO chip (both builds).
- AI commit message (desktop): /api/git/diff-all -> one-line
  suggestion into the commit box; button disabled off-repo.
Gates passed: esbuild + parse, 137 element IDs, DOM-stub
evaluation, desktop endpoints live-tested.
APK 3,213,424 bytes, md5 aff18926fb373879273e811c45091977,
versionCode 11, same signer. Not device-tested.

## v1.8.0 (Oct 19, 2026) - Phase 9 round + a latent fix
BUG FIXED (latent since Phase 6): openQuick treated /api/files
entries as strings, but both servers return {path, type} objects;
the .endsWith call threw inside a try and silently emptied the
list - Quick Open, the split picker and palette file commands
showed "No files match" on both builds. Now maps type==="file"
entries to paths.
Phase 9 (frontend only): collapsible folder tree (persisted),
Go to Symbol (Ctrl+Shift+O, shares extractSymbols with Outline),
chat @file mentions (button + send-time attach, 2 files max,
plus auto-attach of any selected code), Focus/Zen mode (⛶ hides
both side panels), indent size (2/4) + show-whitespace settings
(new Compartments declared beside wrapComp, above the view),
Bookmarks (per-file lines, localStorage), Fix-with-AI inside
Problems, quiet problems chip after save (desktop checkers).
Gates: parse, 144 IDs, DOM-stub evaluation PASS (harness now
durable at ghost-ide/tools/). APK 3,213,424 bytes, md5 02c43111,
versionCode 12, same signer. Not device-tested.

## v1.9.0 (Oct 19, 2026) - Phase 10 round
Frontend + one endpoint on both servers (POST /api/rename-symbol,
word-boundary identifier rename; desktop live-verified: renamed
3 occurrences of `total`, left `totalCount` untouched, refused
non-identifiers; Java mirror compiled in).
New: breadcrumbs (path + enclosing function, live on selection);
explorer filter input; session restore (open tabs + current file
persist in localStorage and reopen on launch, missing paths
skipped, big-file prompt suppressed for restores); reopen closed
tab (palette); Rename symbol UI (dirty-buffer guard, buffer
reload after); Go to definition (heuristic search over
def/class/function lines, list on multiple hits); Copy contents
in the file menu; problems check also runs on file open;
Write-code-from-comment AI action (reuses the review/apply
panel, inserts under the comment).
Gates: parse, 150 IDs, DOM-stub evaluation PASS.
APK 3,217,520 bytes, md5 178cab89375f51eb76c071ae166d0238,
versionCode 13, same signer. Not device-tested.

## v1.10.0 (Oct 19, 2026) - Phase 11 round
Local History (Timeline) on BOTH servers: every save snapshots the
previous on-disk content to .ghostide-history/<rel>/<name>.<ts>.snap
(newest 20 kept; dot folder - verified invisible to file lists,
search, replace, todos, stats on both builds). GET /api/history +
/api/history/file on both; UI loads a snapshot into the buffer
(nothing written until Save - and that save snapshots the
pre-restore version too, so restores are self-undoing).
Desktop: /api/check-all (project Problems, UI lists + jumps);
git blame for the cursor line in the breadcrumbs bar (debounced,
line-change only); per-file Revert in the Git panel
(/api/git/checkout HEAD); branch chip in the status bar.
Snippets: bodies containing ${1:...} insert as real CM tabstop
snippets; plain bodies unchanged.
All four new desktop endpoints live-verified (history roundtrip,
check-all caught a planted syntax error, blame, revert restored
HEAD content). Gates: parse, 154 IDs, DOM-stub PASS. One javac
fix during build (loop var collision in route()).
APK 3,217,518 bytes, md5 39725cf99e908f48ba15a026b3618838,
versionCode 14, same signer. Not device-tested.

## v1.11.0 (Oct 19, 2026) - Roadmap G1: workspace symbol index
Two-pass symbol index on BOTH servers (defs pass: py def/class/
module values, JS function/class/const-let-var; refs pass:
word-boundary occurrences of defined names), cached against a
(path, size, mtime) fingerprint so any save rebuilds lazily.
Endpoints: GET /api/symbols, /api/symbols/search, /api/references
(300 cap + total). Frontend: Go to definition answers from the
index first (heuristic kept as fallback), new Find All
References panel, Go to Symbol is workspace-wide.
Verified: fixture correctness by hand (defs, kinds, refs counts
exact); 500-file workspace cold build 1.18 s, cached 43 ms,
refs for a shared helper exactly 550. Gates: parse, 155 IDs,
DOM-stub PASS. APK md5 e2dad61c114e1ec1d45b6a8b942bd9c6,
versionCode 15, same signer. Not device-tested.

## v1.12.0 (Oct 19, 2026) - Roadmap G2: repo map
GET /api/repomap on both servers: the G1 index rendered as a
ranked map (files by total refs, symbols by ref count, whole
files dropped past the char budget). Frontend: Tools -> Repo
map viewer with one-tap "attach to next message", plus a
Settings toggle to ride along with every chat. Verified: map
content/ranking/truncation on fixtures; map-only Q&A proof -
the desktop 7B answered which file/class owns greetings and
which function creates them from the map alone, no file
contents. Gates passed. APK 3221617 bytes md5 dc0b2230f8388c0d590b826d6b35151f,
versionCode 16, same signer. NOTE: the remote-storage upload
service has had an orphaned write stuck since the v1.11.0
upload (every attempt: "equivalent connector write already
running"); both v1.11.0 and v1.12.0 links are pending that
clearing. Repos pushed for both versions.

## v1.13.0 (Oct 19, 2026) - Roadmap G3: refactoring engine
POST /api/refactor on both servers, always preview-first:
compute -> unified diff -> Apply writes through the save path
(so Local History snapshots make every refactor undoable).
Ops: extract-function (desktop, AST: infers params + returns),
extract-variable (both; function-scoped on desktop, file-wide
on phone), inline (both, index-driven, single-assignment
guard), move with import fixups (both; py module paths, JS
relative specifiers). Desktop-verified: all four ops applied
to fixtures and the programs produced IDENTICAL output
(64.17 / 107.0 / "thing works") before and after. Phone
extract-function returns an honest desktop-only message.
Gates passed. APK 3229806 bytes md5 3cef7368d4c5fc6bec06b5ad4fda542f,
versionCode 17, same signer. Upload link still pending (see
v1.12.0 note - the service itself is now timing out too).

## v1.14.0 (Oct 19, 2026) - Roadmap G4: GhostSlice v2 smart context
New chat context mode "smart" on BOTH builds (the phone's is a
native port - slices were desktop-only before): seed = current
file, then its imports, its tests, files that import it, and
files defining symbols it uses, packed into the budget with a
reason per file and a cut list. Benchmark on a 4-file shop
fixture, same model + budget: current-file context scored 0/3
on dependency questions; smart scored 3/3 substantively (the
strict keyword scorer said 2/3 - one answer said "7%" instead
of "0.07"). Gates passed. APK 3229807 bytes md5
4f12745c7083c7cb1d7906a15c8c878f, versionCode 18, same signer.
Upload links still pending the stuck upload service.

## v1.15.0 (Oct 19, 2026) - Roadmap G5: agent mode
Chat gains an Agent mode toggle: a 12-step loop over the chat
endpoint with a strict one-action protocol (READ / WRITE /
CHECK / DONE / ASK). Writes stage in memory only; a review
panel offers per-file approve (with diff view) or reject-all;
Stop mid-run discards everything - the workspace is never
touched until approval. New endpoints: /api/check-content
(desktop checks staged code; phone returns ok:null = skipped)
and /api/diff-text on both builds.
Verified: action parser 7/7 unit cases; FULL live run with
the desktop 7B on the shop fixture - READ-probe, WRITE new
file, READ, WRITE edit, CHECK (both OK), DONE in 6 steps;
applied result computed exactly right (200 cart -> tax ->
10% off -> "charged 192.60"). Gates passed. APK 3233905 bytes
md5 7cc2db79fd1c4dca80c537adcea161c9, versionCode 19, same
signer. Upload links still pending the stuck upload service.

## v1.16.0 (Oct 19, 2026) - Roadmap G6: test runner panel
Desktop: GET /api/tests/discover (test_*.py, *_test.py,
*.test.js with per-test def lines) and POST /api/tests/run
(one file or all). Python runs through a stdlib mini-runner
(plain test_* functions + unittest classes, JSON per test -
no pytest dependency); Node uses node --test with a parser
handling both TAP and the spec reporter (incl. harvesting
fail lines from its failing-tests section). Panel lists
per-test pass/fail; tapping a failure jumps to its line.
Optional run-related-tests-on-save with a failing chip in
the status bar. Phone: honest stub (no runtime there yet).
Verified on a fixture suite with planted failures: reported
EXACTLY those - py 3 pass/2 fail with assert lines and
messages, node 1/1 with the right line. Gates passed.
APK 3233902 bytes md5 72e2f7469011cd607ce4fac926473e46, versionCode 20, same signer.
Upload links still pending the stuck upload service.

## v1.17.0 (Oct 19, 2026) - Roadmap G7: debugger (desktop)
Breakpoints via a clickable editor gutter (persisted per
file), a pdb/bdb bridge process (debug_bridge.py, JSON-lines
protocol) owned by the desktop server per session, and a
debug panel: continue / step over / step into / step out,
live variables pane, captured program output. Phone shows
an honest desktop-only message.
Verified by a scripted session that finds a planted
double-count bug BY STEPPING: breakpoint in a loop, variables
prove total=20 after one 10-price; continue-through-exit
returns the program's stdout. Gates passed. APK 3238001 bytes
md5 f9c550457b3316932eddeccdaf8a04c8, versionCode 21, same signer. Upload links still
pending the stuck upload service.

## v1.18.0 (Oct 19, 2026) - Roadmap G8: self-fixing loop
"Fix my tests (AI)": runs the suite, hands failures to the
agent (G5 protocol), whose writes apply immediately - each
backed up in memory and snapshotted by Local History. Tests
re-run after every round (max 3). A round that increases
failures is reverted on the spot. The attempt log ends in a
report panel with an Undo-all button.
Verified live with the desktop 7B on a fixture whose
percent_off subtracted dollars instead of percent: the
agent read the test, read the source, fixed it in ONE round
(1 failing -> 0), suite green with no human edits. Gates
passed. APK 3237998 bytes md5 2fbcf399bffc216da2441fde8ee07f6e, versionCode 22, same
signer. Upload links still pending the stuck service.

## v1.19.0 (Oct 19, 2026) - Roadmap G9: Python runs on-device
Bundles a Termux CPython 3.14.8 runtime: interpreter +
library closure + all 75 extension modules flattened into
nativeLibraryDir (SONAMEs renamed to end in .so with
.dynstr patching - see python-runtime/G9_SCOPING.md and
assemble.py), stdlib as an asset unpacked to filesDir.
/api/run now executes .py files on the phone (same response
shape as desktop, 20s timeout, stdin EOF); /api/check and
/api/check-content give REAL Python syntax results on-device
instead of ok:null. APK 14470364 bytes md5 ba2d9cc2aed9b2020b1d62f6a78753c4, versionCode 23,
same signer. Sandbox cannot run ARM: assembly fully verified
(closure over patched ELFs, APK contents), first execution
test is Ryan's phone; failures surface as plain errors.
Upload links still pending the stuck upload service.

## v1.20.0 (Oct 19, 2026) - Roadmap G10: terminal sessions + REPL
Desktop terminal is now a persistent shell session: cwd,
environment and variables survive across commands (sentinel
protocol carries each command's exit code + cwd; a 30s
timeout restarts the session rather than desyncing it;
switching workspace resets it). New Python REPL pane on
both builds: a persistent python3 -u -i session - on the
phone it runs on the G9 bundled interpreter, so variables
and imports persist between lines there too. Session reset
button in the pane header.
Verified: desktop session transcript (cd + var persistence,
rc tracking, reset), REPL transcript (state across calls,
multi-line block executing on the blank line) - both live
against the real endpoints. Phone REPL is Java-mirrored and
rides on G9's device proof. APK 14470365 bytes md5 67192dd36846c9749525db533bee321c,
versionCode 24, same signer. Upload links still pending.
