# webdriver (proof of concept)

Browser control for kmet through [Etaoin](https://github.com/clj-commons/etaoin)
and W3C WebDriver, running in-process in the extension. It does not use an
agent-browser CLI wrapper. Page snapshots with element refs follow
[agent-browser](https://github.com/vercel-labs/agent-browser)'s design.

One tool, `webdriver`, controls a headless Chromium session. The session
starts on first use and stays open between calls:

| action | args | result |
|---|---|---|
| `start` | – | starts the browser |
| `go` | `url` | navigates, then returns the URL and title |
| `info` | – | returns the current URL and title |
| `snapshot` | `interactive`, `compact`, `depth`, `urls`, `selector` | returns an accessibility outline with element refs (up to 30000 chars) |
| `text` | `ref`/`selector`/`xpath` (default `body`) | returns visible text, truncated to 8000 chars |
| `click` | `ref`/`selector`/`xpath` | waits for the element to be visible, then clicks it |
| `fill` | `ref`/`selector`/`xpath`, `text` | clears the field, then types the text |
| `wait` | `ref`/`selector`/`xpath`, `timeout` | waits until the element is visible (default 10 s) |
| `js` | `script` | runs JavaScript; use `return` to get a value |
| `source` | – | returns the page HTML |
| `screenshot` | – | returns a PNG as a tool image attachment |
| `quit` | – | closes the browser and chromedriver |

Unloading or reloading the extension calls `shutdown`, which quits the browser
and the driver process.

## Snapshots and refs

`snapshot` renders Chrome's accessibility tree as an outline. Elements you can
act on, and named landmarks, get a ref:

```
- navigation "Primary" [ref=e1]
  - link "Home" [ref=e2]
- form "Profile" [ref=e5]
  - textbox "Name" [required, ref=e6]: Ada
  - checkbox "Subscribe" [checked=true, ref=e7]
  - button "Save" [ref=e11]
  - button "Delete" [disabled, ref=e12]
```

Then act with `{"action": "click", "ref": "e11"}`. Refs can be written as
`e11`, `@e11` or `ref=e11`.

- **What gets a ref.** Interactive roles (button, link, textbox, checkbox,
  combobox, option, …) and table cells always get one. Headings, landmarks,
  forms and list items get one only when they have a name.
- **Options.**
  - `interactive`: show only nodes that have refs, each nested under the
    nearest ref'd ancestor.
  - `compact`: drop unnamed structure (list, row, main, …) and lift its
    children up a level.
  - `depth`: limit how deep the outline goes.
  - `urls`: add link URLs.
  - `selector` (CSS): show only that subtree. Refs keep their page-wide
    numbers.
- **Stable refs.** An element keeps its ref across snapshots for as long as
  the page does not navigate. Elements that appear later get the next number.
  After a navigation, every ref expires and the tool asks for a new snapshot.
- **Stale refs.** If an element was re-rendered, its ref is looked up again by
  role and name. That only happens when the match is certain. If the name was
  shared by several elements when the snapshot was taken, or is shared by
  several elements now, the tool refuses and asks for a new snapshot.
- **How a ref reaches the element.** Each ref maps to a Chrome DOM node id.
  To act on it, the tool marks that one element with `data-kmet-ref="eN"` and
  runs the normal WebDriver action on `[data-kmet-ref='eN']`. Scrolling,
  visibility checks and clicking therefore stay WebDriver's own.

### Implementation

| namespace | role |
|---|---|
| `webdriver.snapshot` | Pure functions, no I/O: AX node list → tree → `assign-refs` (reduces over ref state as plain data) → `shape` (noise, interactive, compact, depth, subtree) → `render`. Also `recover` and `parse-ref`. |
| `webdriver.core` | I/O: Chrome DevTools calls through chromedriver's `goog/cdp/execute` endpoint, using the existing Etaoin session (no second connection). Ref state lives in one atom and is advanced with a single `swap!` per snapshot. |

Compared with agent-browser, this version works on tree data instead of
filtering rendered lines, and its ref state is an immutable value instead of
a mutable `RefMap`.

It does not yet include agent-browser's iframe snapshots, its "cursor
interactive" pass (clickable `div`s with no ARIA role), or its
icon/row/test-id hints.

Snapshots are **Chromium-only**. They rely on the Chrome DevTools
accessibility tree, and Firefox and Safari WebDriver do not expose one.
agent-browser's own WebDriver backend has no snapshot for the same reason.

## Requirements

You need `chromedriver` and Chromium/Chrome with matching major versions. The
extension finds them in this order: the row config, then environment
variables, then `PATH`.

```edn
{:id :ext/webdriver :plugin :ext/webdriver
 :config {:chromedriver "/path/to/chromedriver"   ; or WEBDRIVER_CHROMEDRIVER
          :browser "/path/to/chromium"            ; or WEBDRIVER_BROWSER
          :headless true
          :args ["--window-size=1280,900"]}}
```

On Nix: `nix build nixpkgs#chromedriver nixpkgs#chromium`.

## How Etaoin runs inside kmet's SCI host

Etaoin 1.1.43 comes from Maven unchanged. Three of its dependencies cannot
load in kmet's extension context, so `deps.edn` excludes them and `src/`
provides small shims with the same namespace names:

| excluded | problem | shim |
|---|---|---|
| `cheshire` | Jackson reflection is not available in the bb native image | `cheshire.core`/`cheshire.factory` over `kmet.libs.json` |
| `babashka.http-client` | Direct use is blocked in extensions | `babashka.http-client/request` over `kmet.libs.http` (`:proxy :none`, because WebDriver runs on localhost) |
| `tools.logging` | Needs `agent`, which kmet's SCI context does not have | debug/info are dropped; warn/error go to `kmet.libs.log` |

One Etaoin call also needed a workaround: `fill` iterates
`String.codePoints()` as an `IntStream`, and that class is not exposed. The
tool sends WebDriver's Element Send Keys request directly instead.

`:loader [:sci]` only: a `jolt dist` binary cannot resolve `deps.edn` yet.

## Tests

**Offline tests** need no browser and no kmet checkout:

```sh
cd extensions/webdriver && bb test
```

They run the pure snapshot namespace over a real Chromium AX tree captured
from `test/fixtures/page.html`, in `test/fixtures/ax.edn`. The tests cover:
rendering and states, noise removal, the `interactive`/`compact`/`depth`/`urls`
options, selector scoping, numbering of duplicate names, ref stability after
the DOM changes, ref reset on navigation, and stale-ref recovery and refusal.

To regenerate the fixture after changing the page (needs chromedriver and
chromium on `PATH`):

```sh
bb --config /dev/null scripts/capture_fixture.bb
```

**Smoke test** uses a real browser. Run it from a kmet checkout:

```sh
cd ../kmet-agent
bb ../kmet-extensions/extensions/webdriver/scripts/smoke.bb
```

It loads the extension through the real SCI loader and covers:
- selector actions: go, text, fill, click (XPath), js, screenshot;
- a snapshot of the fixture page, checked to give the same refs as the
  offline test;
- fill/click/text by ref, and a scoped snapshot;
- stale-ref recovery, and refusal when the match is ambiguous;
- a new element getting the next ref, refs expiring on navigation, and an
  unknown ref;
- cancellation, and no chromedriver process left after unload.

The first run downloads Etaoin into `~/.m2`. After that, the extension loads
in about 0.3 s and a snapshot takes about 15 ms.

## Known limits / next steps

- One shared session per extension load: there are no tabs and no per-agent
  isolation yet.
- Snapshots do not include iframes, unlabeled clickable `div`s, or
  disambiguation hints (see Implementation above).
- Marking an element with `data-kmet-ref` is a DOM change, and an app that
  watches DOM attributes could see it. The fallback would be agent-browser's
  approach of dispatching mouse events through the Chrome DevTools protocol.
- Firefox/geckodriver is not wired up. In Etaoin it is one call (`e/firefox`),
  but `snapshot` would not be available there.
