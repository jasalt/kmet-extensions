# Design and port plan: pi-btw → kmet

**Reviewed; direction proposed; not implemented.** This document reviews
[dbachelder/pi-btw](https://github.com/dbachelder/pi-btw) and specifies a port
as a kmet directory extension. The upstream clone is pinned at
`d0d1ba5404b66058c501ed3e286733660df56aa9` (v0.7.1, 2026-10-01) under
`../../kmet/target/reference/pi-btw-source/` (the same convention as the
`pi-pins-source` / `pi-imgview-source` clones). The API names in §4 are
**proposed contracts, not existing callable functions** — a faithful port
requires one host capability addition first.

## 1. Configuration reviewed

[`home.nix`](../../lima-default/home-manager/home.nix#L191) installs
`npm:pi-btw@0.5.0` through the shared
[agent module](../../clankerfiles/home-manager/modules/agent.nix) — the same
provisioning path reviewed for `extension-toggle`. No btw-specific settings,
flags, or env vars are provisioned there; `PI_BTW_FOCUS_KEYS` is a runtime
terminal env var only. No other kmet-extensions project overlaps this
feature.

**Version drift:** the installed package is 0.5.0; the reviewed clone is
0.7.1. The port targets upstream HEAD semantics, with these features arriving
after the installed release:

| Version | Feature the port inherits |
| --- | --- |
| 0.5.0 (installed) | focused overlay, hidden thread, `--save`, `/btw:tangent`, inject/summarize, model/thinking overrides, focus-key env override, abort-first Escape, Alt+w width toggle |
| 0.6.0 | `/btw:ask` enforced read-only mode; `/side` alias |
| 0.7.0 | `btw.json` opt-in child-extension allowlists; authoritative `<btw_capabilities>` prompt note |

Upstream's Pi version matrix (0.85.1–1.x, Node 22.19+) is irrelevant to kmet.
MIT license; attribution must be preserved in the port
(`target/reference/pi-btw-source/LICENSE`).

## 2. Upstream architecture to preserve

One extension file (`extensions/btw.ts`, 2,985 lines) plus a config loader
(`btw-extension-tools.ts`, 111), a bundled skill (`skills/btw/SKILL.md`), and
144 vitest cases (runtime 102, extension-tools 8, real-SDK 2+describe blocks).
The essential structure:

| Behavior | Reference (btw.ts) |
| --- | --- |
| Child session | `createBtwSubSession` → pi `createAgentSession` with an in-memory `SessionManager`, mode tool allowlist (`BTW_TOOLS_BY_MODE`: contextual/tangent = read/bash/edit/write; readonly = read/grep/find/ls) |
| Context seeding | `buildBtwSeedState` — main branch via `buildSessionContext`, minus visible btw notes; `/btw:tangent` seeds nothing; thread continuation marker pair, then replayed `BtwDetails` exchanges |
| System prompt | `createBtwResourceLoader` — parent prompt minus dynamic date/cwd footer, plus `BTW_SYSTEM_PROMPT` and an authoritative `<btw_capabilities>` note naming the child's actual tools |
| Model runtime | `createBtwModelRuntimeOptions` — copies registered providers/native providers/runtime API keys into an isolated `ModelRuntime`; summarize uses the same with `tools: []` and thinking off |
| Transcript | Pure state machine (`BtwTranscriptState`) fed from child session events: turn boundaries with outcome, user/assistant/thinking text upserts, tool-call↔result pairing, 400-char result truncation, streaming flags |
| Overlay | `BtwOverlayComponent` (Container+Input+Markdown+Text) mounted `nonCapturing` via `ui.custom` with manual `handle.focus/unfocus`; top-center, `minWidth 72`, `maxHeight "78%"`; window (78% + margins) vs full-width (100%, borderless sides) modes; mouse reporting owned by the overlay; SGR wheel scroll; `app.clear`/`select.cancel` clear-then-dismiss; Esc aborts first while streaming, dismisses second |
| Focus keys | `resolveBtwFocusShortcuts` — defaults `alt+/`, `super+/`, `ctrl+alt+w`, env-overridable with strict key-grammar validation, fall back to defaults when nothing usable |
| Persistence | Hidden thread as custom entries (`btw-thread-entry` / `btw-thread-reset` / `btw-model-override` / `btw-thinking-override`); visible notes as `btw-note` custom messages, filtered out of main LLM context by the `context` event handler; restore on `session_start` / `session_tree` replays branch entries after the last reset |
| Concurrency | `btwLifecycleGeneration` token; serialized `btwSessionCreationQueue` and `btwSubmissionQueue`; abort-settlement waits before follow-ups; every await re-checks the generation |
| Handoff | `/btw:inject` formats the child thread as `User:/Assistant:` exchanges; `/btw:summarize` runs a tool-free child turn with `BTW_SUMMARIZE_SYSTEM_PROMPT`; both deliver via `sendUserMessage` (`followUp` when busy), then reset + dismiss |
| Commands | `btw`, `side` (alias), `btw:tangent`, `btw:ask`, `btw:new`, `btw:clear`, `btw:model`, `btw:thinking`, `btw:inject`, `btw:summarize`; in-modal slash routing dispatches `btw:*` commands inside the overlay, other `/`-input goes to the child prompt |
| Headless | Composer-only commands require the TUI (`canRenderBtwOverlay`); completed answers display as visible notes elsewhere; `--save` forced when no overlay |
| Extension tools | `btw.json` allowlists (global `~/.pi/agent/btw.json`, trusted project `.pi/btw.json` replace-not-merge) load extra pi extensions into non-readonly child sessions with a BTW-owned install root |

## 3. Baseline kmet support and gaps (kmet at `432c0122`)

### 3.1 What ports directly

| Upstream need | kmet support (verified) |
| --- | --- |
| Overlay mount + manual focus | `ext/ui-custom` `{ overlay overlay-options on-handle }`; `kmet.tui.core/tui-show-overlay` returns the OverlayHandle `{:hide :set-hidden! :is-hidden? :focus :unfocus :is-focused?}`; `:non-capturing`, `:anchor :top-center`, `:width "78%"/"100%"`, `:margin {:top 1 :left 2 :right 2}`, `:min-width 72`, `:max-height "78%"` all exist (`src/kmet/tui/core.clj`) |
| Overlay components | shared `kmet.tui.components.{container,text,input,markdown}`, `kmet.tui.utils` (`wrap-text-with-ansi`, `truncate-to-width`, `visible-width`), `kmet.tui.core/tui-request-render`, `kmet.tui.keybindings/matches-key` with `app.clear` / `tui.select.cancel`, `kmet.tui.keys` (incl. `super` modifier) — the whole prefix is on the extension allowlist (`src/kmet/app/extensions/context.cljc` `tui-library-namespaces`) |
| Shortcuts before builtins | `ext/register-shortcut!` with raw key ids; extension shortcuts run before every builtin binding; defaults `alt+/`, `super+/`, `ctrl+alt+w`, `alt+w` do not collide with builtin defaults; `ctrl+q` (`app.quit`) is the one chord that can never run — not used here |
| Hidden thread state | `sess/append-entry!` custom entries — never in LLM context; restore via `(:session ctx)` `:get-branch` on `:session-start` and `:session-tree` |
| Visible saved notes | `ext/send-message!` `{:custom-type :btw-note :content ... :display true :details ...}` + `ext/register-message-renderer!`; `:trigger-turn`/`:deliver-as :follow-up` for the busy path |
| Keeping notes out of main context | `ext/on-event api :context` returns the filtered `:messages` (last non-nil handler wins) — the exact pi `context` hook btw uses |
| Handoff delivery | `ext/send-user-message` (`:deliver-as :follow-up`) |
| Command context | `ctx` carries `:mode`, `:has-ui`, `:cwd`, `:model`, `:thinking-level`, `:is-idle`, `:is-project-trusted`, `:get-system-prompt` (`build-extension-context`) |
| Model registry | `(:models api)`: `find`, `get-api-key-and-headers`, `has-configured-auth`, `get-provider-auth-status`, `get-registered-provider-config` — enough for `resolveBtwModel`'s override/fallback logic |
| Background work | `kmet.libs.concurrent/spawn` daemon threads (no `future` under SCI) |
| Skill | `io/resource "skills/btw/SKILL.md"` + `ext/register-skill!` with `{:location "btw:skills/btw/SKILL.md"}` |

### 3.2 The blocking gap: no sub-session capability

`create-extension-api` (`src/kmet/app/extensions.cljc`) exposes commands,
tools, tool sources, events, hooks, flags, shortcuts, markdown transformers,
messages, renderers, skills/prompts, model/session/ui facades, `exec` —
**nothing that runs an LLM call or an agent session**. The machinery a child
session needs — `kmet.ai.llm`, `kmet.ai.api/*` (10 wire APIs), `kmet.app.loop`
(`run-agent-turn`), `kmet.app.session` (`build-context` / `context-messages`)
— is all outside the shared namespace allowlist; requiring any of it fails
the extension load by design (the same boundary `extension-toggle`'s review
hit).

So under the current contract a btw port **cannot**:

- run a side conversation with tool access (the headline feature);
- stream child events into an overlay transcript (no events to subscribe to);
- apply `/btw:model` / `/btw:thinking` overrides to an actual child run;
- run the tool-free `/btw:summarize` turn;
- seed a child from the main-session context (`buildSessionContext`
  equivalent is internal).

**Rejected alternative:** implement the provider wire inside the extension —
`kmet.libs.http` + `models/get-api-key-and-headers` + `get-all-tools`
`:execute` fns could, in principle, drive a hand-rolled agent loop. Rejected:
it duplicates `kmet.ai.api/*` (~2,900 lines of wire builders plus the 890-line
SSE layer), forks every provider/thinking feature kmet fixes, and turns the
extension into a second agent implementation the host cannot see. Extensions
own UI and policy; the host owns agent execution.

### 3.3 Secondary gaps and deliberate differences

1. **`btw.json` child-extension allowlists are kmet-inapplicable** — they load
   TypeScript pi packages. Replace with an optional tools allowlist in the
   child capability (or drop for v1: the mode tool sets already come from the
   host registry). The 0.7.0 `<btw_capabilities>` authoritative-note behavior
   is kept — it is prompt text, not package loading.
2. **No ModelRuntime cloning needed.** Pi isolates child runtimes because its
   factory cache shares module globals; kmet's child would run in-process over
   the same models registry, so the entire `createBtwModelRuntimeOptions`
   apparatus (registered/native provider copying, runtime API keys,
   subscription/keyless auth tests) collapses into "the host resolves auth
   through the parent registry". Keep the *observable* fallback semantics
   (`resolveBtwModel`'s override → main-model fallback with a warning) — that
   ports as pure logic over `(:models api)`.
3. **Mouse reporting is not enabled by kmet's main TUI** (no fullscreen mode
   exists either), so the port always takes the "regular mode" branch: the
   overlay component writes `\e[?1000h\e[?1006h` on mount and the reverse on
   dispose, and parses SGR wheel sequences itself — same as upstream.
   `tui/terminal.clj` exposes a `write-fn` for exactly this kind of raw
   sequence.
4. **Overlay options resolve at show time** in kmet too (the entry stores its
   `:options`), so the Alt+w width toggle still requires dismiss + reopen with
   draft persistence — port the upstream approach unchanged.
5. **`syncUi`'s `setWidget("btw", undefined)` is vestigial upstream** (a
   leftover docked widget); the kmet port uses no widget, only the overlay.
6. **Embedded-input cursor trick** (upstream renders its Input unfocused to
   keep the row geometrically stable) must be re-validated against kmet's
   `Input` cursor-marker behavior in a PTY test, not assumed.

## 4. Recommended host capability: `(:agent api)`

One addition to the extension contract, modeled on pi's `createAgentSession`
but shaped like the event vocabulary the overlay already consumes. These
names are **proposed**:

```clojure
(def session (ext/create-agent-session api
             {:model model-rec              ; from (:models api) — nil = inherit ctx model
              :thinking-level :high        ; nil = inherit
              :tools ["read" "bash" "edit" "write"]  ; registry subset; [] = tool-free
              :inherit-context? true       ; host seeds from the main branch
              :context-filter predicate    ; host applies after seeding (hide btw notes)
              :system-prompt "..."         ; override (nil = main prompt, dynamic footer stripped)
              :append-system-prompt ["..."]}))
;; => {:prompt  (fn [text opts] core-async/promise-of-response)  ; opts: {:signal abort-atom}
;;     :abort   (fn [])                    ; idempotent; settles before dispose
;;     :aborted? :streaming? (fn [] bool)
;;     :subscribe (fn [callback])          ; => unsub; callback receives event maps:
;;                                          ;   :turn-start/:turn-end {:message :outcome}
;;                                          ;   :message-start/:message-update/:message-end {:message}
;;                                          ;   :tool-execution-start/:update/:end
;;     :messages (fn [])                   ; child state for handoff extraction
;;     :dispose  (fn [])}
```

Design rules:

- **One implementation.** Built in the app layer over the same machinery the
  main loop uses (`kmet.app.session` in-memory records, the tools registry
  subset, the `run-agent-turn` loop), not a second agent. Injection at the
  composition boundary avoids require cycles (the `extension-toggle` §4
  pattern).
- **Per-session subscriptions only.** Child events must never reach the global
  event bus or the main transcript/chat — upstream's overlay is fed by a
  direct `session.subscribe`, and the main UI must not see child tool
  activity. This is the property the 0.7.x tests actually pin.
- **`abort` must settle before `dispose` completes** (upstream
  `requestBtwSessionAbort` + `disposeChildSession` ordering), and `:prompt`
  must reject while a previous turn or abort is settling — or the capability
  exposes the raw state and the extension keeps its own submission queue
  (simpler; prefer this).
- **Context seeding stays host-side** (`:inherit-context?`), because
  `session/build-context` + `session/context-messages` are internal; the
  extension cannot and should not rebuild main context itself. Do not offer a
  "read the last outgoing context" escape hatch via the `:context` event —
  it only fires during provider requests and invites stale seeding.
- **Auth is host business.** The child resolves keys/headers through the
  parent registry (`get-api-key-and-headers`); the extension only does the
  override/fallback *decision* logic. No runtime key copying exists to port.
- **Scope guard.** Tools are limited to names the registry resolves; the child
  cannot fabricate capabilities. Keep the `<btw_capabilities>` note
  host-generated from the actual active tool set (upstream's fix for
  inherited capability claims), with the `:append-system-prompt` escape hatch
  for the BTW identity prompt and the summarizer's tool-free wording.
- **Versioning + docs in the same change:** wire through `kmet.extension`,
  `kmet.app.extensions` (+ nullable-API fixture), document in
  `src/kmet/extension.md`, register host test namespaces. Older hosts produce
  an explicit unsupported-capability error; the extension degrades to the
  no-overlay note behavior only for *its own* commands, not silently.

## 5. Extension design (`../kmet-extensions/btw/`)

```text
btw/
  README.md  PORT.md  LICENSE (MIT, upstream attribution)
  bb.edn                       ; pins/savelast pattern: test + test-changed tasks,
                               ; :paths ["src" "test" "../../kmet/src"]
  src/extension.edn            ; {:name "btw" :entry btw.core :loader [:jolt :sci]}
  src/btw/core.clj             ; init/shutdown, registrations, command dispatch
  src/btw/model.clj            ; args parsing, overrides, restore, handoff formatting
  src/btw/transcript.clj       ; pure transcript state machine (1:1 upstream port)
  src/btw/overlay.clj          ; overlay component + mouse reporting + focus keys
  src/btw/agent.clj            ; child-session lifecycle over the (:agent api) capability
  src/btw/skills/btw/SKILL.md  ; ported verbatim (command list adjusted only if names change)
  test/btw/{core,model,transcript,overlay}-test.clj
  scripts/smoke.bb             ; nullable-API load/unload smoke
```

State and concurrency: keep the upstream shape — atoms for
`pending-thread`, `pending-mode`, model/thinking overrides, transcript state,
`lifecycle-generation`, and serialized submission/creation queues — but with
`kmet.libs.concurrent/spawn` workers instead of promise chains: overlay
submits, child creation, and handoffs run on spawned threads; every callback
re-checks the generation token before touching state; UI refresh goes through
the overlay's refresh atom + `tui-request-render`. Never deref a child
promise on the input thread.

Pure ports (upstream → btw.*): `parseBtwArgs` / `parseBtwModelArgs` /
`parseBtwThinkingArgs` → `btw.model`; the transcript functions
(`ensureTranscriptTurn`, `finishTranscriptTurn`, upserts, tool-call pairing,
`summarizeToolResult`, `buildOverlayTranscript` line building,
`extractBtwHandoffThread`, continuation-marker detection) → `btw.transcript`;
`resolveBtwFocusShortcuts` + validation grammar + `describeFocusShortcuts` →
`btw.overlay` (env var renamed `KMET_BTW_FOCUS_KEYS`); `BtwOverlayComponent`
render/handleInput frame logic → `btw.overlay` using
`kmet.tui.components.*` + `kmet.tui.utils`.

Persistence mapping: entry types `btw-thread-entry`, `btw-thread-reset`,
`btw-model-override`, `btw-thinking-override` via `append-entry!`; restore on
`:session-start` and `:session-tree` (replay branch after last reset; drop
model overrides no longer in the registry); visible notes via `send-message!`
with `:custom-type :btw-note` + message renderer returning a message map with
label `[BTW]` (kmet renders the markdown content itself); `:context` handler
filters `{:custom-type :btw-note :display true}` messages out of the outgoing
list. `--save` while the main session is busy uses `:deliver-as :follow-up`
(queued state, same three-state save reporting).

Headless rules: composer-only `/btw`, `/btw:tangent`, `/btw:new`, `/btw:ask`
require `(:mode ctx) :interactive`; otherwise notify "pass the question
inline" and force the visible-note path for completed answers — exactly the
RPC/SDK behavior upstream.

Deferred (explicit non-goals for v1): `btw.json` extension allowlists (no
replacement needed until the child capability gains a tools-allowlist knob);
`/btw:ask`'s grep/find/ls depend on the opt-in bundled grep/find/ls extensions
being enabled — the mode's tool list should be intersected with what the
registry actually provides, warning when the read-only set is incomplete.

## 6. Validation and evidence

This review was offline: upstream cloned at `d0d1ba5` (v0.7.1) and read in
full; kmet inspected at `432c0122` (working tree, unrelated untracked files
untouched). No kmet source was changed, no extension was loaded, no tests
were executed, no live LLM/session interaction or PTY run was performed.
Claims about the kmet surface cite the files above and were verified by
reading, not execution.

Upstream's 144 tests are the behavioral baseline to reproduce (102 runtime:
sub-session creation/tool surfaces/context seeding and exclusion, override
inheritance and restore, abort/settlement ordering, overlay render geometry
and width modes, mouse reporting ownership, Esc semantics, in-modal slash
routing, RPC display, focus-shortcut validation; 8 config; 2 real-SDK).
Acceptance gates:

