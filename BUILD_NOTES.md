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

## v1.21.0 (Oct 19, 2026) - Roadmap G11: full git on phone
The phone's /api/git/* stubs are gone. A bundled Termux git
2.56 (assemble_git.py, same pattern as G9's Python) backs
status, diff, log, diff-all, blame, init, stage, checkout
and commit with response shapes identical to desktop.
GIT_EXEC_PATH is a symlink farm giving the multicall
binaries their dashed names; commit falls back to a
GhostIDE identity only when the repo has none configured.
The git panel gains an Init button (endpoint added to BOTH
builds - desktop never had one). Remote helpers are staged
too (libgitremotehttp.so), so clone/fetch/push over https
have their machinery, subject to on-device proof.
Verified: assembler closure over the union lib set, APK
contents (all 109 libs + templates), desktop init endpoint
live. On-device git proof rides with G9's. APK 19202053 bytes
md5 e4a020716f836ff3eae47d7bfed95b26, versionCode 25, same signer.

## v1.22.0 (Oct 19, 2026) - Roadmap G12: model store
The model manager becomes a store of four curated
Qwen2.5-Coder Q4_K_M builds: 0.5B Sprinter (380 MB, ~0.7 GB
RAM), 1.5B Balanced (default), 3B Smarter, 7B Max (4.7 GB,
~5.5 GB RAM - phones with 8 GB+, the desktop-class model).
New entries carry exact sizes + SHA-256 from the HF LFS
metadata (bartowski repos), enforced by the existing
verified downloader; the screen now totals the storage the
downloaded models use, and Delete reclaims it (that flow
already existed per variant). APK 19202051 bytes md5 089f90c2fe1d895df3180fb5aec4415d,
versionCode 26, same signer. Downloading a second store
model on-device is part of Ryan's device pass.

## v1.23.0 (Oct 19, 2026) - Roadmap G13: voice coding
Mic buttons in chat and the editor header. On the phone
they open the system speech recognizer through a
GhostVoice JavascriptInterface (RECORD_AUDIO requested at
tap time; the app records nothing itself) and the
transcript lands in the chat box or at the editor cursor.
Spoken commands: "save file", "run it", "new file", and
"Ask Ghost ..." which sends straight to chat. Desktop has
no bridge, so its buttons show a polite phone-feature note,
as the roadmap specifies. Verified: gates + javac, dex and
manifest carry the bridge and permission, voice-command
patterns unit-checked. Speaking proof is Ryan's phone.
Arc C (phone power) complete pending device proofs.
APK 19202053 bytes md5 85cd7e61d7bc2b5d84f0a139271c8bb6, versionCode 27, same signer.

## v1.24.0 (Oct 19, 2026) - Roadmap G14: project templates
Tools -> "New project from template": six starters defined
once in the frontend and created via /api/file, so they
work identically on phone and desktop - Python CLI, Python
library + tests, static site, Node CLI, GGUF tool script,
blank. Name substitution (project/package/year), an
existing-folder guard, the main file opens ready to edit,
and a starter prompt is placed in the chat box (not sent).
Verified on desktop by creating all six through the real
API: the Python CLI runs, the library's suite passes 2/2
in the test runner, the Node CLI runs, the GGUF tool
passes its syntax check, substitution leaves no
placeholders. APK 19206150 bytes md5 05d71b0abeb88f836311e2d1c4b81866, versionCode 28,
same signer. Upload links still pending.

## v1.25.0 (Oct 19, 2026) - Roadmap G15: plugin system
Plugins are folders in the workspace plugins/<id>/ with a
plugin.json manifest + plugin.js. Scripts run in sandboxed
iframes (opaque origin) and reach the IDE only through the
ghost postMessage API: files list/read/write, editor
current/selection/insert/open, ask-Ghost, panel UI +
notify. Actions surface in Tools and the command palette;
the manager (Tools -> Plugins) enables/disables, removes
(folder delete on both builds), installs the two samples
(Word Count Pro, JSON Toolbox) and rescans. One design
catch: the file listing hides dotfolders on both builds,
so the plugin folder is the visible plugins/ - which also
makes plugins editable in the IDE itself. Verified on
desktop: discovery, exact word counts, JSON format/minify
written through the API, error paths, surgical removal.
Phone run joins Ryan's device pass. APK 19210246 bytes
md5 da9cdc442a5cf4997eab7aaccb2d6c0a, versionCode 29, same signer.

## v1.26.0 (Oct 19, 2026) - Roadmap G16: pair mode
Two devices, one workspace: everyone opens the SAME
server (desktop in LAN mode; the phone joins from its
Models screen via "Pair with a desktop", host remembered).
Both servers gain /api/pair/heartbeat + /api/pair/peers
(25 s presence expiry). The web UI heartbeats name + open
file + dirty flag, shows peers in a header chip and the
Pair panel (tap a peer's file to open it), auto-reloads
clean editors when a peer changes the open file, and
guards every save with an FNV-1a base/mine/server
decision: a conflicting save is diverted to a
<file>.conflict-<device> copy - the server copy is never
silently overwritten - and resolves from the panel (keep
mine / use server / view the copy). Proven on desktop
with a two-client simulation against the live server:
decision table 5/5, presence, no-clobber conflict, clean
reload. Two-device session is Ryan's proof. APK 19214341
bytes md5 12b9b30f376d3d5e45f805d2ddf02ff7, versionCode 30, same signer.

## v1.27.0 (Oct 19, 2026) - Roadmap G17: encrypted backup
Optional and off by default. Tools -> Encrypted backup:
create zips the workspace (same file set as zip export;
the backups/ folder and .ghostbak files are excluded)
and encrypts it with a passphrase the app never stores:
PBKDF2-HMAC-SHA256 (200k rounds) derives an encryption
key + MAC key, the zip is XORed with an HMAC-SHA256
keystream under a random nonce, and the MAC covers
salt || nonce || ciphertext (encrypt-then-MAC), so a
wrong passphrase fails integrity before decrypting. One
format on both builds - server.py functions and
BackupCrypto.java mirror each other - proven compatible
BOTH directions: the real Java class on a desktop JVM
decrypted the Python-made backup (content hash matched),
and the Python server restored the Java-made backup
byte-exact. Desktop round trip wiped + restored
byte-identical (recursive SHA-256), tamper rejected.
The .ghostbak lands in backups/ for Ryan to move to
Drive/WebDAV/USB. APK 19218437 bytes md5 081e6d507c124db552e568a1f9dcd7f2,
versionCode 31, same signer. Arc D complete pending
device proofs for G15-G17.

## v1.28.0 (Oct 19, 2026) - Roadmap G18: desktop apps
Desktop packaging lands. server.py gains APP_VERSION
1.28.0, frozen-app support (bundled web/playground data,
~/GhostIDE seeded workspace, llama resolved from a
bundled llama/ folder), a verified in-app 7B download
(Settings; resume + exact-size + SHA-256 gates, catalog
values checked against the real GGUF on disk), an
update check that only reports (VERSION.json on GitHub;
never downloads or installs), and py_cmd() so spawned
Python work uses a real interpreter in packaged builds.
packaging/ holds the PyInstaller spec, per-OS build
scripts (llama.cpp b11429 assets pinned by name),
one-command installers and a manual GitHub Actions
workflow for all three OSes. LINUX PROVEN END TO END:
built GhostIDE-linux.tar.gz (26358544 bytes), installed with
./install.sh into a clean HOME (env -i), the packaged
binary served the IDE with a seeded workspace, started
the bundled llama + 7B, and answered a chat ("packaged
ok"). Windows/Mac packages are scripted and
workflow-ready; their first builds run on those OSes.
Phone: version parity + honest stubs. APK 19218438 bytes
md5 588339521da3a39b1fa4a338b29b6532, versionCode 32, same signer.

## Oct 19, 2026 - Roadmap G19 store prep (app stays v1.28.0)
Everything Ryan-independent is built and proven:
- Production upload key generated (RSA 4096, alias
  ghostide-upload, cert SHA-256 e7acb19c...). Keystore +
  password live only in ~/workspace/ghostide-apk/
  (chmod 600 password file), never in git.
- build-release.sh: proto-format aapt2 link -> base
  module zip -> bundletool build-bundle -> jarsigner
  with the upload key -> ghostide-release.aab (19.2 MB),
  and bundletool build-apks --mode=universal ->
  ghostide-universal.apk, the tester-equivalent build,
  verified (package/version/libs match the sideload APK;
  production cert). Note: production-signed builds can
  not update debug-signed installs; a one-time reinstall
  is expected when Ryan moves to the store build.
- Privacy policy text: PRIVACY.md in the ghost-ide repo
  (honest to the app: no collection; per-feature network
  disclosures incl. system speech recognition).
- Listing draft: store/LISTING.md (no em dashes,
  screenshot slots left for real captures only).
Still Ryan's: $25 Play fee + account, policy hosting,
screenshots, closed-track creation, rollout call.

## v1.29.0 (versionCode 33) - Oct 19, 2026 - Roadmap G21
Language packs: first-class HTML, CSS, JSON, Markdown,
SQL, Rust, and Go next to Python/JS.
- Highlighting: @codemirror/lang-sql, lang-rust, and
  lang-go join the bundle; langFor covers all pack
  extensions. File tree icons per language (fileIcon).
- Checkers (desktop /api/check): JSON (already), SQL
  NEW - statements split with sqlite3.complete_statement
  and executed in order on an in-memory database, errors
  reported with the statement's start line; Go via
  gofmt -e when Go is installed; Rust via rustc
  --emit=metadata when rustc is installed; absent
  toolchains get an explicit ok:null explanation, never
  a fake pass. HTML/CSS/Markdown have no local checker
  and say so.
- Run configs (desktop /api/run): .sql runs on an
  in-memory database and prints query rows; .go via
  go run; .rs compiles with rustc into a temp dir and
  runs the binary; .py/.js/.sh as before.
- Phone: /api/check gains a real JSON checker (org.json);
  Python unchanged; other types keep the honest note.
Proven against test-fixtures/langpack with a real Go
1.27.2 toolchain (SHA-verified from go.dev): gofmt
check OK, go run prints, SQL check catches a typo at
line 2, SQL run prints the fixture rows, broken JSON
fails, honest nulls elsewhere. Rust paths are wired the
same way but rustc is not installed in the sandbox, so
they are proven to the not-installed gate.

## v1.30.0 (versionCode 34) - Oct 19, 2026 - Roadmap G22
SQLite studio: opening a .db/.sqlite/.sqlite3 file opens
the studio panel instead of the text editor.
- Desktop: POST /api/db/info (tables, columns with PK
  flags, row counts; read-only URI open) and
  /api/db/query (one statement; read-only mode=ro unless
  write:true - SQLite itself rejects writes, no string
  matching; query rows capped at 500, values truncated).
- Phone: same endpoints on android SQLiteDatabase
  (OPEN_READONLY vs OPEN_READWRITE, execSQL for writes,
  changes() for rowcount, typed cursor values incl. blob
  placeholders).
- Panel: table chips (tap = schema + SELECT preview),
  query box, results grid, Read-only / Edits-allowed
  toggle.
Proven on test-fixtures/db/notes.db: info exact, SELECT
returns the seeded rows, a write in read-only mode
fails with SQLite's own error and does not persist, a
write with the toggle on lands (rowcount 1) and reads
back. Phone proof joins the device pass.

## v1.31.0 (versionCode 35) - Oct 19, 2026 - Roadmap G23
API client (Postman-lite), Tools -> API client:
- Requests are sent by the app's own server (POST
  /api/http on both builds: urllib on desktop,
  HttpURLConnection on the phone), so local dev servers
  without CORS headers just work. Method, URL, headers,
  body; response shows status, ms, size, headers, and a
  pretty-printed JSON body; bodies cap at 512 KB.
- Saved requests per project in .ghostide/api-requests
  .json; history (last 20) in localStorage, click to
  refill the form.
- Proven end to end against a local target API: GET
  with response-header inspection, POST echo with a
  custom header received, 404 passthrough, plain
  connection-refused error, saved-request round trip.
- Build note: the first cut imported urllib inside
  do_POST, which made Python treat urllib as a local
  and broke EVERY desktop POST (UnboundLocalError).
  Caught by the fixture tests before shipping; imports
  now live at module level. Lesson: no inline imports
  of module names the handler also uses.

## v1.32.0 (versionCode 36) - Oct 19, 2026 - Roadmap G24
Docs mode:
- renderMarkdown rewritten: headings 1-6, tables,
  ordered + task lists, blockquotes, hr, strikethrough,
  relative images (served via /api/raw) and relative
  .md links that open in the app. Still escape-first.
- Mermaid: dependency-free subset renderer to inline
  SVG (the real mermaid package is ~122 MB unpacked).
  Flowcharts (shapes, labels, dashed, TD/LR) and
  sequence diagrams render in preview and in exports;
  other diagram types fall back to a labeled source
  block.
- Live preview: typing in an open .md re-renders the
  preview (debounced, from the live doc, unsaved edits
  included).
- Export docs site (Tools): all workspace .md files
  become one self-contained docs-site.html with a nav,
  per-page sections, diagrams inlined, and inter-doc
  links rewritten to anchors.
Proven: renderer harness 16/16 (incl. the real
ROADMAP_20.md and an XSS escape test); the real export
function run against a live server produced a
standalone site passing 6/6 checks.

## v1.33.0 (versionCode 37) - Oct 19, 2026 - Roadmap G25
Asset tools (Tools -> Asset browser):
- /api/files now carries file sizes on both builds.
- Asset browser: image grid with thumbnails (checker
  background so transparency reads), dimensions from the
  loaded image, sizes, heaviest-first sort with the top
  card flagged - or name sort.
- Icon maker: glyph (emoji or up to 4 chars) on a
  rounded/circle/square/transparent backing with color
  pickers and a live canvas preview. Generate writes an
  8-file PNG set to icons/ (48/72/96/144/192/512, Apple
  touch icon, favicon 32) through the new POST
  /api/file64 (base64 binary write, 8 MB cap, both
  builds).
Proven: /api/files sizes exact against the fixture
(heavy.png 1,441,103 B vs small.png 76 B); the file64
round trip is byte-identical by md5 with a valid PNG
header and IHDR. The canvas drawing itself runs in the
browser; Arc F closes with this goal.

## v1.34.0 (versionCode 38) - Oct 19, 2026 - Roadmap G26
Pre-commit AI review (git panel -> Review, or the palette):
- A deterministic rules scan over the added lines of the
  diff: secrets (AWS key ids, GitHub tokens, sk- keys,
  private keys, hardcoded credential assignments), bug
  risks (eval/exec, bare except, mutable default args,
  shell=True, pickle, innerHTML), dead code (commented-out
  code), debug leftovers and new TODOs.
- Findings are anchored to file + new-file line (the diff
  parser tracks hunk offsets), jump to the line on tap,
  and are dismissible (persisted per finding id).
- Committing with un-dismissed secret findings asks once
  with the locations listed; the review can advise but
  never hard-blocks.
- Ask Ghost sends the diff to the model for its own
  anchored read on top of the rules.
- /api/git/diff-all cap raised 8000 -> 60000 on both
  builds so reviews see whole diffs.
Proven on test-fixtures/review (a real git repo): both
planted secrets caught at their exact lines, the planted
mutable-default / bare-except / eval bugs caught,
commented-out code and debug print flagged, the clean
file silent, and a fully clean diff returns zero
findings - 10/10 harness assertions.

## v1.35.0 (versionCode 39) - Oct 19, 2026 - Roadmap G27
Context lab (Tools -> Context lab, or the palette):
- GET /api/context/stats on both builds runs a faithful
  port of GhostSlice's estimator (tokens.py method v2:
  BPE-like split, per-language calibration factors,
  minified-JS family, scanner walk rules - skip dirs,
  binary exts out, lockfiles counted and flagged, 5 MB
  per-file cap). The lab therefore shows the same numbers
  `ghostslice scan` prints for the same tree.
- Panel: workspace totals, a token heatmap (tile colour
  and weight by share, tap opens the file), the 30 most
  expensive files with % of total, what-if slice controls
  (skip tests / docs / lockfiles, max file size, folder
  prefix) with kept-vs-dropped token math, and a window
  fit readout (greedy biggest-first into 8k/32k/128k).
Proven: the endpoint matches GhostSlice's own scan
EXACTLY on two trees - the benchmark fixture (705 tokens;
min.js 484 with the minified factor, per-file counts all
equal) and the docs fixture (2360). Slice-math harness
7/7 (its first run caught a test_a.py pattern miss and an
unsorted fitWindow; both fixed). Caught at build: a Java
lambda in the phone port (metafactory) - replaced with an
anonymous Comparator per house rules.

## v1.36.0 (versionCode 40) - Oct 19, 2026 - Roadmap G28
Offline reference docs (Tools -> Reference docs, palette):
- refdocs.json (748 entries, 366 KB): the Python slice is
  extracted from the interpreter's real documentation by
  tools/gen_refdocs.py (signatures + doc text for 25 stdlib
  modules, their callables/classes, and the core builtins);
  the JavaScript slice is 44 hand-written core entries.
- GET /api/refdocs/search + /api/refdocs/get on both
  builds (phone reads the bundled asset; build.sh stages
  refdocs.json at [0.8/8]). Ranking: exact title, suffix,
  contains, then per-token title/summary/body signals.
- Entry view with Ask Ghost with this page: drops the page
  into the chat box so the model answers from the page.
Proven: search ranks the right page first on five natural
queries (os.path.join, array map, join path, random
integer, parse json); the full offline loop (bundled page
-> composed prompt -> local llama 7B, loopback only)
answered "what does os.path.join('/a', '/b') return and
why" correctly from the page ('/b', absolute component
discards the rest). Generator lessons banked in its
comments: stdlib alias layers (posix/genericpath) defeat
owner filters, and module constants carry their type's
doc string - callables/classes only.

