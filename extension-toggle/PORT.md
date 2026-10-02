# Design and port plan: pi-extension-toggle → kmet

**Accepted direction; not implemented.** Add a package-level `:enabled`
switch and a public resource-management capability in kmet, then implement
this standalone extension in `../kmet-extensions/extension-toggle/`.
The API names below are proposed contracts, not existing callable functions.
This document preserves the original review evidence and specifies the
implementation plan. No Nix or runtime source changes accompany this plan.

## 1. Configuration reviewed

[`home.nix`](../../lima-default/home-manager/home.nix#L186) includes
`npm:@petechu/pi-extension-toggle@0.1.3` in `clanker.agent.packages`.
`npm:pi-toggle-skills@0.1.16` is a separate adjacent package; it is not part
of this port. No toggle-specific configuration was found there.

The [Home Manager flake](../../lima-default/home-manager/flake.nix) imports
`clankerfiles.homeManagerProfiles.browser`. The shared
[agent module](../../clankerfiles/home-manager/modules/agent.nix) generates
Pi settings from `clanker.agent.settings`, replacing its `packages` key
with the declared nonempty package list. It deliberately leaves upstream
`programs.pi-coding-agent.settings` unset.

The [provisioner](../../clankerfiles/home-manager/lib/provision-settings.nix)
installs settings as a regular mode-0600 file, replacing it on activation.
Consequently:

- Pi can persist toggle changes during normal use.
- Global toggle changes survive Pi restarts but reset on Home Manager
  activation. The provisioner does not manage project `.pi/settings.json`.
- The package-list option is a list of strings, not filtered package objects.
  Declaratively preserving package-level disabled states would need a separate
  Nix design; this review does not change that ownership policy.

The flake locks clankerfiles at
`92e1630fd956782a274ee77d4f39edf9ff5fbdd7`. The sibling checkout has a newer
HEAD, but the two implementation files above match the pinned revision.
No activation or Nix build was run.

## 2. Pi behavior to preserve

The installed package identifies itself as `@petechu/pi-extension-toggle`
version `0.1.3`, MIT-licensed. Reference files are `index.ts`, `utils.ts`,
`README.md`, and `tests/` under
`~/.pi/agent/npm/node_modules/@petechu/pi-extension-toggle/`.

| Behavior | Reference implementation |
| --- | --- |
| Resources | Extensions, skills, prompts, themes, including disabled discovered items |
| Rows | One row per package source; individual top-level local resources |
| Checked state | Checked when **any** resource in the row is enabled |
| Search | Labels, source, paths, scope, type and metadata; token filtering followed by fuzzy ordering |
| Input | Arrows navigate; Space toggles; Enter applies; Backspace/Delete edit; Ctrl+U clears; Esc clears query before canceling; `?` opens help |
| Pending state | Changes survive filtering and floating-window hide/show; cancel writes nothing |
| Persistence | Only rows whose final checkbox differs from the initial state are written |
| Normal packages | Disable writes empty filters for all four types; enable removes those filters |
| Top-level resources | Exact `-path`/`+path` entries, relative to the owning settings root |
| Self-protection | Excludes manager-like sources/paths from the picker |
| Command | `/extension-toggle` waits for idle, uses a docked picker, then offers reload after saving |
| Shortcut | `Ctrl+Shift+E` opens or hides/shows a floating picker; saves without offering immediate reload |

Important qualifications from the source:

- The floating shortcut does **not** call `waitForIdle` and receives no reload
  callback. Do not infer identical lifecycle behavior from the README prose.
- Switching from the docked picker to floating closes the former and creates
  a fresh run; only floating hide/show demonstrably retains its selections.
- Package groups are keyed by raw source string, not scope plus canonical
  identity. A kmet port should not reproduce possible cross-scope collisions.
- Enabling a package clears previous resource filters; this is not a
  reversible snapshot of a partially enabled package.

## 3. Baseline kmet support and gaps (before implementation)

### Reuse the backend, not the CLI screen

[`kmet.app.packages`](../../kmet/src/kmet/app/packages.clj) owns unified
resource resolution and settings mutation. The
[`kmet config` screen](../../kmet/src/kmet/app/ui/resource_config.clj)
already handles packages, top-level resources, bundled extensions, and
user/project views. It applies individual resource changes immediately and
supports project inherit/load/unload states; Pi's grouped, staged picker is
not simply that screen with a new command name.

Resolution is local-only: remote npm/Git package sources are skipped with a
warning. This port does not make Pi's TypeScript packages loadable in kmet.
Kmet uses EDN settings/themes and Clojure extension artifacts.

### Public API boundary

The extension contract exposes commands, shortcuts, custom UI, identity,
notifications, and command-context idle/reload operations. It does **not**
expose resource discovery or settings toggling. `get-loaded-extensions` is
an internal host function and would omit disabled resources and other types
anyway.

An actual isolated-loader probe rejects an extension requiring
`kmet.app.packages`. Keep business logic in the host and expose explicit
capabilities through `kmet.extension`; do not whitelist app internals or
copy the resolver into the extension.

### Persistence differences that require tests

1. **Single-extension packages ignore resource filters.** A `:packages`
   source pointing to a file or `extension.edn` directory resolves enabled
   even when all four filters are empty. Existing config rows say “always
   loaded.” This does not mean a similar artifact discovered through an
   auto-extension directory is untoggleable.
2. **Project deltas are not normal packages.** For `:autoload false`, empty
   filters contribute no overrides and therefore inherit the user state.
   Never apply Pi's empty-filter disable operation to such an entry, or
   collapse it to a plain source string. Preserve its delta semantics.
3. **Plain top-level paths seed discovery.** Replacing the only explicit
   file path with `-path` removes it from the resolver's inventory altogether.
   Preserve the discovery entry and append/replace only override entries so
   the disabled resource remains available to re-enable. The current
   `apply-top-level-toggle!` helper also needs attention here; reuse alone
   does not fix this edge case.
4. **An explicit manifest directory is not its own container.** In the
   probed `:extensions [artifact-root]` case, directory scanning does not
   yield the manifest root itself. Prefer linking the future `src/` artifact
   into an auto-extension directory. Do not copy that alternative install
   instruction from the sibling codex-usage README without a host fix.
5. **Bundled items use a different key.** They are controlled by
   `:bundled-extensions`, not `:packages` or top-level `:extensions`.
6. **Scope and paths belong to the host.** Resolve relative paths against
   the agent directory or launch-project `.kmet`, not necessarily `ctx.cwd`
   after switching sessions. Preserve canonical identity, precedence and
   normalized path separators.

The current interactive context returns false for `:is-project-trusted`,
while normal loading and config resolution include project resources. That
callback is not a usable permission gate for this feature as-is. The bridge
must explicitly match the host's resource-scope policy rather than silently
hiding all project resources or inventing a separate trust system.

## 4. Recommended host capability

### Pi architecture reference and kmet adaptation

[Pi](https://github.com/earendil-works/pi) provides reusable host services.
The reviewed toggle v0.1.3 imports `DefaultPackageManager` and `SettingsManager`
from `@earendil-works/pi-coding-agent`, constructs them inside the extension,
and computes settings changes itself. Its UI uses `pi-tui`; reload goes through
the command context. This describes the installed extension's integration,
not a fresh audit of upstream Pi main.

Retain Pi's separation of discovery, selection and persistence, but adapt the
service boundary to kmet's capability-based extension contract:

```text
Pi toggle → directly constructed package/settings services
kmet toggle → public resource capability → host resource/settings service
                                            ↑
                                       kmet config
```

The kmet extension owns UI and staged user intent; the host owns discovery,
package gates, scope rules and safe persistence. This avoids importing app
internals, keeps both interfaces consistent, and preserves filters through the
new `:enabled` gate rather than copying Pi's destructive filter replacement.

These names and shapes are **proposed**, not current callable APIs:

```clojure
(ext/resource-snapshot api)
;; => {:revision opaque-token :sources [source-descriptor ...]}

(ext/apply-resource-changes! api
  {:revision opaque-token
   :changes [{:id opaque-source-id :enabled false}]})
;; => {:changed [...] :errors [...] :reload-required? true}
```

The host should return data-only descriptors: stable identity, owning/write
scope, origin, label, resource types/paths, effective enabled state, mixed
state, and toggleability with a reason. Include disabled resources, but not
arbitrary settings contents, credentials, internal records or executable
callbacks. Use scope and resolved identity rather than display strings as
keys. Resolve manager identity from the calling extension's artifact path;
protect the whole owning package if toggling it would disable the manager.

The host apply operation should:

- Accept only identities from a valid snapshot; re-read and validate affected
  settings before applying. Reject stale targets instead of guessing.
- Toggle the package-level `:enabled` gate for every package shape, preserving
  all resource filters and unrelated metadata. Follow section 8's scope rules.
- Handle project delta entries with explicit gate overrides, never
  normal-package empty-filter semantics. Display the owning scope and
  distinguish inherited state from an explicit project override.
- Preserve explicit discovery paths for top-level resources, and use existing
  bundled-name semantics for bundled resources if included in version one.
- Support single-file, archive and manifest-directory packages through the
  same gate as container packages; do not write ineffective resource filters.
- Merge under the settings file lock, preserving unrelated keys/comments
  where possible. Existing read-then-save helpers are not a multi-resource
  transaction. Do not overwrite malformed settings as an empty map.
- Report per-scope failures and partial success honestly. Two settings files
  are not one atomic transaction. Do not reload after a failed apply without
  explaining what was saved.

Keep the implementation in the application layer. `packages` already depends
on `extensions`, so adding a reverse eager require creates a cycle: inject
host capability callbacks at the composition boundary (or extract the
resource service cleanly). Extend the nullable test API and extension
contract documentation in the same change. Older hosts should produce an
explicit unsupported-capability message rather than fall back to raw writes.

## 5. Picker and lifecycle design

Use `kmet.tui` Hiccup/fn components and keyed rows, following
[`tui.md`](../../kmet/src/kmet/tui/tui.md). Keep search, selected identity,
initial states and pending changes in one model independent of the visible
row order. Package checkboxes represent the package gate, not Pi's “any
resource enabled” convention. Show effective resource counts and mixed or
fully filtered status separately; an enabled package may have zero enabled
resources. Top-level and bundled checkboxes represent resource enabled state.

`ext/ui-custom` supplies a promise and a close callback. Floating handles
support hide/show and focus; reuse the same component while hidden. Keep help
inside the owned component or manage a separate overlay explicitly: invoking
another `ui-custom` replaces the current custom dialog rather than simply
stacking a second owned dialog. Account for kmet's default overlay border and
background when sizing a small terminal.

Register shortcuts once the interactive registry exists, such as on
`:session-start`, replacing the previous registration so `/new` does not
accumulate callbacks. Startup loads extensions before that registry is
installed. Handle the shortcut inside the focused picker as well as at the
editor level. Test terminals that cannot distinguish Ctrl+Shift+E; the slash
command remains the fallback.

Never dereference UI/idle promises on the input thread. Use the supported
`kmet.libs.concurrent/spawn` worker with cancellation and a generation token;
shutdown, dialog replacement and reload must invalidate outstanding work.
The current custom-dialog reset/disposal path must not be assumed to resolve
every pending promise. Stop workers without waiting indefinitely on it.

For Pi parity, the command waits for idle and offers reload after saving;
the floating path saves and instructs the user to run `/reload`. Invoke the
command context's reload flow, not `reload-extensions!`: the former refreshes
all four resource types and the system prompt. Recheck idleness before
reload, since the user can resume activity after opening the picker.

## 6. Suggested standalone layout

```text
extension-toggle/
  README.md
  PORT.md
  bb.edn
  src/extension.edn
  src/extension_toggle/core.clj
  src/extension_toggle/model.clj
  src/extension_toggle/ui.clj
  test/extension_toggle/...
  scripts/smoke.bb
```

The future manifest would use `:name "extension-toggle"`,
`:entry extension-toggle.core`, and `:loader [:jolt :sci]`. Installation should
link this project's **src directory** into `~/.kmet/agent/extensions` (honoring
`KMET_CODING_AGENT_DIR`) or `.kmet/extensions`. No manifest is added yet, so
this documentation directory cannot be accidentally loaded as an extension.
Preserve upstream MIT attribution/license notices if source is adapted.

## 7. Validation and evidence

Executed offline with Babashka:
`bb target/extension-toggle-review/probe.bb` from the kmet checkout. Its
scratch fixtures and script are ignored review artifacts, not shipped tests.
Assertions passed for:

- empty filters leaving single-file and manifest packages enabled;
- empty filters disabling resources in an ordinary container package;
- exact exclusions disabling auto-discovered and explicitly included files;
- removing the plain explicit path removing that item from discovery;
- an explicit manifest root not appearing as its own discovered artifact;
- empty project delta filters inheriting the enabled user resource;
- an actual extension load rejecting `kmet.app.packages` imports.

No live settings were written by the probe. No Pi tests, Jolt execution,
real-terminal interaction or Home Manager activation was performed; Node and
Jolt were not available on this shell's PATH. The inspected kmet checkout was
at `84fac16b` with pre-existing source/test changes, which this review leaves
untouched. Findings describe that working tree, not a pristine release.

Implementation acceptance should cover:

- Pure model tests: grouping across scopes, mixed states, self-protection,
  search, hidden-row selection, toggling back to the initial state, cancel.
- Host tests: package gates across all artifact shapes, preserved resource
  filters, project gate overrides, explicit paths, bundled state, stale
  snapshots, malformed settings, concurrent edits, permission failures and
  partial multi-scope writes.
- Isolated SCI smoke: load, command/shortcut registration, missing capability,
  fake resource apply, unload and reload cleanup. Register any new core test
  namespaces with the host runner.
- PTY/terminal tests: docked/floating flows, hide/show/help, narrow sizes,
  busy agent, repeated session starts, replacement dialogs and unload.
- Changed-file tests/lint/format for core changes; standalone extension tests,
  targeted lint/format and artifact packaging. Run Jolt checks separately when
  available; do not claim native support based solely on the manifest.

## 8. Accepted package gate design

The user accepted replacing the “always loaded” limitation with an explicit
package-level switch. The port intentionally differs from Pi: disabling and
re-enabling a package preserves its resource selection instead of clearing
filters. `:autoload false` retains its existing meaning; it is not repurposed.

### Settings and effective state

```clojure
{:packages
 [{:source "/path/to/my-extension" :enabled false}
  {:source "/path/to/resource-package"
   :enabled false
   :skills ["!experimental/**"]}]}
```

- A plain string package is enabled. In an ordinary object entry, absent
  `:enabled` means true; present values must be booleans. Reject invalid
  values explicitly rather than treating arbitrary truthy values as enabled.
- The gate applies uniformly to single files (including archives), manifest
  directories, and multi-resource container directories.
- Resolve the package and its filter-derived resource states even when the
  gate is off. Final resource state is `package-enabled AND filter-enabled`.
  Single-extension sources retain their existing filter behavior, but their
  final state is now controlled by the package gate.
- Apply existing canonical-path precedence before skipping disabled items
  during loading. A disabled winning source must not fall through to an
  enabled duplicate from a lower-priority layer. Do not let a package gate
  disable an independently winning top-level resource just because paths match.
- Keep configured package descriptors separate from discovered resource rows.
  An empty, missing or unsupported source remains listed with its gate and a
  diagnostic, even if it produces no resources. Listing must neither install
  packages nor execute extension initialization.
- Enabling removes only the disabled gate in an ordinary entry (canonical
  default true); disabling writes `:enabled false`. Collapse an object to a
  string only when no other metadata remains. Never erase filters or unrelated
  fields to simplify the entry.

### Project scope and overrides

Use the same canonical package identity and source normalization as existing
package deduplication. A normal project package remains a replacement for the
same-identity user entry: absent gate means true, as for any ordinary entry.

A project `:autoload false` entry remains a delta over a user package:

| Project delta gate | Effective gate |
| --- | --- |
| absent | Inherit user gate |
| `false` | Disabled in this project |
| `true` | Enabled in this project, even if globally disabled |

Resource-filter deltas continue to compose as before and are applied
independently of the gate. An explicit project true gate does not remove
resource exclusions. With no user base, preserve current delta resource
resolution and use true as the default gate; do not broaden resource discovery.

“Reset to inherit” removes only `:enabled` from a project delta. Remove a
redundant delta only when it contains no remaining overrides or unrelated
metadata. Existing filter-edit cleanup must retain gate-only entries rather
than drop them or convert them to ordinary packages.

The main picker writes to each row's owning settings entry by default.
Inherited user rows explicitly say that a default edit affects all projects.
Provide a project-only override action for inherited packages with
inherit/enabled/disabled choices; it creates or updates a normalized
`:autoload false` delta. Display the write scope separately from effective
state. No project change silently edits global settings.

### Shared resource API refinements

Section 4's snapshot should distinguish package `:enabled` (effective gate),
`:override-state` (inherit/enabled/disabled), resource effective counts and
filter-derived states. Source IDs include origin, identity and scope, not
just labels. Include write-target descriptors for permitted project overrides.

Ordinary checkbox changes retain `{:id id :enabled boolean}`. An explicit
project action uses `{:id project-target-id :state :inherit|:enabled|:disabled}`;
these are alternative request forms, not conflicting fields on one change.
The host validates targets against the snapshot and returns updated state.
Package-gate changes count as saved changes even when all resources remain
filtered out. An empty change batch performs no writes and requests no reload.

Self-protection is caller-specific UI policy, not a security sandbox. The
extension bridge rejects changes that would disable the calling manager's
winning resource or owning package. The standalone `kmet config` command can
still deliberately disable that package because it does not depend on the
manager remaining loaded.

### Runtime behavior and compatibility

Saving changes never unloads a running extension immediately. Until a full
idle `/reload` or restart, existing registrations remain active. Reload uses
normal shutdown/unload cleanup, then loads only enabled resolved resources.
Background work remains each extension's cleanup responsibility; this is not
a forced thread-kill mechanism. UI must distinguish “saved; reload required”
from “applied to this running session.”

Existing settings without the new key retain their behavior. Existing empty
resource filters remain empty: enabling the gate will not undo them. Explain
“enabled, all resources filtered out” and direct users to resource controls.
Do not automatically migrate empty filters into package-disable flags.

Older kmet versions do not honor this new key and may load disabled packages.
Document the minimum supporting host version at release, and require the
new capability version before loading the functional picker. That capability
check cannot make downgrading the host safe; warn explicitly in setup docs.

## 9. Implementation sequence and acceptance gates

### Phase 1 — Host resolution and persistence

Work primarily in `src/kmet/app/packages.clj` and settings persistence helpers.
Introduce a package-source descriptor and effective-gate computation; preserve
resource metadata needed to explain disabled versus filtered states. Update
all package mutation/cleanup paths to preserve `:enabled` and unknown fields.
Fix explicit top-level path preservation alongside the shared mutation layer.

Add tests in `test/kmet/app/test_packages.clj` (and focused settings tests):
missing/true/false/invalid gates; file/archive/manifest/container sources;
missing and empty sources; duplicate precedence; global disable/project enable;
project disable/inherit reset; ordinary project replacement; orphan deltas;
filter editing with gate-only deltas; filtered-state restoration across off/on.
Verify resolution does not execute disabled extensions.

Deliverable: resolver and settings operations with backward-compatible defaults
and no live-settings writes in tests. The earlier review probe is baseline
evidence only; convert relevant cases into maintained regression tests.

### Phase 2 — Shared service and public capability

Implement snapshot/apply in the application layer, with callback injection
avoiding the packages/extensions require cycle. Wire it through
`src/kmet/extension.clj`, `src/kmet/app/extensions.cljc` and the host composition
boundary; include capability versioning and nullable fixtures. Reuse the same
mutation service from `kmet config`, not a second implementation.

Validate and transform each settings file under its lock; check stale snapshot
state there, not only before locking. Preserve unrelated edits and reject
malformed files. Preflight the batch before writing; report per-scope results
if a later write fails. Tests cover no-op, stale targets, protected manager,
invalid requests, unrelated edits, lock/write errors and partial success.

Document contracts in `src/kmet/extension.md`; update the application guide
only if composition/layer rules change. Register new core test namespaces.

### Phase 3 — `kmet config` integration

Update `src/kmet/app/ui/resource_config.clj` with a package-level gate control,
effective resource counts and project inherit/enabled/disabled state. Replace
“always loaded” for single-extension packages with the real gate control.
Keep per-resource filters independently editable and explain when a disabled
package masks them. Re-read through the shared service after writes.

Deliverable: package switching works without installing the new extension,
with tests for scope selection, retained filters and single-artifact packages.
Document settings and config behavior in user-facing `docs/`.

### Phase 4 — Standalone extension

Create the layout in section 6. Implement a pure picker model, Hiccup UI and
command lifecycle using only public host capabilities and shared libraries.
Cover package gate checkboxes, individual local resources, bundled resources
through their existing name-based controls, and explicit project overrides.

Preserve search, staged apply/cancel, help, manager self-protection and floating
hide/show. Use the runtime behavior in section 5; no automatic reload from an
agent-loop callback. Tests must prove cleanup across `/new`, dialog replacement,
reload and unload. Keep commands and fixtures offline in the SCI smoke script.

### Phase 5 — Integration, documentation and packaging

Run changed-file test/lint/format gates for core changes and targeted standalone
extension gates; do not substitute whole-repository suites during routine
iteration. Add real terminal and Jolt checks when those environments exist,
recording any unverified platform behavior. Exercise an extension that registers
a command, tool and shortcut: disable/save leaves them active; reload removes
them; re-enable/reload restores them without duplicate registrations.

Update this README with installation, minimum host capability/version, package
versus filter semantics, project overrides and reload behavior. Pack the
artifact only after loader smoke tests pass. Preserve upstream license notices
for adapted source. No Nix provisioning changes, remote-package support or
immediate hot-unload are included in this plan.
