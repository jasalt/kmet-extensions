(ns schedule.ledger-test
  (:require [clojure.test :refer [deftest is testing]]
            [schedule.ledger :as ledger]
            [schedule.support :as support]))

(deftest append-read-history
  (let [dir (support/temp-dir "sched-ledger")
        file (str (str dir) "/runs.ednl")
        run (fn [id status]
              (ledger/build-run {:run-id id :job-id "job" :job-name "n"
                                 :scope :global :idempotency-key (str "job:" id)
                                 :source :tick :status status
                                 :started-at 1 :ended-at 2
                                 :tier :read-only :missed-window :catch-up-one :kind :prompt}))]
    (testing "append never throws; newest first; corrupt lines skipped"
      (is (true? (ledger/append! file (run "r1" :delivered))))
      (spit file "garbage-not-edn\n" :append true)
      (is (true? (ledger/append! file (run "r2" :delivered))))
      (is (= ["r2" "r1"] (map :run-id (ledger/read-recent file))))
      (is (= 2 (count (ledger/read-recent file)))))
    (testing "was-delivered? matches key + status"
      (is (ledger/was-delivered? file "job:r1"))
      (is (not (ledger/was-delivered? file "job:missing")))
      (is (true? (ledger/append! file (run "r3" :error))))
      (is (not (ledger/was-delivered? file "job:r3")) "errors are not deliveries"))
    (testing "history filters by job and caps"
      (is (= 3 (count (ledger/history file))))
      (is (= 3 (count (ledger/history file "job"))))
      (is (= 1 (count (ledger/history file "job" 1))))
      (is (empty? (ledger/history file "other"))))
    (testing "rotation keeps the newest lines, byte-bounded"
      (let [file2 (str (str dir) "/rotate.ednl")]
        (dotimes [i 60]
          (ledger/append! file2 (assoc (run (str "r" i) :delivered)
                                       :detail (apply str (repeat 200 "x"))) 2000))
        ;; each row ~300+ bytes; a 2KB cap forces rotation
        (is (< (count (slurp file2)) 4000) "file rotated below roughly the cap")
        (let [rows (ledger/read-recent file2)]
          (is (< 0 (count rows) 60) "rotation keeps a bounded recent window")
          (is (= "r59" (:run-id (first rows))) "newest survived rotation")
          (is (nil? (some #(= "r0" (:run-id %)) rows)) "oldest rotated away"))))
    (support/delete-dir! dir)))

(deftest build-run-defaults
  (let [row (ledger/build-run {:job-id "j" :status :ok})]
    (is (= "j" (:job-id row)))
    (is (pos? (count (:run-id row))))
    (is (= :ok (:status row)))))
