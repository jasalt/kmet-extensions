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

(deftest activity-line
  (is (= "<div id=\"activity\"></div>" (view/activity nil)))
  (let [html (view/activity {:kind :waiting-for-model})]
    (is (str/includes? html "class=\"activity waiting-for-model\""))
    (is (str/includes? html "class=\"spinner\""))
    (is (str/includes? html "Waiting for model")))
  (is (= "Working" (view/activity-text {:kind :working})))
  (is (= "Retrying (2/3) in 4s" (view/activity-text {:kind :retry :attempt 2 :max-attempts 3 :seconds 4})))
  (is (= "Compacting context" (view/activity-text {:kind :compaction :reason :manual})))
  (is (= "Context overflow detected, auto-compacting" (view/activity-text {:kind :compaction :reason :overflow})))
  (is (= "Auto-compacting" (view/activity-text {:kind :compaction :reason :threshold})))
  (is (= "Summarizing branch" (view/activity-text {:kind :branch-summary}))))

(deftest statusline
  (is (= ["999" "1.2k" "12k" "1.2M" "12M"] (map view/format-tokens [999 1234 12345 1234567 12345678])))
  (let [s (view/statusline {:mark "б" :cwd "~/src/<x>" :branch "main" :session-name "web"
                            :usage {:input 1234 :output 56 :cache-read 2000 :cache-write 0 :cost 0.0123}
                            :hit-rate 61.83 :context {:tokens 95000 :context-window 100000}
                            :model "p/m1" :reasoning? true :thinking :high})]
    (is (str/includes? s "<span class=\"mark\">Кб</span> ~/src/&lt;x&gt; (main) • web"))
    (is (str/includes? s "↑1.2k ↓56 R2.0k CH61.8% $0.012 <span class=\"ctx-error\">95.0%/100k</span>  p/m1 • high")))
  (let [s (view/statusline {:mark "б" :cwd "/w" :usage {} :context {:context-window 200000}
                            :model "p/m1" :reasoning? true :thinking :off})]
    (is (str/includes? s "<span>?/200k</span>  p/m1 • thinking off")))
  (is (str/includes? (view/statusline {:cwd "/w" :usage {} :model "p/m1" :thinking :high})
                     "<span>? tokens</span>  p/m1</div>")
      "non-reasoning models show no thinking suffix"))
