# web-remote — drive the live kmet session from a browser

A minimal local web page that mirrors the running kmet session (normally the
TUI's) and accepts input from it. Server-rendered HTML, one SSE stream, and a
vendored [Datastar](https://data-star.dev) v1.0.5 (`src/web_remote/datastar.js`,
MIT, see `datastar.LICENSE.md`) — no CDN, no build step, no extra dependencies.

## Install

From a kmet checkout:

```sh
bb start install ../kmet-extensions/extensions/web-remote/src
```

Then enable `web-remote` with `kmet plugins` (or `kmet plugins --local`) and
restart, or merge this row into `~/.kmet/agent/patch.edn`:

```clojure
{:rows [{:id :ext/web-remote :plugin :ext/web-remote}]}
```

Uses the SCI loader. Native Jolt execution has not been verified.

## Use

| Command | Effect |
| --- | --- |
| `/web-remote start [port\|host:port]` | Start (default `127.0.0.1` on a free port) and show the login URLs |
| `/web-remote status` / `url` | Show the URLs again and the connected client count |
| `/web-remote stop` | Stop the server and disconnect every client |

Command replies (the URL, warnings, errors) appear in the chat as ephemeral
`[web-remote]` notices: part of the live transcript, never sent to the LLM
nor saved to the session. Without a UI layout they fall back to a flash
notification. The footer shows `web HOST:PORT` while it runs.

Address forms: `8082`, `:8082`, `localhost`, `0.0.0.0:8082`,
`192.168.1.5:8082`, `[::]:8082`, `[::1]`. A wildcard bind (`0.0.0.0`, `[::]`)
lists a loopback URL plus one per detected host address; any bind beyond
loopback adds a warning notice.

Open a URL shown; it carries a
one-off token that is exchanged for a cookie. The server also stops when the
extension unloads or kmet quits.

The page shows:

- **Status:** idle/running/compacting and queued messages, plus Abort while
  busy.
- **Transcript:** the current session branch (last 200 entries) — user,
  assistant (thinking collapsed), tool calls/results, bash, custom messages and
  compaction summaries.
- **Live:** the streaming assistant message and running tools with their output.
- **Working line** (above the composer, like the TUI's spinner): a spinner
  with "Waiting for model" until each LLM call's first token, then
  "Working"; a retry countdown ("Retrying (1/3) in 4s"), compaction
  ("Compacting context", "Auto-compacting", …) or branch summarization
  replaces it while running. Run errors, the repeat-loop guard and failed or
  cancelled compactions appear in the notice line below it.
- **Statusline** (below the composer, like the TUI footer): `Кб` + cwd (git
  branch) • session name, then usage (↑input ↓output R/W cache, CH cache-hit
  rate of the latest response, $cost), context use (% of the window, yellow
  over 70 %, red over 90 %) and provider/model • thinking level. Differences:
  usage sums the active branch (the TUI also counts abandoned branches), there
  is no "(auto)" auto-compaction marker and no extension statuses (neither is
  readable by an extension).

The composer shares draft text with the TUI editor. Browser typing is sent
with a 300 ms debounce; TUI text is polled every 50 ms and adopted while the
browser composer is unfocused. Focus keeps your local text and caret intact.
A write compares the last shared text first: if the TUI or another browser
changed it, the web draft is kept locally and a notice asks you to blur to
adopt the shared draft. Sending clears the TUI draft only if it still matches
the submitted text. TUI collapsed pastes are read expanded. This is not
concurrent editing: the API exposes neither TUI focus nor cursor position,
and an accepted web write moves the TUI cursor to the end. Avoid typing in
both at once.

Autocomplete uses the TUI's provider chain captured when the web server
starts, without replacing the TUI provider. Typing requests suggestions after
150 ms; Tab explicitly requests file completion. Use ↑/↓ to select, Tab or
Enter to apply, Escape to dismiss, or click an item. Ctrl+Enter still sends.
The provider handles slash commands (including argument completion), `@file`
and extension completions registered before web-remote's capture factory.
Later providers appear only if a rebuild puts them before that factory;
there is no public getter for the final chain. No provider means no suggestions
(e.g. headless mode). Input history is deferred; see [todo.md](todo.md).

### Composer design

Datastar owns the prompt/focus/cursor signals and debounced `@post` actions.
`/complete` returns a request-local `#completions` HTML patch, not a global
SSE region: one browser's menu never replaces another's. Local Datastar
selection signals drive highlighting. A bounded server ticket retains the
original provider/items; `/complete/apply` calls that provider and patches a
result signal. The composer adopts it only if its text and caret still match,
then places the caret and sends a normal input event (which shares the draft).
The signal-patch handler uses an explicit return to avoid Datastar's implicit
last-expression rewrite of blocks, and reads the merged result signal (patch
events contain only changed fields).
The textarea itself is never morphed. File/command matching is not duplicated
in browser JavaScript. Up to 50 results and 16 recent request tickets are kept;
an expired ticket simply needs a fresh completion request.

## Interactive panels

The navigation bar opens **Settings**, **Tree / fork**, **Resume**, and
**Commands**. Bare `/settings`, `/tools`, `/model`, `/thinking`, `/theme`,
`/tree`, `/fork`, and `/resume` in the composer open their browser equivalent.
Panels are private to each browser, not broadcast. Reopen to refresh.

- **Settings:** available models, thinking level, session-only active tool
  toggles, browser thinking/tool visibility, TUI thinking/tool display and TUI
  themes. Host changes are live, not persisted. This is a supported subset,
  not the full native settings catalog.
- **Tree / fork:** active-branch entries with IDs, roles and previews. Select
  an entry (or enter a known ID), navigate with optional branch summary and
  instructions, fork from a user message, or set/clear a bookmark. The API
  cannot enumerate abandoned branches; this is not the full TUI tree.
- **Resume:** switch to a known host session file path. Session browsing is
  unavailable through the API.
- **Copy last reply:** copies plain text to the browser clipboard (requires
  browser permission and a secure context, normally localhost or HTTPS).

Session changes and tool selection require idle/no pending messages. Approvals
and arbitrary native dialogs stay in the TUI. The complete capability audit
and proposed improvements are in [kmet-api.md](kmet-api.md).

Input: send a prompt (Ctrl+Enter); while the agent runs it is delivered as
steer or follow-up (selectable). Also abort, switch model (available models),
set the thinking level, compact, rename the session and start a new session
(idle only). Tool approvals and other dialogs stay in the TUI.

## Security

- Binds `127.0.0.1` unless given another address. A random 32-char token is
  minted per start; `GET /?token=…` sets an `HttpOnly; SameSite=Strict`
  cookie, and every other request needs it.
- The `Host` header must name the server by an allowed `HOST:PORT` (DNS
  rebinding): the bound address and the name given to `start`, `localhost`
  for a loopback bind, and for a wildcard bind loopback plus the detected host
  addresses (best effort: the default route's source address and the
  hostname's addresses — reach it by another address and you get 403; bind
  that address explicitly instead). POSTs also need a matching `Origin`.
- **Beyond loopback it is plain HTTP**: the token and cookie cross the
  network in clear text. Use it only on a trusted network, or tunnel it (e.g.
  `ssh -L 8082:127.0.0.1:8082 host`) and keep the default bind.
- All session text is HTML-escaped and rendered as plain text (no markdown).
  The page sends a strict CSP (`'unsafe-eval'` is required by Datastar
  expressions), `X-Frame-Options: DENY`, `no-store` and `no-referrer`.
- The HTTP server caps request lines (8 KiB), headers (64), bodies (256 KiB),
  connections (32) and applies a 10 s read timeout.

Anyone with the URL (or local access to your browser profile) controls the
agent with your permissions; treat it like a shell. Stop it when done.

## Design

- `http.clj` — a tiny HTTP/1.1 server on `java.net.ServerSocket`, one thread
  per connection (`kmet.libs.concurrent/spawn`); plain replies use
  `Connection: close`, `/events` stays open as SSE. All Java interop lives here.
- `view.clj` — a small escaping hiccup renderer, the page shell and the
  `#status`, `#transcript`, `#live`, `#activity`, `#notice` and `#statusline`
  regions.
- `completion.clj` — captured TUI provider, request-local suggestion menus and
  bounded tickets for applying the original provider's edits.
- `core.clj` — event handlers only update an atom. One render thread wakes
  every 50 ms, re-renders the dirty regions (the transcript whenever the
  session leaf moves) and wakes the SSE clients; each client sends only the
  regions that changed since its last send, as `datastar-patch-elements`.
  A reconnect gets a full render. The composer sits outside the patched
  regions so typing is never clobbered.

## Test

```sh
bb test   # set KMET_CHECKOUT if kmet-agent is not at ../../../kmet-agent
```

Covers request parsing and limits, SSE framing, escaping, auth (token, cookie,
Host, Origin), every action and an SSE stream round trip against the nullable
extension api.
