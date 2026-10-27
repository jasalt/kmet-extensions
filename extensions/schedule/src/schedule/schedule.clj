(ns schedule.schedule
  "Schedule parsing and next-run calculation.

  Port of pi-schedule src/schedule.ts. Supported forms:
    every 30m | 30m | every 2h | 1d            (interval, min 1m, max 90d)
    daily at 09:00 | daily 09:00 | at 09:00    (daily local wall clock, DST-safe)
    in 10m | once 10m | 30s                    (one-shot, max 90d)

  All times are epoch-millis longs on the wall clock (absolute persisted
  values); the daily form computes the next local occurrence through
  java.time so DST transitions are handled like pi's day-walk."
  (:require [schedule.util :as util]
            [clojure.string :as str]))

(def ^:private ms-units {"m" 60000 "h" 3600000 "d" 86400000})
(def ^:private once-ms-units (assoc ms-units "s" 1000))
(def ^:private max-once-ms (* 90 86400000))
(def ^:private max-interval-ms (* 90 86400000))

(defn parse-error
  "Throw a schedule parse error (pi ScheduleParseError)."
  [message]
  (throw (ex-info message {:type :schedule-parse-error})))

(defn- parse-daily
  [raw input]
  (when-let [m (or (re-matches #"^(?:daily\s+(?:at\s+)?)?(\d{1,2}):(\d{2})$" raw)
                   (re-matches #"^at\s+(\d{1,2}):(\d{2})$" raw))]
    (let [hour (util/parse-integer (nth m 1))
          minute (util/parse-integer (nth m 2))]
      (when-not (and (integer? hour) (<= 0 hour 23))
        (parse-error (str "Invalid hour in \"" input "\" (expected 0-23)")))
      (when-not (and (integer? minute) (<= 0 minute 59))
        (parse-error (str "Invalid minute in \"" input "\" (expected 0-59)")))
      {:type :daily
       :hour hour
       :minute minute
       :at (format "%02d:%02d" hour minute)})))

(defn- parse-interval
  [raw input]
  (when-let [m (re-matches #"^(?:every\s+)?(\d+)\s*([mhd])$" raw)]
    (let [n (util/parse-integer (nth m 1))
          unit (nth m 2)
          every-ms (* n (get ms-units unit))]
      (when-not (and (integer? n) (pos? n))
        (parse-error (str "Interval must be a positive integer: \"" input "\"")))
      (when (< every-ms 60000)
        (parse-error "Minimum interval is 1m"))
      (when (> every-ms max-interval-ms)
        (parse-error "Maximum interval is 90d"))
      {:type :interval
       :every-ms every-ms
       :every (str n unit)})))

(defn- parse-once
  [raw input]
  (when-let [m (re-matches #"^(?:in|once)\s+(\d+)\s*([smhd])$" raw)]
    (let [n (util/parse-integer (nth m 1))
          unit (nth m 2)
          delay-ms (* n (get once-ms-units unit))]
      (when-not (and (integer? n) (pos? n))
        (parse-error (str "Once delay must be a positive integer: \"" input "\"")))
      (when (> delay-ms max-once-ms)
        (parse-error "Maximum once delay is 90d"))
      {:type :once
       :delay-ms delay-ms
       :delay (str n unit)})))

(defn parse-schedule
  "Parse a human schedule string into a spec map
  (pi parseSchedule). Throws a :schedule-parse-error on anything else."
  [input]
  (let [raw (str/replace (str/lower-case (str/trim (str input))) #"\s+" " ")]
    (when (str/blank? raw)
      (parse-error "Empty schedule string"))
    (or (parse-daily raw input)
        (parse-once raw input)
        (parse-interval raw input)
        (parse-error (str "Unrecognized schedule \"" input "\". Use e.g. \"every 30m\", "
                          "\"every 2h\", \"every 1d\", \"daily at 09:00\", or \"in 10m\".")))))

(defn from-parts
  "Build a spec from separate tool params (pi scheduleFromParts): exactly one
  of :every / :daily-at / :once must be non-blank."
  [{:keys [every daily-at once]}]
  (let [present (keep #(when (and (string? %) (seq (str/trim %))) %)
                      [every daily-at once])]
    (when-not (= 1 (count present))
      (parse-error (str "Provide exactly one of \"every\" (e.g. \"30m\"), "
                        "\"dailyAt\" (e.g. \"09:00\"), or \"once\" (e.g. \"10m\").")))
    (cond
      (and (string? every) (seq (str/trim every)))
      (parse-schedule (str "every " (str/trim every)))
      (and (string? daily-at) (seq (str/trim daily-at)))
      (parse-schedule (str "daily at " (str/trim daily-at)))
      :else (parse-schedule (str "in " (str/trim once))))))

(defn- next-daily-occurrence
  "The next epoch-ms local-wall-clock occurrence of hour:minute at or after
  FROM-MS (strictly after unless INCLUSIVE?). java.time resolves a DST gap by
  shifting forward, so a shifted (nonexistent) time is detected and the day
  walks forward — pi's spring-forward gap walk; fall-back ambiguity keeps
  java.time's platform pick, like pi."
  [schedule-spec from-ms inclusive? zone]
  (let [zone (or zone (java.time.ZoneId/systemDefault))
        {:keys [hour minute]} schedule-spec
        from-zdt (.atZone (java.time.Instant/ofEpochMilli from-ms) zone)
        build (fn [local-date]
                (.atZone (.atTime local-date (java.time.LocalTime/of hour minute)) zone))]
    (loop [date (.toLocalDate from-zdt) guard 0]
      (let [cand (build date)
            wall-ok? (and (= hour (.getHour cand)) (= minute (.getMinute cand)))]
        (cond
          (and (not wall-ok?) (< guard 48))
          (recur (.plusDays ^java.time.LocalDate date 1) (inc guard))

          (not wall-ok?)
          ;; give up walking — take the shifted instant (best effort, pi parity)
          (.toEpochMilli (.toInstant cand))

          :else
          (let [t (.toEpochMilli (.toInstant cand))]
            (if (if inclusive? (>= t from-ms) (> t from-ms))
              t
              (recur (.plusDays ^java.time.LocalDate date 1) (inc guard)))))))))

(defn next-run-at
  "The next run time for SCHEDULE-SPEC from FROM-MS (epoch ms) — at/after
  FROM-MS when INCLUSIVE? (a brand-new job's first slot), strictly after
  otherwise (pi computeNextRunAt)."
  ([schedule-spec from-ms inclusive?]
   (next-run-at schedule-spec from-ms inclusive? nil))
  ([schedule-spec from-ms inclusive? zone]
   (case (:type schedule-spec)
     :interval (if inclusive? from-ms (+ from-ms (:every-ms schedule-spec)))
     :once (if inclusive? from-ms (+ from-ms (:delay-ms schedule-spec)))
     :daily (next-daily-occurrence schedule-spec from-ms inclusive? zone))))

(defn format-schedule
  "Human-readable schedule summary (pi formatSchedule)."
  [schedule-spec]
  (case (:type schedule-spec)
    :interval (str "every " (:every schedule-spec))
    :once (str "once in " (:delay schedule-spec))
    :daily (str "daily at " (:at schedule-spec))))

(defn format-relative
  "Format an epoch-ms timestamp relative to NOW-MS for status text
  (pi formatRelative)."
  [ms now-ms]
  (let [ms (long (or ms 0))
        now-ms (long (or now-ms 0))
        delta (- ms now-ms)
        abs (max delta (- delta))
        future? (pos? delta)]
    (cond
      (< abs 60000) (if future? "in <1m" "just now")
      (< abs 3600000) (str (when future? "in ") (Math/round (/ abs 60000.0)) "m" (when-not future? " ago"))
      (< abs 172800000) (str (when future? "in ") (Math/round (/ abs 3600000.0)) "h" (when-not future? " ago"))
      :else (str (when future? "in ") (Math/round (/ abs 86400000.0)) "d" (when-not future? " ago")))))
