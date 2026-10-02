(ns schedule.core-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.extension :as ext]
            [schedule.core :as core]
            [schedule.runner :as runner]))

(deftest init-registers-tool-skill-and-events
  (let [dir (fs/canonicalize "src")
        {:keys [api state]} (ext/create-nullable-api {:agent-dir (fs/canonicalize ".")})
        api (assoc api :extension-dir (str dir))
        r (core/init api)]
    (try
      (is (contains? (:tools @state) "schedule") "the schedule tool registered")
      (is (= 1 (count (:skills @state))) "the bundled skill registered")
      (is (str/includes? (:content (first (:skills @state))) "Schedule — recurring agent tasks"))
      (is (seq (get-in @state [:handlers :session-start])) "session lifecycle bound")
      (is (seq (get-in @state [:handlers :agent-settled])) "privilege settle bound")
      (is (seq (get-in @state [:handlers :session-before-compact])))
      (is (seq (get-in @state [:handlers :compaction-end])))
      (is (seq (:tool-call-hooks @state)) "the privilege hook registered")
      (is (map? r) "init returns the runner")
      (is (false? @(:stop r)))
      (finally
        (core/shutdown api)
        (is (true? @(:stop r)) "shutdown stops the ticker")))))

(deftest shutdown-is-idempotent
  (let [{:keys [api]} (ext/create-nullable-api)]
    (core/init api)
    (core/shutdown api)
    (core/shutdown api)
    (is true "a second shutdown is a no-op")))
