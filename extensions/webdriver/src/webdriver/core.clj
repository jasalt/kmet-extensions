(ns webdriver.core
  "Drive a real browser from kmet through Etaoin (W3C WebDriver), with no
  agent-browser CLI in between. One tool, `webdriver`, holds a single lazily
  started session per extension load. `snapshot` renders the accessibility
  tree with element refs (webdriver.snapshot); actions accept `ref`."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [etaoin.api :as e]
            [kmet.extension :as ext]
            [webdriver.snapshot :as snap]))

(def ^:private max-text-chars 8000)

(def ^:private max-snapshot-chars 30000)

(def ^:private session (atom nil))

(def ^:private refs
  "Snapshot ref state (see webdriver.snapshot). Quitting drops the refs but
  keeps the counter, so a ref never names an element in a later session."
  (atom nil))

;; ─── Configuration ───────────────────────────────────────────────────────

(defn- which [names]
  (some #(some-> (fs/which %) str) names))

(defn driver-opts
  "Etaoin boot options from row CONFIG, then env, then PATH discovery.
  Keys: :chromedriver, :browser, :headless (default true), :sandbox (default
  true; false passes --no-sandbox, which containers often need), :args."
  [config]
  (let [driver (or (:chromedriver config) (System/getenv "WEBDRIVER_CHROMEDRIVER")
                   (which ["chromedriver"]))
        browser (or (:browser config) (System/getenv "WEBDRIVER_BROWSER")
                    (which ["chromium" "chromium-browser" "google-chrome" "google-chrome-stable"]))
        sandbox? (if (contains? config :sandbox)
                   (boolean (:sandbox config))
                   (not= "false" (System/getenv "WEBDRIVER_SANDBOX")))]
    (cond-> {:headless (if (contains? config :headless) (boolean (:headless config)) true)
             :args (into (cond-> ["--disable-dev-shm-usage"] (not sandbox?) (conj "--no-sandbox"))
                         (:args config))}
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
  (swap! refs select-keys [:next])
  (when-let [driver @session]
    (reset! session nil)
    (try (e/quit driver) (catch Throwable _ nil))))

;; ─── Chrome DevTools (via chromedriver's goog/cdp/execute) ───────────────

(defn- cdp
  "One Chrome DevTools command over the WebDriver session."
  [driver cmd params]
  (:value (e/execute {:driver driver :method :post
                      :path [:session (:session driver) :goog :cdp :execute]
                      :data {:cmd cmd :params params}})))

(defn- ax-nodes [driver] (:nodes (cdp driver "Accessibility.getFullAXTree" {})))

(defn- document-id
  "The main frame's loader id: it changes on every navigation, which retires refs."
  [driver]
  (get-in (cdp driver "Page.getFrameTree" {}) [:frameTree :frame :loaderId]))

(defn- subtree-backend-ids
  "DOM backend ids under the first element matching CSS SELECTOR."
  [driver selector]
  (let [root (get-in (cdp driver "DOM.getDocument" {:depth 0}) [:root :nodeId])
        node-id (:nodeId (cdp driver "DOM.querySelector" {:nodeId root :selector selector}))]
    (when (or (nil? node-id) (zero? node-id))
      (throw (ex-info (str "Selector " (pr-str selector) " did not match any element") {})))
    (letfn [(ids [n] (into #{(:backendNodeId n)} (mapcat ids)
                           (concat (:children n) (:shadowRoots n))))]
      (ids (:node (cdp driver "DOM.describeNode" {:nodeId node-id :depth -1 :pierce true}))))))

(def ^:private ref-attr "data-kmet-ref")

(defn- tag-backend-node!
  "Mark the element with BACKEND-ID with `data-kmet-ref=REF`. Returns false when
  the node no longer exists in the live document."
  [driver backend-id ref]
  (try
    (let [object-id (get-in (cdp driver "DOM.resolveNode" {:backendNodeId backend-id})
                            [:object :objectId])]
      (true? (get-in (cdp driver "Runtime.callFunctionOn"
                          {:objectId object-id
                           :functionDeclaration
                           (str "function(r){ if (!this.isConnected) return false;"
                                " document.querySelectorAll('[" ref-attr "]').forEach(e => e.removeAttribute('" ref-attr "'));"
                                " this.setAttribute('" ref-attr "', r); return true; }")
                           :arguments [{:value ref}]
                           :returnByValue true})
                     [:result :value])))
    (catch Throwable _ false)))

(defn- ref-query!
  "Resolve REF to a CSS query for a WebDriver action. The cached backend node is
  tried first; a stale node is recovered by unique role+name (as agent-browser
  does), else the error asks for a new snapshot."
  [driver ref-arg]
  (let [ref (or (snap/parse-ref ref-arg)
                (throw (ex-info (str "Not a ref: " (pr-str ref-arg) " (expected e.g. e7)") {})))
        state @refs
        entry (or (get-in state [:refs ref])
                  (throw (ex-info (str "Unknown ref " ref " (refs expire when the page changes). Take a new snapshot.") {})))
        _ (when (not= (:doc state) (document-id driver))
            (throw (ex-info (str "Ref " ref " is from a previous page. Take a new snapshot.") {})))
        ;; a connected backend node is the same element, even if its text changed
        live? (tag-backend-node! driver (:backend-id entry) ref)]
    (when-not live?
      (let [fresh (snap/recover entry (ax-nodes driver))]
        (when-not (tag-backend-node! driver fresh ref)
          (throw (ex-info (str "Ref " ref " could not be resolved. Take a new snapshot.") {})))
        (swap! refs #(-> %
                         (assoc-in [:refs ref :backend-id] fresh)
                         (update :by-backend dissoc (:backend-id entry))
                         (assoc-in [:by-backend fresh] ref)))))
    {:css (str "[" ref-attr "='" ref "']")}))

(defn take-snapshot!
  "Snapshot the page, advancing the ref state."
  [driver {:keys [selector interactive compact depth urls]}]
  (let [doc (document-id driver)
        opts (cond-> {:interactive (boolean interactive) :compact (boolean compact) :urls (boolean urls)}
               depth (assoc :depth (long depth))
               selector (assoc :within (subtree-backend-ids driver selector)))
        nodes (ax-nodes driver)
        result (volatile! nil)]
    (swap! refs (fn [state]
                  (let [r (snap/snapshot state doc nodes opts)]
                    (vreset! result r)
                    (:state r))))
    @result))

;; ─── Actions ─────────────────────────────────────────────────────────────

(defn- truncate
  ([s] (truncate s max-text-chars))
  ([s limit]
   (let [s (str s)]
     (if (> (count s) limit)
       (str (subs s 0 limit) "\n… [truncated " (- (count s) limit) " chars]")
       s))))

(defn- query-of
  "The element query for ARGS: :ref (from a snapshot), :selector (CSS) or :xpath."
  [driver {:keys [ref selector xpath]}]
  (cond ref (ref-query! driver ref)
        xpath {:xpath xpath}
        selector {:css selector}
        :else (throw (ex-info "ref, selector (CSS) or xpath is required" {}))))

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

(def ^:private actions
  #{"start" "go" "info" "snapshot" "text" "click" "fill" "wait" "js" "source" "screenshot" "quit"})

(defn- validate!
  "Reject bad ARGS before the browser starts or the page is touched."
  [{:keys [action url text ref selector xpath]}]
  (let [fail #(throw (ex-info % {}))]
    (cond
      (not (actions action)) (fail (str "Unknown action " (pr-str action) ". One of: "
                                        (str/join ", " (sort actions))))
      (and (= "go" action) (str/blank? url)) (fail "url is required")
      (and (= "fill" action) (not (string? text))) (fail "text is required (use \"\" to clear the field)")
      (and (#{"click" "fill" "wait"} action) (not (or ref selector xpath)))
      (fail "ref, selector (CSS) or xpath is required"))))

(defn run-action
  "Execute one tool ACTION against the session (started on demand)."
  [config {:keys [action url text script timeout] :as args}]
  (validate! args)
  (case action
    "quit" (do (stop!) (text-result "Browser closed."))
    (let [driver (start! config)]
      (case action
        "start" (text-result (str "Browser ready (" (if (:headless (driver-opts config)) "headless" "headed") ")."))
        "go" (do (e/go driver url)
                 (text-result (page-summary driver)))
        "info" (text-result (page-summary driver))
        "snapshot" (let [{:keys [text refs]} (take-snapshot! driver args)]
                     {:content (str (page-summary driver) "\nrefs: " refs "\n\n"
                                    (truncate text max-snapshot-chars))
                      :is-error false})
        "text" (let [q (if (or (:ref args) (:selector args) (:xpath args)) (query-of driver args) {:css "body"})]
                 (wait-for! driver q timeout)
                 (text-result (str/join "\n---\n" (map #(e/get-element-text-el driver %)
                                                       (e/query-all driver q)))))
        "click" (let [q (query-of driver args)]
                  (wait-for! driver q timeout)
                  (e/click driver q)
                  (text-result (str "Clicked.\n" (page-summary driver))))
        "fill" (let [q (query-of driver args)]
                 (wait-for! driver q timeout)
                 (e/clear driver q)
                 (send-keys! driver q text)
                 (text-result "Filled."))
        "wait" (do (wait-for! driver (query-of driver args) timeout) (text-result "Visible."))
        "js" (text-result (pr-str (e/js-execute driver (or script "return document.title;"))))
        "source" (text-result (e/get-source driver))
        "screenshot" (let [b64 (:value (e/execute {:driver driver :method :get
                                                   :path [:session (:session driver) :screenshot]}))]
                       {:content (str "Screenshot of " (e/get-url driver))
                        :images [{:data b64 :mime-type "image/png"}]
                        :is-error false})))))

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
            :description "start | go | info | snapshot | text | click | fill | wait | js | source | screenshot | quit"}
   :url {:type :string :description "go: URL to open" :optional? true}
   :ref {:type :string :description "Element ref from the latest snapshot, e.g. e7 (text/click/fill/wait)" :optional? true}
   :selector {:type :string :description "CSS selector (text/click/fill/wait); snapshot: limit to this subtree" :optional? true}
   :xpath {:type :string :description "XPath, alternative to selector" :optional? true}
   :text {:type :string :description "fill: value to type" :optional? true}
   :script {:type :string :description "js: script body; use `return` for a value" :optional? true}
   :timeout {:type :number :description "Seconds to wait for the element (default 10)" :optional? true}
   :interactive {:type :boolean :description "snapshot: only elements with refs" :optional? true}
   :compact {:type :boolean :description "snapshot: drop unnamed structure" :optional? true}
   :depth {:type :number :description "snapshot: max tree depth" :optional? true}
   :urls {:type :boolean :description "snapshot: include link URLs" :optional? true}})

(defn init [api]
  (let [config (or (:config api) {})]
    (ext/register-tool! api
                        {:name "webdriver"
                         :label "WebDriver"
                         :description (str "Control a headless Chromium through W3C WebDriver (Etaoin). "
                                           "The browser starts on first use and persists between calls. "
                                           "Use snapshot to see the page as an accessibility outline with refs "
                                           "(e.g. `button \"Save\" [ref=e7]`), then act with ref=e7. "
                                           "Refs stay valid until navigation; take a new snapshot after the page changes. "
                                           "Actions: start, go, info, snapshot, text, click, fill, wait, js, source, screenshot, quit.")
                         :params params
                         ;; one browser and one ref table: concurrent calls would race on
                         ;; start!, quit and the data-kmet-ref marker
                         :execution-mode :sequential
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
