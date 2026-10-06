# session-migrate

Independent Claude Code → **kmet** session importer, adapted from
[xhluca/session-migrate](https://github.com/xhluca/session-migrate) and the
[Go/PiG port](https://github.com/jasalt/pig-extensions/tree/main/extensions/session-migrate).
No Python dependency, core patch, background job, tool or automatic
migration. `src/extension.edn` prefers native Jolt, with SCI fallback.

Verified against latest upstream kmet at validation time:
`a41fe5c01f2c115ea42fce4ea9764b9bc563a810`, Babashka 1.13.225 and Jolt 0.8.18,
on Linux x86_64. See [validation](docs/validation.md) and
[provenance / behavior differences](docs/provenance.md).

## Install

From a kmet checkout:

```sh
bb start install /absolute/path/to/kmet-extensions/session-migrate/src
# or: jolt start install /absolute/path/to/kmet-extensions/session-migrate/src
```

Restart kmet or run `/reload`. The installable `src/` includes the upstream MIT
license. Loading only registers the command; it does not read a transcript or
change the active session.

## Use

```text
/session-migrate inspect claude /absolute/path/session.jsonl
/session-migrate save claude "/path with spaces/session.jsonl"
/session-migrate import claude 73fea258-9467-4a17-877b-ef6bcd0898b7
```

- **inspect**: validate and display a multiline, content-free hash/count report
  in the scrollable conversation using `ui-chat-info` (like `codex-usage`). It is
  UI-only: never sent to the model or persisted across restarts. Noninteractive
  mode retains JSON notification output. No writes, switch or model call.
- **save**: create a fresh native `.ednl` session and `.ednl.migration.json`
  manifest, without changing the active session.
- **import**: save, then await the public interactive `:switch-session`
  capability. Cancelled switches retain the saved files and report their path.
  Noninteractive import is refused; use `save` and resume the resulting file.

Paths are literal, relative to the current runtime cwd, with `~` expansion;
optional matching outer quotes are removed. No shell evaluation or globbing of
path arguments. UUID lookup searches `CLAUDE_CONFIG_DIR/projects/*/<uuid>.jsonl`,
or `~/.claude/projects/*/<uuid>.jsonl`; zero/multiple matches are refused.

The destination uses the public API's **agent directory and current cwd**, with
kmet's default `sessions/--cwd--/` layout. The source cwd is not adopted. The
current extension API has **no configured session-directory getter**: this
extension does **not honor a custom `--session-dir`**. Use the reported path with
`--session <path>` or kmet's built-in `/import` if you want another storage root.

## What carries over

- Active UUID ancestry, not flattened append order; inactive forks excluded.
- User/assistant text, historical tool calls/results, embedded base64 images,
  explicit/AI title, and Claude compact-summary context.
- Fresh native session/entry identities and parent links. Compactions use a
  self-anchored `:first-kept-id`, so pre-compaction history stays in the file but
  is not replayed to the provider.

Private/signed thinking, documents, remote images, privileged configuration,
attachments and unknown metadata/content are omitted and counted. Recorded tools
are **history only**: import never executes them, installs capabilities or calls
a provider. An explicit subsequent user message is a normal kmet turn, using
kmet's existing configuration, permissions and tools.

This is **Claude → kmet only**, not upstream's 18-format adapter/export matrix,
catalog, Python CLI or bidirectional migration.

## Safety and native-format limits

- Requires idle/no pending messages for `save`/`import`. Concurrent/reentrant
  migrations are refused; unload cancels bounded processing per registration.
- One bounded source snapshot: 256 MiB total, 32 MiB per record, 256 JSON nesting
  levels; strict raw UTF-8, JSON objects with no trailing data, graph and
  completed-tool validation. Orphan/duplicate/unresolved tool exchanges are
  refused, never synthesized.
- Private `0700` staging; both outputs `0600`; complete files published with
  **no-replace hard links**. Ordinary failures roll back newly published files.
  Source and existing destination files are never overwritten.
- Publication needs POSIX permissions and hard-link support. The file pair is
  **not crash-atomic**. The portable bb/Jolt writer does **not fsync**; unlike the
  Go port, power-loss durability is not guaranteed.
- Manifest contains hashes, counts, target identity and format only — not source
  paths, titles, prompts, reasoning, arguments or tool output. Its target hash
  describes the **initial file**; kmet may append entries after opening it.
- kmet separates tool-result text from images and assistant content from calls:
  cross-media/inter-call interleaving is not lossless. Tool-result layout
  normalization is counted. Its public JSON boundary converts fractional/exponent
  arguments to binary floating point; these are counted as
  `tool-argument-float-normalized`, and nonfinite values are refused. Integral
  arguments retain the host JSON reader's arbitrary-precision representation.
- Importing a complete branch does not certify that it is safe to trust. Review
  `inspect` first. Treat subsequent continuation as untrusted historical input.

## Tests

```sh
KMET_SOURCE_ROOT=/path/to/clean/kmet scripts/check.sh bb
KMET_SOURCE_ROOT=/path/to/clean/kmet JOLT_BIN=/path/to/jolt scripts/check.sh jolt
python3 scripts/integration.py --kmet-source /path/to/clean/kmet
python3 scripts/integration.py --kmet-source /path/to/clean/kmet \
  --host jolt --executable /path/to/jolt
```

`check.sh` uses isolated homes/dummy configuration; integration uses a real PTY,
real extension loader/session switch, and a loopback-only dummy provider. It also
supports `--artifact <kmet.jar|native-kmetj>` and `--output <new-directory>` for
built-artifact tests and retained evidence. Python is **test-only**. Source-mode
host dependency resolution may fetch kmet's pinned build dependencies, but no
vendor model, real credentials, alerts or reset redemption are used.
