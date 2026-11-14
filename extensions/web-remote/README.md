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
| `/web-remote start [port]` | Start on `127.0.0.1` (default: a free port) and show the login URL |
| `/web-remote status` / `url` | Show the URL again and the connected client count |
| `/web-remote stop` | Stop the server and disconnect every client |

The footer shows `web :PORT` while it runs. Open the URL shown; it carries a
one-off token that is exchanged for a cookie. The server also stops when the
extension unloads or kmet quits.

The page shows:

- **Status:** idle/running/compacting, model, thinking level, session name and
  queued messages, plus Abort while busy.
- **Transcript:** the current session branch (last 200 entries) — user,
  assistant (thinking collapsed), tool calls/results, bash, custom messages and
  compaction summaries.
- **Live:** the streaming assistant message and running tools with their output.

Input: send a prompt (Ctrl+Enter); while the agent runs it is delivered as
steer or follow-up (selectable). Also abort, switch model (available models),
set the thinking level, compact, rename the session and start a new session
(idle only). Tool approvals and other dialogs stay in the TUI.

## Security

- Binds `127.0.0.1` only. A random 32-char token is minted per start; `GET
  /?token=…` sets an `HttpOnly; SameSite=Strict` cookie, and every other
  request needs it.
- The `Host` header must be `127.0.0.1:PORT` or `localhost:PORT` (DNS
  rebinding); POSTs also need a same-origin `Origin`.
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
  `#status`, `#transcript`, `#live` and `#notice` regions.
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
