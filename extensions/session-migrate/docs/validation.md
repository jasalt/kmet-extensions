# Validation

## Verified target

Latest upstream kmet was rechecked during the work and advanced from the initial
`cf44023538cba064bc712310ebc6e74bd8cb4abc` checkout. **All final gates below were
rerun at `a41fe5c01f2c115ea42fce4ea9764b9bc563a810`**, with clean tracked files.
The user's modified `../kmet` checkout was left untouched.

- Linux x86_64; Babashka **1.13.225**; Jolt **0.8.18**.
- Jolt official x86_64 Linux release tar SHA-256:
  `554f6100132bb75468df5f46a85d144248c5b681d091c489117daa34763072b0`,
  checked against its published `.sha256` file. The initially available Jolt
  0.7.28 was below latest kmet's floor and was **not** the verification host.
- Built Babashka jar and optimized dynamic native Jolt executable both report
  `kmet dev-ga41fe5c`.
- Tested jar SHA-256:
  `e4f6e1dd86bba7dfa60c895fc2e999a07aa1143470545c70a65910c54dc75fd2`.
- Tested native `kmetj` SHA-256:
  `388a77d69346a0cd7a09575ce4ea9f8a2504e27db70e36f3af7786e52420b6e5`.
  This is a dynamic Linux build, not a static/self-contained distribution.

## Passing gates

| Gate | Evidence |
| --- | --- |
| Babashka offline suite | **16 tests, 136 assertions**, 0 failures/errors |
| Jolt offline suite | **16 tests, 136 assertions**, 0 failures/errors |
| Real SCI extension loader | Load, execute `inspect`/`save`, native load/context, unload/deregister, reload and execute again |
| Real native Jolt extension loader | Same lifecycle and native context checks |
| Source CLI, Babashka | All 11 subprocess checks below pass |
| Source CLI, Jolt | All 11 subprocess checks below pass |
| Built Babashka jar | All 11 subprocess checks below pass |
| Built native Jolt executable | All 11 subprocess checks below pass |
| clj-kondo 2026.08.04 | 0 errors, 0 warnings (source, tests, test helper, lint hook) |
| cljfmt 0.16.6 | Source/tests/manifests/helper/hook formatting checked |

The offline suite covers native-source counts/hash/title; branch/fork ordering;
large integral tool arguments; orphan, duplicate and unresolved tools; missing,
cyclic and mixed-ID ancestry; sidechains; strict UTF-8; malformed/trailing JSON;
actual stream bounds after an understated stat; JSON nesting limits;
cancellation during scanning;
compact-summary preserved loops and self anchors; image/result normalization;
private-content omissions; finite-only/count-reported float arguments; private
permissions and content-free manifests; ordinary rollback; eight parallel
publications; literal/ambiguous UUID resolution; idle/queue/headless refusal;
cancelled host switches; reentrancy, unload and per-registration cancellation.

`scripts/integration.py` starts real kmet interactive subprocesses with a
controlling **text PTY**, private HOME/agent/config directories, a test-only
provider/probe extension and an HTTP server bound to **127.0.0.1**. Its eleven
checks, separately passed in all four execution forms, are:

1. `inspect` performs no writes, switch or model request. Interactive output is
   a multiline `ui-chat-info` report in the scrollable live conversation,
   excluded from model context and not persisted. The PTY checks its label;
   the continuation wire checks exclude both label and source checksum.
2. Real UUID lookup works; ambiguous UUIDs refuse without mutation.
3. `save` publishes native files without switching the live branch.
4. `import` actually changes the live session: source title, 12 native branch
   entries including title, native calls/results/image and new leaf.
5. Malformed source refuses without publication, switch or model request;
   source sentinel content never appears in the terminal error.
6. Manifest source/initial-target hashes agree; both files are mode `0600`.
7. A subsequent turn sends historical tool calls, matching results and native
   image content through the real OpenAI-compatible provider converter.
8. A loopback dummy reply reaches the active branch and persists.
9. Restart with `--session <path>` restores the title and continuation; a second
   local turn includes the earlier reply in the provider request.
10. Import a **synthetic** compaction source; the next provider request contains
    its summary and post-compaction message but excludes earlier history.
11. Source fixture/UUID copy, malformed source and compaction source hashes stay
    unchanged. Each execution form makes exactly **three** dummy model requests;
    migration itself makes none.

The native Claude fixture hash is
`9103243215e8cfb960495d2e0097c2c6d5787fe6dc93e2166fad7c7ebe827c3c`.
Its provenance identifies native Claude Code 2.1.209 production/sanitization;
this work did not make a new vendor capture.

