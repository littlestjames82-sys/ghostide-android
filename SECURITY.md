# Security policy

## The model

GhostIDE's security model — loopback binding, pairing
roles, input pins, backup crypto, and the agent's
rails — is documented in
[docs/SECURITY.md](docs/SECURITY.md), including an
explicit list of what is *not* claimed.

## Reporting a vulnerability

Email **gdev6145@gmail.com** with:

- the GhostIDE version (Settings → About),
- what you found and how to reproduce it,
- what an attacker gains, as concretely as you can.

Please do not open a public issue for a live
vulnerability. You will get a personal reply; the
studio's target is one business day.

## Scope notes

- The app binds its server to 127.0.0.1; findings
  that require a malicious app already on the device
  with greater privileges are usually out of scope,
  but report anyway if you think the boundary matters.
- The bundled ecosystem tools run with the app's
  permissions against the workspace you open. A tool
  escaping the workspace is in scope.
- Findings in the desktop edition belong to the
  [ghost-ide](https://github.com/littlestjames82-sys/ghost-ide)
  repo; the same email reaches the same person.
