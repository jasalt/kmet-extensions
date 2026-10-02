# savelast for kmet

Port of [atomdmac/pi-savelast](https://github.com/atomdmac/pi-savelast),
referenced at `efb580c1e7f95e230c2c413021b6680abfa287c7`. Adds one slash
command, with no dependencies, configuration, tools, or automatic saves.

## Usage

```text
/savelast                    # <current-project>/<epoch-milliseconds>.md
/savelast summary.md
/savelast notes/response.md
/savelast ../docs/output.md
/savelast /absolute/path/output.md
```

The entire trimmed argument is a path, resolved against the command's current
working directory. Paths containing spaces work without quotes; shell quotes
and `~` are not expanded. Parent directories are created automatically.
Existing files are **overwritten**, without confirmation.

Only text blocks from the latest assistant message are saved, joined with
newlines. Thinking, tool calls, images, tool results, and user/extension messages
are excluded. String content is also supported. Original text whitespace is
preserved; no extra final newline is added. Files are written as UTF-8, whatever
the extension of the supplied path.

If there is no assistant message, or its text is empty/whitespace-only, the
command warns and writes nothing. It does not fall back to an older response
when the latest message contains only tool calls or thinking. Success and
filesystem errors use UI notifications, matching the Pi extension.

**Branch behavior:** this port uses kmet's public `ctx.session :get-branch`
facade, so it saves the latest assistant message on the **active branch**.
Upstream scans Pi's full `getEntries()` list, including abandoned branches;
kmet does not expose the unfiltered full list in its extension API. Session
and working-directory changes are read afresh on every invocation. No kmet
internal namespaces or session-file parsing are used.

## Install

`src/` is the extension artifact root, not this project directory. From here:

```sh
mkdir -p ~/.kmet/agent/extensions
ln -s "$PWD/src" ~/.kmet/agent/extensions/savelast
```

Or use the package manager:

```sh
kmet install /absolute/path/to/kmet-extensions/savelast/src
# Add --local for project scope.
```

For project-local discovery, link `src/` into `.kmet/extensions/savelast`
in that project. Restart kmet or run `/reload`. `KMET_CODING_AGENT_DIR`
relocates the global agent directory. The manifest declares SCI and native
Jolt compatibility; no runtime `deps.edn` is needed.

## Development

Tests use the sibling `../../kmet/src` checkout and write only disposable
fixtures under `target/`:

```sh
bb test-changed
# Or select a namespace explicitly:
bb test savelast.core-test
```

From the sibling kmet checkout, validate just this extension:

```sh
bb ../kmet-extensions/savelast/scripts/smoke.bb
bb lint ../kmet-extensions/savelast/src ../kmet-extensions/savelast/test ../kmet-extensions/savelast/scripts
bb format-check ../kmet-extensions/savelast/src ../kmet-extensions/savelast/test ../kmet-extensions/savelast/scripts ../kmet-extensions/savelast/bb.edn
bb pack-extension ../kmet-extensions/savelast/src target/savelast.jar
bb ../kmet-extensions/savelast/scripts/smoke.bb target/savelast.jar
```

The smoke script exercises the real isolated loader, live sessions, branch
navigation, cwd switching, writes, warnings, errors, reload, and command cleanup.
Native Jolt and actual terminal notification rendering require their respective
environments; they are not verified by the Babashka tests.
