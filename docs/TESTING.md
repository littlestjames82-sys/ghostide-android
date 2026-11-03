# GhostIDE — How a build earns the right to ship

GhostIDE is developed in audited cycles (see
`CAMPAIGN_20x20.md` in the desktop repo): batches of
improvements alternate with batches of adversarial
audits against a standing checklist (A1–A20) plus
cycle-specific rails. No build ships on a builder's
say-so; each gate below must pass, in the parent
session, for the exact artifact that gets signed.

## The gates

### 1. Version gate
`check_versions.py` — the version lives in exactly five
places (Python server, Java server, VERSION.json,
AndroidManifest name+code, changelog head). The build
refuses a half-bumped release. versionCode increments
by exactly 1 per release.

### 2. Bundle gate (the one that matters most)
The web frontend is executed headlessly against a DOM
stub (`runbundle.mjs`). A bundle that dies during
startup — the failure that once shipped a dead-on-
arrival build — cannot pass: the bundle must get past
extension construction and reach the known, benign
stub artifact error. This gate exists because parse
checks and HTTP smoke tests once let a stillborn
bundle through.

### 3. Harness suite
`node tools/harness/run-all.mjs` — **137 harness files
at 3.10.0**, each pinning behavior in the source it
ships: route presence and parity, validation pins,
agent rails, UI wiring, historical regressions. Pins
are written against the *current* source shape, and
when a feature deliberately changes a surface (a route
added, a verb list extended), the affected pins are
advanced in the same batch with a dated note — the
suite failing on a stale pin is treated as the suite
working, and the fix is a conscious re-pin, never a
quiet deletion.

Three censuses deserve naming:

- **Route census** — exact route-literal counts per
  server (144 desktop / 134 Java at 3.10.0). Any route
  added or removed moves the number on purpose, in the
  open.
- **Sink census** — `innerHTML` occurrences in the built
  bundle, pinned at **101**. New UI is expected to use
  textContent/createElement; the census has caught real
  additions mid-cycle (a new panel at 102 was rewritten
  before it shipped).
- **Parser-site counts** — where a grammar lives in two
  places (the agent's action parsers), occurrences are
  count-pinned ≥2, after a cycle where a single-site pin
  passed while the second parser lagged.

### 4. Live fixture probes
Pin tests prove shape; fixtures prove behavior. The
desktop server is started against durable fixture
workspaces and driven over HTTP: git branch/stash/tag
operations on a real repo (created, switched, refused,
restored), search/replace semantics on a throwaway copy
(including dry-run leaving files byte-identical),
single-test runs on Python and JS fixtures, malformed
JSON, traversal attempts, and a **canary sweep** — a
workspace containing a planted API key is queried
through the routes and the key must appear zero times.

### 5. Ecosystem bundle gate
`eco_bundle_gate.py` runs **every bundled tool's own
test suite** (2,871 tests at generation e13) and fails
the APK build if any fail or if the bundle's version
pins drift from the sources. The shipped bundle is
therefore never ahead of its proofs.

### 6. Artifact verification
After `build.sh`: the APK's badging is read back
(package, versionCode, versionName), the signing
certificate is verified live against the preserved
keystore's known SHA-256, and the bundled web asset
inside the APK is grepped for the cycle's markers —
proving the artifact in hand contains the code the
harnesses just passed, not a stale build.

### 7. Push read-back
Source syncs to GitHub through the API with a full
read-back diff; a push counts only when the diff comes
back **EMPTY**.

## The honest layer model

Proof comes in layers, and the record says which layer
proved what:

1. **Compile + pin** — the Java server builds; harnesses
   pin every route and rail.
2. **Desktop-live** — the same API, proven over HTTP
   against fixtures.
3. **Device** — a human installs the build and works it.

Layer 3 stacks: when a cycle's features are proven at
layers 1–2, the device pass for them rides the next
installed build, and BUILD_NOTES.md says so explicitly
rather than implying device proof that hasn't happened.
