(ns schedule.runner
  "Due-job runner with reliability controls.

  Port of pi-schedule src/runner.ts. Fires on:
    1. :session-start (:startup/:new/:resume/:continue/:fork) when jobs are
       due and the agent is not already busy with the user's own prompt
    2. an in-session ticker (a concurrent/spawn daemon thread), only while
       idle

  Action kinds: :prompt (inject isolated agent task), :shell (run command,
  optional agent wake via :wake-on), :notify (UI/console reminder),
  :message (session custom message, no agent turn). Mitigations: idempotency
  keys + re-check after lock, single-flight job locks, missed-window policy,
  fire caps per wave, the project trust gate for auto-fire, privilege tiers
  pushed when a delivery starts a turn, and a bounded probe-send retry loop
  around kmet's 'compaction in progress' rejection.

  Clock discipline (kmet AGENTS.md §Time): due/next-run math and persisted
  timestamps are wall-clock millis; the ticker cadence, compaction park
  loop, and error-notify cooldown run on the monotonic clock."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [kmet.libs.concurrent :as concurrent]
            [schedule.action :as action]
            [schedule.ledger :as ledger]
            [schedule.lock :as lock]
            [schedule.policy :as policy]
            [schedule.privilege :as privilege]
            [schedule.prompt :as prompt]
            [schedule.schedule :as sched]
            [schedule.store :as store]
            [schedule.trust :as trust]))