- **Host capability:** unit tests over a fake registry for tool allowlisting,
  context seeding + filtering, per-session event isolation (child events never
  reach the global bus), abort→dispose settlement, tool-free summarize mode,
  auth fallback; nullable-API fixture records `:agent` calls; `extension.md`
  updated; new host test namespaces registered in the runner.
- **Extension pure model:** offline clojure.test for parsing, transcript
  state machine, restore replay, handoff extraction, focus-key validation and
  env fallback — no host dependencies (pins/savelast test style).
- **Overlay:** PTY/tmux capture scripts (`kmet/scripts/` conventions) for
  width modes, borderless full-width drag-selection geometry, mouse wheel
  enable/restore, Esc-first-abort, `app.clear` clear-then-dismiss, embedded
  Input cursor stability.
- **Lifecycle:** smoke load/unload proves registrations (10 commands, 2
  shortcuts, skill, message/entry renderers, `:context` filter, session
  event handlers) deregister cleanly across `/reload` and `/new`.
- **Gates:** host changes through `bb test-changed` / `lint-changed` /
  `format-check-changed`; the standalone project through its own `bb.edn`
  tasks; full suites only at integration time.

## 7. Implementation sequence

1. **Phase 1 — host `(:agent api)` capability** (§4): app-layer child-session
   runner + contract docs + nullable fixtures + host tests. Nothing else can
   land honestly before this; do not ship a degraded "one-shot completion"
   btw under the same command names.
2. **Phase 2 — pure model:** `btw.model` + `btw.transcript` ports with
   offline tests (no capability needed yet).
3. **Phase 3 — overlay:** `btw.overlay` component + focus shortcuts + width
   toggle against a scripted fake child (pre-seeded transcript events).
4. **Phase 4 — lifecycle & persistence:** `btw.agent` (generation tokens,
   serialized queues, abort settlement), commands, in-modal routing, entry
   persistence + restore, `--save`/queued notes, `:context` filtering,
   inject/summarize handoff, headless rules.
5. **Phase 5 — skill, packaging, PTY validation:** bundled SKILL.md via
   `register-skill!`, `bb pack-extension` artifact, real-terminal and Jolt
   checks, README rewrite from plan to usage, minimum-host-version note (the
   `:agent` capability version).
