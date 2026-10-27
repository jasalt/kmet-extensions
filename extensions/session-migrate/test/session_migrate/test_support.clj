;; Copyright (c) 2026 Jarkko Saltiola; SPDX-License-Identifier: MIT
(ns session-migrate.test-support
  (:require [babashka.fs :as fs]
            [kmet.libs.json :as json]))

(def root (or (System/getenv "SESSION_MIGRATE_ROOT") (str (fs/cwd))))
(def native-fixture (str (fs/path root "testdata/claude-native.jsonl")))

(defmacro with-temp [[dir] & body]
  `(let [~dir (str (fs/create-temp-dir (cond-> {:prefix "session-migrate-test-"}
                                         (System/getenv "TMPDIR") (assoc :dir (System/getenv "TMPDIR")))))]
     (try ~@body (finally (fs/delete-tree ~dir)))))

(defn source! [dir rows]
  (let [path (str (fs/path dir (str (random-uuid) ".jsonl")))]
    (spit path (apply str (map #(str (json/generate-string %) "\n") rows)) :encoding "UTF-8")
    path))

(defn message [id parent role content]
  {:type role :uuid id :parentUuid parent :sessionId "source"
   :timestamp "2026-01-01T00:00:00Z" :message {:role role :content content}})
