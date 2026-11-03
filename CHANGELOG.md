# Changelog

User-facing history of GhostIDE. The complete
per-version engineering record — including every audit
batch and what it proved — lives in
[BUILD_NOTES.md](BUILD_NOTES.md), and the same
changelog ships inside the app (Settings → What's new).

GhostIDE is developed in **20x20 campaign cycles**:
twenty improvements followed by twenty adversarial
audits, released in batches of five. Audit batches are
listed because they are half the product's story — they
repeatedly catch real defects in the cycle's own new
code before it settles.

## Cycle 8 — Craft (v3.03–v3.10, Nov 2, 2026)

- **Editor diagnostics**: syntax problems marked in the
  gutter as you type, save, or switch files; a status
  chip names the line and jumps to it. Compute-only.
- **Code folding, completed**: the fold machinery the
  buttons always promised, wired in both editor panes.
- **Git finish**: tags (mark HEAD or any commit from
  the commit viewer) and amend complete the git panel.
- **Tests craft**: run one test at a time, re-run only
  the failures, and the last verdict rides a status
  chip. Skipped tests never count as passes.
- Audits: route census exactly at its deliberate new
  counts; tag rules character-identical to branch
  rules; canary sweep clean over the new routes; the
  ecosystem gate re-run green for all 29 tools.

## Cycle 7 — Flow (v2.95–v3.02, Nov 2, 2026)

- **Branches** in the Git panel: create, switch,
  delete, current branch protected.
- **Stash**: push (incl. untracked), apply, pop, drop —
  with an honest message when git stashed nothing.
- **Search power**: ignore-case and regex modes,
  match-by-match navigation, and replace-all that
  counts first and states the exact number.
- **Continuity**: sessions restore cursor positions;
  find text persists; stash state shows in summaries.
- Audits: branch/stash pins identical on both servers;
  stash refs rebuilt from a validated index.

## Cycle 6 — Agentic (v2.87–v2.94, Nov 2, 2026)

- **The self-repair loop**: the agent cannot declare
  DONE over broken staged code; failures feed back as
  bounded repair rounds, and applied changes verify on
  disk automatically.
- **Budgets and batching**: user-set step budget and
  repair cap; read-only batches run concurrently;
  loop detection (warn at 3, stop at 4).
- **Sub-runs and memory**: EXPLORE dispatches bounded
  read-only sub-runs; NOTE keeps an agent scratchpad
  that primes future runs.
- **Background runs**: a status pill with pause /
  resume / stop from any view; queued tasks run FIFO.
- Audits: exactly one road to DONE and it passes the
  gate; the sub-run whitelist proven by negative space.

## Cycle 5 (v2.79–v2.86, Nov 2, 2026)

- Commit viewer (hex-pinned), clickable verify
  problems, history controls, settings copy, tree
  sort, palette mirrors.
- The cycle's headline audit caught a **silently
  frozen interface** (two health pollers sharing a
  name had broken the bundle build): fixed, and a
  freshness check joined the permanent suite.
- The Android repo's CI frontend job was repaired
  (a sibling-repo layout bug).

## Cycle 4 (v2.71–v2.78, Nov 1, 2026)

- Git ahead/behind, folder counts, word counts,
  persistent terminal history; deny-all for the
  approval queue; verify trends; grouped find;
  What's new in Settings.
- Audits: concurrency under parallel approvals held;
  sub-kilobyte snapshot deltas no longer round up.

## Cycle 3 (v2.63–v2.70, Nov 1, 2026)

- Per-server MCP restart, paired-devices readout
  (roles and expiry only), data-pack viewer, atomic
  writes on both servers, bundle self-check, idle
  session reaping.
- Audits caught and fixed a data-pack panel calling
  routes that did not exist — before it reached a
  phone.

## Cycle 2 (v2.55–v2.62, Nov 1, 2026)

- Persistent MCP sessions (GhostMCP 0.3.0 daemon),
  header redaction for data files, replay export,
  queue filters, ecosystem board freshness.
- The deepened secrets audit chased a planted canary
  through every new output — including a data file
  whose column header *was* the canary. Clean.

## Cycle 1 (v2.47–v2.54, Nov 1, 2026)

- The studio shelf: Recipes, Leads, QuoteShot, and
  session Replay join the Tools panel; GhostMCP 0.2
  adds resources and prompts.
- The first audit round found the agent's MCP calls
  bypassing the approval rails; they now queue like
  commands. Four of five areas passed clean under
  live attack probes.

## Before the campaign (selected)

- **v2.45** — the Approve queue (GhostApprove):
  agent commands and deletes queue for a hash-bound
  human decision; worktrees; the file watch feed.
- **v2.44** — MCP client; Git Verdict (GhostReview)
  and Scope (GhostScope); the verify evidence
  contract (Executed / Claimed / Gaps).
- **v2.43** — the agent toolbelt: REVERSE, ECO,
  VERIFY, TEST, GIT actions; batchable read-only
  actions; GhostRE 0.2.
- **v2.42** — the Reverse panel: REA bridge on
  desktop, GhostRE built into every build.
- **v1.51–v1.52** — the ecosystem runner and the
  phone-safe bundle: the IDE begins executing the
  real Ghost tools against the workspace.

Earlier history (from the first local-history builds
through the ecosystem roadmap) is in BUILD_NOTES.md.
