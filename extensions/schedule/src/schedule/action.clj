(ns schedule.action
  "Job kind validation, wake policy, and lifecycle helpers.

  Port of pi-schedule src/action.ts. Pure functions — delivery lives in the
  runner. Job kinds: :prompt (default) | :shell | :notify | :message."
  (:require [schedule.util :as util]
            [clojure.string :as str]
            [schedule.policy :as policy]))

(def default-kind :prompt)
(def default-shell-timeout-ms 60000)
(def max-shell-timeout-ms 600000)
(def max-shell-output-chars 8000)

(def ^:private kinds #{:prompt :shell :notify :message})
(def ^:private wake-ons #{:always :failure :success :never})

(defn as-kind
  "Valid job-kind keyword for V, or DEFAULT (pi normalizeJobAction's lenient
  path — the store normalizes foreign rows; the tool validates strictly)."
  ([v] (as-kind v default-kind))
  ([v default]
   (let [k (policy/as-keyword v)]
     (if (contains? kinds k) k default))))

(defn as-wake-on
  "Valid wake-on keyword for V, or nil."
  [v]
  (let [k (policy/as-keyword v)]
    (when (contains? wake-ons k) k)))

(defn- check-len
  [label v max-chars]
  (when (and (string? v) (> (count v) max-chars))
    (throw (ex-info (str label " is too long (" (count v) " chars; max " max-chars ")")
                    {:type :schedule-action-error}))))

(defn action-error
  "Throw a create-time action validation error (pi ActionError)."
  [message]
  (throw (ex-info message {:type :schedule-action-error})))

(defn resolve-wake-on
  "Effective wake-on for a job: explicit value wins; default 'always' when
  any follow-up text is set, else 'never' (pi resolveWakeOn)."
  [{:keys [wake-on prompt success-prompt failure-prompt]}]
  (if (as-wake-on wake-on)
    (as-wake-on wake-on)
    (if (or (and (string? prompt) (seq (str/trim (str prompt))))
            (and (string? success-prompt) (seq (str/trim (str success-prompt))))
            (and (string? failure-prompt) (seq (str/trim (str failure-prompt)))))
      :always
      :never)))

(defn shell-ok?
  "A shell result is ok on exit 0 and not killed (pi shellResultOk)."
  [{:keys [exit killed]}]
  (and (= 0 (long (or exit -1))) (not (true? killed))))

(defn should-wake?
  "Whether a shell job should wake the agent for this result
  (pi shouldWakeForShell)."
  [job result]
  (let [wake-on (resolve-wake-on job)
        ok (shell-ok? result)]
    (case wake-on
      :never false
      :always true
      :success ok
      :failure (not ok)
      false)))

(defn select-shell-follow-up
  "The follow-up instruction for a shell wake: success-prompt, else
  failure-prompt, else prompt, else generic review text (pi
  selectShellFollowUp)."
  [job result]
  (let [ok (shell-ok? result)]
    (or (and ok (some-> (:success-prompt job) str/trim not-empty))
        (and (not ok) (some-> (:failure-prompt job) str/trim not-empty))
        (some-> (:prompt job) str/trim not-empty)
        (when (not= :never (resolve-wake-on job))
          "Review this scheduled shell command result and decide next steps."))))

(defn clamp-timeout-ms
  "Shell timeout in ms: positive, rounded, capped at 10 minutes
  (pi clampTimeoutMs)."
  [v]
  (let [n (cond (number? v) v (string? v) (util/parse-integer (str/trim v)) :else nil)]
    (when (and (some? v) (or (not (number? n)) (<= n 0)))
      (action-error "timeoutMs must be a positive number"))
    (if (some? n)
      (min (long n) max-shell-timeout-ms)
      default-shell-timeout-ms)))

(defn truncate-output
  "Middle-truncate shell output to MAX-CHARS (head + '…' + tail, pi
  truncateOutput)."
  ([text] (truncate-output text max-shell-output-chars))
  ([text max-chars]
   (let [s (str (or text ""))]
     (if (<= (count s) max-chars)
       s
       (let [head (- (quot max-chars 2) 2)
             tail (- max-chars head 5)]
         (str (subs s 0 head) "\n…\n" (subs s (- (count s) tail))))))))

