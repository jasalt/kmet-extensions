;; Real-browser smoke: loads the extension through kmet's SCI extension loader
;; and drives headless Chromium over a local file.
;; Run from a kmet checkout (chromedriver + chromium on PATH, or
;; WEBDRIVER_CHROMEDRIVER / WEBDRIVER_BROWSER set):
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
                "<p id='out'></p></body></html>"))

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
        (let [r (run {:action "go" :url (str "file://" page)})]
          (check (str/includes? (:content r) "title: WD Smoke") "go + title"))
        (check (= "Hello webdriver" (:content (run {:action "text" :selector "#h"}))) "text by CSS")
        (check (not (:is-error (run {:action "fill" :selector "#name" :text "kmet"}))) "fill")
        (check (not (:is-error (run {:action "click" :xpath "//button[@id='go']"}))) "click by XPath")
        (check (= "Hi, kmet" (:content (run {:action "text" :selector "#out"}))) "DOM updated after click")
        (check (= "\"WD Smoke\"" (:content (run {:action "js" :script "return document.title;"}))) "js")
        (let [r (run {:action "screenshot"})
              img (first (:images r))]
          (check (and (= "image/png" (:mime-type img)) (str/starts-with? (:data img) "iVBOR"))
                 (str "screenshot PNG (" (count (:data img)) " b64 chars)")))
        (let [r (run {:action "text" :selector "#missing" :timeout 1})]
          (check (:is-error r) "missing element is a tool error, not an exception"))
        (check (:is-error ((:execute tool) {:action "info"} nil (atom true) {})) "cancel signal honored")
        (finally
          (extensions/unload-all-extensions!)))
      (Thread/sleep 500)
      (check (= before (chrome-procs)) "unload quit chromedriver (no leaked process)")
      (fs/delete page)
      (println "webdriver smoke passed."))))
