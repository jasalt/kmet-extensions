# pins for kmet

Port of [s4lv0/pi-pins](https://github.com/s4lv0/pi-pins) (referenced at the
cloned commit under kmet's `target/reference/pi-pins-source`). Pin assistant
messages and recall them in a borderless full-width overlay browser, without
taking permanent screen space. No tools, settings, or external dependencies.
Upstream's MIT license is retained in `LICENSE`.

## Commands

| Command | Effect |
| --- | --- |
| `/pin [label]` | Pin the latest nonempty assistant text (free-text label, auto-generated if omitted) |
| `/pin pick` | Interactively pick one of the ten latest assistant texts |
| `/pin show [n]` | Open the pin browser, optionally preselecting `#n` |
| `/pin list [n]` | Alias for `show` |
| `/pin rm <n>` | Remove pin `#n` |
| `/pin clear` | Remove all pins (IDs restart at 1) |
| `/pin help` | Show instructions in the chat (never opens a browser — an upstream quirk, fixed) |

Subcommands are case-insensitive and offer tab completion. `/pin` followed by
any other text is a free label. A no-arg subcommand followed by more text is
also a free label (upstream quirk, kept).

## Viewer keys

| Key | Action |
| --- | --- |
| `↑` / `↓` | Scroll content line by line |
| `PgUp` / `PgDn` | Switch between pins |
| `g` / `G` | Jump to top / bottom of content |
| `q` / `Esc` / `Enter` | Close the overlay |

## Storage

Pins are stored as `pin-state` custom entries in the session file itself, so
they survive `/reload`, restarts, and `/resume`, and follow session tree
branches: each branch restores the snapshot last saved on it, and a fresh
branch starts from the pins inherited at its fork point. Custom entries never
enter the model context — pinning adds nothing to the transcript the model
sees.

The last snapshot on the branch wins. A corrupt saved snapshot fails visibly
(an error notification, no state change) rather than silently discarding your
pins. A missing or stale `next-id` is repaired above the highest existing id.

Only assistant **text** is pinned (string content or text blocks); thinking,
tool calls, and image attachments are excluded. Labels are auto-generated from
the first nonblank line (markdown markers stripped, 42 chars) when not given.

## Viewer

The browser is a modal overlay: full terminal width with zero side padding and
no outer border, 80% of the terminal height anchored to the top. The pin list
and content are rendered with kmet's shared Markdown renderer, so headings,
code, and table borders render exactly like the chat transcript. The immutable
pin snapshot is captured when the modal opens, so pins cannot change mid-visit.
The picker (select-list) returns the chosen candidate by numeric identity,
not its possibly duplicated preview label.

`/pin show` and `/pin pick` require interactive TUI mode; in print/headless
runs they warn instead, while the mutating commands (`/pin`, `rm`, `clear`)
still work. A live modal is closed on session switch/tree events and on
extension shutdown.

## Install

From the sibling **kmet checkout** (no `kmet` executable required):

```sh
bb start install ../kmet-extensions/pins/src
```

With an installed executable, the equivalent is
`kmet install /absolute/path/to/pins/src`. Add `--local` for project scope.
`src/` is the extension artifact root, not the project directory. Restart
kmet or run `/reload`.

For live development, symlink `src/` into `~/.kmet/agent/extensions/pins` or
the project's `.kmet/extensions/pins`. The global directory honors
`KMET_CODING_AGENT_DIR`. The manifest declares native Jolt and SCI loaders.

## Development

From this extension directory, using the sibling `../../kmet/src` checkout:

```sh
bb test-changed
bb test pins.model-test pins.ui-test pins.core-test
```

Tests are offline (no network, no sleeps); disposable fixtures live under
`target/`. New test namespaces belong in this project's `bb.edn`.

From the sibling kmet checkout:

```sh
bb ../kmet-extensions/pins/scripts/smoke.bb
bb lint ../kmet-extensions/pins/src ../kmet-extensions/pins/test ../kmet-extensions/pins/scripts ../kmet-extensions/pins/bb.edn
bb format-check ../kmet-extensions/pins/src ../kmet-extensions/pins/test ../kmet-extensions/pins/scripts ../kmet-extensions/pins/bb.edn
bb pack-extension ../kmet-extensions/pins/src target/pins.jar
bb ../kmet-extensions/pins/scripts/smoke.bb target/pins.jar
```

The smoke script exercises the real isolated SCI loader and registries,
command flows, modal geometry, branch-local persistence, reload, and
deregistration — all offline. Actual interactive terminal rendering (real
key delivery, theme, resizing) and native Jolt loading require their
respective environments and are not verified by the Babashka tests.
