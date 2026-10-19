# GhostIDE - Play Store listing (DRAFT for Ryan's approval)

Status: draft copy only. Nothing here is submitted.
Screenshots: REAL captures from Ryan's phone only
(no mockups rule). Placeholders marked [SHOT n].

## App title (30 chars)

GhostIDE: Offline AI Editor

## Short description (80 chars)

Code with a local AI on your phone. No account, no cloud, your code stays yours.

## Full description

Your code never leaves the device. The AI lives here.

GhostIDE is a full code editor for your phone with an
AI assistant that runs entirely on the device. Download
a model once, then write, run, test, and ship code on a
plane, in a cabin, in a dead zone, anywhere. No account.
No subscription. No telemetry.

WHAT YOU GET

- A real editor: syntax highlighting for Python,
  JavaScript, and more, tabs, split view, find and
  replace with regex, minimap-free focus mode, local
  file history with restore.
- A local AI that knows your project: chat with context
  modes that slice your repo down to what matters, an
  agent mode that plans multi-file features and shows
  you every change before it lands, autocomplete from
  the same on-device model.
- Run and verify on the phone: a bundled Python runtime
  runs your .py files, a test runner with a results
  panel, a debugger, and a self-fixing loop that runs
  your tests, repairs failures, and re-runs.
- Full git on the phone: status, diff, stage, commit,
  branches, blame, history.
- A model store: pick your brain. A 0.5B sprinter for
  speed, a 1.5B all-rounder, a 3B, or the 7B max, each
  checksum-verified at download and deletable to reclaim
  space.
- Voice coding: dictate into chat or the editor and use
  spoken commands like "save file" and "run it".
- Tools that respect you: project templates, a sandboxed
  plugin system, pair mode for phone and desktop on your
  own network with conflict protection, and optional
  end-to-end encrypted backups you control.

PRIVACY, PLAINLY

No accounts, no ads, no analytics, no tracking. The
privacy policy is short because there is little to say:
we cannot see your code, so we cannot sell it, leak it,
or train on it.

Built by Ghost Developer Studio in Oakdale, Tennessee.

[SHOT 1: editor with a Python file open]
[SHOT 2: AI chat answering a question about the code]
[SHOT 3: agent mode review panel with a diff]
[SHOT 4: test runner results]
[SHOT 5: git panel]
[SHOT 6: model store]

## Category

Tools (or Productivity). Contains ads: no.
In-app purchases: none at launch.

## Content rating notes

Developer tool. No user-generated content sharing, no
social features. Voice dictation uses the system speech
service.

## Data safety (for the Play form)

- Data collected: none.
- Data shared: none.
- Optional network features are user-initiated:
  model downloads from Hugging Face, LAN pair mode,
  user-stored encrypted backups.

## Release gates still Ryan's

- $25 Play Console developer fee and account.
- Privacy policy hosted at a public URL (PRIVACY.md in
  the ghost-ide repo is the text; hosting is a separate
  decision, the studio site works).
- Screenshots from real use.
- Closed testing track first, then the staged rollout
  call.
- Production signing: upload key generated Oct 19,
  2026; keystore and password live ONLY in
  ~/workspace/ghostide-apk/ (never in git). Play App
  Signing enrollment happens at first upload.
