;; Run from the kmet checkout:
;; bb ../kmet-extensions/codex-usage/scripts/smoke.bb
(require '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[kmet.app.commands :as commands]
         '[kmet.app.extensions :as extensions]
         '[kmet.ai.models :as models]
         '[kmet.libs.http :as http]
         '[kmet.libs.json :as json])

(let [artifact (str (fs/path (fs/parent *file*) "../src"))
      calls (atom [])
      requests (atom [])
      payload (atom {:rate_limit {:primary_window {:used_percent 25 :limit_window_seconds 18000 :reset_at 1700000000}}})
      ctx {:model {:provider :adapter :id "codex" :base-url "https://test.invalid/v1"}}
      check (fn [ok message] (when-not ok (throw (ex-info message {:type :smoke-failure}))))]
  (extensions/set-ui-registry!
   {:set-status (fn [& args] (swap! calls conj (vec (cons :status args))))
    :notify (fn [& args] (swap! calls conj (vec (cons :notify args))))})
  ;; Shared SCI vars capture host functions when the extension loads, so install
  ;; the offline HTTP/auth seams before loading, not just around command calls.
  (try
    (with-redefs [models/get-api-key-and-headers (fn [_] {:ok true :api-key "test"})
                  http/request (fn [r] (swap! requests conj r) {:status 200 :body (json/generate-string @payload)})]
      (let [loaded (extensions/load-extension! artifact)]
        (check (nil? (:error loaded)) (str "Load failed: " (:error loaded)))
        (check (= :sci (:loader-kind loaded)) "Expected real isolated SCI loader"))
      ((:extension-handler (commands/find-command "codex-usage")) ctx "")
      (check (some #(and (= :status (first %)) (str/includes? (or (nth % 2) "") "75%/5h")) @calls)
             "Footer meter missing")
      (check (some #(and (= :notify (first %)) (str/includes? (second %) "[███████████████░░░░░]")) @calls)
             "Usage card missing")
      (reset! payload {})
      ((:extension-handler (commands/find-command "codex-reset")) ctx "exact-id")
      (check (some #(and (= :post (:method %)) (= {:credit_id "exact-id"} (json/parse-string (:body %) true))) @requests)
             "Reset request missing")
      (let [before (count @calls)]
        ((:extension-handler (commands/find-command "codex-usage")) ctx "")
        (check (= :error (last (last @calls))) "Refresh failure not reported")
        (check (= (+ before 2) (count @calls)) "Refresh failure displayed a success card")))
    (finally
      (extensions/unload-all-extensions!)
      (extensions/clear-ui-registry!)))
  (check (nil? (commands/find-command "codex-usage")) "Unload leaked commands")
  (println "SCI load, usage, reset, failure, and unload smoke passed."))
