(ns web-remote.completion-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.tui.autocomplete :as ac]
            [web-remote.completion :as completion]))

(deftest provider-roundtrip
  (let [provider (ac/make-combined-provider :commands-fn (constantly [{:name "hello" :description "Say <hello>"}]))
        state (atom {:completion-provider provider})
        response (completion/suggest! state {:prompt "/hel" :cursor 4 :force false})
        ticket (ffirst (:completion-tickets @state))]
    (is (str/includes? (:body response) "Say &lt;hello&gt;"))
    (is (str/includes? (:body response) "datastar-patch-elements"))
    (is (str/includes? (:body (completion/apply! state {:ticket ticket :index 0})) "\"text\":\"/hello \""))
    (is (= "" (:body (completion/apply! state {:ticket ticket :index -1}))))
    (is (= "" (:body (completion/apply! state {:ticket "missing" :index 0}))))
    (dotimes [_ 20] (completion/suggest! state {:prompt "/hel" :cursor 4}))
    (is (= 16 (count (:completion-tickets @state))))
    (is (= "" (:body (completion/apply! state {:ticket ticket :index 0}))))))

(deftest multiline-and-invalid-input
  (let [state (atom {:completion-provider (ac/make-combined-provider :commands-fn (constantly [{:name "hello"}]))})]
    (completion/suggest! state {:prompt "first\n/hel\n" :cursor 10})
    (let [ticket (ffirst (:completion-tickets @state))
          body (:body (completion/apply! state {:ticket ticket :index 0}))]
      (is (str/includes? body "\"text\":\"first\\n/hello \\n\""))
      (is (str/includes? body "\"cursor\":13")))
    (doseq [data [{:prompt nil} {:prompt "/hel" :cursor -1} {:prompt "/hel" :cursor 100}]]
      (is (= 200 (:status (completion/suggest! state data)))))))
