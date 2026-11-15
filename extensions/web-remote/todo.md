# web-remote todo

## Input history (deferred)

The TUI editor keeps an in-memory ↑/↓ history: prompts it submits, plus the
session branch's user messages when a session is resumed (empty ones skipped,
consecutive duplicates collapsed, newest 100 kept). The web composer has no
history yet.

Options:

- **A. Web ↑/↓ from the session branch (no core change).** Build the list
  from `(:get-branch (ext/session api))` user messages with the same rules,
  serve it as a signal (or a `/history` fetch) and walk it with ↑/↓ when the
  caret is on the first/last line, as the TUI does. Includes prompts typed in
  the TUI, since they land in the session. ~25 lines.
- **B. Web prompts in the TUI's ↑/↓ (needs a core change).** No extension
  hook adds to the TUI editor's history: `send-user-message` does not, unlike
  the TUI's own submit (`editor-add-to-history!` in
  `kmet.ui.tui.interactive.turn`). Either add a `ui-add-to-history`
  capability to `kmet.extension` or have `send-user-message` record the text
  in the active editor's history. Until then a web prompt reaches the TUI's
  history only when the session is reloaded (history is rebuilt from the
  branch).
- **C. Both,** sharing one list: A for the browser, B so the TUI sees web
  prompts immediately.
