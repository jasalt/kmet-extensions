(ns schedule.ledger
  "Append-only run ledger (EDN lines, ~/.kmet/agent/schedule/runs.ednl).

  Port of pi-schedule src/ledger.ts. Forensic trail + secondary idempotency
  check: the primary at-most-once signal is the job row's
  :last-idempotency-key. append! never throws — disk trouble must not block
  next-run-at advancement; the file rotates in place (newest 1000 lines,
  byte-bounded to half the cap) so it cannot grow unbounded. The append+rotate
  pair runs under the file lock so a rotating process cannot drop a
  concurrent append."
  (:require [schedule.util :as util]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.libs.edn-store :as edn-store]))

(def max-history
  "Max ledger lines retained in memory for history / idempotency."
  200)

(def max-ledger-bytes
  "Rotate the ledger once it grows past this (keeps the newest lines)."
  (* 5 1024 1024))

(def ^:private rotate-keep-lines 1000)

(defn new-run-id
  "A 16-hex-char run id."
  []
  (subs (str/replace (str (util/uuid)) "-" "") 0 16))

(defn byte-length
  "UTF-8 byte length."
  ^long [s]
  (alength (.getBytes ^String (str s) "UTF-8")))

(defn build-run
  "A complete ledger row from a partial map (pi buildRun)."
  [{:keys [run-id job-id job-name scope project-path idempotency-key source
           status started-at ended-at detail tier missed-window kind]}]
  {:run-id (or run-id (new-run-id))
   :job-id job-id
   :job-name job-name
   :scope scope
   :project-path project-path
   :idempotency-key idempotency-key
   :source source
   :status status
   :started-at started-at
   :ended-at ended-at
   :detail detail
   :tier tier
   :missed-window missed-window
   :kind kind})

(defn- rotate-locked!
  "Size-capped rewrite keeping the newest lines (pi maybeRotateLocked).
  MUST run under the ledger file lock; best-effort — never throws."
  [file max-bytes]
  (try
    (when (and (fs/exists? file) (> (fs/size file) max-bytes))
      (let [lines (filterv seq (str/split (slurp file) #"\n"))
            target-bytes (max 1 (quot max-bytes 2))
            keep (loop [i (dec (count lines)) acc [] bytes 0]
                   (if (or (neg? i) (>= (count acc) rotate-keep-lines)
                           (> (+ bytes (byte-length (nth lines i)) 1) target-bytes))
                     acc
                     (recur (dec i) (conj acc (nth lines i))
                            (+ bytes (byte-length (nth lines i)) 1))))
            tmp (str file "." (subs (str (java.util.UUID/randomUUID)) 0 8) ".tmp")]
        (spit tmp (str (str/join "\n" (reverse keep)) (when (seq keep) "\n")))
        (fs/move tmp file {:replace-existing true})))
    (catch Exception _)))

(defn append!
  "Append a run row, best-effort — returns true on success, never throws
  (pi RunLedger.append). Rotate when over MAX-BYTES (default 5 MB)."
  ([file run] (append! file run max-ledger-bytes))
  ([file run max-bytes]
   (try
     (fs/create-dirs (fs/parent file))
     (edn-store/with-file-lock (str file ".lock")
       (fn []
         (spit file (str (pr-str run) "\n") :append true)
         (rotate-locked! file max-bytes)))
     true
     (catch Exception _ false))))

(defn read-recent
  "Recent run rows, newest first (last 200 lines; corrupt lines skipped;
  a missing file is [])."
  [file]
  (try
    (when (fs/exists? file)
      (let [lines (filterv seq (str/split (slurp file) #"\n"))
            tail (subvec lines (max 0 (- (count lines) max-history)))]
        (->> (keep (fn [line] (try (let [v (edn/read-string line)]
                                     (when (map? v) v))
                                   (catch Exception _)))
                   tail)
             (reverse)
             (vec))))
    (catch Exception _ [])))

(defn was-delivered?
  "True when this idempotency key already has a successful delivery within
  the recent window (secondary — the job row's key is primary)."
  [file idempotency-key]
  (boolean (some (fn [run] (and (= idempotency-key (:idempotency-key run))
                                (= :delivered (:status run))))
                 (read-recent file))))

(defn history
  "Recent runs, newest first, optionally filtered by job id, capped at LIMIT
  (default 20)."
  ([file] (history file nil 20))
  ([file job-id] (history file job-id 20))
  ([file job-id limit]
   (let [rows (if (seq (str/trim (str (or job-id ""))))
                (filter #(= (str/trim (str job-id)) (:job-id %)) (read-recent file))
                (read-recent file))]
     (vec (take (or limit 20) rows)))))
