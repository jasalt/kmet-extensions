# BTW for kmet — design and port plan

Port of [dbachelder/pi-btw](https://github.com/dbachelder/pi-btw) (installed
as `npm:pi-btw@0.5.0` in
[`home.nix`](../../lima-default/home-manager/home.nix#L191); reviewed upstream
v0.7.1, pinned under
`../../kmet/target/reference/pi-btw-source/`).

**Reviewed; not implemented.** See [PORT.md](PORT.md) for the upstream
architecture, the kmet gap analysis, the proposed host capability, the
extension design and the phased implementation plan.

## Architecture

Pi's btw opens a real child agent session (`createAgentSession`) with tool
access, seeded from the main conversation, driven into a focused overlay.
Kmet's extension contract today exposes no way to run a sub-session — the
`kmet.ai.*` / `kmet.app.loop` machinery is deliberately outside the extension
namespace allowlist — so the port is gated on one proposed host capability,
`(:agent api)` (`create-agent-session`), implemented once in the host over the
same loop the main session uses. The extension then owns only UI and policy:
the overlay, the transcript state machine, thread persistence, and the
`/btw*` commands.

## Accepted behavior (planned)

- `/btw` / `/side` continue a hidden side thread in a focused overlay while
  the main run keeps working; `/btw:tangent` is contextless, `/btw:ask` is
  structurally read-only (`read`/`grep`/`find`/`ls` only), `/btw:new` restarts
  contextual.
- Hidden thread state persists as custom entries and survives `/reload`,
  session-tree navigation and restart; visible `--save` notes render in the
  transcript but stay out of the main agent's LLM context.
- `/btw:model` and `/btw:thinking` set BTW-only overrides (with fallback to
  the main model when the override has no credentials); `/btw:summarize`
  runs tool-free with thinking off.
- `/btw:inject` / `/btw:summarize` hand the thread back as a follow-up user
  message and clear the thread.
- Focus toggling via `alt+/`, `super+/`, `ctrl+alt+w` (env-overridable via
  `KMET_BTW_FOCUS_KEYS`), width toggle `alt+w` (window ↔ borderless
  full-width), Esc aborts a streaming answer first and dismisses second.
- Composer-only commands require the interactive TUI; headless runs display
  completed answers as visible session notes.

Deferred: the 0.7.0 `btw.json` child-extension allowlists (TypeScript-package
feature with no kmet equivalent).

This folder contains only documentation and is not a loadable extension.
Future installation should link this project's **src directory** into
`~/.kmet/agent/extensions` (honoring `KMET_CODING_AGENT_DIR`) or
`.kmet/extensions`.
