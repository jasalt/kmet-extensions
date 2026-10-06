# Provenance and scope

## References and copied artifacts

- Original: <https://github.com/xhluca/session-migrate>, revision
  `c23b1dbd21404f78be3b69d42ff4fb158ff52105` (MIT).
- Go/PiG behavioral reference: sibling `pig-extensions`, revision `62122e2`
  (`extensions/session-migrate/`). This extension is independent Clojure source;
  it neither invokes that extension nor depends on PiG or Python.
- Target checked from <https://github.com/kmetia/kmet-agent>: latest upstream
  `a41fe5c01f2c115ea42fce4ea9764b9bc563a810`. The pre-existing, modified `../kmet`
  workspace was not edited or used as the verification target.

SHA-256 of reference/copied files (upstream paths relative to its repository):

| File | SHA-256 |
| --- | --- |
| Upstream `LICENSE`; retained verbatim as `LICENSE` and installable `src/LICENSE` | `f44a6129dfbf3f2bf68e333d33489361a2113ebe2804e5852eb39adcf58bd35b` |
| Upstream `src/session_migrate/formats/claude.py` — algorithm/format reference, not vendored runtime | `b914060468f8afd79500b12fd5f53da256190369af356e5dd015f2a226a8613f` |
| `testdata/claude-native.jsonl`, copied unchanged from the Go port | `9103243215e8cfb960495d2e0097c2c6d5787fe6dc93e2166fad7c7ebe827c3c` |
| `testdata/claude-native.provenance.json`, copied unchanged | `92308721671f99ae2486df90be6db2ce12be6fbe68f12caeb6d02db067a2d3b3` |
| Go reference `claude.go` | `11e9f672a21052067cf7f362dd9da3f6922e7ddbfe27227da32551a3729d4af5` |
| Go reference `writer.go` | `4c0709dfa746b25b6558aa8c7b3971c9e9b59ffcfcb674111028274a01115e50` |
| Go reference `extension.go` | `fd182c01b45fe694ade9a990f28a07dfd45cffe1170337732db58e24d0df8cf9` |

The native fixture is the upstream sanitized Claude Code **2.1.209** capture at
`tests/native_corpus/v1/sources/claude/2.1.209/portable-rich/native/73fea258-9467-4a17-877b-ef6bcd0898b7.jsonl`.
Its original provenance remains intact (original artifact paths are upstream
paths, not paths in this extension). This is native-produced source evidence,
not a newly performed vendor capture. The tool-result image, compaction,
malformed-input, graph-fork and cancellation cases in this project are synthetic
supplementary tests and are not represented as native Claude captures.

## Deliberate differences

- Only Claude → kmet; no 18-harness matrix, exporter, catalog, IR or Python CLI.
- Native **kmet EDNL v1**, not Pi/PiG JSONL v3. Header/entry identities are fresh;
  tools remain canonical historical `:tool-calls` / `:tool_result` records;
  compact summaries become native `:compaction` entries with self anchors.
- Public `kmet.extension` capabilities only; no app-internal dependencies in
  installable source. Tests intentionally use host session/loader APIs as
  evidence. Uses existing shared `kmet.libs.*`, fs, io and string facilities.
- Source configuration/cwd, privileged messages, private/signed thinking,
  documents and opaque metadata are not migrated. Omissions are counted.
- Incomplete tool exchanges are refused, not repaired with synthetic upstream
  results. Invalid/missing/cyclic ancestry, duplicates, mixed session IDs and
  sidechain-only/active-sidechain transcripts are refused. JSON nesting is
  capped at 256 levels before the host decoder, preventing stack exhaustion.
- The current kmet API supplies an agent directory, **not** its configured
  session-storage directory. Output therefore uses the documented default
  agent-dir/current-cwd layout and does not honor custom `--session-dir`.
- kmet separates assistant calls from content and tool-result images from text;
  media/call interleaving is not lossless. Joined tool text/image layout is
  counted as normalized. The shared JSON parser decodes decimal/exponent
  arguments as floating point: counted normalization, finite-only, unlike the
  Go port's raw JSON-number representation.
- Private no-overwrite hard-link publication and rollback are retained, but the
  portable bb/Jolt filesystem writer does not fsync. Neither implementation's
  two-file publication is crash-atomic.
- The manifest exposes no source path/content. Target checksum is initial,
  because normal kmet loading/continuation can append to the file.
- `import` is awaited interactive switching; headless `import` is refused.
  `save` does not switch. A cancelled host switch keeps the saved files.
- Registration and PTY text are not graphics proof. No private transcript,
  vendor continuation, account mutation, reset redemption or real alert was
  used or claimed.
