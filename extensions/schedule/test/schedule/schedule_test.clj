(ns schedule.schedule-test
  (:require [clojure.test :refer [deftest is testing]]
            [schedule.schedule :as sched]))

(def utc (java.time.ZoneId/of "UTC"))
;; 2025-01-01T00:00:00Z
(def t0 1735689600000)

(deftest parse-schedule-forms
  (testing "intervals"
    (is (= {:type :interval :every-ms 1800000 :every "30m"} (sched/parse-schedule "every 30m")))
    (is (= (:every-ms (sched/parse-schedule "2h")) 7200000))
    (is (= (:every-ms (sched/parse-schedule "Every  1 d")) 86400000))
    (is (thrown-with-msg? Exception #"positive integer" (sched/parse-schedule "every 0m")))
    (is (thrown-with-msg? Exception #"Unrecognized" (sched/parse-schedule "every 30s"))
        "seconds are only valid for once")
    (is (thrown-with-msg? Exception #"Unrecognized" (sched/parse-schedule "nope"))))
  (testing "daily"
    (is (= {:type :daily :hour 9 :minute 0 :at "09:00"} (sched/parse-schedule "daily at 09:00")))
    (is (= :daily (:type (sched/parse-schedule "at 17:30"))))
    (is (thrown-with-msg? Exception #"Invalid hour" (sched/parse-schedule "24:00")))
    (is (thrown-with-msg? Exception #"Invalid minute" (sched/parse-schedule "09:60"))))
  (testing "once"
    (is (= {:type :once :delay-ms 30000 :delay "30s"} (sched/parse-schedule "in 30s")))
    (is (= :once (:type (sched/parse-schedule "once 10m"))))
    (is (thrown-with-msg? Exception #"Maximum once delay" (sched/parse-schedule "in 91d")))))

(deftest from-parts-xor
  (is (= :interval (:type (sched/from-parts {:every "30m"}))))
  (is (= :daily (:type (sched/from-parts {:daily-at "09:00"}))))
  (is (= :once (:type (sched/from-parts {:once "10m"}))))
  (is (thrown-with-msg? Exception #"exactly one" (sched/from-parts {:every "30m" :daily-at "09:00"})))
  (is (thrown-with-msg? Exception #"exactly one" (sched/from-parts {}))))

(deftest next-run-at-math
  (testing "interval/once anchor from 'now'"
    (is (= (+ t0 1800000) (sched/next-run-at {:type :interval :every-ms 1800000} t0 false)))
    (is (= t0 (sched/next-run-at {:type :interval :every-ms 1800000} t0 true)))
    (is (= (+ t0 30000) (sched/next-run-at {:type :once :delay-ms 30000} t0 false))))
  (testing "daily next occurrence (UTC)"
    ;; 2025-01-01T00:00Z — 09:00 later same day
    (is (= 1735722000000 (sched/next-run-at {:type :daily :hour 9 :minute 0} t0 false utc)))
    ;; 00:30 is still ahead on the same day
    (is (= 1735691400000 (sched/next-run-at {:type :daily :hour 0 :minute 30} t0 false utc)))
    ;; inclusive fires the same slot
    (is (= 1735689600000 (sched/next-run-at {:type :daily :hour 0 :minute 0} t0 true utc)))))

(deftest formatting
  (is (= "every 30m" (sched/format-schedule (sched/parse-schedule "every 30m"))))
  (is (= "daily at 09:00" (sched/format-schedule (sched/parse-schedule "daily at 09:00"))))
  (is (= "once in 10m" (sched/format-schedule (sched/parse-schedule "in 10m"))))
  (is (= "in <1m" (sched/format-relative (+ t0 30000) t0)))
  (is (= "in 5m" (sched/format-relative (+ t0 300000) t0)))
  (is (= "just now" (sched/format-relative t0 t0)))
  (is (= "2h ago" (sched/format-relative (- t0 7200000) t0)))
  (is (= "in 2d" (sched/format-relative (+ t0 172800000) t0))))
