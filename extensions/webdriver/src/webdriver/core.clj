(ns webdriver.core
  "Proof of concept: drive a real browser from kmet through Etaoin (W3C
  WebDriver), with no agent-browser CLI in between. One tool, `webdriver`,
  holds a single lazily started session per extension load."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [etaoin.api :as e]
            [kmet.extension :as ext]))

(def ^:private max-text-chars 8000)

(defonce ^:private session (atom nil))

;; ─── Configuration ───────────────────────────────────────────────────────

(defn- which [names]
  (some #(some-> (fs/which %) str) names))

(defn driver-opts
  "Etaoin boot options from row CONFIG, then env, then PATH discovery.
  Keys: :chromedriver, :browser, :headless (default true), :args."
  [config]
  (let [driver (or (:chromedriver config) (System/getenv "WEBDRIVER_CHROMEDRIVER")
                   (which ["chromedriver"]))
        browser (or (:browser config) (System/getenv "WEBDRIVER_BROWSER")
                    (which ["chromium" "chromium-browser" "google-chrome" "google-chrome-stable"]))]
    (cond-> {:headless (if (contains? config :headless) (boolean (:headless config)) true)
             :args (into ["--no-sandbox" "--disable-dev-shm-usage"] (:args config))}
      driver (assoc :path-driver driver)
      browser (assoc :path-browser browser))))

;; ─── Session ─────────────────────────────────────────────────────────────

(defn- start! [config]
  (or @session
      (let [opts (driver-opts config)]
        (when-not (:path-driver opts)
          (throw (ex-info "chromedriver not found: set WEBDRIVER_CHROMEDRIVER or the row's :chromedriver config" {})))
        (reset! session (e/chrome opts)))))

(defn stop!
  "Quit the browser and the chromedriver process, if running."
  []
  (when-let [driver @session]
    (reset! session nil)
    (try (e/quit driver) (catch Throwable _ nil))))

;; ─── Actions ─────────────────────────────────────────────────────────────

(defn- truncate [s]
  (let [s (str s)]
    (if (> (count s) max-text-chars)
      (str (subs s 0 max-text-chars) "\n… [truncated " (- (count s) max-text-chars) " chars]")
      s)))

(defn- query-of [{:keys [selector xpath]}]
  (cond xpath {:xpath xpath}
        selector {:css selector}
        :else (throw (ex-info "selector (CSS) or xpath is required" {}))))

(defn- wait-for! [driver q timeout]
  (e/wait-visible driver q {:timeout (or timeout 10)}))

(defn- page-summary [driver]
  (str "url: " (e/get-url driver) "\ntitle: " (e/get-title driver)))

(defn- send-keys!
  "Element Send Keys. Etaoin's fill-el splits text via IntStream.iterator, which
  kmet's SCI class surface does not expose; WebDriver takes the whole string."
  [driver q text]
  (e/execute {:driver driver :method :post
              :path [:session (:session driver) :element (e/query driver q) :value]
              :data {:text text}}))

(defn- text-result [s] {:content (truncate s) :is-error false})

(defn run-action
  "Execute one tool ACTION against the session (started on demand)."
  [config {:keys [action url text script timeout] :as args}]
  (case action
    "quit" (do (stop!) (text-result "Browser closed."))
    (let [driver (start! config)]
      (case action
        "start" (text-result (str "Browser ready (" (if (:headless (driver-opts config)) "headless" "headed") ")."))
        "go" (do (when (str/blank? url) (throw (ex-info "url is required" {})))
                 (e/go driver url)
                 (text-result (page-summary driver)))
        "info" (text-result (page-summary driver))
        "text" (let [q (if (or (:selector args) (:xpath args)) (query-of args) {:css "body"})]
                 (wait-for! driver q timeout)
                 (text-result (str/join "\n---\n" (map #(e/get-element-text-el driver %)
                                                       (e/query-all driver q)))))
        "click" (let [q (query-of args)]
                  (wait-for! driver q timeout)
                  (e/click driver q)
                  (text-result (str "Clicked.\n" (page-summary driver))))
        "fill" (let [q (query-of args)]
                 (wait-for! driver q timeout)
                 (e/clear driver q)
                 (send-keys! driver q (str text))
                 (text-result "Filled."))
        "wait" (do (wait-for! driver (query-of args) timeout) (text-result "Visible."))
        "js" (text-result (pr-str (e/js-execute driver (or script "return document.title;"))))
        "source" (text-result (e/get-source driver))
        "screenshot" (let [b64 (:value (e/execute {:driver driver :method :get
                                                   :path [:session (:session driver) :screenshot]}))]
                       {:content (str "Screenshot of " (e/get-url driver))
                        :images [{:data b64 :mime-type "image/png"}]
                        :is-error false})
        (text-result (str "Unknown action: " action))))))

(defn- error-message [ex]
  (let [d (ex-data ex)]
    (or (get-in d [:response :value :message])
        (:message d)
        (ex-message ex)
        (some-> d :type str)
        (str ex))))

(defn execute
  "Contextual tool entry: honor cancellation, map failures to tool errors."
  [config args _on-update signal _ctx]
  (if (and signal @signal)
    {:content "Cancelled." :is-error true}
    (try (run-action config (into {} (map (fn [[k v]] [(keyword k) v])) args))
         (catch Throwable ex
           {:content (str "webdriver " (:action args) " failed: " (truncate (error-message ex)))
            :is-error true}))))

(def ^:private params
  {:action {:type :string
            :description "start | go | info | text | click | fill | wait | js | source | screenshot | quit"}
   :url {:type :string :description "go: URL to open" :optional? true}
   :selector {:type :string :description "CSS selector (text/click/fill/wait)" :optional? true}
   :xpath {:type :string :description "XPath, alternative to selector" :optional? true}
   :text {:type :string :description "fill: value to type" :optional? true}
   :script {:type :string :description "js: script body; use `return` for a value" :optional? true}
   :timeout {:type :number :description "Seconds to wait for the element (default 10)" :optional? true}})

(defn init [api]
  (let [config (or (:config api) {})]
    (ext/register-tool! api
                        {:name "webdriver"
                         :label "WebDriver"
                         :description (str "Control a headless Chromium through W3C WebDriver (Etaoin). "
                                           "The browser starts on first use and persists between calls. "
                                           "Actions: start, go, info, text, click, fill, wait, js, source, screenshot, quit.")
                         :params params
                         :contextual? true
                         :execute (fn [args on-update signal ctx]
                                    (execute config args on-update signal ctx))})))

(def plugin
  "Extension lifecycle entry point."
  {:name :ext/webdriver
   :apply (fn [_ctx api] (init api) nil)})

(defn shutdown
  "Unload/reload: never leak a browser or chromedriver process."
  [_api]
  (stop!))
