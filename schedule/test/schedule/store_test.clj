(ns schedule.store-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [schedule.schedule :as sched]
            [schedule.store :as store]
            [schedule.support :as support]))

(def ^:dynamic *layout* nil)

(use-fixtures :each
  (fn [f]
    (let [layout (support/make-project)
          paths (store/paths (:agent layout))]
      (binding [*layout* layout]
        (with-redefs [store/paths (constantly paths)]
          (try (f)
               (finally (support/delete-dir! (:home layout)))))))))

(defn create-job*
  ([m] (create-job* m (:project *layout*)))
  ([m cwd]
   (store/create-job (store/paths (:agent *layout*))
                     (merge {:name "job" :prompt "do the thing"
                             :schedule (sched/parse-schedule "every 1h")
                             :scope :global
                             :project-path cwd
                             :now-ms 1735689600000}
                            m))))

(deftest paths-and-default-scope
  (let [paths (store/paths (:agent *layout*))]
    (is (str/ends-with? (:global-file paths) "schedules.edn"))
    (is (= (str (fs/path (:project *layout*) ".kmet" "schedule.edn"))
           ((:project-file paths) (:project *layout*)))))
  (is (= :project (store/default-scope (:project *layout*))))
  (is (= :global (store/default-scope (:home *layout*)))))

(deftest create-list-get-roundtrip
  (let [paths (store/paths (:agent *layout*))
        job (create-job* {:scope :project})
        listed (store/list-for-cwd paths (:project *layout*))]
    (is (= 1 (count listed)))
    (is (= (:id job) (:id (first listed))))
    (is (= :project (:scope (first listed))))
    (is (= (:project *layout*) (:project-path (first listed))))
    (is (pos? (:next-run-at (first listed))))
    (is (= job (store/get-job paths (:id job) (:project *layout*))))
    (is (nil? (store/get-job paths "nope" (:project *layout*))))))

(deftest project-provenance-not-the-scope-label
  ;; a cloned project file cannot relabel a row as global to bypass the trust gate
  (let [paths (store/paths (:agent *layout*))
        project-file ((:project-file paths) (:project *layout*))]
    (fs/create-dirs (fs/parent project-file))
    (spit project-file (str "{:version 1 :jobs [{:id \"dead\" :name \"evil\" :kind :shell"
                            " :command \"curl evil\" :scope :global"
                            " :schedule {:type :interval :every-ms 60000 :every \"1m\"}"
                            " :next-run-at 0 :run-count 0 :created-at 0 :updated-at 0}]}"))
    (let [listed (store/list-for-cwd paths (:project *layout*))]
      (is (= 1 (count listed)))
      (is (= :project (:scope (first listed))))
      (is (= (:project *layout*) (:project-path (first listed))))
      (is (= :shell (:kind (first listed)))))))

(deftest mark-attempt-advances-and-counts
  (let [paths (store/paths (:agent *layout*))
        job (create-job* {})
        next-before (:next-run-at job)
        skipped (store/mark-attempt paths job 1735689600001 :skipped {:error "x"})
        fired (store/mark-attempt paths skipped 1735689600002 :ok {:idempotency-key "k"})]
    (is (= :skipped (:last-status skipped)))
    (is (= 0 (:run-count skipped)) "skips never count")
    (is (pos? (- (:next-run-at skipped) next-before)) "a skip still advances the slot")
    (is (= 1 (:run-count fired)))
    (is (= :ok (:last-status fired)))
    (is (not= (:next-run-at fired) (:next-run-at skipped)))
    (is (= "k" (:last-idempotency-key fired)))))

(deftest enable-disable-terminate-remove
  (let [paths (store/paths (:agent *layout*))
        job (create-job* {})
        disabled (store/set-enabled paths (:id job) (:project *layout*) false)]
    (is (false? (:enabled disabled)))
    (is (empty? (store/due-jobs paths (:project *layout*) Long/MAX_VALUE)))
    (is (:enabled (store/set-enabled paths (:id job) (:project *layout*) true)))
    (let [terminated (store/terminate paths disabled :max-runs)]
      (is (= :max-runs (:terminated terminated)))
      (is (false? (:enabled terminated)))
      (is (empty? (store/due-jobs paths (:project *layout*) Long/MAX_VALUE)))
      ;; re-enabling clears the terminal flag
      (is (nil? (:terminated (store/set-enabled paths (:id job) (:project *layout*) true)))))
    (is (some? (store/remove-job paths (:id job) (:project *layout*))))
    (is (nil? (store/remove-job paths (:id job) (:project *layout*))))))

(deftest due-jobs-filter
  (let [paths (store/paths (:agent *layout*))
        j1 (create-job* {:now-ms (- 1735689600000 7200000)})
        _ (create-job* {:name "future" :now-ms 1735689600000})
        disabled (create-job* {:name "off" :now-ms (- 1735689600000 7200000)})]
    (store/set-enabled paths (:id disabled) (:project *layout*) false)
    (let [due (store/due-jobs paths (:project *layout*) 1735689600000)]
      (is (= 1 (count due)))
      (is (= (:id j1) (:id (first due)))))))

(deftest corrupt-store-quarantines
  (let [paths (store/paths (:agent *layout*))
        project-file ((:project-file paths) (:project *layout*))]
    (fs/create-dirs (fs/parent project-file))
    (spit project-file "{:version 1 :jobs [not-a-map")
    (is (thrown-with-msg? Exception #"quarantined" (store/list-for-cwd paths (:project *layout*))))
    (is (seq (fs/glob (fs/parent project-file) "schedule.edn.corrupt-*")) "bad file moved aside")
    (is (not (fs/exists? project-file)) "original gone")
    ;; a retry after restoring the quarantined file works (no restart needed)
    (let [q (first (fs/glob (fs/parent project-file) "schedule.edn.corrupt-*"))]
      (fs/move (str q) project-file))
    (is (thrown-with-msg? Exception #"quarantined" (store/list-for-cwd paths (:project *layout*))))
    ;; wrong version quarantines too
    (spit project-file "{:version 99 :jobs []}")
    (is (thrown-with-msg? Exception #"unsupported version" (store/list-for-cwd paths (:project *layout*))))))

(deftest job-cap-per-scope
  (let [paths (store/paths (:agent *layout*))]
    (dotimes [_ 50]
      (create-job* {:scope :global}))
    (is (thrown-with-msg? Exception #"Job limit reached"
                          (create-job* {:scope :global})))))
