(ns schedule.action-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [schedule.action :as action]))

(deftest normalize-create-kind
  (testing "prompt requires prompt text"
    (is (thrown-with-msg? Exception #"requires \"prompt\"" (action/normalize-create-kind {})))
    (is (= {:kind :prompt :prompt "do it" :force-tier-mutate false}
           (action/normalize-create-kind {:kind "prompt" :prompt " do it "}))))
  (testing "shell requires command and forces mutate"
    (let [r (action/normalize-create-kind {:kind "shell" :command "npm test" :wake-on "failure"
                                           :failure-prompt "fix it"})]
      (is (= :shell (:kind r)))
      (is (= "npm test" (:command r)))
      (is (= :failure (:wake-on r)))
      (is (true? (:force-tier-mutate r))))
    (is (thrown-with-msg? Exception #"requires \"command\"" (action/normalize-create-kind {:kind "shell"})))
    (is (thrown-with-msg? Exception #"only valid for kind=shell"
                          (action/normalize-create-kind {:kind :prompt :prompt "x" :command "ls"})))
    (is (thrown-with-msg? Exception #"Invalid wakeOn" (action/normalize-create-kind
                                                       {:kind "shell" :command "ls" :wake-on "sometimes"}))))
  (testing "wake-on default: always when any follow-up text, else never"
    (is (= :never (:wake-on (action/normalize-create-kind {:kind "shell" :command "ls"}))))
    (is (= :always (:wake-on (action/normalize-create-kind {:kind "shell" :command "ls" :prompt "review"}))))))

(deftest shell-wake-decisions
  (let [ok {:exit 0 :killed false}
        bad {:exit 1 :killed false}]
    (is (action/shell-ok? ok))
    (is (not (action/shell-ok? bad)))
    (is (action/should-wake? {:wake-on :failure :prompt "x"} bad))
    (is (not (action/should-wake? {:wake-on :failure :prompt "x"} ok)))
    (is (action/should-wake? {:wake-on :success} ok))
    (is (not (action/should-wake? {:wake-on :never :prompt "x"} bad)))
    (is (= "good" (action/select-shell-follow-up {:success-prompt "good" :failure-prompt "bad"} ok)))
    (is (= "bad" (action/select-shell-follow-up {:success-prompt "good" :failure-prompt "bad"} bad)))
    (is (= "fallback" (action/select-shell-follow-up {:prompt "fallback"} bad)))
    (is (nil? (action/select-shell-follow-up {} bad)))
    (is (string? (action/select-shell-follow-up {:wake-on :always} bad)))))

(deftest timeout-and-truncation
  (is (= 60000 (action/clamp-timeout-ms nil)))
  (is (= 123456 (action/clamp-timeout-ms 123456)))
  (is (= 600000 (action/clamp-timeout-ms 99999999)))
  (is (thrown? Exception (action/clamp-timeout-ms -5)))
  (is (= "short" (action/truncate-output "short" 100)))
  (is (str/includes? (action/truncate-output (apply str (repeat 300 "x")) 40) "…")))

(deftest max-runs-and-terminal
  (is (nil? (action/normalize-max-runs nil)))
  (is (= 10 (action/normalize-max-runs "10")))
  (is (thrown? Exception (action/normalize-max-runs 0)))
  (doseq [v ["x" 1.5 -1 {}]]
    (is (thrown? Exception (action/normalize-max-runs v))))
  (is (= :once (action/terminal-reason {:schedule {:type :once}} 1)))
  (is (nil? (action/terminal-reason {:schedule {:type :interval :every-ms 1} :max-runs 5} 4)))
  (is (= :max-runs (action/terminal-reason {:schedule {:type :interval :every-ms 1} :max-runs 5} 5))))
