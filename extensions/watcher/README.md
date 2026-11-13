# watcher — editor comment requests for kmet

Port of [jasalt/pi-watcher](https://github.com/jasalt/pi-watcher), revision
`9c48401c35455451dd35d233e4259dfadf72c534`. Upstream attribution/license is
retained in `LICENSE` and the installable `src/LICENSE` (WTFPL v2).

## Install

From a kmet checkout:

```sh
bb start install ../kmet-extensions/extensions/watcher/src
```

Add `--local` for project scope. Installation makes watcher available but does
not activate it. Run `kmet plugins`, enable `watcher` in the global scope, then
restart kmet. For project-only activation, use `kmet plugins --local` instead.
`/reload` only reloads already-mounted extension rows; it does not activate a
newly installed extension.

Alternatively, merge this row into `~/.kmet/agent/patch.edn` (preserving any
existing rows and patches), then restart:

```clojure
{:rows [{:id :ext/watcher :plugin :ext/watcher}]}
```

This collection's launcher makes extensions discoverable automatically, but
activation still requires a composition row. Uses the SCI loader; no extra
runtime dependencies. Native Jolt execution has not been verified.

In interactive sessions it starts automatically unless disabled. Save a file:

```python
value = value.strip()  # handle None AI!
# AI? explain why this branch is needed
# AI. context for the next request
```

`AI!` requests focused edits; `AI?` requests an answer (no edits unless requested).
`AI.` provides context but never triggers a turn alone. Case-insensitive markers
must start the comment or end it after whitespace. Supports `#`, `//`, `--`, and
`;`/`;;;` comments, including inline comments. Quoted strings on the same line
are skipped. This is a heuristic, not a language-aware parser: multiline strings,
regex literals and language-specific comment rules can produce false positives.

## Commands

```text
/watcher status
/watcher config
/watcher start
/watcher stop
/watcher scan
/watcher clear
/watcher retry
/watcher global start|stop
/watcher project start|stop
```

Bare start/stop persist project settings. Global changes remain subject to project
overrides. Status/config show effective settings, config paths, queue size, last
trigger and errors. The footer shows on/off/queued/dispatching/error.

Manual scan works even when disabled or in print mode; it can dispatch a prompt.
Live watching does not start in print mode. Clear resets the pending queue and
in-memory processed ledger, allowing unchanged markers to trigger again. Retry
releases the last batch's ledger entries and rescans current files: it never sends
stale snippets and cannot restore comments already removed by cleanup.

## Configuration

Precedence: defaults → `<agent-dir>/watcher.json` →
`<cwd>/.kmet/extensions/watcher.json`. Agent dir comes from kmet (normally
`~/.kmet/agent`, honoring `KMET_CODING_AGENT_DIR`). Files are JSON; start/stop
preserve other keys. Configuration is read on session start/reload and start/stop.

```json
{
  "enabled": true,
  "scanOnStart": true,
  "roots": ["."],
  "include": ["**/*"],
  "ignore": [
    "**/.git/**", "**/.jj/**", "**/.kmet/**", "**/.pi/**",
    "**/.agents/**", "**/node_modules/**", "**/dist/**",
    "**/build/**", "**/.cache/**"
  ],
  "maxFileBytes": 1048576,
  "debounceMs": 300,
  "pollMs": 1000,
  "contextLines": 12,
  "maxPromptBytes": 60000,
  "marker": "AI",
  "removeHandledMarkerComments": true
}
```

Arrays replace defaults (they do not append). Globs support `*`, `**`, `?` and
`**/` matching zero or more directories, not brace expansions or negation.
Requests queue until the agent is idle. Invalid configuration reports an error
rather than silently enabling the watcher. Set roots to `src`/`tests` in large
repositories; polling traverses eligible files each interval. `scanOnStart: false`
suppresses existing marker intents until their comment block changes.

## Queue, cleanup and safety

- Polling replaces Node/chokidar. A stable snapshot must survive `debounceMs`
  before dispatch; typical latency is two polling intervals.
- Busy agents, pending messages, and compaction postpone delivery. Each poll
  rescans, so deleted/changed queued comments do not dispatch stale requests.
- One watcher batch is active at a time. Normalized comment-block intent plus
  relative path suppress repeats for this session. Session changes and reloads
  reset the ledger. Send failures do not mark intents processed.
- Cleanup waits for an `agent-end` followed by an idle/no-pending poll. It reparses
  files and removes only still-matching handled **marker lines**, preserving
  inline code and non-marker comment lines. This is deliberately narrower than
  upstream's block cleanup. The event indicates a finished run, not proof that
  the requested change succeeded; disable cleanup if you want to review markers.
- Cleanup uses a best-effort reread guard, not an atomic filesystem transaction.
  Simultaneous editor writes can still race. Stop pauses scans and cleanup;
  restarting resumes them. Unload/shutdown invalidates polling threads.
- Paths outside cwd, symlinks, oversized files and NUL-containing files are
  skipped. These checks are not a security sandbox against hostile filesystem
  races. `.gitignore` is not consulted.
- Oversized prompt batches remain queued with an error instead of truncating
  instructions and marking unseen requests handled. Narrow roots, remove markers,
  or raise `maxPromptBytes` to unblock.
- Comments and nearby source are sent as a user message to the active model.
  Keep secrets out of snippets, and enable only in trusted worktrees. External
  programs or collaborators able to edit watched files can trigger model turns.

## Validation

```sh
cd extensions/watcher
bb test
# Optional: KMET_CHECKOUT=/path/to/kmet-agent bb test

# From the kmet checkout (includes this extension in discovery):
bb ../kmet-extensions/scripts/startup-smoke.bb
```

Offline tests cover parsing, cleanup, ignore/symlink/binary filtering, idle/pending
queue behavior, stale request removal, suppression, config precedence, dispatch
failure, disabled manual scans and prompt limits. SCI load/unload/reload smoke
was verified against the local kmet checkout. Interactive model execution and
native Jolt have not been end-to-end tested. The upstream Node monitoring scripts
and UI widget are not ported; kmet's status slot and `/watcher status` replace them.
