(ns schedule.privilege-queue-test
  (:require [clojure.test :refer [deftest is]]
            [schedule.privilege :as privilege]
            [schedule.privilege-test :refer [attach-to-fake call-tool]]))

(deftest queued-tiers-activate-only-for-their-own-turn
  (let [{:keys [state tool-hook start! settle!]} (attach-to-fake)]
    (privilege/reserve! state "restricted" :read-only)
    (privilege/reserve! state "mutable" :mutate)
    (start! "ordinary user prompt")
    (is (nil? (call-tool tool-hook "bash" {})))
    (settle!)
    (start! "restricted")
    (is (:block (call-tool tool-hook "bash" {})))
    (settle!)
    (start! "mutable")
    (is (nil? (call-tool tool-hook "bash" {})))
    (settle!)
    (is (not (privilege/scheduled-turn-active? state)))))

(deftest failed-delivery-cancels-reservation
  (let [{:keys [state tool-hook start!]} (attach-to-fake)
        token (privilege/reserve! state "failed" :read-only)]
    (privilege/cancel! state token)
    (start! "failed")
    (is (nil? (call-tool tool-hook "bash" {})))
    (is (not (privilege/scheduled-turn-active? state)))))
