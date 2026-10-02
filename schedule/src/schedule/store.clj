(ns schedule.store
  "Hybrid job store: global (<agent-dir>/schedule/schedules.edn) + project
  (<project>/.kmet/schedule.edn).

  Port of pi-schedule src/store.ts, transposed to EDN. All timestamps are
  epoch-millis longs. Corrupt / wrong-version files are quarantined (renamed
  to <file>.corrupt-<ts>), never silently wiped; mutations take the
  kmet.libs.edn-store file lock and re-read inside the lock (cross-session
  read-modify-write). Provenance is which file a row was loaded from — rows
  in the project file are always project jobs of that root, never the row's
  own :scope label (pi's trust-gate relabeling fix)."
  (:require [schedule.util :as util]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.libs.edn-store :as edn-store]
            [schedule.action :as action]
            [schedule.policy :as policy]
            [schedule.schedule :as sched]))

(def store-version 1)

(defn store-error
  "Throw a store error (pi StoreError)."
  [message]
  (throw (ex-info message {:type :schedule-store-error})))

(defn new-job-id
  "A 12-hex-char job id (pi newJobId's randomBytes(6).toString('hex'))."
  []
  (subs (str/replace (str (util/uuid)) "-" "") 0 12))

(defn paths
  "Store paths under AGENT-DIR (the extension keeps its state under the host
  agent dir so KMET_CODING_AGENT_DIR relocates it): global store, runs
  ledger, trust registry, lock dir, and the project-file resolver."
  [agent-dir]
  (let [agent-dir (or (some-> agent-dir str not-empty)
                      (str (fs/path (System/getProperty "user.home") ".kmet" "agent")))
        global-dir (str (fs/path agent-dir "schedule"))]
    {:global-dir global-dir
     :global-file (str (fs/path global-dir "schedules.edn"))
     :runs-file (str (fs/path global-dir "runs.ednl"))
     :trust-file (str (fs/path global-dir "trusted.edn"))
     :lock-dir (str (fs/path global-dir "locks"))
     :project-file (fn [project-root]
                     (str (fs/path (str project-root) ".kmet" "schedule.edn")))}))

(defn- clamp-str
  "STRING capped at MAX chars, or nil."
  [v max-chars]
  (when (string? v)
    (not-empty (subs v 0 (min (count v) max-chars)))))

