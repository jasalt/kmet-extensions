(ns schedule.trust
  "Project trust registry — the auto-fire gate for project-scope jobs.

  Port of pi-schedule src/trust.ts. A <project>/.kmet/schedule.edn that
  arrives with a cloned repository can carry :kind :shell or :tier :mutate
  rows — auto-firing those would be arbitrary code execution at session
  start. Automatic waves (session start / tick) only fire project jobs whose
  root is listed in <agent-dir>/schedule/trusted.edn. The registry fails
  closed: missing, unreadable, or corrupt means untrusted."
  (:require [schedule.util :as util]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.libs.edn-store :as edn-store]))

(def ^:private trust-version 1)

(defn- normalize-root
  "Canonical absolute project root (Windows paths compared case-insensitively)."
  [root]
  (let [canon (str (try (fs/canonicalize (str root)) (catch Exception _ (fs/absolutize (str root)))))]
    (if (fs/windows?) (str/lower-case canon) canon)))

(defn- read-file-map
  "The trust file as {:version 1 :projects {root {:trusted-at ms}}} — an
  empty registry when missing/corrupt (reads for writing recover; the gate
  itself fails closed)."
  [file]
  (try
    (when (fs/exists? file)
      (let [parsed (edn/read-string (slurp file))]
        (when (and (map? parsed) (= trust-version (:version parsed)) (map? (:projects parsed)))
          {:version trust-version :projects (:projects parsed)})))
    (catch Exception _)))

(defn trusted?
  "True when PROJECT-ROOT is in the trust registry — fail closed on any
  read/parse problem."
  [file project-root]
  (boolean
   (when-let [registry (read-file-map file)]
     (let [entry (get (:projects registry) (normalize-root project-root))]
       (and (map? entry) (contains? entry :trusted-at))))))

(defn trust!
  "Trust PROJECT-ROOT (idempotent; refreshes :trusted-at to NOW-MS).
  Corrupt registries are replaced — the file is rewritten whole anyway."
  [file project-root now-ms]
  (fs/create-dirs (fs/parent file))
  (edn-store/with-file-lock (str file ".lock")
    (fn []
      (let [registry (or (read-file-map file) {:version trust-version :projects {}})
            updated (assoc-in registry [:projects (normalize-root project-root)] {:trusted-at now-ms})
            tmp (str file "." (util/uuid) ".tmp")]
        (try
          (spit tmp (str (pr-str updated) "\n"))
          (fs/move tmp file {:replace-existing true :atomic-move true})
          (finally (fs/delete-if-exists tmp))))))
  nil)

(defn list-trusted
  "Trusted project roots (normalized), for diagnostics."
  [file]
  (vec (keys (:projects (or (read-file-map file) {:projects {}})))))
