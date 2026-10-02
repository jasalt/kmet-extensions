(ns schedule.policy-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.libs.concurrent :as concurrent]
            [schedule.policy :as policy]
            [schedule.schedule :as sched]))

(def interval-job
  {:id "abc" :next-run-at 1000 :missed-window :catch-up-one
   :schedule (sched/parse-schedule "every 1h")})

(def skip-job (assoc interval-job :missed-window :skip))

(deftest keyword-normalization
  (is (= :read-only (policy/as-tier "read_only")))
  (is (= :read-only (policy/as-tier :read-only)))
  (is (= :mutate (policy/as-tier "MUTATE")))
  (is (= :read-only (policy/as-tier "bogus")))      ;; lenient default
  (is (= :catch-up-one (policy/as-missed-window "catch_up_one")))
  (is (= :skip (policy/as-missed-window "skip")))
  (is (= :catch-up-one (policy/as-missed-window nil)))
  (is (= :project (policy/as-scope "project")))
  (is (= :global (policy/as-scope "weird" :global))))

(deftest idempotency-and-grace
  (is (= "abc:1000" (policy/idempotency-key interval-job)))
  (testing "grace floor is 2x tick; 25% of period; capped at 15m"
    (is (= 60000 (policy/grace-ms (assoc interval-job :schedule (sched/parse-schedule "every 1m")) 30000)))
    (is (= 900000 (policy/grace-ms (assoc interval-job :schedule (sched/parse-schedule "every 1d")) 30000)))
    (is (= 3600000 (policy/grace-ms (assoc interval-job :schedule (sched/parse-schedule "daily at 09:00")) 30000)))))

(deftest decide-due
  (testing "catch-up-one always fires the missed slot"
    (is (= :fire (:action (policy/decide-due (assoc interval-job :next-run-at 0) 1000000 30000))))
    (is (= "catch_up_one" (:reason (policy/decide-due (assoc interval-job :next-run-at 0) 1000000 30000)))))
  (testing "skip fires within grace, skips beyond"
    (is (= :fire (:action (policy/decide-due (assoc skip-job :next-run-at 990000) 1000000 30000))))
    (let [d (policy/decide-due (assoc skip-job :next-run-at 0) 1000000 30000)]
      (is (= :skip (:action d)))
      (is (re-find #"missed_window_skip" (:reason d)))))

  (testing "next-after advances strictly"
    (is (= (+ 1000 3600000) (policy/next-after (:schedule interval-job) 1000)))))

(deftest tier-contracts
  (is (str/includes? (policy/tier-contract :read-only) "PRIVILEGE: read_only"))
  (is (str/includes? (policy/tier-contract "mutate") "PRIVILEGE: mutate"))
  (is (str/includes? (policy/tier-contract :suggest) "draft patches")))

(deftest create-rate-limiter
  (let [{:keys [try-take!]} (policy/create-rate-limiter)
        t (atom 0)
        now #(swap! t + 1000)]
    (is (every? true? (repeatedly 10 #(try-take! (now)))))
    (is (not (try-take! (now))))
    ;; a minute passes (monotonic window slides)
    (reset! t (+ @t 60000))
    (is (try-take! (now)))))
