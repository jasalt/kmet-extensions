(ns web-remote.panels-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [web-remote.panels :as panels]))

(defn- fixture []
  (let [calls (atom [])
        active (atom ["read"])
        entries [{:id "u1" :role :user :content "<hello>"} {:id "a1" :role :assistant :content "answer"}]
        api (-> (:api (ext/create-nullable-api))
                (assoc :get-all-tools (constantly [{:name "read" :description "Read <file>"} {:name "write"}])
                       :get-active-tools #(deref active) :set-active-tools #(reset! active %))
                (assoc :session {:get-branch (constantly entries)
                                 :get-entry (fn [id] (some #(when (= id (:id %)) %) entries))
                                 :get-label (constantly nil) :set-label! #(swap! calls conj [:label %1 %2])}))
        ctx {:is-idle (constantly true)
             :navigate-tree #(do (swap! calls conj [:navigate %1 %2]) {:cancelled false})
             :fork #(do (swap! calls conj [:fork %]) {:cancelled false})
             :switch-session #(do (swap! calls conj [:resume %]) {:cancelled false})}]
    {:api api :state (atom {:ctx ctx}) :calls calls :active active}))

(deftest menus-and-tools
  (let [{:keys [api state active]} (fixture)]
    (is (str/includes? (:body (panels/open! api state {:panel "settings"})) "Read &lt;file&gt;"))
    (is (str/includes? (:body (panels/open! api state {:panel "tree"})) "&lt;hello&gt;"))
    (is (str/includes? (:body (panels/open! api state {:panel "resume"})) "Session path"))
    (is (str/includes? (:body (panels/open! api state {:panel "close"})) "<section id=\"panel\"></section>"))
    (panels/action! api state {:action "tool" :value "write" :enabled true})
    (is (= #{"read" "write"} (set @active)))
    (panels/action! api state {:action "tool" :value "read" :enabled false})
    (is (= ["write"] @active))
    (is (str/includes? (:body (panels/action! api state {:action "tool" :value "unknown"})) "Unknown tool"))))

(deftest session-controls
  (let [{:keys [api state calls]} (fixture)]
    (panels/action! api state {:action "navigate" :target "u1" :summarize true :instructions "focus"})
    (is (= [:navigate "u1" {:summarize true :label nil :custom-instructions "focus"}] (last @calls)))
    (panels/action! api state {:action "fork" :target "u1"})
    (is (= [:fork "u1"] (last @calls)))
    (is (str/includes? (:body (panels/action! api state {:action "fork" :target "a1"})) "Choose a user"))
    (panels/action! api state {:action "label" :target "u1" :label " mark "})
    (is (= [:label "u1" "mark"] (last @calls)))
    (panels/action! api state {:action "resume" :value "/session.edn"})
    (is (= [:resume "/session.edn"] (last @calls)))
    (is (str/includes? (:body (panels/action! api state {:action "navigate" :target "unknown"})) "Unknown entry"))
    (swap! state assoc-in [:ctx :is-idle] (constantly false))
    (is (str/includes? (:body (panels/action! api state {:action "fork" :target "u1"})) "Agent is busy"))
    (is (= 4 (count @calls)))))
