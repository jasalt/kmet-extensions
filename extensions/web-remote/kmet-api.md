# kmet API parity audit

Audited against `kmet.extension`, the extension contract, the TUI UI registry,
builtin commands and settings selector in the companion kmet-agent checkout.
This extension is a companion, **not a replacement front end**. It must not
replace the process UI port, monkey-patch host vars, require internal app
namespaces, or read private config/session files to simulate missing APIs.

## Implemented public capabilities

| Surface | Browser equivalent | Limits |
| --- | --- | --- |
| Prompt / steer / follow-up | Composer, delivery selector, Ctrl+Enter | Dialog commands without an equivalent still act in the TUI |
| Abort | Abort buttons | No dedicated cancel-compaction API |
| Model / thinking | Composer selectors and Settings | Available authenticated models only; scoped cycling selection has no setter here |
| Active tools | Settings → Active tools, individual enable/disable | Session-only `get-all-tools`, `get-active-tools`, `set-active-tools`; no persistence, scoped config, or deferred source management |
| Theme | Settings → TUI theme | Shared theme registry + `ui-set-theme`; affects TUI only, not persisted |
| Thinking / tools display | Settings → Display | Browser-local show/hide; separate explicit TUI mode controls; no getters for TUI thinking mode |
| Session branch / tree | Tree / fork panel, `/tree`, `/fork` | Last 300 active-branch entries, IDs, roles, previews and bookmarks; not the complete branching tree |
| Navigate | Select entry or enter known ID → Navigate | Uses ctx `navigate-tree`; optional branch summary and custom instructions; idle-only |
| Fork | Select a user entry → Fork | Uses ctx `fork`; idle-only; native behavior may restore a draft |
| Labels | Set bookmark, blank removes | Session facade `set-label!` / `get-label` |
| Resume | Known session file path → Resume | ctx `switch-session`; no session enumeration; idle-only |
| New / name / compact | Composer controls | Existing ctx/session operations; compactor configuration unavailable |
| Transcript / streaming / usage / activity | Live page | Public events and session facade; ephemeral native notices/custom renderers aren't replayable |
| Draft | Guarded two-way text sync | No cursor/focus/change events from TUI; expanded pastes; see README |
| Autocomplete | Captured TUI provider → Datastar menus | Capture-chain ordering; no final-provider getter |
| Commands | Commands panel + completion | Public registry may enumerate only extension commands; captured TUI autocomplete includes more |
| Copy | Copy last reply | Browser clipboard permission/secure-context restrictions; host clipboard unchanged |

Panels are private to the requesting browser. Actions use the same cookie,
Host and same-origin checks as prompt submission. Session actions and tool
selection refuse while busy/queued. Changing settings via buttons does not
send text to the LLM. Native dialog commands recognized without arguments
(`/settings`, `/tools`, `/model`, `/thinking`, `/theme`, `/tree`, `/fork`,
`/resume`) open browser panels instead of launching native dialogs. Arguments
continue through normal command handling. Panels are snapshots: reopen to
refresh changes from the TUI.

## Not feasible with current public read/write surfaces

| Feature | Missing seam / suggested improvement |
| --- | --- |
| Full `/settings` parity | A typed settings descriptor catalog (id, scope, current value, allowed values, sensitive/restart flags) and validated get/set service with runtime application + optional persistence. Current settings include compactor, auto-compaction, HTTP transport/timeouts, steering/follow-up modes, tree filter, editor/output padding, autocomplete height, images and others; writing config files would bypass runtime effects. |
| Complete `/tree` | `get-tree` / all non-custom entries with parent IDs, labels, leaf ID and revision. `get-all-entries` currently means **custom entries of a requested type**, not the entire session. `get-entry` can resolve known IDs but cannot discover abandoned branches. |
| `/resume` browser | Paginated session summaries/search scoped to current project or all projects; include stable IDs/path, name, timestamp, preview. Do not expose raw session files unnecessarily. |
| Clone / import / export | Explicit operations returning typed results/download data, not TUI-bound commands, filesystem probing or subprocesses. |
| `/config`, scoped models, extensions/plugins, skills/prompts | Readable catalogs with selection/enable operations and scope/persistence semantics. Registration APIs contribute resources; they are not management APIs. |
| Authentication / `/login` | Front-end-neutral auth state machine and safe challenge/action records; never send API keys, auth headers, tokens or provider config wholesale to the browser. Model facades exposing auth material are deliberately unused. |
| Approvals / dialogs | Subscribe/respond capability for pending UI requests scoped to the agent, with IDs, schema, defaults, timeouts and cancellation. The runtime has plumbing, but `kmet.extension` doesn't let a companion attach without replacing the UI port. |
| Arbitrary `ui-custom` / widgets | TUI components aren't HTML. Add optional semantic web renderer or typed dialog/widget descriptors and lifecycle events; never scrape terminal ANSI or intercept factories. |
| Notifications, ephemeral chat, working-message overrides, extension statuses | Read-only snapshot + event stream from the UI service. Existing setters have no observer counterpart. |
| Terminal-specific functions | Hotkeys, suspend, external editor, image terminal rendering, native custom header/footer/editor are local TUI behavior; provide browser equivalents only where meaningful. |
| Editor parity | Subscribe to draft changes with revision/source, focus ownership, cursor/selection, structured paste attachments, compare-and-set, and editor history getter/add operation. History alternatives are in todo.md. |
| Queue editing | Read queue contents and reorder/remove/edit actions; current events expose counts/updates, composer can enqueue but cannot safely restore/edit pending prompts. |
| Model / theme changes | Capability getters plus declared changed events for all controls. Public theme mutation doesn't promise persistence; don't imply saved settings. |
| Command execution | Front-end-neutral command descriptors with argument schemas, capabilities, return data and optional semantic interaction. Today native command handlers can mount TUI dialogs or add ephemeral messages invisible to this page. |
| Lifecycle / context | Fresh context getter or session-changed event carrying capabilities/model/cwd. Stored command ctx can become stale after session changes; closures may be live but scalar fields aren't. |
| Concurrency | Document which public calls are safe off the TUI thread; offer a serialized UI/action dispatcher for browser requests and polling. |

## Intentional exclusions

This extension doesn't invoke shutdown/reload from browser panels (can terminate
its own server mid-response), mutate provider registrations, expose secrets,
spawn a host shell for missing UI operations, or persist settings by editing
host files. These API powers are not equivalent to useful UI parity. Browser
access already grants agent control; bind remotely only on trusted networks.

## Verification

Offline tests cover escaping, tool selection, entry validation, navigation
options, user-only forks, bookmarks, resume and busy refusal. Chromium verified
Settings and Tree panel rendering and `/settings` routing with no page errors
against the controlled public-API fixture. Real SCI load/unload passes. Actual
navigation/fork/theme effects in a live TUI remain unverified; tests validate
calls and results, not the host's native UI rendering.
