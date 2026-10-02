# Schedule (work in progress)

External kmet extension port of [pi-schedule](https://github.com/pungggi/pi-schedule)
v0.4.0 (MIT; original copyright notice retained in `LICENSE`). SCI host loading
and unload are verified. Native Jolt execution, subprocess races, and shell
process-tree termination still need verification before production use.

The `schedule` tool manages interval, daily-local-time, and one-shot jobs.
Kinds are `prompt`, `shell`, `notify`, and `message`; actions are `create`,
`list`, `cancel`, `enable`, `disable`, `run_now`, `history`, and `trust`.
See `src/skills/schedule/SKILL.md` for the tool workflow.

## Safety and persistence

- Global jobs live in `<agent-dir>/schedule/schedules.edn`; project jobs in
  `<cwd>/.kmet/schedule.edn`. Runs use `runs.ednl`, trust uses `trusted.edn`.
- Project jobs never auto-fire until their canonical project root is trusted.
  Inspect project schedules before granting trust: shell jobs execute code.
  Explicit `run_now` bypasses that automatic trust gate.
- Shell jobs force `mutate`. Prompt tiers are `read_only`, `suggest`, and
  `mutate`. Strict read-only allows only known read tools plus schedule
  list/history; arbitrary execution tools are blocked.
- Privileges are reserved before submission and activated by the host's
  before-agent-start hook for the exact registered prompt. An unrelated
  turn settling cannot discard a queued task's restriction.
- Persisted shell output is redacted. Transient agent context is not;
  do not assume redaction removes secrets from the session transcript.
- Persistence uses same-directory atomic replacement for stores and trust.
  Mutations read the latest row inside the lock: stale run completion preserves
  a concurrent disable and cannot resurrect a cancelled job. Creation caps
  are checked inside the insertion lock.
  Delivery locks and idempotency records reduce duplicate runs, but delivery
  and persistence are not transactional: this is not an exactly-once service.
- The scheduler only runs while kmet is running; it is not an OS service.

## Development

With the sibling `kmet` checkout available:

```sh
bb test
# From the kmet checkout:
bb lint ../kmet-extensions/schedule/src ../kmet-extensions/schedule/test
bb format-check ../kmet-extensions/schedule/src ../kmet-extensions/schedule/test
```

Tests use offline fake host APIs and artifacts under `target/`. From the kmet
checkout, verify isolated SCI loading without adding the extension to settings:

```sh
bb -e "(require '[kmet.app.extensions :as e]) (prn (e/load-extension! \"../kmet-extensions/schedule/src\")) (e/unload-all-extensions!)"
```

The extension artifact root is `src/` (it contains `extension.edn`), not the
outer development directory. No user settings are changed by this check.
