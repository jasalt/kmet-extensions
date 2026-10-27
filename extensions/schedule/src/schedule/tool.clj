(ns schedule.tool
  "The agent-facing 'schedule' tool.

  Port of pi-schedule src/tool.ts. Actions: create | list | cancel | enable
  | disable | run_now | history | trust. Job kinds (create kind): :prompt |
  :shell | :notify | :message. The execute fn is contextual (needs ctx.cwd
  for scope resolution and run-now's lookup), so it receives
  (fn [args on-update signal ctx]). Tool args arrive with the JSON schema's
  string keys; the arg helpers accept both keyword and string keys."
  (:require [schedule.util :as util]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [schedule.action :as action]
            [schedule.ledger :as ledger]
            [schedule.policy :as policy]
            [schedule.privilege :as privilege]
            [schedule.runner :as runner]
            [schedule.schedule :as sched]
            [schedule.store :as store]
            [schedule.trust :as trust]))

(def ^:private arg-spec
  "The tool's parameter schema (pi ScheduleParams — the model-facing names
  match pi verbatim)."
  {:action {:type :string :description "create | list | cancel | enable | disable | run_now | history | trust"}
   :name {:type :string :description "Job name (create)" :optional? true}
   :kind {:type :string
          :description "What fires when due (create): prompt (default) | shell | notify | message"
          :optional? true}
   :prompt {:type :string
            :description "Task/reminder text (required for prompt/notify/message; optional shell follow-up)"
            :optional? true}
   :command {:type :string :description "Shell command for kind=shell (e.g. \"npm test\")" :optional? true}
   :wakeOn {:type :string
            :description "Shell only: always | failure | success | never (default: always when any follow-up text is set)"
            :optional? true}
   :successPrompt {:type :string :description "Shell only: agent follow-up when the command succeeds" :optional? true}
   :failurePrompt {:type :string :description "Shell only: agent follow-up when the command fails" :optional? true}
   :timeoutMs {:type :number :description "Shell only: exec timeout ms (default 60000, max 600000)" :optional? true}
   :once {:type :string
          :description "One-shot delay, e.g. \"10m\" or \"30s\" (xor with every/dailyAt)"
          :optional? true}
   :maxRuns {:type :number
             :description "Max deliveries before the job auto-disables (default: unlimited)"
             :optional? true}
   :every {:type :string :description "Interval, e.g. \"30m\", \"2h\", \"1d\" (create)" :optional? true}
   :dailyAt {:type :string :description "Daily local time \"HH:MM\" (create)" :optional? true}
   :scope {:type :string :description "global | project (default: project if .kmet exists)" :optional? true}
   :missedWindow {:type :string
                  :description "catch_up_one (default) | skip — overdue handling"
                  :optional? true}
   :tier {:type :string
          :description "Privilege tier for agent-waking fires: read_only (default) | suggest | mutate. shell forces mutate"
          :optional? true}
   :id {:type :string :description "Job id" :optional? true}
   :limit {:type :number :description "history: max rows (default 10)" :optional? true}})

(defn- str-arg
  "Non-blank string value of arg K (keyword or string key)."
  [args k]
  (when (map? args)
    (let [v (or (get args k) (get args (name k)))]
      (when (and (string? v) (seq v)) v))))

(defn- raw-arg
  [args k]
  (if (contains? args k) (get args k) (get args (name k))))

(defn- enum-arg
  [args k normalize default]
  (let [v (raw-arg args k)]
    (if (nil? v) default
        (or (normalize v nil)
            (action/action-error (str "Invalid " (name k) ": " (pr-str v)))))))

(defn- num-arg
  "Numeric value of arg K (keyword or string key), or nil."
  [args k]
  (when (map? args)
    (let [v (or (get args k) (get args (name k)))]
      (cond (number? v) v
            (string? v) (try (util/parse-integer (str/trim v)) (catch Exception _ nil))
            :else nil))))

(defn- text-result
  "A tool result with TEXT content (pi textResult — handled errors return
  plain text, not tool errors)."
  [text]
  {:content text :is-error false})

;; ─── Row formatting (pi summarize / formatRelative) ──────────────────────

