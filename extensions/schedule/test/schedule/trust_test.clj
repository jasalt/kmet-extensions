(ns schedule.trust-test
  (:require [clojure.test :refer [deftest is testing]]
            [schedule.support :as support]
            [schedule.trust :as trust]))

(deftest trust-fails-closed
  (let [dir (support/temp-dir "sched-trust")
        file (str (str dir) "/trusted.edn")]
    (testing "missing / unreadable / corrupt means untrusted"
      (is (not (trust/trusted? file (str dir))))
      (spit file "{:version 1 :projects {")
      (is (not (trust/trusted? file (str dir))))
      (spit file "{:version 99 :projects {}}")
      (is (not (trust/trusted? file (str dir)))))
    (testing "trust! grants; repeated reads stay trusted"
      (trust/trust! file (str dir) 123)
      (is (trust/trusted? file (str dir)))
      (is (= [(str dir)] (trust/list-trusted file)))
      (is (not (trust/trusted? file (str dir "/other")))))
    (testing "corrupt registry is replaced by the next trust!"
      (spit file "not edn at all")
      (is (not (trust/trusted? file (str dir))))
      (trust/trust! file (str dir) 456)
      (is (trust/trusted? file (str dir))))
    (support/delete-dir! dir)))