The real loader tests exposed a SCI `random-uuid` availability difference; the
writer now uses `java.util.UUID/randomUUID`. Testing native media exposed Jolt's
regex-map replacement difference; image conversion uses portable literal
replacements. These fixes are exercised by actual loaded commands and native
fixture/provider tests, not assumed from root namespace tests.

## Detached tool-result recovery follow-up

Before changing the importer, a synthetic transcript reproduced the malfunction:
records `u -> a`, result `r -> a`, and continuation `next -> a` selected only
`[u a next]` and failed completed-tool validation. Changing only the continuation
parent to `r` passed with one call and one result. No private transcript or
vendor service was used for this reproduction.

The recovery fix was tested in Babashka source mode against the local kmet
checkout at `9825d77f48056c7887a6980cc9406704f6ce7a20` (clean tracked files,
pre-existing untracked files left untouched). These are follow-up gates, not a
rerun of the historical Jolt/built-artifact matrix above:

- `scripts/check.sh bb`: **19 tests, 165 assertions**, zero failures/errors,
  plus the real-loader/save/context/reload smoke test.
- `python3 scripts/integration.py --kmet-source /path/to/kmet`: all previous
  checks plus synthetic detached-result native import and dummy-provider
  continuation. Native order is user/assistant/tool/assistant; recovered result
  content and its tool-call ID reach the provider converter. Four loopback
  dummy requests total; import itself makes none. All source hashes unchanged.

Recovery tests also cover physical child-before-parent order, multiple results,
ambiguous candidates, already-resolved/inactive calls, wrong/missing linkage and
session identity, sidechains, metadata, compact summaries, and sibling text.
The bounded recovery scans check cancellation. No synthetic results are created.
Jolt is not available in the follow-up environment, so this change has not been
requalified on Jolt or the built artifacts.

## Reproduce

From this extension directory, using a disposable clean upstream checkout:

```sh
export KMET_SOURCE_ROOT=/absolute/path/to/clean/kmet
export JOLT_BIN=/absolute/path/to/jolt-0.8.18
# Pin the checkout to the revision above for identical target behavior.
scripts/check.sh bb
scripts/check.sh jolt

python3 scripts/integration.py --kmet-source "$KMET_SOURCE_ROOT"
python3 scripts/integration.py --kmet-source "$KMET_SOURCE_ROOT" \
  --host jolt --executable "$JOLT_BIN"
```

The check script strips the environment and uses fresh isolated homes. Trust-store
paths may pass through for kmet's pinned build-dependency downloads; no real
provider credentials or user config pass through. `TMPDIR` may point to a larger
disposable filesystem: a first native build exceeded the sandbox `/tmp` quota;
a clean cache-directory build then succeeded. No upstream patch was needed.

Build artifacts in that **disposable** checkout (not the user's modified one):

```sh
# Use an isolated build HOME; dependency downloads only, no real account config.
(cd "$KMET_SOURCE_ROOT" && HOME=/path/to/isolated/build-home bb uberjar)
(cd "$KMET_SOURCE_ROOT" && HOME=/path/to/isolated/build-home \
  "$JOLT_BIN" dist --dynamic --out /path/to/disposable/artifacts --jolt "$JOLT_BIN")

python3 scripts/integration.py --kmet-source "$KMET_SOURCE_ROOT" \
  --artifact "$KMET_SOURCE_ROOT/target/kmet.jar"
python3 scripts/integration.py --kmet-source "$KMET_SOURCE_ROOT" \
  --host jolt --artifact /path/to/disposable/artifacts/kmetj
```

Pass `--output <new-directory>` to retain `result.json`, sanitized-fixture
`requests.json`, `probes.jsonl`, private test homes and PTY logs for inspection.
Without it, disposable evidence is removed. The probe/provider helper is
**test-only**, outside installable `src/`.

Formatting/linting (clj-kondo must be available):

```sh
clj-kondo --lint src test testdata/migration_probe.clj .clj-kondo/hooks
# From the clean kmet checkout, with its pinned tooling deps:
bb format-check /absolute/path/to/this/extension/src \
  /absolute/path/to/this/extension/test \
  /absolute/path/to/this/extension/testdata/migration_probe.clj \
  /absolute/path/to/this/extension/.clj-kondo \
  /absolute/path/to/this/extension/bb.edn
```

## Not claimed

No terminal image-rendering/graphics proof, private Claude transcript, vendor
continuation, Windows/macOS test, static native build, crash/power-loss durability,
full upstream adapter matrix or custom `--session-dir` integration. Text PTYs
prove command/session/provider behavior only. Native Jolt is verified here;
registration alone is not counted as functional evidence.