(defn- truncate
  [s n]
  (let [one (str/replace (str/replace (str s) #"\s+" " ") #"^\s+|\s+$" "")]
    (if (<= (count one) n) one (str (subs one 0 (dec n)) "…"))))

(defn summarize-job
  "A job's list row (pi summarize): state/scope/kind/tier, schedule, next
  and last runs, run counts, last status, and the last shell outcome."
  [job now-ms]
  (let [state (cond (:terminated job) (str "off/terminated:" (name (:terminated job)))
                    (:enabled job) "on"
                    :else "off")
        next (sched/format-relative (:next-run-at job) now-ms)
        last (if (:last-run-at job) (sched/format-relative (:last-run-at job) now-ms) "never")
        kind (name (action/as-kind (:kind job)))
        payload (truncate (action/payload-summary job) 120)
        wake (if (and (= "shell" kind) (:wake-on job)) (str "  wakeOn: " (name (:wake-on job))) "")
        max-runs (if (:max-runs job) (str "  runs: " (:run-count job) "/" (:max-runs job)) "")
        shell-info (if (:last-shell job)
                     (str "  lastExit=" (or (:exit (:last-shell job)) "?")
                          (when (:killed (:last-shell job)) " killed")
                          (when-not (:ok (:last-shell job)) " (failed)"))
                     "")]
    (str "- " (:id job) "  " (:name job) "  [" state "/" (name (:scope job)) "/" kind "/"
         (name (policy/as-tier (:tier job))) "]\n"
         "  schedule: " (sched/format-schedule (:schedule job))
         "  missedWindow: " (name (policy/as-missed-window (:missed-window job))) wake "\n"
         "  next: " next "  last: " last "  runs: " (:run-count job) max-runs
         (when (:last-status job) (str "  lastStatus: " (name (:last-status job))))
         shell-info "\n"
         "  " (if (= "shell" kind) "command" "prompt") ": " payload)))

;; ─── Action handlers ──────────────────────────────────────────────────────

(defn- notify-high-privilege-create
  "P3 persistence-amplification mitigation (pi notifyHighPrivilegeCreate):
  creating a :shell or :mutate job is the highest-privilege act this tool
  offers — it persists and fires unattended (global scope: every session).
  Surface it to the human at create time on every channel (the fire-time
  notify comes after execution); never blocks — best-effort. The notice
  itself survives a hostile name/command: control chars and newlines are
  stripped so it cannot be concealed."
  [api ctx job]
  (let [shell? (= :shell (:kind job))
        mutate? (= :mutate (:tier job))]
    (when (or shell? mutate?)
      (let [clean (fn [v] (-> (str (or v "")) (str/replace #"[\u0000-\u001F\u007F\u0080-\u009F]" " ")
                              (str/replace #"\s+" " ") str/trim))
            where (if (= :global (:scope job))
                    "every future session, in any project"
                    "this project's future sessions")
            cmd (if shell? (str " command=" (pr-str (clean (:command job)))) "")
            msg (str "[schedule] created "
                     (if shell? "shell (runs as mutate)" "prompt (tier=mutate)")
                     " job \"" (clean (:name job)) "\" — it will fire unattended in "
                     where "." cmd
                     " If you did not expect this, cancel it: schedule action=cancel id=" (:id job))]
        (try
          (if (:has-ui ctx) (ext/ui-notify api msg :warning) (println msg))
          (catch Exception _))
        (try
          (ext/send-message! api
                             {:custom-type :schedule
                              :content msg
                              :display true
                              :details {:job-id (:id job) :kind :create-notice
                                        :scope (:scope job) :tier (:tier job)}}
                             {:trigger-turn false})
          (catch Exception _))))))

(defn- handle-create
  [api runner limiter paths cwd ctx args]
  (if-not ((:try-take! limiter))
    (text-result (str "Error: create rate limit (" (:max-creates-per-minute policy/limits)
                      "/min). Slow down."))
    (let [name (some-> (str-arg args :name) str/trim)]
      (cond
        (or (nil? name) (str/blank? name))
        (text-result "Error: \"name\" is required for create")

        (> (count name) (:max-name-chars policy/limits))
        (text-result (str "Error: name is too long (" (count name) " chars; max "
                          (:max-name-chars policy/limits) ")"))

        :else
        (let [normalized (action/normalize-create-kind
                          {:kind (str-arg args :kind)
                           :prompt (str-arg args :prompt)
                           :command (str-arg args :command)
                           :wake-on (str-arg args :wakeOn)
                           :success-prompt (str-arg args :successPrompt)
                           :failure-prompt (str-arg args :failurePrompt)
                           :timeout-ms (raw-arg args :timeoutMs)})
              schedule-spec (sched/from-parts {:every (str-arg args :every)
                                               :daily-at (str-arg args :dailyAt)
                                               :once (str-arg args :once)})
              scope (enum-arg args :scope policy/as-scope (store/default-scope cwd))
              missed-window (enum-arg args :missedWindow policy/as-missed-window policy/default-missed-window)
              requested-tier (enum-arg args :tier policy/as-tier policy/default-tier)
              tier (if (:force-tier-mutate normalized) :mutate requested-tier)
              job (store/create-job paths
                                    (merge (select-keys normalized
                                                        [:prompt :kind :command :wake-on
                                                         :success-prompt :failure-prompt :timeout-ms])
                                           {:name name
                                            :schedule schedule-spec
                                            :scope scope
                                            :project-path cwd
                                            :missed-window missed-window
                                            :tier tier
                                            :max-runs (action/normalize-max-runs (raw-arg args :maxRuns))}))]
          ;; creating a job here is an explicit act in this project — trust it
          ;; for auto-fire; never auto-trust from a *scheduled* turn (a fired
          ;; turn must not be able to unlock its own project's gate)
          (when (and (= :project (:scope job))
                     (not (runner/scheduled-turn-active? runner)))
            (trust/trust! (:trust-file runner) cwd (System/currentTimeMillis)))
          (notify-high-privilege-create api ctx job)
          (text-result
           (str/join "\n"
                     [(str "Created job " (:id job) " \"" (:name job) "\" ("
                           (sched/format-schedule (:schedule job)) ", " (clojure.core/name (:scope job)) ").")
                      (str "kind=" (clojure.core/name (:kind job)) "  tier=" (clojure.core/name (:tier job))
                           "  missedWindow=" (clojure.core/name (:missed-window job))
                           (when (= :shell (:kind job))
                             (str "  command=" (pr-str (:command job))
                                  "  wakeOn=" (clojure.core/name (or (:wake-on job) :never)))))
                      (str "Next run: " (sched/format-relative (:next-run-at job) (System/currentTimeMillis)) ".")
                      (str "Use schedule action=run_now id=" (:id job) " to fire immediately.")])))))))

(defn- handle-list
  [paths cwd trust-file now-ms]
  (let [jobs (store/list-for-cwd paths cwd)
        untrusted? (fn [j] (and (= :project (:scope j))
                                (not (trust/trusted? trust-file (or (:project-path j) cwd)))))]
    (if (empty? jobs)
      (text-result "No scheduled jobs. Create one with action=create, name, kind (optional), prompt or command, and every or dailyAt.")
      (text-result
       (str/join "\n"
                 (concat ["Scheduled jobs:"]
                         (map (fn [j]
                                (str (summarize-job j now-ms)
                                     (when (untrusted? j)
                                       "\n  [untrusted-project — will not auto-fire; schedule action=trust]")))
                              jobs)
                         (when (some untrusted? jobs)
                           [(str "\n" (count (filter untrusted? jobs))
                                 " project job(s) are in an untrusted project: they never auto-fire. "
                                 "Inspect .kmet/schedule.edn first, then run schedule action=trust to allow auto-fire.")])))))))

(defn- handle-trust
  [runner cwd]
  (trust/trust! (:trust-file runner) cwd (System/currentTimeMillis))
  (let [project-jobs (filter #(= :project (:scope %)) (store/list-for-cwd (:paths runner) cwd))]
    (text-result (str "Trusted project " cwd ". " (count project-jobs)
                      " project job(s) can now auto-fire when due. "
                      "(Only trust projects you have inspected: .kmet/schedule.edn can contain shell jobs.)"))))

(defn- handle-cancel
  [paths id cwd]
  (if (str/blank? (or id ""))
    (text-result "Error: \"id\" is required for cancel")
    (if-let [removed (store/remove-job paths id cwd)]
      (text-result (str "Cancelled job " (:id removed) " \"" (:name removed) "\"."))
      (text-result (str "Job " id " not found.")))))

(defn- handle-enable
  [paths id cwd enabled]
  (let [verb (if enabled "enable" "disable")]
    (if (str/blank? (or id ""))
      (text-result (str "Error: \"id\" is required for " verb))
      (if-let [job (store/set-enabled paths id cwd enabled)]
        (text-result (str (if enabled "Enabled" "Disabled") " job " (:id job) " \"" (:name job) "\"."))
        (text-result (str "Job " id " not found."))))))

(defn- handle-run-now
  [runner paths id cwd ctx]
  (if (str/blank? (or id ""))
    (text-result "Error: \"id\" is required for run_now")
    (if-let [job (store/get-job paths id cwd)]
      (if (:terminated job)
        (text-result (str "Job " (:id job) " \"" (:name job) "\" is terminated ("
                          (name (:terminated job)) "). Cancel and recreate to run again."))
        (let [results (runner/fire-due runner ctx {:source :run-now :job-ids [(:id job)]})
              updated (or (first results) (store/get-job paths (:id job) cwd))]
          (cond
            (empty? results)
            (text-result (str "Did not fire job " (:id job) " \"" (:name job)
                              "\": runner returned no result (another wave may be active, "
                              "or the job became unavailable). Check schedule action=history id="
                              (:id job) "."))
            (= :ok (:last-status updated))
            (text-result (str "Delivered job " (:id updated) " \"" (:name updated) "\" (kind="
                              (name (action/as-kind (:kind updated))) ", tier="
                              (name (policy/as-tier (:tier updated))) ")."
                              (when (:last-shell updated) (str " shell exit=" (or (:exit (:last-shell updated)) "?") "."))
                              " Check schedule action=history id=" (:id updated) "."))
            :else
            (text-result (str "Job " (:id updated) " \"" (:name updated) "\": "
                              (case (:last-status updated)
                                :error (str "failed to deliver — " (or (:last-error updated) "unknown error"))
                                :skipped (str "was skipped — " (or (:last-error updated) "policy"))
                                :locked "is locked (already running)"
                                (str "ended with status=" (name (or (:last-status updated) :unknown))))
                              ". Check schedule action=history id=" (:id updated) ".")))))
      (text-result (str "Job " id " not found.")))))

(defn- handle-history
  [runs-file id limit]
  (let [rows (ledger/history runs-file id (if (and (number? limit) (pos? limit)) (min (long limit) 50) 10))]
    (if (empty? rows)
      (text-result "No run history. Jobs record every fire/skip in the ledger when they run.")
      (text-result
       (str/join "\n"
                 (cons "Run history (newest first):"
                       (map (fn [row]
                              (str (:run-id row) "  " (:job-id row) "  " (:job-name row) "  "
                                   (name (or (:status row) :unknown)) "  " (name (or (:source row) :unknown))
                                   (when (:detail row) (str "  — " (truncate (:detail row) 80)))))
                            rows)))))))

;; ─── Registration ─────────────────────────────────────────────────────────

(defn register-tool!
  "Register the 'schedule' tool (pi registerScheduleTool). RUNNER is the
  runner state (trust file + fire-due + scheduled-turn-active?); LIMITER is
  the per-process create rate limiter."
  [api runner limiter]
  (let [paths (:paths runner)]
    (ext/register-tool! api
                        {:name "schedule"
                         :label "Schedule"
                         :description
                         (str "Manage scheduled agent tasks and actions (reviews, polls, shell checks, reminders). "
                              "Actions: create, list, cancel, enable, disable, run_now, history, trust. "
                              "Create kind: prompt (default) | shell | notify | message. "
                              "Schedules: every \"30m\"/\"2h\"/\"1d\" or dailyAt \"09:00\" (local time), or once \"10m\" (one-shot). "
                              "Defaults: tier=read_only (shell→mutate), missedWindow=catch_up_one. "
                              "Due jobs fire on session start and every 30s while the session stays open and the agent is idle. "
                              "Project-scope jobs only auto-fire in trusted projects (action=trust trusts the current project).")
                         :contextual? true
                         :params arg-spec
                         :execute
                         (fn [args _on-update _signal ctx]
                           (let [cwd (:cwd ctx)
                                 id (str-arg args :id)
                                 action-name (str/lower-case (or (str-arg args :action) ""))]
                             (try
                               (case action-name
                                 "create" (handle-create api runner limiter paths cwd ctx args)
                                 "list" (handle-list paths cwd (:trust-file runner) (System/currentTimeMillis))
                                 "cancel" (handle-cancel paths id cwd)
                                 "enable" (handle-enable paths id cwd true)
                                 "disable" (handle-enable paths id cwd false)
                                 "run_now" (handle-run-now runner paths id cwd ctx)
                                 "history" (handle-history (:runs-file runner) id (num-arg args :limit))
                                 "trust" (handle-trust runner cwd)
               ;; unknown action
                                 (text-result (str "Unknown action: " action-name)))
                               (catch Exception e
               ;; handled errors (parse/store/action/run) surface as tool
               ;; text, not tool errors — matching pi's textResult behavior
                                 (text-result (str "Error: " (ex-message e)))))))})))