(def fire-on-reasons
  "kmet session-start reasons that process due jobs — everything except
  :reload (a reload re-emits :session-start for the same session; the ticker
  restarts either way)."
  #{:startup :new :resume :continue :fork})

(def compaction-poll-ms 500)
(def compaction-probe-ms 5000)
(def compaction-wait-max-ms 120000)
(def error-notify-cooldown-ms 300000)

(def ^:private win-git-bash-paths
  "Candidate Git Bash binaries on Windows (pi resolveShell): the bare 'bash'
  can resolve to the WSL launcher, which exits non-zero with no execution."
  ["C:\\Program Files\\Git\\bin\\bash.exe"
   "C:\\Program Files\\Git\\usr\\bin\\bash.exe"
   "C:\\Program Files (x86)\\Git\\bin\\bash.exe"])

(defn resolve-shell
  "The shell binary for shell jobs: KMET_SCHEDULE_SHELL (absolute path) wins
  outright; on Windows prefer the first existing Git Bash; else 'bash'."
  []
  (or (some-> (System/getenv "KMET_SCHEDULE_SHELL") str/trim not-empty)
      (when (fs/windows?)
        (some #(when (fs/exists? %) %) win-git-bash-paths))
      "bash"))

(defn default-shell-exec
  "The real shell executor (pi deliverShell's pi.exec): runs
  `<shell> -lc <command>` in CWD with a TIMEOUT-MS deadline. Uses
  babashka.process directly — a deref deadline plus destroy is the only way
  to distinguish a killed/timeout run from a plain exit code, which the
  wake-on decision and the persisted :last-shell need. Returns
  {:exit n :out str :err str :killed bool}."
  [command {:keys [cwd timeout-ms]}]
  (let [p (proc/process [(resolve-shell) "-lc" command]
                        (cond-> {:out :string :err :string}
                          cwd (assoc :dir cwd)))
        r (try (deref p (or timeout-ms action/default-shell-timeout-ms) ::timeout)
               (catch Exception _ {:exception true}))]
    (cond
      (= ::timeout r)
      (do (try (proc/destroy p) (catch Exception _))
          (let [final (try (deref p 2000 nil) (catch Exception _ nil))]
            {:exit (or (:exit final) -1)
             :out (str (or (:out final) ""))
             :err (str (or (:err final) ""))
             :killed true}))

      (map? r) {:exit (:exit r) :out (str (or (:out r) "")) :err (str (or (:err r) "")) :killed false}
      :else {:exit -1 :out "" :err "shell execution failed" :killed false})))

(defn make-runner
  "A fresh runner state map for API + store PATHS. OPTS (tests):
  :now-ms (0-arg fn, default wall clock), :shell-exec (fn [command opts]),
  :tick-ms, :compaction-wait-ms, :compaction-poll-ms, :compaction-probe-ms."
  ([api paths] (make-runner api paths {}))
  ([api paths {:keys [now-ms shell-exec tick-ms compaction-wait-ms
                      compaction-poll-ms compaction-probe-ms]}]
   {:api api
    :paths paths
    :runs-file (:runs-file paths)
    :trust-file (:trust-file paths)
    :locks (lock/make-lock-manager (:lock-dir paths))
    :privilege (privilege/make-privilege)
    :tick-ms (or tick-ms policy/tick-ms)
    :now-ms (or now-ms #(System/currentTimeMillis))
    :shell-exec (or shell-exec default-shell-exec)
    :compaction-wait-ms (or compaction-wait-ms compaction-wait-max-ms)
    :compaction-poll-ms (or compaction-poll-ms schedule.runner/compaction-poll-ms)
    :compaction-probe-ms (or compaction-probe-ms schedule.runner/compaction-probe-ms)
    :stop (atom false)
    :thread (atom nil)
    :ticker-generation (atom 0)
    :ctx (atom nil)
    :cwd (atom nil)
    :wave-lock (Object.)
    :wave-active (atom false)
    :compacting (atom false)
    :last-error-notify (atom {:at 0 :key ""})}))

(defn scheduled-turn-active?
  "True while a scheduled delivery is the active agent turn (the privilege
  stack is non-empty) — gates implicit project trust on create (a fired
  turn must not be able to unlock its own project's gate)."
  [runner]
  (privilege/scheduled-turn-active? (:privilege runner)))

(defn stop-ticker!
  "Stop the ticker thread (session shutdown / new start / unload)."
  [runner]
  (reset! (:stop runner) true)
  (swap! (:ticker-generation runner) inc)
  nil)

(defn shutdown
  "Full teardown on unload (/reload): stop the ticker and clear the
  privilege stack. Every registration is deregistered by the host."
  [runner]
  (stop-ticker! runner)
  (privilege/clear! (:privilege runner))
  (reset! (:ctx runner) nil)
  nil)

;; ─── Notification helpers ──────────────────────────────────────────────────

(defn- note-ctx!
  "Capture the freshest ctx/cwd (handlers receive a fresh ctx per event; its
  fns read live state, so a captured ctx stays usable for the ticker)."
  [runner ctx]
  (reset! (:ctx runner) ctx)
  (when-let [cwd (:cwd ctx)] (reset! (:cwd runner) cwd)))

(defn- notify-info
  "Info-level notify (UI or console) — no cooldown."
  [runner ctx msg]
  (if (:has-ui ctx)
    (ext/ui-notify (:api runner) msg :info)
    (println msg)))

(defn- notify-error
  "Error notify (UI or console) with a 5-minute per-key cooldown."
  [runner ctx msg error-key]
  (let [{:keys [at key]} @(:last-error-notify runner)
        now (concurrent/monotonic-ms)]
    (when-not (and (= error-key key) (< (- now at) error-notify-cooldown-ms))
      (reset! (:last-error-notify runner) {:at now :key error-key})
      (if (:has-ui ctx)
        (ext/ui-notify (:api runner) msg :error)
        (binding [*out* *err*] (println msg))))))

(defn- notify-trust-gate
  "Tell the user project jobs were held back by the trust gate (once per
  session-start wave)."
  [runner ctx gated]
  (let [names (str/join ", " (map (fn [j] (str "\"" (:name j) "\" (" (:id j) ")")) gated))]
    (notify-info runner ctx
                 (str "[schedule] held back " (count gated) " project job(s) — this project is not trusted: "
                      names ". Inspect .kmet/schedule.edn (untrusted files can carry shell jobs), "
                      "then allow auto-fire with: schedule action=trust"))))

;; ─── Prompt delivery ──────────────────────────────────────────────────────

(defn- compaction-busy?
  "Recognize kmet's prompt-submission rejection while context compaction is
  in flight: the structured {:type :compaction-in-progress} ex-data is
  authoritative; the message match is a belt-and-braces fallback."
  [e]
  (or (= :compaction-in-progress (:type (ex-data e)))
      (str/includes? (str (ex-message e)) "compaction is in progress")))

(defn- send-now
  "One submission attempt — the original synchronous send (pi sendNow): a
  busy agent (or an explicit deliver-as) queues via :follow-up."
  [runner body ctx deliver-as]
  (let [busy? (if (fn? (:is-idle ctx)) (not ((:is-idle ctx))) true)]
    (if (or deliver-as busy?)
      (ext/send-user-message (:api runner) body {:deliver-as (or deliver-as :follow-up)})
      (ext/send-user-message (:api runner) body))))

(defn- send-agent-message
  "Submit the agent message, waiting out in-flight context compaction
  (pi sendAgentMessage): the attempt itself is the probe — kmet raises the
  rejection at the top of send-user-message before anything is queued, so a
  doomed attempt is side-effect-free and always happens first (this covers
  a stale :compacting flag). While the throw says compaction is running,
  park on the flag with a probe send every :compaction-probe-ms (a cancelled
  compaction never emits :compaction-end — only a probe discovers it),
  bounded by :compaction-wait-ms so a stuck compaction degrades to the
  normal delivery-error path, never a hung wave."
  [runner body ctx deliver-as]
  (let [deadline (+ (concurrent/monotonic-ms) (:compaction-wait-ms runner))]
    (loop []
      (let [outcome (try
                      (send-now runner body ctx deliver-as)
                      {:ok true}
                      (catch Exception e
                        (if (or (not (compaction-busy? e))
                                (>= (concurrent/monotonic-ms) deadline))
                          {:error e}
                          {:park (let [park-until (min (+ (concurrent/monotonic-ms)
                                                          (:compaction-probe-ms runner))
                                                       deadline)]
                                   (loop []
                                     (when (and @(:compacting runner)
                                                (< (concurrent/monotonic-ms) park-until))
                                       (Thread/sleep (:compaction-poll-ms runner))
                                       (recur))))})))]
        (cond
          (:ok outcome) (do (reset! (:compacting runner) false) nil)
          (:error outcome) (throw (:error outcome))
          :else (recur))))))

;; ─── Delivery (pi deliver / deliverShell) ────────────────────────────────

(defn- submit-scheduled!
  [runner body tier ctx deliver-as]
  (let [token (privilege/reserve! (:privilege runner) body tier)]
    (try
      (send-agent-message runner body ctx deliver-as)
      (catch Exception e
        (privilege/cancel! (:privilege runner) token)
        (throw e)))))

(defn- deliver-shell
  "Run a shell job's command and decide the wake (pi deliverShell). The
  persisted/display copies are redacted; the transient follow-up prompt
  keeps the full output (the agent needs it, and it lives only in session
  context). A global shell job has no project path, so it runs in the
  session cwd — a relative command is session-dependent (pi's documented
  limitation)."
  [runner ctx job {:keys [run-id source forced? deliver-as]}]
  (let [command (some-> (:command job) str/trim not-empty)]
    (when-not command
      (throw (ex-info (str "shell job \"" (:name job) "\" has no command")
                      {:type :schedule-run-error})))
    (let [cwd (or (:project-path job) (:cwd ctx) @(:cwd runner))
          timeout-ms (or (:timeout-ms job) action/default-shell-timeout-ms)]
      (notify-info runner ctx (str "[schedule] running shell \"" (:name job) "\": " command))
      (let [raw ((:shell-exec runner) command {:cwd cwd :timeout-ms timeout-ms})
            last-shell {:ok (action/shell-ok? raw)
                        :command command
                        :cwd cwd
                        :timeout-ms timeout-ms
                        :exit (:exit raw)
                        :killed (boolean (:killed raw))
                        :stdout (action/truncate-output (:out raw))
                        :stderr (action/truncate-output (:err raw))}
            persisted (-> last-shell
                          (update :stdout prompt/redact-secrets)
                          (update :stderr prompt/redact-secrets))]
        (try (ext/send-message! (:api runner)
                                {:custom-type :schedule
                                 :content (str "Shell \"" (:name job) "\" exit " (:exit last-shell)
                                               (when (:killed last-shell) " (killed)") ": " command)
                                 :display true
                                 :details {:job-id (:id job) :kind :shell
                                           :run-id run-id :result persisted}}
                                {:trigger-turn false})
             (catch Exception _))
        (let [woke-agent (if (action/should-wake? job last-shell)
                           (if-let [instruction (action/select-shell-follow-up job last-shell)]
                             (do (submit-scheduled! runner
                                                    (prompt/build-shell-follow-up-prompt
                                                     {:job job :run-id run-id :source source
                                                      :forced? forced? :result last-shell
                                                      :instruction instruction})
                                                    (:tier job) ctx deliver-as)
                                 true)
                             false)
                           false)]
          {:detail (str "shell exit=" (:exit last-shell)
                        (when (:killed last-shell) " killed")
                        (when woke-agent " woke"))
           :woke-agent woke-agent
           :last-shell persisted})))))

(defn- deliver
  "Deliver one job by kind. Returns {:detail str :woke-agent bool
  :last-shell map?}. :notify/:message/:quiet-shell never start an agent
  turn (no privilege push)."
  [runner ctx job {:keys [run-id source forced? deliver-as]}]
  (case (action/as-kind (:kind job))
    :notify
    (let [msg (prompt/notify-label job)]
      (notify-info runner ctx msg)
      (try (ext/send-message! (:api runner)
                              {:custom-type :schedule
                               :content msg
                               :display true
                               :details {:job-id (:id job) :kind :notify :run-id run-id}}
                              {:trigger-turn false})
           (catch Exception _))
      {:detail "notify" :woke-agent false})

    :message
    (do (try (ext/send-message! (:api runner)
                                {:custom-type :schedule
                                 :content (or (some-> (:prompt job) str/trim not-empty) (:name job))
                                 :display true
                                 :details {:job-id (:id job) :kind :message :run-id run-id}}
                                {:trigger-turn false})
             (catch Exception e
               ;; no custom-message channel on this build — surface to the
               ;; console so the message is delivered somewhere
               (println (prompt/notify-label job))))
        {:detail "message" :woke-agent false})

    :shell
    (deliver-shell runner ctx job {:run-id run-id :source source
                                   :forced? forced? :deliver-as deliver-as})

    ;; :prompt (default) — the isolated agent task contract
    (let [body (prompt/build-fire-prompt {:job job :run-id run-id
                                          :source source :forced? forced?})]
      (submit-scheduled! runner body (:tier job) ctx deliver-as)
      {:detail "prompt" :woke-agent true})))

;; ─── One due job (pi processOne) ──────────────────────────────────────────

(defn- already-delivered?
  "Durable primary signal: the job row's key + ok status; the ledger window
  is secondary (pi alreadyDelivered)."
  [runner job key]
  (or (and (= key (:last-idempotency-key job)) (= :ok (:last-status job)))
      (ledger/was-delivered? (:runs-file runner) key)))

(defn- process-one
  "Process one due job (pi processOne): pre-lock idempotency, missed-window
  policy, the fire cap, the single-flight lock, a post-lock idempotency
  re-check on a fresh re-read, delivery, privilege enter when the delivery
  started an agent turn, store advance BEFORE the ledger write, terminal
  handling, and the error path (advance so a broken delivery never
  hot-loops). Returns the updated job, or nil when held (over-cap or lock
  contention — the job stays due, no ledger spam)."
  [runner ctx job {:keys [source forced? deliver-as allow-fire?]}]
  (let [now-ms ((:now-ms runner))
        run-id (ledger/new-run-id)
        key (if forced?
              (str (:id job) ":force:" run-id)
              (policy/idempotency-key job))
        lookup-cwd (or (:cwd ctx) @(:cwd runner) (str (fs/cwd)))
        run-row (fn [status subject detail idem-key]
                  (ledger/build-run {:run-id run-id
                                     :job-id (:id subject) :job-name (:name subject)
                                     :scope (:scope subject) :project-path (:project-path subject)
                                     :idempotency-key idem-key :source source :status status
                                     :started-at now-ms :ended-at ((:now-ms runner))
                                     :detail detail
                                     :tier (:tier subject) :missed-window (:missed-window subject)
                                     :kind (:kind subject)}))
        skip! (fn [subject detail]
                (let [advanced (store/mark-attempt (:paths runner) subject now-ms :skipped
                                                   {:error detail
                                                    :idempotency-key (policy/idempotency-key subject)})]
                  (ledger/append! (:runs-file runner) (run-row :skipped subject detail
                                                               (policy/idempotency-key subject)))
                  advanced))]
    (cond
      ;; pre-lock idempotency (cheap)
      (and (not forced?) (already-delivered? runner job key))
      (skip! job "idempotent_replay")

      ;; missed-window policy (run-now bypasses)
      (and (not forced?)
           (let [d (policy/decide-due job now-ms (:tick-ms runner))]
             (= :skip (:action d))))
      (skip! job (:reason (policy/decide-due job now-ms (:tick-ms runner))))

      ;; over cap: stay due, do NOT write ledger spam (busy flood)
      (not allow-fire?) nil

      ;; single-flight lock; locked = silent retry (no advance, no ledger)
      :else
      (when-let [handle ((:try-acquire (:locks runner)) (:id job))]
        (try
          (let [fresh (store/get-job (:paths runner) (:id job) lookup-cwd)
                fresh-key (if forced? key (when fresh (policy/idempotency-key fresh)))]
            (when (and fresh
                       (or forced?
                           (and (:enabled fresh) (not (:terminated fresh))
                                (= key fresh-key)
                                (<= (:next-run-at fresh) now-ms))))
              (or
            ;; re-check after lock (check-then-act fix)
               (when (and (not forced?) (already-delivered? runner fresh fresh-key))
                 (skip! fresh "idempotent_replay_post_lock"))
               (let [{:keys [detail woke-agent last-shell]}
                     (deliver runner ctx fresh {:run-id run-id :source source
                                                :forced? forced? :deliver-as deliver-as})
                  ;; structural tier enforcement only when an agent turn started
                     _ woke-agent
                  ;; store advances FIRST (durable); the ledger is best-effort
                     updated (store/mark-attempt (:paths runner) fresh now-ms :ok
                                                 {:idempotency-key fresh-key :last-shell last-shell})
                     term (action/terminal-reason updated (:run-count updated))
                     final (if term (store/terminate (:paths runner) updated term) updated)]
                 (ledger/append! (:runs-file runner)
                                 (run-row :delivered final
                                          (str detail (when term (str " terminated:" (name term))))
                                          fresh-key))
                 final))))
          (catch Exception e
            (let [message (ex-message e)]
              (if (:has-ui ctx)
                (ext/ui-notify (:api runner)
                               (str "[schedule] failed to fire \"" (:name job) "\": " message)
                               :error)
                (binding [*out* *err*]
                  (println (str "[schedule] failed to fire \"" (:name job) "\": " message))))
             ;; advance on error so a broken delivery path never hot-loops;
             ;; re-read the freshest row inside the lock like the success path
              (let [error-subject (or (store/get-job (:paths runner) (:id job) lookup-cwd) job)
                    error-key (if forced? key (policy/idempotency-key error-subject))
                    updated (store/mark-attempt (:paths runner) error-subject now-ms :error
                                                {:error message :idempotency-key error-key})
                    term (action/terminal-reason updated (:run-count updated))
                    final (if term (store/terminate (:paths runner) updated term) updated)]
                (ledger/append! (:runs-file runner)
                                (run-row :error final
                                         (str message (when term (str " terminated:" (name term))))
                                         error-key))
                final)))
          (finally
            ((:release handle))))))))

;; ─── Waves (pi runWave / fireDue) ─────────────────────────────────────────

(defn- eligible-for-auto-fire?
  "Global jobs always are; project jobs need a trusted project root — a
  cloned .kmet/schedule.edn can carry shell/mutate rows (arbitrary code
  execution), so automatic waves never fire them from an untrusted root."
  [runner job ctx]
  (if (not= :project (:scope job))
    true
    (let [root (or (:project-path job) (:cwd ctx) @(:cwd runner))]
      (trust/trusted? (:trust-file runner) root))))

(defn- run-wave
  "One wave of due jobs (pi runWave). Returns the updated jobs. Auto waves
  drop when another wave is active (checked in fire-due); run-now waits on
  the wave lock instead. The trust gate holds untrusted project jobs back
  (they stay due, untouched, no ledger rows) and reports once per
  session-start wave."
  [runner ctx {:keys [source job-ids]}]
  (try
    (let [lookup-cwd (or (:cwd ctx) @(:cwd runner) (str (fs/cwd)))
          now-ms ((:now-ms runner))
          candidates (if (seq job-ids)
                       (keep #(store/get-job (:paths runner) % lookup-cwd) job-ids)
                       (if (and (= :tick source)
                                (fn? (:is-idle ctx))
                                (not ((:is-idle ctx))))
                         []
                         (store/due-jobs (:paths runner) lookup-cwd now-ms)))]
      (if (empty? candidates)
        []
        (let [gated (when (not= :run-now source)
                      (filterv #(not (eligible-for-auto-fire? runner % ctx)) candidates))
              allowed (if (= :run-now source)
                        candidates
                        (filterv #(eligible-for-auto-fire? runner % ctx) candidates))]
          (when (and (empty? allowed) (= :session-start source) (seq gated))
            (notify-trust-gate runner ctx gated))
          (if (empty? allowed)
            []
            (let [max-fires (case source
                              :session-start (:max-fires-per-session-start policy/limits)
                              :tick (:max-fires-per-tick policy/limits)
                              ;; :run-now — everything the caller asked for
                              Long/MAX_VALUE)
                  results (atom [])
                  attempts (atom 0)]
              (doseq [job allowed :while (not @(:stop runner))]
                (when-let [result (process-one runner ctx job
                                               {:source source
                                                :forced? (= :run-now source)
                                                :deliver-as (if (zero? @attempts) nil :follow-up)
                                                :allow-fire? (or (= :run-now source)
                                                                 (< @attempts max-fires))})]
                  (swap! results conj result)
                  (when (contains? #{:ok :error} (:last-status result))
                    (swap! attempts inc))))
              (when (and (= :session-start source) (seq gated))
                (notify-trust-gate runner ctx gated))
              @results)))))
    (catch Exception e
      (if (= :run-now source)
        (throw e)
        (let [store-error? (= :schedule-store-error (:type (ex-data e)))
              prefix (if store-error? "store error: " "runner error: ")]
          (notify-error runner ctx
                        (str "[schedule] " prefix (ex-message e))
                        (str (if store-error? "store:" "runner:") (ex-message e)))
          [])))))

(defn fire-due
  "Process due jobs (or forced job ids). Auto waves drop when another wave
  is active; :run-now serializes on the wave lock (never a silent no-op).
  Returns the updated jobs (pi fireDue)."
  [runner ctx {:keys [source job-ids]}]
  (locking (:wave-lock runner)
    (if (and (not= :run-now source) @(:wave-active runner))
      []
      (do (reset! (:wave-active runner) true)
          (try (run-wave runner ctx {:source source :job-ids job-ids})
               (finally (reset! (:wave-active runner) false)))))))

;; ─── Ticker + lifecycle ───────────────────────────────────────────────────

(defn- tick!
  "One ticker pass: fire due jobs only while the agent is idle (the ticker
  callback in pi startTicker)."
  [runner]
  (when-let [ctx @(:ctx runner)]
    (when (and (fn? (:is-idle ctx)) ((:is-idle ctx)))
      (fire-due runner ctx {:source :tick}))))

(defn- start-ticker!
  "Start the in-session ticker: a daemon thread sleeping in slices, firing a
  tick every :tick-ms on the monotonic clock (pi startTicker's setInterval
  + unref). Unload (/reload) stops it via the stop atom; every
  session-start restarts it."
  [runner]
  (stop-ticker! runner)
  (reset! (:stop runner) false)
  (let [generation @(:ticker-generation runner)
        active? #(and (= generation @(:ticker-generation runner))
                      (not @(:stop runner)))]
    (reset! (:thread runner)
            (concurrent/spawn
             (fn []
               (loop [next-tick (+ (concurrent/monotonic-ms) (:tick-ms runner))]
                 (when (active?)
                   (Thread/sleep 250)
                   (if (>= (concurrent/monotonic-ms) next-tick)
                     (do (when (active?) (try (tick! runner) (catch Exception _)))
                         (when (active?)
                           (recur (+ (concurrent/monotonic-ms) (:tick-ms runner)))))
                     (recur next-tick))))))))
  nil)

(defn- on-session-start
  "Session start: fresh ctx/cwd, fresh compaction flag, stop the old ticker,
  fire due jobs (unless the agent is already busy with the user's own
  prompt — kmet's interactive mode submits no CLI initial prompt, so a busy
  agent at session-start covers pi's anti-hijack rule), restart the ticker."
  [runner ev ctx]
  (note-ctx! runner ctx)
  (stop-ticker! runner)
  (reset! (:stop runner) false)
  (reset! (:compacting runner) false)
  (when (contains? fire-on-reasons (:reason ev))
    (let [busy? (or (and (fn? (:is-idle ctx)) (not ((:is-idle ctx))))
                    (and (fn? (:has-pending-messages ctx)) ((:has-pending-messages ctx))))]
      (when-not busy?
        (fire-due runner ctx {:source :session-start}))))
  (start-ticker! runner))

(defn attach
  "Bind session lifecycle + privilege hooks (pi ScheduleRunner.attach). Call
  once from init."
  [runner]
  (let [api (:api runner)]
    (privilege/attach api (:privilege runner))
    (ext/on-event api :session-start (fn [ev ctx] (on-session-start runner ev ctx)))
    ;; track compaction so scheduled wakes wait it out instead of crashing
    ;; into the rejection — hint only; the thrown error is authoritative
    (ext/on-event api :session-before-compact (fn [_ _] (reset! (:compacting runner) true)))
    (ext/on-event api :compaction-end (fn [_ _] (reset! (:compacting runner) false)))
    ;; a cancelled compaction never emits :compaction-end — kmet's
    ;; counterpart event (belt-and-braces with the probe send)
    (ext/on-event api :session-compact-failed (fn [_ _] (reset! (:compacting runner) false)))
    (ext/on-event api :session-shutdown
                  (fn [_ _]
                    (stop-ticker! runner)
                    (privilege/clear! (:privilege runner))
                    (reset! (:compacting runner) false))))
  runner)
