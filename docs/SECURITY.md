# GhostIDE Android — Security model

What the app defends, how, and — just as important — what
it does not claim. Current as of 3.10.0.

## Network surface

- The in-app server binds **127.0.0.1 only**. Nothing in
  the app exposes the API to the LAN by default.
- Request bodies are capped at **64 MB**; malformed JSON
  gets a clean error, never a crash; duplicate
  Content-Length headers, chunked-encoding tricks, and
  truncated bodies are rejected.
- Unknown routes return a JSON 404.

## Pairing

- Remote access (pair mode) requires a token. Tokens
  expire after **12 hours** and can be revoked from the
  host (revocation is host-local only).
- Roles: **viewers are read-only** — every POST is refused
  except the pairing heartbeat. Editors get the API the
  local UI gets, no more.
- One approval-decision route per server; agent and tool
  approvals all ride it, and decisions are appended to a
  ledger (`.ghostide/approve-store/ledger.jsonl`) that is
  the ground truth for what was approved or denied.

## Filesystem containment

- Every file route resolves through a single safe-path
  check: paths that escape the workspace are refused
  (`path escapes workspace`), on both servers.
- All writes are **atomic**: temp sibling file, then an
  atomic rename, so a killed write never leaves a torn
  file. This holds on the desktop and phone servers.
- The agent's scratchpad and notes live at one pinned
  path inside `.ghostide/`, size-capped.

## Git input discipline

Git is where a code editor most easily becomes a command
injection surface, so every client-supplied value is
pinned before it reaches a process:

- Branch and tag names must match
  `[A-Za-z0-9][A-Za-z0-9._/-]*`, with `..`, trailing `/`,
  and `.lock` refused. The current branch cannot be
  deleted.
- Stash operations take an **integer index** (0–99, bools
  explicitly rejected on the desktop server); the server
  rebuilds the ref (`stash@{n}`) itself — a client ref is
  never interpolated.
- Commit hashes are hex-pinned (`[0-9a-f]{7,40}`) before
  use in show/diff routes.
- Search patterns are length-capped (200 chars) on both
  servers; a bad regex is a clean error.
- Amend is a plain `git commit --amend` — no force flags
  exist anywhere near it.

## Backups

- Encryption: **AES-256-GCM** with **PBKDF2-HMAC-SHA256**
  key derivation (older backup formats remain readable).
- Restore is staged: extract to a staging area, validate
  (path traversal refused, duplicate entries rejected,
  size limits enforced), then apply with rollback copies
  kept until the apply succeeds.

## The agent's rails

The agent is the most powerful thing in the app, so it is
the most fenced:

- **Writes are staged.** Nothing the agent produces lands
  on disk until the user approves it in the review panel.
- **DONE is gated.** Before an agent run can report
  completion, every staged file is syntax-checked; failures
  feed back as repair rounds under a user-set cap.
- **Run/delete/MCP actions** honor a policy pack (the
  Seatbelt rules): destructive or policy-flagged actions
  require explicit approval; approvals are recorded in
  the ledger.
- **Sub-runs are read-only.** EXPLORE dispatches from a
  whitelist that contains no write, run, delete, MCP, or
  nested-explore branch — proven by negative space in the
  audit harnesses, not by intention.
- **Git is read-only for the agent**: status, diff, log,
  branches, stash list, tags. Branch/stash/tag mutations
  and amend each have exactly one caller in the codebase:
  the human-facing UI.
- Loop detection stops a run repeating identical actions
  (warn at 3, stop at 4); step budgets are user settings,
  not constants.

## Secrets

- The IDE stores no credentials in its settings file.
- GhostEnv, the bundled .env doctor, compares variable
  **names only** — values never leave the file.
- The audit suites sweep shipped sources and live
  responses for canary credentials every cycle; a planted
  key in a test workspace must appear in **zero** route
  responses, including error paths.

## What is *not* claimed

- The tamper-evidence of the approval ledger is
  tamper-**evident**, not tamper-proof: silent truncation
  of the ledger's tail is not detectable.
- A syntax check is not a security audit of your code;
  the verify pipeline reports evidence levels and says
  what it could not check.
- On-device models are small models: their suggestions —
  like the agent's staged writes — are reviewable
  proposals, and the review step is a load-bearing part
  of the design, not a courtesy.
