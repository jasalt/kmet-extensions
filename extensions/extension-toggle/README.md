# Extension toggle for kmet — design and port plan

Port of `npm:@petechu/pi-extension-toggle@0.1.3`, configured in
[`home.nix`](../../lima-default/home-manager/home.nix#L186).

**Accepted design; not implemented.** See [PORT.md](PORT.md) for the
architecture comparison, settings semantics, implementation phases and tests.

## Architecture

Pi's toggle directly constructs Pi package/settings services and computes
filter updates inside the extension. Kmet will instead expose a public
resource capability backed by the same host service as `kmet config`.
The extension owns the picker; core owns discovery and persistence.

## Accepted behavior

- `/extension-toggle` and a floating shortcut manage extensions, skills,
  prompts and themes, with staged selections and apply/cancel.
- A package-level `:enabled` flag supports files, manifest directories and
  multi-resource packages uniformly. Disabled packages remain discoverable.
- Disabling/re-enabling preserves resource filters. Project deltas can
  override the gate or inherit it; `:autoload false` keeps its existing role.
- Saved changes take effect on idle reload or restart, not immediate unload.
- Top-level resources retain individual toggles; bundled extensions use their
  existing settings key. The manager protects itself from accidental disable.

The plan includes core API/resolver changes, `kmet config` integration and
this standalone extension. No Nix changes are planned. Pi's global settings
remain writable but reset on Home Manager activation; project settings are
not provisioned by that module.

Future installation should link this project's **src directory** into an
extension auto-discovery directory. This folder currently contains only
documentation and is not a loadable extension.