## v1.37.0 (versionCode 41) - Oct 19, 2026 - Roadmap G29
Semantic code search (find panel -> Meaning button):
- POST /api/semsearch on both builds ranks FUNCTIONS (the
  G1 symbol index, chunked def-to-def) for plain-English
  queries: identifier splitting (snake/camel), light
  stemming, and a curated coding-concept table (download ~
  fetch/retrieve; retry ~ attempt/backoff; owe ~ total/
  sum/price) plus coverage bonuses. Each result carries
  its matched terms as the "why". Plain text Find stays
  beside it in the same panel.
- Honest scope, stated in the UI: this is lexical-semantic
  ranking, not a neural embedding model.
Proven on test-fixtures/sem with the roadmap's own bar:
three plain-English queries verified to share ZERO word
stems with their target functions (checked with the
server's own tokenizer) all rank the right function
FIRST - retries failed downloads -> fetch_with_backoff,
greets newcomers by mail -> send_welcome_email, what does
the customer owe with tax included -> compute_cart_total
(6/6, harness at test-fixtures/sem-harness.py). The proof
rounds caught a stopword-after-stemming slip ("does" ->
"doe") and a concept group missing its own query word
("owe"); both fixed.

## v1.38.0 (versionCode 42) - Oct 19, 2026 - Roadmap G30
Error memory (closes Arc G):
- Errors from Run and Check are fingerprinted by a
  normalized signature (error line with paths, numbers,
  hex addresses and quoted names skeletonized, so the
  same error in another file at another line matches).
- A pending error whose file later runs/checks clean
  after edits becomes a memory: the fix is the trimmed
  before/after change summary. Unchanged content records
  nothing.
- A repeat anywhere prepends the run pane with "Seen
  before. Last time, this fixed it (in <file>):" plus the
  stored change - before any new guessing. Tools -> Error
  memory lists everything remembered (60 most recent)
  with a Forget all. All local (localStorage).
Proven by a 9/9 harness running the exact scenario from
the roadmap: NameError fixed in a.py, then the same error
(new variable name, new path, new line) in b.py surfaces
a.py's fix; different errors never collide.

## v1.39.0 (versionCode 43) - Oct 19, 2026 - Roadmap G31
Macros and the multi-cursor pack:
- Macros (Tools -> Macros): steps are find & replace
  (plain or line-anchored regex), trim trailing
  whitespace, leading tabs -> spaces, ensure single final
  newline. Record mode captures project Find & Replace
  operations as steps; steps can also be added by hand.
  Macros persist in localStorage and run on the current
  file or across a folder prefix (200-file cap, confirm
  first, per-file report).
- Multi-cursor: basicSetup already carried multiple
  selections, Mod-d next-occurrence and Alt-drag column
  selection; G31 adds Mod-Alt-Up/Down (cursor above/
  below), Mod-Shift-d (select all occurrences), and a
  palette command placing cursors on every selected line.
Proven on test-fixtures/macro (harness 8/8): a recorded
cleanup macro (regex print-line removal + detab + trim +
final newline) transforms three dirty files to their
exact goldens, is idempotent, reports honest step counts,
and every output passes py_compile. The first harness run
caught regex steps missing the multiline flag.

## v1.40.0 (versionCode 44) - Oct 19, 2026 - Roadmap G32
Verify pipeline (Tools -> Verify pipeline, palette):
- POST /api/verify/run runs the whole health pass in one
  tap: syntax check-all, the test suite, and the slice
  report - composed from the SAME helpers the individual
  tools use (_check_all extracted from its route;
  discover_tests + run_test_file; _context_stats), so
  the pipeline cannot drift from the tools it summarizes.
- It grades an evidence level with printed criteria
  (L1 checked, L2 tested, L3 green with real tests) and
  writes a shareable VERIFY_REPORT.md into the workspace.
- Phone edition is honest about its stages: checks run
  the real on-device checkers (Python via the bundled
  interpreter, JSON via org.json), the tests stage is
  marked not-run (desktop-only) and evidence caps at L1.
Proven 15/15 on test-fixtures/verify: every number in the
pipeline output equals the individual tools' outputs on
the same tree; evidence reads L2 while a planted syntax
error stands, and after fixing it, both the tools and the
pipeline move to clean and the level rises to L3.

## v1.41.0 (versionCode 45) - Oct 19, 2026 - Roadmap G33
Big-screen mode (frontend + CSS):
- All three panes (Files | Editor | Chat) get drag-resize
  handles on wide screens; widths persist across sessions
  (localStorage ghostide-panes, clamped 150-560 px).
- Big-screen mode (Tools / palette): wider default panes
  (264/408 px), slightly larger chat text; auto-applies
  on screens >= 1280 px wide unless the user turned it
  off; the stacked phone layout (<= 900 px) is untouched,
  so foldables switch by width as they fold/unfold.
- Keyboard shortcut map: Tools -> Keyboard shortcuts
  lists the IDE's real bindings (verified against the
  keymaps: Ctrl/Cmd-K quick open, Shift-P palette,
  Shift-O symbols, multi-cursor set, line ops). When a
  hardware keyboard pairs (first modifier chord on a
  fine-pointer or wide screen), a chip offers the map.
Status: BUILT. The done-when asks for a tablet/fold
proof, which needs a real wide screen (desktop browser,
tablet, or DeX) - Ryan-side check.

## v1.42.0 (versionCode 46) - Oct 19, 2026 - Roadmap G34
App-state carry (Tools -> Export / Import app state):
- Export gathers every ghostide-* localStorage entry by
  prefix (settings, snippets, bookmarks, breakpoints,
  macros, error memory, API history, saved requests'
  dismissals, pair identity, pane layout, big-screen pref,
  open-tabs session) into one ghostide-state.json written
  to the workspace root. Prefix enumeration means state
  added in future versions carries automatically.
- Import validates the envelope (app + kind markers,
  non-empty, keys inside the namespace, string values),
  confirms with the entry count and export date, applies,
  and reloads so every subsystem re-reads its state.
- Honest scope, stated in the export message and code:
  chat transcripts are never stored (session-only) and
  file-history snapshots live with the project files, so
  neither is in the carry file.
Proven 10/10 by test-fixtures/state-harness.mjs: collect
skips foreign keys, a wiped store restores field by field
to identical values, and foreign/empty/out-of-namespace
files are refused.
