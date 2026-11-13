# webdriver (proof of concept)

Browser control for kmet through [Etaoin](https://github.com/clj-commons/etaoin)
and W3C WebDriver, running in-process in the extension. It does not use an
agent-browser CLI wrapper.

One tool, `webdriver`, controls a headless Chromium session. The session
starts on first use and stays open between calls:

| action | args | result |
|---|---|---|
| `start` | – | starts the browser |
| `go` | `url` | navigates, then returns the URL and title |
| `info` | – | returns the current URL and title |
| `text` | `selector`/`xpath` (default `body`) | returns visible text, truncated to 8000 chars |
| `click` | `selector`/`xpath` | waits for the element to be visible, then clicks it |
| `fill` | `selector`/`xpath`, `text` | clears the field, then types the text |
| `wait` | `selector`/`xpath`, `timeout` | waits until the element is visible (default 10 s) |
| `js` | `script` | runs JavaScript; use `return` to get a value |
| `source` | – | returns the page HTML |
| `screenshot` | – | returns a PNG as a tool image attachment |
| `quit` | – | closes the browser and chromedriver |

Unloading or reloading the extension calls `shutdown`, which quits the browser
and the driver process.

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

## Smoke test

This test uses a real browser. Run it from a kmet checkout:

```sh
cd ../kmet-agent
bb ../kmet-extensions/extensions/webdriver/scripts/smoke.bb
```

It loads the extension through the real SCI loader and runs these actions
against a local page: go, text (CSS), fill, click (XPath), check the DOM
change, js, and screenshot. It also checks error handling for a missing
element and for cancellation, and confirms that unload leaves no chromedriver
process running. The first run downloads Etaoin into `~/.m2`. After that, the
extension loads in about 0.3 s.

## Known limits / next steps

- One shared session per extension load: there are no tabs and no per-agent
  isolation yet.
- Results are raw text and HTML. There is no compact accessibility
  snapshot or element references, which agent-browser tools provide.
- There is no offline unit test with a fake driver yet. Only the real-browser
  smoke test exists.
- Firefox/geckodriver is not wired up. In Etaoin it is one call (`e/firefox`).
