;; Run from a kmet checkout: bb /path/to/nofity-pushover/scripts/smoke.bb
;; Uses the real isolated loader and registries with offline HTTP and a temp agent dir.
(require '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[kmet.app.commands :as commands]
         '[kmet.app.event-bus :as events]
         '[kmet.app.extensions :as extensions]
         '[kmet.app.tools.core :as tools]
         '[kmet.config :as cfg]
         '[kmet.libs.http :as http]
         '[kmet.libs.json :as json])

(fs/create-dirs "target")
(let [dir (str (fs/create-temp-dir {:prefix "pushover-smoke-" :dir "target"}))
      artifact (str (fs/path (fs/parent *file*) "../src"))
      calls (atom [])
      requests (atom [])
      status (atom 200)
      ctx {:mode :interactive :signal (constantly false)}
      check (fn [ok message] (when-not ok (throw (ex-info message {:type :smoke-failure}))))]
  (spit (str (fs/path dir "nofity-pushover.json"))
        (json/generate-string {:pushover {:userKey "test-user" :appToken "test-token" :device "test-phone"}}))
  (extensions/set-ui-registry!
   {:build-context (constantly ctx)
    :set-status (fn [& args] (swap! calls conj (vec (cons :status args))))
    :notify (fn [& args] (swap! calls conj (vec (cons :notify args))))})
  (try
    ;; SCI shares host vars at load, so install all HTTP seams before loading.
    (with-redefs [cfg/get-agent-dir (constantly dir)
                  http/request (fn [request]
                                 (swap! requests conj request)
                                 {:status @status :body (if (= @status 200) "{\"status\":1}" "denied")})]
      (let [loaded (extensions/load-extension! artifact)]
        (check (nil? (:error loaded)) (str "Load failed: " (:error loaded)))
        ;; Older kmet-agent load results predate the :loader-kind field.
        (when (contains? loaded :loader-kind)
          (check (= :sci (:loader-kind loaded)) "Expected actual isolated SCI loader")))
      (check (some? (tools/get-tool "notify_human")) "Original tool name not registered")
      (check (some? (commands/find-command "notify-human-test")) "Manual test command missing")
      (events/emit-event! {:type :session-start})
      (check (empty? @requests) "Session start sent an automatic alert")
      (check (some #(= [:status "nofity-pushover" "human notify: ready"] %) @calls)
             "Ready footer status missing")
      (let [signal (atom false)
            result (tools/execute-tool "notify_human" {:message "Need human approval" :priority 1}
                                       {:ctx ctx :signal signal})
            request (first @requests)]
        (check (not (:is-error result)) "Tool request failed")
        (check (= 200 (get-in result [:details :status])) "Tool result details missing")
        (check (str/includes? (:content result) "Pushover notification sent (200)") "Tool success message missing")
        (check (= "https://api.pushover.net/1/messages.json" (:url request)) "Wrong Pushover endpoint")
        (check (identical? signal (:signal request)) "Tool abort atom not forwarded")
        (check (str/includes? (:body request) "priority=1") "Tool parameters were not encoded")
        (reset! signal true)
        (let [before (count @requests)]
          (check (:is-error (tools/execute-tool "notify_human" {:message "Cancelled"}
                                                {:ctx ctx :signal signal})) "Cancelled tool did not fail")
          (check (= before (count @requests)) "Cancelled tool sent an alert")))
      ((:extension-handler (commands/find-command "notify-human-test")) ctx "Offline command test")
      (check (= [:notify "Pushover test sent (200)." :info] (last @calls)) "Manual test result missing")
      (reset! status 400)
      (check (:is-error (tools/execute-tool "notify_human" {:message "Simulated failure"} {:ctx ctx}))
             "HTTP failure was reported as success")
      ((:extension-handler (commands/find-command "notify-human-test")) ctx "Simulated failure")
      (check (= :error (last (last @calls))) "Manual test failure not reported"))
    (finally
      (extensions/unload-all-extensions!)
      (extensions/clear-ui-registry!)
      (fs/delete-tree dir)))
  (check (nil? (tools/get-tool "notify_human")) "Unload leaked notify_human")
  (check (nil? (commands/find-command "notify-human-test")) "Unload leaked command")
  (check (= [:status "nofity-pushover" nil] (last @calls)) "Unload left footer status")
  (println "SCI load, notify_human, command, abort, failure, and unload smoke passed (offline)."))
