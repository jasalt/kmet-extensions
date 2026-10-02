(ns schedule.lock-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [schedule.lock :as lock]
            [schedule.support :as support]))

(deftest acquire-release-cycle
  (let [dir (support/temp-dir "sched-lock")
        mgr (lock/make-lock-manager (str dir))]
    (testing "first acquire wins; in-process second fails"
      (let [h ((:try-acquire mgr) "job1")]
        (is (some? h))
        (is (= "job1" (:job-id h)))
        (is (nil? ((:try-acquire mgr) "job1")))
        ((:release h))
        (let [again ((:try-acquire mgr) "job1")]
          (is (some? again) "release unlocks the id")
          ((:release again)))))
    (testing "job ids are sanitized for lock file names"
      (let [h ((:try-acquire mgr) "evil/../id")]
        (is (fs/exists? (str (fs/path (str dir) "evil____id.lock"))))
        ((:release h))))
    (testing "release only deletes a lock still owned by this token"
      (let [h1 ((:try-acquire mgr) "job2")
            _ ((:release h1))
            h2 ((:try-acquire mgr) "job2")
            ;; a stale h1 release must not delete h2's lock
            _ ((:release h1))
            exists? (fs/exists? (str (fs/path (str dir) "job2.lock")))]
        (is exists?)
        ((:release h2))
        (is (not (fs/exists? (str (fs/path (str dir) "job2.lock")))))))
    (support/delete-dir! dir)))

(deftest stale-takeover
  (let [dir (support/temp-dir "sched-stale")
        mgr (lock/make-lock-manager (str dir))
        lock-dir (str (fs/path (str dir) "stale.lock"))]
    ;; a foreign lock older than STALE-MS is presumed orphaned
    (fs/create-dirs lock-dir)
    (spit (str (fs/path lock-dir "owner")) "ancient-token")
    (fs/set-last-modified-time lock-dir (- (System/currentTimeMillis) lock/stale-ms 60000))
    (let [h ((:try-acquire mgr) "stale")]
      (is (some? h) "a stale lock is taken over")
      ((:release h))
      (is (not (fs/exists? lock-dir))))
    ;; a fresh foreign lock is NOT taken over
    (fs/create-dirs lock-dir)
    (spit (str (fs/path lock-dir "owner")) "live-token")
    (is (nil? ((:try-acquire mgr) "stale")) "a live foreign lock blocks acquisition")
    (fs/delete-tree lock-dir)
    (support/delete-dir! dir)))