(defn normalize-job
  "Fill defaults and clamp foreign rows (pi normalizeJob): lenient — an
  unknown kind/scope/tier/missed-window degrades to its default, never throws.
  A non-map returns a minimal placeholder row."
  [raw]
  (let [raw (if (map? raw) raw {})
        pos-int (fn [v] (when (and (number? v) (pos? v)) (long v)))
        non-neg-int (fn [v] (max 0 (long (or v 0))))]
    {:id (or (when (string? (:id raw)) (:id raw)) (new-job-id))
     :name (or (clamp-str (:name raw) (:max-name-chars policy/limits)) "unnamed")
     :prompt (or (clamp-str (:prompt raw) (:max-prompt-chars policy/limits)) "")
     :kind (action/as-kind (:kind raw))
     :command (clamp-str (:command raw) (:max-command-chars policy/limits))
     :wake-on (when-let [w (action/as-wake-on (:wake-on raw))] w)
     :success-prompt (clamp-str (:success-prompt raw) (:max-prompt-chars policy/limits))
     :failure-prompt (clamp-str (:failure-prompt raw) (:max-prompt-chars policy/limits))
     :timeout-ms (pos-int (:timeout-ms raw))
     :schedule (if (map? (:schedule raw)) (:schedule raw) {:type :interval :every-ms 3600000 :every "1h"})
     :scope (policy/as-scope (:scope raw))
     :project-path (when (string? (:project-path raw)) (:project-path raw))
     :enabled (if (boolean? (:enabled raw)) (:enabled raw) true)
     :terminated (when (contains? #{:once :max-runs} (:terminated raw)) (:terminated raw))
     :missed-window (policy/as-missed-window (:missed-window raw))
     :tier (if (= :shell (action/as-kind (:kind raw))) :mutate (policy/as-tier (:tier raw)))
     :max-runs (pos-int (:max-runs raw))
     :created-at (non-neg-int (:created-at raw))
     :updated-at (non-neg-int (:updated-at raw))
     :last-run-at (pos-int (:last-run-at raw))
     :next-run-at (non-neg-int (:next-run-at raw))
     :run-count (non-neg-int (:run-count raw))
     :last-status (when (contains? #{:ok :error :skipped :locked} (:last-status raw)) (:last-status raw))
     :last-error (clamp-str (:last-error raw) 2000)
     :last-idempotency-key (clamp-str (:last-idempotency-key raw) 200)
     :last-shell (when (map? (:last-shell raw)) (:last-shell raw))}))

(defn- quarantine!
  "Move a bad store file aside and throw — never treat it as empty and
  silently overwrite later (pi quarantineAndThrow)."
  [file reason]
  (let [ts (str/replace (str (System/currentTimeMillis)) #"[^0-9]" "")
        quarantined (str file ".corrupt-" ts)]
    (try
      (when (fs/exists? file)
        (fs/move file quarantined))
      (catch Exception _))
    (store-error
     (if (fs/exists? quarantined)
       (str "Schedule store unreadable (" reason "): quarantined to " (fs/file-name quarantined)
            ". Inspect it, restore it over " (fs/file-name file) ", then retry (no restart needed).")
       (str "Schedule store unreadable (" reason "): could not quarantine " (fs/file-name file)
            ". Fix permissions/disk and retry.")))))

(defn- read-store-file
  "Read + validate one store file: {:version 1 :jobs [job ...]} (missing →
  empty; corrupt/wrong-version → quarantine + throw)."
  [file]
  (if-not (fs/exists? file)
    {:version store-version :jobs []}
    (let [text (try (slurp file) (catch Exception e (quarantine! file (str "read failed: " (ex-message e)))))
          parsed (try (when (seq (str/trim (str text)))
                        (edn/read-string text))
                      (catch Exception e (quarantine! file (str "invalid EDN: " (ex-message e)))))]
      (cond
        (nil? parsed) {:version store-version :jobs []}
        (not (map? parsed)) (quarantine! file "not a store map")
        (not= store-version (:version parsed)) (quarantine! file (str "unsupported version " (:version parsed)
                                                                      " (expected " store-version ")"))
        (not (vector? (:jobs parsed))) (quarantine! file "missing :jobs vector")
        (not (every? map? (:jobs parsed))) (quarantine! file ":jobs contains invalid rows")
        :else {:version store-version
               :jobs (mapv normalize-job (:jobs parsed))}))))

(defn- write-store-file
  "Write a store map as pretty EDN (one job per line), creating parent dirs."
  [file store]
  (fs/create-dirs (fs/parent file))
  (let [tmp (str file "." (util/uuid) ".tmp")]
    (try
      (spit tmp (str "{:version " store-version
                     "\n :jobs [" (str/join "\n        " (map pr-str (:jobs store))) "]\n}\n"))
      (fs/move tmp file {:replace-existing true :atomic-move true})
      (finally (fs/delete-if-exists tmp)))))

(defn- with-store-lock
  "Run F with the per-file store lock held (the edn-store directory lock;
   never nest — callers inside a lock must read/write raw). The parent dir
   is created first: the first-ever write cannot otherwise take a lock in a
   directory that does not exist yet."
  [file f]
  (fs/create-dirs (fs/parent file))
  (edn-store/with-file-lock (str file ".lock") f))

(defn- job-file
  [paths job]
  (if (= :global (:scope job)) (:global-file paths)
      ((:project-file paths) (or (:project-path job) (str (fs/cwd))))))

(defn- update-job!
  "Apply F to the latest row under its store lock. Missing rows stay missing."
  [paths job f]
  (let [file (job-file paths job)]
    (with-store-lock file
      (fn []
        (let [store (read-store-file file)
              idx (first (keep-indexed (fn [i j] (when (= (:id job) (:id j)) i)) (:jobs store)))]
          (when (some? idx)
            (let [latest (merge (nth (:jobs store) idx)
                                (select-keys job [:scope :project-path]))
                  updated (normalize-job (f latest))]
              (write-store-file file (assoc-in store [:jobs idx] updated))
              updated)))))))

(defn- insert-job!
  "Insert a new row and check the per-scope cap inside the same file lock."
  [paths job]
  (let [file (if (= :global (:scope job)) (:global-file paths)
                 ((:project-file paths) (or (:project-path job) (str (fs/cwd)))))]
    (with-store-lock file
      (fn []
        (let [store (read-store-file file)
              normalized (normalize-job job)]
          (when (>= (count (:jobs store)) (:max-jobs-per-scope policy/limits))
            (store-error (str "Job limit reached (" (:max-jobs-per-scope policy/limits)
                              " per " (name (:scope job)) " scope). Cancel unused jobs first.")))
          (write-store-file file (update store :jobs conj normalized))
          normalized)))))

(defn list-for-cwd
  "All jobs visible in CWD (pi listForCwd): global rows plus this project's
  rows. Provenance is the file, not the row's label: rows in the project file
  are project jobs of this root (a cloned file cannot relabel a shell row
  as 'global' to bypass the trust gate); project rows carrying a foreign
  project-path are dropped."
  [paths cwd]
  (let [root (str (try (fs/canonicalize (str cwd)) (catch Exception _ (str cwd))))
        global (:jobs (read-store-file (:global-file paths)))
        canonical (fn [p] (str (try (fs/canonicalize (str p)) (catch Exception _ (str p)))))
        project (let [pf ((:project-file paths) root)]
                  (when (fs/exists? pf)
                    (let [rows (filter (fn [j] (or (nil? (:project-path j))
                                                   (= root (canonical (:project-path j)))))
                                       (:jobs (read-store-file pf)))]
                      (mapv (fn [j] (assoc j :scope :project :project-path root)) rows))))]
    (vec (concat global project))))

(defn get-job
  "A visible job by id, or nil (pi ScheduleStore.get)."
  [paths id cwd]
  (when (seq (str/trim (str id)))
    (first (filter #(= (str/trim (str id)) (:id %)) (list-for-cwd paths cwd)))))

(defn create-job
  "Create and persist a new job (pi ScheduleStore.create). INPUT:
  {:name :prompt :kind :command :wake-on :success-prompt :failure-prompt
  :timeout-ms :schedule :scope :project-path :missed-window :tier :max-runs
  :now-ms (optional, tests)}. Throws :schedule-store-error on the per-scope
  job cap."
  [paths input]
  (let [scope (policy/as-scope (:scope input))
        project-root (when (= :project scope)
                       (str (try (fs/canonicalize (str (or (:project-path input) (fs/cwd))))
                                 (catch Exception _ (str (or (:project-path input) (fs/cwd)))))))
        now-ms (long (or (:now-ms input) (System/currentTimeMillis)))]
    (let [job {:id (new-job-id)
               :name (str/trim (str (:name input)))
               :prompt (str/trim (str (or (:prompt input) "")))
               :kind (action/as-kind (:kind input))
               :command (some-> (:command input) str/trim not-empty)
               :wake-on (when-let [w (action/as-wake-on (:wake-on input))] w)
               :success-prompt (some-> (:success-prompt input) str/trim not-empty)
               :failure-prompt (some-> (:failure-prompt input) str/trim not-empty)
               :timeout-ms (:timeout-ms input)
               :schedule (:schedule input)
               :scope scope
               :project-path project-root
               :enabled true
               :terminated nil
               :missed-window (policy/as-missed-window (:missed-window input))
               :tier (policy/as-tier (:tier input))
               :max-runs (:max-runs input)
               :created-at now-ms
               :updated-at now-ms
               :last-run-at nil
               :next-run-at (sched/next-run-at (:schedule input) now-ms (= :daily (get-in input [:schedule :type])))
               :run-count 0
               :last-status nil}]
      (insert-job! paths job))))

(defn remove-job
  "Remove a job by id (global first, then this project's file). Returns the
  removed job or nil."
  [paths id cwd]
  (let [id (str/trim (str id))
        remove-from (fn [file]
                      (with-store-lock file
                        (fn []
                          (let [store (read-store-file file)
                                idx (first (keep-indexed (fn [i j] (when (= id (:id j)) i)) (:jobs store)))]
                            (when idx
                              (let [removed (nth (:jobs store) idx)]
                                (write-store-file file {:version store-version
                                                        :jobs (into (subvec (vec (:jobs store)) 0 idx)
                                                                    (subvec (vec (:jobs store)) (inc idx)))})
                                removed))))))]
    (or (remove-from (:global-file paths))
        (remove-from ((:project-file paths)
                      (str (try (fs/canonicalize (str cwd)) (catch Exception _ (str cwd)))))))))

(defn mark-attempt
  "Record a fire/skip attempt: advance next-run-at from AT-MS (unless
  :advance? false) and bump run-count for :ok/:error only (skips and locks
  never count). Returns the updated job (pi markAttempt)."
  [paths job at-ms status & [{:keys [error idempotency-key advance? last-shell]}]]
  (update-job! paths job
               (fn [latest]
                 (-> latest
                     (assoc :last-run-at at-ms
                            :next-run-at (if (false? advance?) (:next-run-at latest)
                                             (max (:next-run-at latest) (policy/next-after (:schedule latest) at-ms)))
                            :run-count (if (contains? #{:ok :error} status)
                                         (inc (:run-count latest)) (:run-count latest))
                            :last-status status
                            :last-error error
                            :last-idempotency-key (or idempotency-key (:last-idempotency-key latest))
                            :updated-at at-ms)
                     (cond-> (some? last-shell) (assoc :last-shell last-shell))))))

(defn set-enabled
  "Enable/disable a job; re-enabling clears the terminal flag so a max-runs
  job can resume (pi setEnabled). Returns the updated job or nil."
  [paths id cwd enabled]
  (when-let [job (get-job paths id cwd)]
    (update-job! paths job
                 (fn [latest]
                   (assoc latest :enabled (boolean enabled)
                          :terminated (when-not enabled (:terminated latest))
                          :updated-at (System/currentTimeMillis))))))

(defn terminate
  "Mark a job terminal (disabled + reason) after it exhausted its runs
  (pi terminate)."
  [paths job reason]
  (update-job! paths job
               #(assoc % :enabled false :terminated reason :updated-at (System/currentTimeMillis))))

(defn due-jobs
  "Enabled, non-terminated jobs whose next-run-at is at or before NOW-MS
  (pi dueJobs)."
  [paths cwd now-ms]
  (filter (fn [j]
            (and (:enabled j) (not (:terminated j))
                 (<= (long (or (:next-run-at j) 0)) (long now-ms))))
          (list-for-cwd paths cwd)))

(defn default-scope
  "Default scope when the agent omits it (pi defaultScope): :project when a
  .kmet marker dir exists in CWD, else :global."
  [cwd]
  (if (fs/directory? (str (fs/path (str cwd) ".kmet"))) :project :global))
