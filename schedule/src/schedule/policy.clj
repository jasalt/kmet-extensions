(ns schedule.policy
  "Reliability policy: limits, missed-window decisions, tier contracts,
   keyword normalization, and the create rate limiter.

   Port of pi-schedule src/policy.ts. The clock discipline follows the kmet
   host rules (AGENTS.md §Time): wall-clock millis are absolute persisted
   values (next-run-at, job timestamps); every duration and sliding window
   (the create limiter) runs on the monotonic clock."
  (:require [clojure.string :as str]
            [kmet.libs.concurrent :as concurrent]
            [schedule.schedule :as sched]))

(def tick-ms
  "How often the in-session ticker checks for due jobs (a duration — monotonic)."
  30000)

(def limits
  "Hard caps against self-spam and backlog storms (pi LIMITS)."
  {:max-jobs-per-scope 50
   :max-fires-per-session-start 5
   :max-fires-per-tick 3
   :max-creates-per-minute 10
   :max-name-chars 200
   :max-prompt-chars 20000
   :max-command-chars 10000})

(def default-tier :read-only)
(def default-missed-window :catch-up-one)

(def tiers
  "Privilege tier keyword set (pi read_only | suggest | mutate)."
  #{:read-only :suggest :mutate})

(def missed-windows
  "Missed-window policy keyword set (pi catch_up_one | skip)."
  #{:catch-up-one :skip})

(def scopes
  "Job scope keyword set (pi global | project)."
  #{:global :project})

(defn as-keyword
  "Normalize V (keyword or string, any case, underscores or dashes, optional
   leading colon) to a keyword; nil-safe."
  [v]
  (when (some? v)
    (let [s (str/trim (str/lower-case (str v)))
          s (if (str/starts-with? s ":") (subs s 1) s)
          s (str/replace s #"[_]" "-")]
      (keyword s))))

(defn as-tier
  "Valid tier keyword for V, or DEFAULT (invalid values fall back — the store
   normalizes foreign rows leniently; the tool validates before it gets here)."
  ([v] (as-tier v default-tier))
  ([v default]
   (let [k (as-keyword v)]
     (if (contains? tiers k) k default))))

(defn as-missed-window
  "Valid missed-window keyword for V, or DEFAULT."
  ([v] (as-missed-window v default-missed-window))
  ([v default]
   (let [k (as-keyword v)]
     (if (contains? missed-windows k) k default))))

(defn as-scope
  "Valid scope keyword for V, or DEFAULT."
  ([v] (as-scope v :global))
  ([v default]
   (let [k (as-keyword v)]
     (if (contains? scopes k) k default))))

(defn idempotency-key
  "Stable key for a job's due slot (pi idempotencyKeyFor): same job + same
   planned next-run-at ⇒ same key ⇒ at-most-once delivery."
  [job]
  (str (:id job) ":" (:next-run-at job)))

(defn grace-ms
  "How late a 'skip' job is still on time (pi graceMsFor). Floor is 2× the
   tick so a healthy in-session job is not spuriously skipped when the ticker
   lands just after the due instant: interval/once max(2×tick, 25% of the
   period) capped at 15m; daily 1h."
  [job tick-ms*]
  (let [tick-floor (* 2 (or tick-ms* tick-ms))
        period (case (:type (:schedule job))
                 :interval (:every-ms (:schedule job))
                 :once (:delay-ms (:schedule job))
                 nil)]
    (if (some? period)
      (min (max tick-floor (quot period 4)) 900000)
      (max tick-floor 3600000))))

(defn decide-due
  "Decide whether a due job (next-run-at <= NOW-MS) should fire or be skipped
   (pi decideDue). catch-up-one always fires once for the missed slot; skip
   fires only within the grace of the planned slot, else rolls forward
   without delivering. run-now bypasses this entirely."
  [job now-ms tick-ms*]
  (let [key (idempotency-key job)
        policy* (as-missed-window (:missed-window job))
        planned (try (Long/parseLong (str (:next-run-at job))) (catch Exception _ 0))
        overdue-ms (max 0 (- now-ms planned))
        grace (grace-ms job tick-ms*)]
    (if (and (= :skip policy*) (> overdue-ms grace))
      {:action :skip
       :reason (str "missed_window_skip (overdue " (quot overdue-ms 60000) "m > grace)")
       :idempotency-key key}
      {:action :fire
       :reason (if (and (= :catch-up-one policy*) (> overdue-ms grace))
                 "catch_up_one" "due")
       :idempotency-key key})))

(defn next-after
  "The next run strictly after AT-MS for a job's schedule spec (pi nextAfter)."
  [schedule-spec at-ms]
  (sched/next-run-at schedule-spec at-ms false))

(def tier-contracts
  "Privilege tier prompt-contract blocks (pi tierContract)."
  {:read-only
   ["PRIVILEGE: read_only"
    "- Do NOT modify files, commit, push, open PRs, install packages, or change config."
    "- Investigate and report only. Prefer read/search/status tools."]
   :suggest
   ["PRIVILEGE: suggest"
    "- You may draft patches or proposals, but do NOT apply them, commit, push, or open PRs unless the user explicitly asks in this turn."
    "- Prefer dry-run / report output."]
   :mutate
   ["PRIVILEGE: mutate"
    "- Workspace changes are allowed when necessary for this task."
    "- Still prefer the smallest safe change; summarize every mutation."]})

(defn tier-contract
  "The prompt-contract lines for TIER (pi tierContract)."
  [tier]
  (str/join "\n" (get tier-contracts (as-tier tier) (tier-contracts :read-only))))

(defn create-rate-limiter
  "A per-process sliding-window create limiter (pi CreateRateLimiter):
   {:try-take! (fn ([] ...) ([now-ms]) ...)} — the 1-arity exists for tests;
   the default clock is monotonic (a duration window)."
  []
  (let [timestamps (atom [])]
    {:try-take!
     (fn try-take!
       ([] (try-take! (concurrent/monotonic-ms)))
       ([now-ms]
        (locking timestamps
          (swap! timestamps (fn [ts] (vec (filter #(>= % (- now-ms 60000)) ts))))
          (if (>= (count @timestamps) (:max-creates-per-minute limits))
            false
            (do (swap! timestamps conj now-ms)
                true)))))}))
