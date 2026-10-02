(ns schedule.runner-test
  "Runner integration — the orchestration core.

  Drives the real store + ledger + locks + privilege + trust with a fake api
  and a controllable clock, mirroring pi-schedule's runner.test.ts harness:
  at-most-once delivery, fire caps, the idle gate, run-now bypass,
  missed-window skip, the delivery-error advance, the trust gate, the shell
  path with redaction, notify/message kinds, and the compaction probe-send
  retry."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [schedule.runner :as runner]
            [schedule.schedule :as sched]
            [schedule.store :as store]
            [schedule.support :as support]
            [schedule.trust :as trust]))

(def t0 1735689600000)

(defn make-harness
  "A full offline harness. OPTS:
  {:shell-result {:exit n :out s :err s :killed b}
   :send-compaction-times n — first N send-user-message calls throw kmet's
                              compaction-busy rejection
   :send-throws bool        — send-user-message always throws a generic error
   :idle? bool :has-ui bool}"
  [& [{:keys [shell-result send-compaction-times send-throws idle? has-ui]}]]
  (let [layout (support/make-project)
        paths (store/paths (:agent layout))
        clock (atom t0)
        idle (atom (if (nil? idle?) true idle?))
        sent (atom [])
        messages (atom [])
        notifies (atom [])
        shell-calls (atom [])
        busy-left (atom (or send-compaction-times 0))
        handlers (atom {})
        api {:on-before-agent-start (fn [_hook] (fn [] nil))
             :agent-dir (:agent layout)
             :on-event (fn [type handler]
                         (swap! handlers update type (fnil conj []) handler)
                         (fn [] (swap! handlers update type
                                       (fn [hs] (remove #(identical? % handler) hs)))))
             :on-tool-call (fn [hook]
                             (swap! handlers update :tool-call (fnil conj []) hook)
                             (fn [] (swap! handlers update :tool-call
                                           (fn [hs] (remove #(identical? % hook) hs)))))
             :send-user-message (fn send-user-message [text & [opts]]
                                  (cond
                                    send-throws (throw (ex-info "boom" {:type :test}))
                                    (pos? @busy-left)
                                    (do (swap! busy-left dec)
                                        (throw (ex-info
                                                "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry."
                                                {:type :compaction-in-progress})))
                                    :else (swap! sent conj {:text text :opts opts})))
             :send-message! (fn [message & [opts]]
                              (swap! messages conj {:message message :opts opts})
                              nil)
             :exec (fn [& _] {:exit 0 :out "" :err ""})
             :ui {:notify (fn [msg type] (swap! notifies conj [msg type]))}}
        r (runner/make-runner api paths
                              {:now-ms #(deref clock)
                               :tick-ms 1000
                               :compaction-wait-ms 5000
                               :compaction-poll-ms 5
                               :compaction-probe-ms 100
                               :shell-exec (fn [command opts]
                                             (swap! shell-calls conj {:command command :opts opts})
                                             (or shell-result
                                                 {:exit 0 :out "ok\n" :err "" :killed false}))})
        ctx {:cwd (:project layout)
             :has-ui (if (nil? has-ui) true has-ui)
             :is-idle (fn [] @idle)
             :has-pending-messages (fn [] false)
             :mode :interactive}
        job-input (fn [m] (merge {:name "job" :prompt "do the thing"
                                  :schedule (sched/parse-schedule "every 1h")
                                  :scope :global :now-ms (- t0 7200000)}
                                 m))]
    (runner/attach r)
    {:layout layout :paths paths :runner r :ctx ctx :clock clock :idle idle
     :sent sent :messages messages :notifies notifies :shell-calls shell-calls
     :handlers handlers
     :create-global (fn [m] (store/create-job paths (job-input m)))
     :create-project (fn [m] (store/create-job paths (assoc (job-input m)
                                                            :scope :project
                                                            :project-path (:project layout)
                                                            :name "pjob")))
     :fire-due (fn [& [{:keys [source job-ids]}]]
                 (runner/fire-due r ctx {:source (or source :session-start)
                                         :job-ids job-ids}))
     :emit (fn [type payload]
             (doseq [h (get @handlers type)]
               (h payload ctx)))
     :stop! (fn [] (runner/stop-ticker! r))}))

(use-fixtures :each
  (fn [f]
    (try (f)
         (finally (doseq [t (fs/glob "target" "sched-home-*")]
                    (fs/delete-tree (str t)))))))

(defn- last-sent
  [h]
  (-> @(:sent h) last))

(defn- stored
  "The harness's freshest stored row for a job id."
  [h job-id]
  (store/get-job (:paths h) job-id (:cwd (:ctx h))))

(deftest fires-a-due-job-once
  (let [h (make-harness)
        job ((:create-global h) {})
        next-before (:next-run-at job)]
    ((:fire-due h))
    (is (= 1 (count @(:sent h))) "one isolated prompt delivered")
    (is (str/includes? (:text (last-sent h)) "[scheduled-task]"))
    (is (str/includes? (:text (last-sent h)) "PRIVILEGE: read_only"))
    (is (true? (runner/scheduled-turn-active? (:runner h))) "tier entered for the turn")
    (let [after (stored h (:id job))]
      (is (= :ok (:last-status after)))
      (is (= 1 (:run-count after)))
      (is (> (:next-run-at after) next-before) "slot advanced")
      (is (str/starts-with? (:last-idempotency-key after) (str (:id job) ":"))))))

(deftest at-most-once-delivery
  (let [h (make-harness)
        job ((:create-global h) {})]
    ((:fire-due h))
    ((:fire-due h) {:source :tick})
    (is (= 1 (count @(:sent h))) "no second delivery for the same slot")
    (let [after (stored h (:id job))]
      (is (= :ok (:last-status after)) "future slots are untouched")
      (is (= 1 (:run-count after)) "no duplicate attempt"))))

(deftest run-now-bypasses-idempotency
  (let [h (make-harness)
        job ((:create-global h) {})]
    ((:fire-due h))
    ((:fire-due h) {:source :run-now :job-ids [(:id job)]})
    (is (= 2 (count @(:sent h))) "run-now always attempts (unique force key)")
    (is (= :ok (:last-status (stored h (:id job)))))))

(deftest fire-caps-per-wave
  (let [h (make-harness)]
    (dotimes [_ 7] ((:create-global h) {}))
    ((:fire-due h) {:source :session-start})
    (is (= 5 (count @(:sent h))) "max 5 automatic fires per session-start wave")
    (let [jobs (store/list-for-cwd (:paths h) (:cwd (:ctx h)))]
      (is (= 5 (count (filter #(= :ok (:last-status %)) jobs))))
      (is (= 2 (count (filter #(nil? (:last-status %)) jobs))) "overflow stays due")))
  (let [h (make-harness)]
    (dotimes [_ 4] ((:create-global h) {}))
    ((:fire-due h) {:source :tick})
    (is (= 3 (count @(:sent h))) "max 3 per tick wave")))

(deftest tick-requires-idle
  (let [h (make-harness {:idle? false})]
    ((:create-global h) {})
    ((:fire-due h) {:source :tick})
    (is (empty? @(:sent h)))
    (reset! (:idle h) true)
    ((:fire-due h) {:source :tick})
    (is (= 1 (count @(:sent h))))))

(deftest missed-window-skip-advances-without-delivering
  (let [h (make-harness)
        job ((:create-global h) {:missed-window "skip"})]
    ;; overdue by ~2h; a 1h skip job's grace is 15m
    ((:fire-due h))
    (is (empty? @(:sent h)) "stale slots roll forward silently")
    (let [after (stored h (:id job))]
      (is (= :skipped (:last-status after)))
      (is (= 0 (:run-count after)))
      (is (> (:next-run-at after) (:next-run-at job))))))

(deftest delivery-error-advances-and-ledgers
  (let [h (make-harness {:send-throws true})
        job ((:create-global h) {})]
    ((:fire-due h))
    (is (empty? @(:sent h)))
    (let [after (stored h (:id job))]
      (is (= :error (:last-status after)))
      (is (= 1 (:run-count after)) "errors count toward maxRuns")
      (is (> (:next-run-at after) (:next-run-at job))
          "the slot advances so a broken delivery path never hot-loops")
      (is (str/includes? (or (:last-error after) "") "boom")))))

(deftest trust-gate-holds-project-jobs
  (let [h (make-harness)
        job ((:create-project h) {})]
    ((:fire-due h) {:source :session-start})
    (is (empty? @(:sent h)) "untrusted project jobs never auto-fire")
    (is (some (fn [[msg _]] (str/includes? msg "held back 1 project job")) @(:notifies h)))
    (is (nil? (:last-status (stored h (:id job)))) "held jobs stay due, untouched")
    (trust/trust! (:trust-file (:runner h)) (:cwd (:ctx h)) t0)
    ((:fire-due h) {:source :session-start})
    (is (= 1 (count @(:sent h))) "trusted project jobs fire"))
  (let [h (make-harness)
        job ((:create-project h) {})]
    ((:fire-due h) {:source :run-now :job-ids [(:id job)]})
    (is (= 1 (count @(:sent h))) "run-now bypasses the gate without trust")))

(deftest session-start-event-drives-the-wave-and-ticker
  (let [h (make-harness)
        job ((:create-global h) {})]
    ((:emit h) :session-start {:type :session-start :reason :new})
    (is (= 1 (count @(:sent h))) "the session-start event fires due jobs")
    ;; a busy agent at session start skips the wave (kmet has no CLI initial
    ;; prompt; busy covers the same anti-hijack rule)
    (let [h2 (make-harness {:idle? false})]
      ((:create-global h2) {})
      ((:emit h2) :session-start {:type :session-start :reason :new})
      (is (empty? @(:sent h2))))
    ;; a reload session-start does not process due jobs
    (let [h3 (make-harness)
          _ ((:create-global h3) {})
          _ ((:emit h3) :session-start {:type :session-start :reason :new})
          _ (reset! (:sent h3) [])
          _ ((:create-global h3) {:name "another"})
          _ ((:emit h3) :session-start {:type :session-start :reason :reload})]
      (is (empty? @(:sent h3)) "reload restarts the ticker only"))
    ((:stop! h))
    ((:stop! (make-harness)))))

(deftest shell-quiet-then-wake
  (let [h (make-harness)
        job ((:create-global h) {:kind "shell" :command "npm test" :wake-on "never"})]
    ((:fire-due h))
    (is (empty? @(:sent h)) "wake-on never keeps the agent asleep")
    (is (= 1 (count @(:shell-calls h))))
    (is (= "npm test" (:command (first @(:shell-calls h)))))
    (is (= 1 (count @(:messages h))) "the shell result is displayed")
    (let [after (stored h (:id job))]
      (is (= :ok (:last-status after)))
      (is (= 0 (:exit (:last-shell after))))
      (is (false? (runner/scheduled-turn-active? (:runner h)))
          "a quiet shell fire never pushes a tier")))
  (let [h (make-harness {:shell-result {:exit 1 :out "failed output" :err "boom" :killed false}})
        job ((:create-global h) {:kind "shell" :command "npm test" :wake-on "failure"
                                 :failurePrompt "investigate the failure"})]
    ((:fire-due h))
    (is (= 1 (count @(:sent h))) "a failed poll wakes the agent")
    (is (str/includes? (:text (last-sent h)) "## stdout"))
    (is (str/includes? (:text (last-sent h)) "failed output"))
    (is (str/includes? (:text (last-sent h)) "PRIVILEGE: mutate"))
    (is (false? (:ok (:last-shell (stored h (:id job))))))))

(deftest shell-redacts-persisted-output
  (let [h (make-harness {:shell-result {:exit 0 :out "Bearer abcdefghijklmnop" :err "" :killed false}})
        job ((:create-global h) {:kind "shell" :command "true" :wake-on "never"})]
    ((:fire-due h))
    (let [persisted (pr-str (:last-shell (stored h (:id job))))]
      (is (str/includes? persisted "[REDACTED]"))
      (is (not (str/includes? persisted "abcdefghijklmnop"))))))

(deftest notify-and-message-kinds-never-start-a-turn
  (let [h (make-harness)
        _ ((:create-global h) {:kind "notify" :prompt "stretch"})
        _ ((:create-global h) {:kind "message" :prompt "note"})]
    ((:fire-due h))
    (is (empty? @(:sent h)))
    (is (= 2 (count @(:messages h))))
    (is (some (fn [[msg type]] (and (= :info type) (str/includes? msg "stretch"))) @(:notifies h)))
    (is (false? (runner/scheduled-turn-active? (:runner h))))))

(deftest compaction-busy-retries-with-a-probe-send
  (let [h (make-harness {:send-compaction-times 1})
        job ((:create-global h) {})]
    ((:fire-due h))
    (is (= 1 (count @(:sent h))) "the probe send eventually lands")))
