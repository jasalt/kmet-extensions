(ns schedule.privilege-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [schedule.privilege :as privilege]))

(defn attach-to-fake
  "A privilege state attached to a fake api: returns the state plus the
  captured on-tool-call hook and :agent-settled handler."
  []
  (let [tool-hooks (atom [])
        event-handlers (atom {})
        starts (atom [])
        api {:on-before-agent-start (fn [hook] (swap! starts conj hook) (fn [] nil))
             :on-tool-call (fn [hook] (swap! tool-hooks conj hook) (fn [] nil))
             :on-event (fn [type handler]
                         (swap! event-handlers update type (fnil conj []) handler)
                         (fn [] nil))}
        state (privilege/make-privilege)]
    (privilege/attach api state)
    {:state state
     :tool-hook (first @tool-hooks)
     :start! (fn [prompt] (doseq [h @starts] (h {:prompt prompt})))
     :settle! (fn [] (doseq [h (get @event-handlers :agent-settled)] (h {} {})))}))

(defn call-tool
  "Invoke the captured tool-call hook with a raw string-keyed payload."
  [hook tool-name args]
  (hook {:tool-name tool-name :args args}))

(deftest read-only-is-strict
  (let [{:keys [state tool-hook]} (attach-to-fake)]
    (privilege/enter! state :read-only)
    (testing "known-read allowlist passes"
      (doseq [t ["read" "grep" "find" "ls"]]
        (is (nil? (call-tool tool-hook t {"path" "x"})) (str t " should pass")))
      (is (nil? (call-tool tool-hook "schedule" {"action" "list"}))
          "schedule list is a read"))
    (testing "mutating/unknown tools fail closed with :terminate"
      (doseq [t ["bash" "edit" "write" "run_code" "clojure_eval" "mystery_tool"]]
        (let [r (call-tool tool-hook t {})]
          (is (map? r) (str t " should be blocked"))
          (is (true? (:block r)))
          (is (true? (:terminate r)))
          (is (str/includes? (:reason r) "read_only"))))
      (let [r (call-tool tool-hook "schedule" {"action" "create"})]
        (is (true? (:block r)) "schedule mutations are blocked")
        (is (str/includes? (:reason r) "tier=mutate"))))))

(deftest suggest-blocks-exec-not-drafting
  (let [{:keys [state tool-hook]} (attach-to-fake)]
    (privilege/enter! state :suggest)
    (is (nil? (call-tool tool-hook "edit" {})) "drafting stays open")
    (is (nil? (call-tool tool-hook "write" {})))
    (is (true? (:block (call-tool tool-hook "bash" {}))))
    (is (true? (:block (call-tool tool-hook "run_code" {}))))
    (is (true? (:block (call-tool tool-hook "schedule" {"action" "trust"}))))))

(deftest no-tier-no-block
  (let [{:keys [state tool-hook]} (attach-to-fake)]
    (is (nil? (call-tool tool-hook "bash" {})) "interactive turns are unaffected")))

(deftest settled-pops-and-depth-guards
  (let [{:keys [state tool-hook settle!]} (attach-to-fake)]
    (privilege/enter! state :read-only)
    (is (= 1 (privilege/depth state)))
    (is (true? (privilege/scheduled-turn-active? state)))
    (settle!)
    (is (= 0 (privilege/depth state)))
    (is (nil? (call-tool tool-hook "bash" {})) "stack drained — no block")
    (settle!)  ;; extra settles pop nothing
    (is (= 0 (privilege/depth state)))
    (dotimes [_ 20] (privilege/enter! state :read-only))
    (is (<= (privilege/depth state) privilege/max-depth))
    (privilege/clear! state)
    (is (= 0 (privilege/depth state)))))
