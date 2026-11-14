(ns web-remote.view-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [web-remote.view :as view]))

;; ─── view ──────────────────────────────────────────────────────────────────

(deftest escaping
  (is (= "<p id=\"x\" class=\"a b\" title=\"&quot;&gt;\">&lt;script&gt;&amp;</p>"
         (view/html [:p#x.a {:class "b" :title "\">"} "<script>&"])))
  (is (= "<input disabled>" (view/html [:input {:disabled true :hidden false}])))
  (let [html (view/transcript [{:role :user :content [{:type :text :text "<img src=x onerror=alert(1)>"}]}
                               {:role :assistant :content [{:type :text :text "**hi**"}]
                                :tool-calls [{:name "bash" :arguments {:command "ls </tmp>"}}]}
                               {:role :tool :tool-name "bash" :content [{:type :tool_result :content "a<b"}]}])]
    (is (not (str/includes? html "<img")))
    (is (str/includes? html "&lt;img src=x"))
    (is (str/includes? html "**hi**") "no markdown rendering")
    (is (str/includes? html "command: ls &lt;/tmp&gt;"))
    (is (str/starts-with? html "<div id=\"transcript\">"))))

(deftest content-text
  (is (= "a\n[image]\nb" (view/content-text [{:type :text :text "a"} {:type :image :data "…"} "b"])))
  (is (= "x" (view/content-text [{:type :tool_result :content [{:type :text :text "x"}]}]))))
