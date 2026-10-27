(ns schedule.support
  "Test support: fresh directories under the extension's target/ (never
  /tmp — the Termux rule) and small fake-api/ctx builders."
  (:require [babashka.fs :as fs]))

(defn temp-dir
  "A fresh unique directory under target/ — tests never touch /tmp."
  [prefix]
  (let [d (str (fs/path "target" (str prefix "-" (subs (str (java.util.UUID/randomUUID)) 0 8))))]
    (fs/create-dirs d)
    (str (fs/canonicalize d))))

(defn delete-dir!
  [d]
  (fs/delete-tree (str d)))

(defn make-project
  "A harness project layout under a fresh target dir:
  {:home … :agent … :project … :paths store-paths}."
  []
  (let [home (temp-dir "sched-home")
        agent (str (fs/path home "agent"))
        project (str (fs/path home "project"))]
    (fs/create-dirs agent)
    (fs/create-dirs (fs/path project ".kmet"))
    {:home home :agent agent :project project
     :paths nil}))
