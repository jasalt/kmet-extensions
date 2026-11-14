;; Real-browser smoke: loads the extension through kmet's SCI extension loader
;; and drives headless Chromium over a local file.
;; Run from a kmet checkout (chromedriver + chromium on PATH, or
;; WEBDRIVER_CHROMEDRIVER / WEBDRIVER_BROWSER set; WEBDRIVER_SANDBOX=false in
;; containers where Chromium cannot sandbox):
;;   bb ../kmet-extensions/extensions/webdriver/scripts/smoke.bb
(require '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[kmet.app.extensions.core :as extensions]
         '[kmet.app.registry :as registry]
         '[kmet.app.test-support :as support]
         '[kmet.app.tools.registry :as tools])

(def artifact (str (fs/normalize (fs/absolutize (fs/path (fs/parent *file*) "../src")))))

(defn check [ok msg] (when-not ok (throw (ex-info msg {}))) (println "ok -" msg))

(defn chrome-procs []
  (->> (:out (babashka.process/sh "pgrep" "-f" "chromedriver"))
       str/split-lines (remove str/blank?) count))

(def page (str (fs/create-temp-file {:prefix "webdriver-smoke-" :suffix ".html"})))
(spit page (str "<html><head><title>WD Smoke</title></head><body>"
                "<h1 id='h'>Hello webdriver</h1>"
                "<input id='name'><button id='go' onclick=\"document.getElementById('out').textContent="
                "'Hi, '+document.getElementById('name').value\">Go</button>"
                "<p id='out'></p><div id='host'></div>"
                "<script>document.getElementById('host').attachShadow({mode:'open'})"
                ".innerHTML='<button>Shadow</button>'</script></body></html>"))

(support/with-composition
  (fn []
    (let [before (chrome-procs)
          t0 (System/currentTimeMillis)
          loaded (extensions/load-extension! artifact)
          _ (check (nil? (:error loaded)) (str "loaded via " (:loader-kind loaded) " in "
                                               (- (System/currentTimeMillis) t0) "ms " (:error loaded)))
          tool (tools/get-tool (registry/current) "webdriver")
          run (fn [args]
                (let [r ((:execute tool) args nil (atom false) {})]
                  (when (:is-error r) (println "  !" (:content r)))
                  r))]
      (try
        (check (some? tool) "webdriver tool registered")
        (check (= :sequential (:execution-mode tool)) "calls run sequentially")
        (let [r (run {:action "nope"})]
          (check (and (:is-error r) (str/includes? (:content r) "Unknown action")
                      (= before (chrome-procs)))
                 "unknown action is an error and starts no browser"))
        (let [r (run {:action "go" :url (str "file://" page)})]
          (check (str/includes? (:content r) "title: WD Smoke") "go + title"))
        (check (= "Hello webdriver" (:content (run {:action "text" :selector "#h"}))) "text by CSS")
        (check (:is-error (run {:action "fill" :selector "#name"})) "fill without text is refused")
        (check (not (:is-error (run {:action "fill" :selector "#name" :text "kmet"}))) "fill")
        (check (not (:is-error (run {:action "click" :xpath "//button[@id='go']"}))) "click by XPath")
        (check (= "Hi, kmet" (:content (run {:action "text" :selector "#out"}))) "DOM updated after click")
        (check (= "\"WD Smoke\"" (:content (run {:action "js" :script "return document.title;"}))) "js")
        (let [r (run {:action "screenshot"})
              img (first (:images r))]
          (check (and (= "image/png" (:mime-type img)) (str/starts-with? (:data img) "iVBOR"))
                 (str "screenshot PNG (" (count (:data img)) " b64 chars)")))
        ;; ── snapshot + refs over the offline-test fixture page ──
        (let [fixture (str "file://" (fs/path (fs/parent artifact) "test/fixtures/page.html"))
              ref-of (fn [text pattern]
                       (some #(when (str/includes? % pattern) (second (re-find #"ref=(e\d+)" %)))
                             (str/split-lines text)))
              _ (run {:action "go" :url fixture})
              t0 (System/currentTimeMillis)
              snap (:content (run {:action "snapshot" :interactive true}))
              ms (- (System/currentTimeMillis) t0)
              save (ref-of snap "button \"Save\"")
              name-ref (ref-of snap "textbox \"Name\"")]
          (check (and (str/includes? snap "refs: 20") (= "e11" save) (= "e6" name-ref))
                 (str "snapshot refs match the offline fixture (" ms "ms)"))
          (check (not (:is-error (run {:action "fill" :ref name-ref :text "Grace"}))) "fill by ref")
          (check (not (:is-error (run {:action "click" :ref "@e11"}))) "click by @ref")
          (check (= "Saved Grace" (:content (run {:action "text" :selector "#out"}))) "ref actions hit the right elements")
          (check (= "pro" (:content (run {:action "text" :ref "e20"}))) "text by ref (table cell)")
          (let [scoped (:content (run {:action "snapshot" :selector "#form" :interactive true}))]
            (check (and (str/includes? scoped "form \"Profile\" [ref=e5]")
                        (not (str/includes? scoped "navigation")))
                   "selector limits the outline, refs keep page-wide numbers"))
          ;; Re-render the Delete button: its node is replaced, so the ref is recovered by role+name.
          (run {:action "js" :script "const b=[...document.querySelectorAll('button')].find(x=>x.textContent==='Delete'); b.replaceWith(b.cloneNode(true)); return 1;"})
          (check (not (:is-error (run {:action "wait" :ref "e12" :timeout 2}))) "stale ref recovered by unique role+name")
          (run {:action "js" :script "document.querySelectorAll('li button').forEach(b=>b.replaceWith(b.cloneNode(true))); return 1;"})
          (let [r (run {:action "click" :ref "e15"})]
            (check (and (:is-error r) (str/includes? (:content r) "new snapshot"))
                   "ambiguous stale ref refused with a re-snapshot hint"))
          (check (= "e21" (do (run {:action "js" :script "const b=document.createElement('button');b.textContent='Undo';document.querySelector('form').prepend(b);return 1;"})
                              (ref-of (:content (run {:action "snapshot" :interactive true})) "Undo")))
                 "new element gets the next ref; existing refs stay stable")
          (run {:action "go" :url (str "file://" page)})
          (let [r (run {:action "click" :ref "e11"})]
            (check (and (:is-error r) (str/includes? (:content r) "previous page"))
                   "refs expire on navigation"))
          (let [snap (:content (run {:action "snapshot"}))
                shadow (run {:action "click" :ref (ref-of snap "button \"Shadow\"")})]
            (check (< 21 (parse-long (subs (ref-of snap "button \"Go\"") 1)))
                   "the next page continues numbering, so old refs are never reused")
            (check (and (:is-error shadow) (str/includes? (:content shadow) "shadow root"))
                   "a ref inside a shadow root is refused with a clear error"))
          (check (:is-error (run {:action "click" :ref "e99"})) "unknown ref is a tool error"))
        (let [r (run {:action "text" :selector "#missing" :timeout 1})]
          (check (:is-error r) "missing element is a tool error, not an exception"))
        (check (:is-error ((:execute tool) {:action "info"} nil (atom true) {})) "cancel signal honored")
        (finally
          (extensions/unload-all-extensions!)))
      (Thread/sleep 500)
      (check (= before (chrome-procs)) "unload quit chromedriver (no leaked process)")
      (fs/delete page)
      (println "webdriver smoke passed."))))
