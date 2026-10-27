;; Copyright (c) 2026 Jarkko Saltiola; SPDX-License-Identifier: MIT
(ns session-migrate.writer-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.libs.json :as json]
            [session-migrate.claude :as claude]
            [session-migrate.io :as mio]
            [session-migrate.writer :as sut]
            [session-migrate.test-support :refer [with-temp native-fixture source! message]]))

(defn records [path]
  (mapv edn/read-string (str/split (slurp path :encoding "UTF-8") #"\n")))

(deftest native-tree-and-private-manifest
  (with-temp [dir]
    (let [transcript (claude/read-claude native-fixture)
          source-hash (mio/sha256 (slurp native-fixture :encoding "UTF-8"))
          {:keys [path manifest-path session-id]} (sut/write-session! transcript dir dir)
          rows (records path)
          audit (json/parse-string (slurp manifest-path) true)]
      (is (= {:type :session :version 1 :id session-id :cwd dir}
             (dissoc (first rows) :created-at)))
      (is (= "repair-event-window-boundary" (:name (last rows))))
      (is (= (cons nil (map :id (butlast (rest rows)))) (map :parent-id (rest rows))))
      (is (= source-hash (get-in audit [:source :source-sha256])))
      (is (= (:initial-target-sha256 audit) (mio/sha256 (slurp path :encoding "UTF-8"))))
      (is (not (str/includes? (slurp manifest-path) (:title transcript))))
      (doseq [file [path manifest-path]]
        (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions file)))))
      (is (not-any? #(str/starts-with? (fs/file-name %) ".migration-") (fs/list-dir dir)))
      (is (= source-hash (mio/sha256 (slurp native-fixture :encoding "UTF-8"))))
      (let [other (sut/write-session! transcript dir dir)]
        (is (not= path (:path other))) (is (not= session-id (:session-id other)))))))

(deftest compaction-self-anchor
  (with-temp [dir]
    (let [summary (assoc (message "s" "b" "user" "SUMMARY") :isCompactSummary true)
          s (claude/read-claude (source! dir [(message "old" nil "user" "OLD")
                                              {:type "system" :subtype "compact_boundary" :uuid "b" :logicalParentUuid "old"}
                                              summary (message "new" "s" "user" "NEW")]))
          result (sut/write-session! s dir dir)
          compaction (first (filter #(= :compaction (:role %)) (records (:path result))))]
      (is (= (:id compaction) (:first-kept-id compaction))))))

(deftest failed-publication-and-cancellation
  (with-temp [dir]
    (let [transcript (claude/read-claude native-fixture)
          marker (str (fs/path dir "KEEP.ednl"))]
      (spit marker "KEEP")
      (is (thrown? Exception (sut/write-session! transcript "relative" dir)))
      (is (thrown? Exception (sut/write-session! transcript marker dir)))
      (is (thrown? Exception (sut/write-session! {:entries []} dir dir)))
      (is (thrown-with-msg? Exception #"cancelled" (sut/write-session! transcript dir dir (constantly true))))
      (let [calls (atom 0) original fs/create-link]
        (with-redefs [fs/create-link (fn [link existing]
                                       (if (= 2 (swap! calls inc))
                                         (throw (ex-info "synthetic publish failure" {:type :test-failure}))
                                         (original link existing)))]
          (is (thrown-with-msg? Exception #"synthetic" (sut/write-session! transcript dir dir)))))
      (is (= ["KEEP.ednl"] (mapv fs/file-name (fs/list-dir dir))))
      (is (= "KEEP" (slurp marker))))))

(deftest ^:slow parallel-no-overwrite
  (with-temp [dir]
    (let [transcript (claude/read-claude native-fixture)
          work (mapv (fn [_] (future (sut/write-session! transcript dir dir))) (range 8))
          results (mapv deref work)]
      (is (= 8 (count (set (map :path results)))))
      (is (= 8 (count (set (map :session-id results)))))
      (is (= 16 (count (fs/list-dir dir)))))))
