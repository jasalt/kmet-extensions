# kmet extensions

Quick and dirty Pi extension ports for [kmet-agent](https://github.com/kmetia/kmet-agent).

Nine extension directories. Seven have `src/` source and tests (including one
work-in-progress port); two are port *documents* only — no source, not implemented.

| Extension | State | LOC | Summary |
| --- | --- | --- | --- |
| [schedule](schedule/) | Work in progress | 3402 | Interval / daily-local-time / one-shot jobs; kinds `prompt`, `shell`, `notify`, `message`; trust gating and atomic persistence. SCI load verified; native Jolt execution, subprocess races, and shell process-tree termination still unverified. Upstream: [pi-schedule](https://github.com/pungggi/pi-schedule). |
| [imgview](imgview/) | Implemented | 1107 | Displays existing images (no generation). No dependencies or settings. Upstream: [pi-imgview](https://github.com/gregjohnso/pi-imgview). |
| [pins](pins/) | Implemented | 943 | Pins assistant messages and recalls them in a full-width overlay browser; no screen space when unused. Upstream: [pi-pins](https://github.com/s4lv0/pi-pins). |
| [codex-usage](codex-usage/) | Implemented | 467 | Reports Codex usage and allows listing and using banked resets. Port of an upstream TypeScript contrib extension: [pi-codex-usage](https://github.com/jasalt/chatgpt-openai-api-adapter/blob/94f45568b4bd7842b1aef362cc3ba883b1312951/contrib/pi-codex-usage.ts). |
| [nofity-pushover](nofity-pushover/) | Implemented | 455 | One-way `notify_human` alerts via Pushover, plus `/notify-human-test`. Directory name keeps the requested spelling. Custom extension; no Pi upstream. |
| [savelast](savelast/) | Implemented | 308 | One `/savelast` slash command; no dependencies, config, tools, or automatic saves. Upstream: [pi-savelast](https://github.com/atomdmac/pi-savelast). |
| [session-migrate](session-migrate/) | Implemented | 562 | Claude Code → native kmet sessions; inspect/save/import, active-branch tools/images/title/compaction and private manifests. SCI/native Jolt, source and built-artifact import/continuation/reopen verified against latest upstream `a41fe5c`. Upstream: [session-migrate](https://github.com/xhluca/session-migrate). |
| [btw](btw/) | Not implemented | 0 | Port plan only. Direction reviewed and proposed; would require upstream changes. Upstream: [pi-btw](https://github.com/dbachelder/pi-btw). See [PORT.md](btw/PORT.md). |
| [extension-toggle](extension-toggle/) | Not implemented | 0 | Port plan only. Direction accepted, but it depends on a package-level `:enabled` switch and resource-management capability that kmet does not have yet. Upstream: [pi-extension-toggle](https://www.npmjs.com/package/@petechu/pi-extension-toggle). See [PORT.md](extension-toggle/PORT.md). |

## Layout

Each implemented extension is a standalone project: `bb.edn`, `src/` (the
artifact root passed to `kmet install`), `test/`, `scripts/`, `README.md`, and
occasionally `LICENSE` retaining upstream attribution.

## Install

```sh
bb start install ../kmet-extensions/<name>/src    # from the kmet checkout
```

Add `--local` for project scope, then restart kmet or run `/reload`.