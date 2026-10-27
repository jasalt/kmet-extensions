# kmet extensions

Quick and dirty Pi extension ports for [kmet-agent](https://github.com/kmetia/kmet-agent).

Nine extension directories. Seven have `src/` source and tests (including one
work-in-progress port); two are port *documents* only — no source, not implemented.

| Extension | State | LOC | Summary |
| --- | --- | --- | --- |
| [schedule](extensions/schedule/) | Work in progress | 3402 | Interval / daily-local-time / one-shot jobs; kinds `prompt`, `shell`, `notify`, `message`; trust gating and atomic persistence. SCI load verified; native Jolt execution, subprocess races, and shell process-tree termination still unverified. Upstream: [pi-schedule](https://github.com/pungggi/pi-schedule). |
| [imgview](extensions/imgview/) | Implemented | 1107 | Displays existing images (no generation). No dependencies or settings. Upstream: [pi-imgview](https://github.com/gregjohnso/pi-imgview). |
| [pins](extensions/pins/) | Implemented | 943 | Pins assistant messages and recalls them in a full-width overlay browser; no screen space when unused. Upstream: [pi-pins](https://github.com/s4lv0/pi-pins). |
| [codex-usage](extensions/codex-usage/) | Implemented | 467 | Reports Codex usage and allows listing and using banked resets. Port of an upstream TypeScript contrib extension: [pi-codex-usage](https://github.com/jasalt/chatgpt-openai-api-adapter/blob/94f45568b4bd7842b1aef362cc3ba883b1312951/contrib/pi-codex-usage.ts). |
| [nofity-pushover](extensions/nofity-pushover/) | Implemented | 455 | One-way `notify_human` alerts via Pushover, plus `/notify-human-test`. Directory name keeps the requested spelling. Custom extension; no Pi upstream. |
| [savelast](extensions/savelast/) | Implemented | 308 | One `/savelast` slash command; no dependencies, config, tools, or automatic saves. Upstream: [pi-savelast](https://github.com/atomdmac/pi-savelast). |
| [session-migrate](extensions/session-migrate/) | Implemented | 581 | Claude Code → native kmet sessions; inspect/save/import, active-branch tools/images/title/compaction and private manifests. SCI/native Jolt, source and built-artifact import/continuation/reopen verified against latest upstream `a41fe5c`. Upstream: [session-migrate](https://github.com/xhluca/session-migrate). |
| [btw](extensions/btw/) | Not implemented | 0 | Port plan only. Direction reviewed and proposed; would require upstream changes. Upstream: [pi-btw](https://github.com/dbachelder/pi-btw). See [PORT.md](extensions/btw/PORT.md). |
| [extension-toggle](extensions/extension-toggle/) | Not implemented | 0 | Port plan only. Direction accepted, but it depends on a package-level `:enabled` switch and resource-management capability that kmet does not have yet. Upstream: [pi-extension-toggle](https://www.npmjs.com/package/@petechu/pi-extension-toggle). See [PORT.md](extensions/extension-toggle/PORT.md). |

## Layout

Individual extensions live under `extensions/<name>/`, including the two
port-plan-only directories. The Nix launcher, standalone packager, and startup
smoke check discover immediate subdirectories containing `src/extension.edn`,
in sorted order. Add a new manifest-bearing directory there and it is included
automatically; no build-time extension list needs editing. Port plans without
a manifest are skipped. New files must be added to Git for Git-backed Nix builds.

Each implemented extension is a standalone project: `bb.edn`, `src/` (the
artifact root passed to `kmet install`), `test/`, `scripts/`, `README.md`, and
occasionally `LICENSE` retaining upstream attribution.

## Run with Nix (Babashka)

With Nix and flakes enabled, no separate kmet, Babashka, or Java installation
is needed. From this checkout:

```sh
nix run
nix run . -- --version
nix run . -- --provider anthropic --model claude-sonnet-4-6
```

To work in another project, run the flake from that project's directory:

```sh
cd /path/to/your/project
nix run /path/to/kmet-extensions
```

Once the flake is pushed to GitHub, you can also run without cloning:

```sh
nix run github:jasalt/kmet-extensions
```

If flakes are not enabled in your Nix configuration, add
`--extra-experimental-features 'nix-command flakes'` to the `nix` command.

The launcher uses **Babashka**, not Jolt, and enables all seven source
extensions for this process, including the work-in-progress `schedule` port.
It preserves your current directory and existing kmet settings, authentication,
and sessions (`~/.kmet/agent`, or `KMET_CODING_AGENT_DIR`). It does not install
extensions into your settings. Existing configured extensions are still loaded;
avoid installing a second copy of these extensions under another path.
Credentials are still required for providers and Pushover. `/reload` reads the
same immutable Nix-store extension sources; restart `nix run` after source edits.

`flake.lock` pins nixpkgs (including Babashka) and kmet. The initial kmet pin is
`214b551bdff37b4e4a74ca8d380bbb2df2fbaec7`. Nix fetches Maven dependencies with
a content hash and builds kmet's Babashka uberjar; Java is a build-time tool
only. First launch downloads/builds dependencies; later launches reuse the
Nix store, without Maven resolution at startup. Linux and macOS on x86-64 and
ARM64 are exposed; Linux x86-64 was tested.

Maintainers: update nixpkgs with `nix flake update nixpkgs`. To update kmet,
change `inputs.kmet.url` in `flake.nix`, run `nix flake update kmet`, then
`nix build`. If its dependencies changed, temporarily set the Maven
`outputHash` to `pkgs.lib.fakeHash`, build, and replace it with the reported
`got: sha256-…` hash. Commit both `flake.nix` and `flake.lock`. Validate with:

```sh
nix flake check
nix run . -- --version
nix run . -- --help
```

## Home Manager

Add the collection to your configuration's flake inputs:

```nix
inputs.kmet-extensions.url = "github:jasalt/kmet-extensions";
```

Pass `inputs` to your Home Manager modules using
`extraSpecialArgs = { inherit inputs; };` (or
`home-manager.extraSpecialArgs` with the NixOS Home Manager module), then add:

```nix
{ pkgs, inputs, ... }:
{
  home.packages = [
    inputs.kmet-extensions.packages.${pkgs.stdenv.hostPlatform.system}.default
  ];
}
```

After switching your Home Manager configuration, run `kmet-extensions` from
any project. Update the pinned version with `nix flake update kmet-extensions`.
For a local checkout, use `path:/absolute/path/to/kmet-extensions` as the input URL.

## Build a standalone Linux x86-64 executable

```sh
nix build .#standalone
./result/bin/kmet-extensions --version
cp result/bin/kmet-extensions /path/to/destination/kmet-extensions
```

On Linux x86-64, `nix build .#standalone` produces
one approximately 76 MiB executable: static Babashka 1.13.226 with kmet,
its Clojure dependencies, and all seven extensions appended as a JAR. Copy
the executable itself, not the `result` symlink. The destination needs no
Nix, Java, Babashka installation, or companion source files. Portable binaries
are currently supported only for Linux x86-64.

The executable preserves the working directory and uses normal kmet
settings/authentication. Embedded extension files are extracted into a private
per-process temporary directory (`TMPDIR`, or the system default) and deleted
on normal exit; a forced kill can leave that directory behind. `/reload`
continues to use those extracted files. Launch a newly built binary to update
extensions. System CA certificates/network access and provider credentials
are still needed for HTTPS; agent operations need external programs such as
Bash, Git, and ripgrep as appropriate. Those programs are **not** embedded.

`nix run` deliberately retains the Nix-managed launcher with its supporting
tools on `PATH`; `nix build .#launcher` builds that launcher explicitly.
`nix flake check` tests both launchers on Linux x86-64, including executing a
copied standalone binary with an empty tool search path and checking temporary
file cleanup. A manual isolated test also verified `--version` with no
`/nix/store` mounted. Interactive/network behavior on other Linux distributions
has not yet been tested.

## Install into an existing kmet

```sh
bb start install ../kmet-extensions/extensions/<name>/src    # from the kmet checkout
```

Add `--local` for project scope, then restart kmet or run `/reload`.

All seven source extensions use the current plugin contract: `extension.edn`
names `:plugin <namespace>/plugin`, whose `:apply` registers capabilities.
The `init` functions remain internal registration helpers; the host does not
call them directly. UI imports use the split reakt/hikap namespaces.

Check startup, unload, and reload for every source extension from a current
kmet checkout (requires its test source roots):

```sh
bb ../kmet-extensions/scripts/startup-smoke.bb
```

This offline SCI check does not exercise network calls or native Jolt.