(defn normalize-create-kind
  "Validate + normalize create-time kind fields (pi normalizeCreateAction).
  Returns {:kind :prompt|:shell|... :prompt str :command str? :wake-on kw?
  :success-prompt :failure-prompt :timeout-ms int? :force-tier-mutate bool}
  — shell jobs always run as tier=mutate (the command runs outside the agent
  tool path). Throws :schedule-action-error on invalid combinations."
  [{:keys [kind prompt command wake-on success-prompt failure-prompt timeout-ms]}]
  (let [kind (let [k (policy/as-keyword kind)]
               (if (contains? kinds k) k
                   (if (nil? kind) :prompt
                       (action-error (str "Invalid kind \"" kind "\". Use prompt | shell | notify | message.")))))
        prompt (str/trim (str (or prompt "")))]
    (if (= :shell kind)
      (let [command (str/trim (str (or command "")))]
        (when (str/blank? command)
          (action-error "kind=shell requires \"command\" (e.g. \"npm test\" or \"gh run list\")."))
        (check-len "command" command (:max-command-chars policy/limits))
        (check-len "prompt" prompt (:max-prompt-chars policy/limits))
        (check-len "successPrompt" success-prompt (:max-prompt-chars policy/limits))
        (check-len "failurePrompt" failure-prompt (:max-prompt-chars policy/limits))
        (when (and (some? wake-on) (not (str/blank? (str wake-on))) (nil? (as-wake-on wake-on)))
          (action-error (str "Invalid wakeOn \"" wake-on "\". Use always | failure | success | never.")))
        {:kind :shell
         :prompt prompt
         :command command
         :wake-on (resolve-wake-on {:wake-on (as-wake-on wake-on) :prompt prompt
                                    :success-prompt success-prompt :failure-prompt failure-prompt})
         :success-prompt (some-> success-prompt str/trim not-empty)
         :failure-prompt (some-> failure-prompt str/trim not-empty)
         :timeout-ms (clamp-timeout-ms timeout-ms)
         :force-tier-mutate true})
      (do
        (when (str/blank? prompt)
          (action-error (if (= :prompt kind)
                          "kind=prompt requires \"prompt\" (the isolated task text)."
                          (str "kind=" (name kind) " requires \"prompt\" (the " (name kind) " text)."))))
        (check-len "prompt" prompt (:max-prompt-chars policy/limits))
        (when (and (string? command) (seq (str/trim command)))
          (action-error (str "\"command\" is only valid for kind=shell (got kind=" (name kind) ").")))
        (when (and (some? wake-on) (not (str/blank? (str wake-on))))
          (action-error (str "\"wakeOn\" is only valid for kind=shell (got kind=" (name kind) ").")))
        (when (or (some-> success-prompt str/trim not-empty)
                  (some-> failure-prompt str/trim not-empty))
          (action-error "\"successPrompt\"/\"failurePrompt\" are only valid for kind=shell."))
        (when (and (some? timeout-ms) (not= "" (str timeout-ms)))
          (action-error (str "\"timeoutMs\" is only valid for kind=shell (got kind=" (name kind) ").")))
        {:kind kind
         :prompt prompt
         :force-tier-mutate false}))))

(defn payload-summary
  "The job's payload text for list rows (pi payloadSummary): the command for
  shell jobs, else the prompt."
  [job]
  (if (= :shell (:kind job)) (str (or (:command job) "")) (str (or (:prompt job) ""))))

(defn normalize-max-runs
  "Positive integer max-runs, or nil (pi normalizeMaxRuns). Throws on invalid."
  [v]
  (if (or (nil? v) (and (string? v) (str/blank? v)))
    nil
    (let [n (if (string? v) (util/parse-integer (str/trim v)) v)]
      (when-not (and (integer? n) (pos? n))
        (action-error "maxRuns must be a positive integer"))
      (min n 1000000))))

(defn terminal-reason
  "Why a job should stop firing after RUN-COUNT deliveries, or nil
  (pi terminalReason): one-shot 'once' jobs terminate after their single
  delivery; max-runs jobs when the cap is reached."
  [job run-count]
  (cond
    (= :once (get-in job [:schedule :type])) :once
    (and (some? (:max-runs job)) (>= run-count (:max-runs job))) :max-runs
    :else nil))
