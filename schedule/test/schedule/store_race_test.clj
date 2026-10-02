(ns schedule.store-race-test
  (:require [clojure.test :refer [deftest is]]
            [schedule.schedule :as sched]
            [schedule.store :as store]
            [schedule.support :as support]))

(deftest stale-attempts-preserve-disable-and-never-resurrect-cancelled-jobs
  (let [layout (support/make-project)
        paths (store/paths (:agent layout))
        cwd (:project layout)]
    (try
      (let [job (store/create-job paths {:name "race" :prompt "review" :scope :global
                                         :schedule (sched/parse-schedule "every 1h")
                                         :now-ms 1000})]
        (store/set-enabled paths (:id job) cwd false)
        (let [recorded (store/mark-attempt paths job 2000 :ok)]
          (is (false? (:enabled recorded)))
          (is (= 1 (:run-count recorded))))
        (store/mark-attempt paths job 3000 :ok)
        (is (= 2 (:run-count (store/get-job paths (:id job) cwd))))
        (store/remove-job paths (:id job) cwd)
        (is (nil? (store/mark-attempt paths job 4000 :ok)))
        (is (nil? (store/terminate paths job :max-runs)))
        (is (empty? (store/list-for-cwd paths cwd))))
      (finally (support/delete-dir! (:home layout))))))
