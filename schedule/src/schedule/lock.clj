(ns schedule.lock
  "Single-flight locks for job delivery.

  Port of pi-schedule src/lock.ts, adapted to the kmet idiom: the lock is an
  exclusively created *directory* (babashka fs/create-dir throws when the
  path exists — the same primitive kmet.libs.edn-store uses), ownership is a
  token file inside it, and staleness is read from the directory's mtime (a
  wall-clock file-mtime comparison — a lock older than 30 minutes is presumed
  orphaned). Stale takeover moves the orphan aside with an atomic rename so
  at most one racer claims it; release only deletes a lock still owned by
  this token."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def stale-ms
  "A lock held longer than this (by mtime) is presumed orphaned."
  (* 30 60 1000))

(defn- release-held
  "Release the lock for JOB-ID only when TOKEN still owns it (the file token
  guards a stale-takeover victim from deleting the new owner's lock)."
  [held dir job-id token]
  (when (= token (get @held job-id))
    (swap! held dissoc job-id))
  (try
    (when (and (fs/exists? dir) (fs/exists? (str (fs/path dir "owner"))))
      (let [owner (try (slurp (str (fs/path dir "owner"))) (catch Exception _ nil))]
        (when (= token owner)
          (fs/delete-tree dir))))
    (catch Exception _)))

(defn make-lock-manager
  "A per-runner lock manager for LOCK-DIR: {:try-acquire try-acquire}."
  [lock-dir]
  (let [held (atom {})]
    {:held held
     :try-acquire
     (fn try-acquire
       [job-id]
       (when-not (contains? @held job-id)
         (let [safe-id (str/replace (str job-id) #"[^a-zA-Z0-9_-]" "_")
               dir (str (fs/path lock-dir (str safe-id ".lock")))
               token (str (System/currentTimeMillis) "-"
                          (subs (str (java.util.UUID/randomUUID)) 0 8))
               owner-file (fn [] (str (fs/path dir "owner")))
               create (fn []
                        (try (fs/create-dir dir) true (catch Exception _ false)))
               write-owner (fn []
                             (try (spit (owner-file) token) true
                                  (catch Exception _ false)))
               read-token (fn []
                            (try (edn/read-string (slurp (owner-file)))
                                 (catch Exception _ nil)))
               stale? (fn []
                        (try (> (- (System/currentTimeMillis)
                                   (.toMillis (fs/last-modified-time dir)))
                                stale-ms)
                             (catch Exception _ false)))]
           (fs/create-dirs lock-dir)
           (or (when (and (create) (write-owner))
                 (swap! held assoc job-id token)
                 {:job-id job-id
                  :release (fn [] (release-held held dir job-id token))})
               ;; contended: only a genuinely stale holder may be taken over —
               ;; rename aside (one racer wins), then claim
               (when (and (fs/exists? dir) (stale?))
                 (let [dead (str dir ".dead." token)]
                   (try (fs/move dir dead) (catch Exception _))
                   (try (fs/delete-tree dead) (catch Exception _)))
                 (when (and (create) (write-owner))
                   (swap! held assoc job-id token)
                   {:job-id job-id
                    :release (fn [] (release-held held dir job-id token))}))))))}))

