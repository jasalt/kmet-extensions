(ns webdriver.snapshot-test
  "Offline tests over a real Chromium AX tree captured from
  test/fixtures/page.html (scripts/capture_fixture.bb)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [webdriver.snapshot :as snap]))

(def fixture
  (edn/read-string (slurp (io/file "test/fixtures/ax.edn"))))

(def doc (:loader-id fixture))

(defn- run
  ([opts] (run nil (:initial fixture) opts))
  ([state nodes opts] (snap/snapshot state doc nodes opts)))

(defn- line-for [text pattern]
  (some #(when (str/includes? % pattern) (str/trim %)) (str/split-lines text)))

(defn- ref-of [text pattern]
  (some->> (line-for text pattern) (re-find #"ref=(e\d+)") second))

(deftest full-outline
  (let [{:keys [text refs]} (run {})]
    (testing "landmarks, names and states render as agent-browser lines"
      (is (= "- navigation \"Primary\" [ref=e1]" (first (str/split-lines text))))
      (is (= "- heading \"Account settings\" [level=1, ref=e4]" (line-for text "heading")))
      (is (= "- textbox \"Name\" [required, ref=e6]: Ada" (line-for text "textbox")))
      (is (= "- checkbox \"Subscribe\" [checked=true, ref=e7]" (line-for text "checkbox")))
      (is (= "- combobox \"Plan\" [expanded=false, ref=e8]: Pro" (line-for text "combobox")))
      (is (= "- option \"Pro\" [selected, ref=e10]" (line-for text "option \"Pro\"")))
      (is (= "- button \"Delete\" [disabled, ref=e12]" (line-for text "Delete"))))
    (testing "inline text runs are merged; NBSP and zero-width chars are cleaned"
      (is (line-for text "StaticText \"Signed in as ada since 2024.\"")))
    (testing "noise is removed"
      (is (not (str/includes? text "InlineTextBox")))
      (is (not (str/includes? text "RootWebArea")))
      (is (not (str/includes? text "MenuListPopup")))
      (is (not (str/includes? text "generic")))
      (is (not (str/includes? text "StaticText \"Ada\"")) "text repeating a value")
      (is (not (str/includes? text "Hidden")) "hidden DOM is absent from the AX tree"))
    (testing "table cells and named regions get refs; unnamed main does not"
      (is (ref-of text "cell \"plan\""))
      (is (ref-of text "region \"Items\""))
      (is (= "- main" (line-for text "- main"))))
    (is (= 20 refs))))

(deftest refs-are-indented-by-depth
  (let [lines (str/split-lines (:text (run {})))]
    (is (some #(= "    - button \"Save\" [ref=e11]" %) lines))
    (is (some #(= "        - button \"Save\" [ref=e15]" %) lines))))

(deftest interactive-mode
  (let [{:keys [text]} (run {:interactive true})]
    (is (every? #(str/includes? % "ref=") (str/split-lines text)))
    (is (= "  - button \"Save\" [ref=e15]" (some #(when (str/includes? % "e15") %) (str/split-lines text)))
        "ref'd nodes nest under their nearest ref'd ancestor")
    (is (= (:refs (run {})) (:refs (run {:interactive true}))))))

(deftest compact-mode-drops-unnamed-structure
  (let [{:keys [text]} (run {:compact true})]
    (is (not (re-find #"(?m)^\s*- (list|listitem|row|table|main)( \[.*\])?$" text)))
    (is (= "  - cell \"pro\" [ref=e20]" (some #(when (str/includes? % "e20") %) (str/split-lines text)))
        "children lift to the nearest kept ancestor")
    (is (line-for text "StaticText \"First\"") "named text stays")
    (is (< (count (str/split-lines text)) (count (str/split-lines (:text (run {}))))))))

(deftest depth-limit
  (let [{:keys [text]} (run {:depth 1})]
    (is (line-for text "form \"Profile\""))
    (is (not (line-for text "textbox")))
    (is (every? #(<= (count (re-find #"^ *" %)) 2) (str/split-lines text)))))

(deftest urls-are-opt-in
  (is (not (str/includes? (:text (run {})) "url=")))
  (is (= "- link \"Home\" [ref=e2, url=file:///home]" (line-for (:text (run {:urls true})) "Home"))))

(deftest selector-scope
  (let [{:keys [text]} (run {:within (:form-backend-ids fixture)})]
    (is (= "- form \"Profile\" [ref=e5]" (first (str/split-lines text))))
    (is (not (str/includes? text "navigation")))
    (testing "refs are page-wide, so scoping does not renumber"
      (is (= "e11" (ref-of text "button \"Save\"")))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No accessibility node"
                        (run {:within #{-1}}))))

(deftest duplicate-names-record-nth
  (let [{:keys [state]} (run {})
        saves (->> (:refs state) vals (filter #(= ["button" "Save"] ((juxt :role :name) %))))]
    (is (= #{0 1 2} (set (map :nth saves))))
    (is (nil? (get-in state [:refs "e12" :nth])) "unique names carry no nth")))

(deftest refs-are-stable-within-a-document
  (let [first-run (run {})
        second-run (run (:state first-run) (:mutated fixture) {})
        text (:text second-run)]
    (testing "existing elements keep their refs after DOM changes"
      (doseq [[pattern ref] [["link \"Home\"" "e2"] ["textbox" "e6"] ["Delete" "e12"] ["cell \"pro\"" "e20"]]]
        (is (= ref (ref-of text pattern)) pattern)))
    (testing "new elements get the next free number"
      (is (= "e21" (ref-of text "button \"Undo\"")))
      (is (= 22 (get-in second-run [:state :next]))))
    (testing "the same snapshot twice is a fixed point"
      (is (= (:state second-run) (:state (run (:state second-run) (:mutated fixture) {})))))))

(deftest navigation-resets-refs
  (let [state (:state (run {}))
        after (snap/snapshot state "other-loader" (:mutated fixture) {})]
    (is (= "other-loader" (get-in after [:state :doc])))
    (is (= "e1" (ref-of (:text after) "navigation")))
    (is (= "e5" (ref-of (:text after) "form \"Profile\"")))
    (is (= "e6" (ref-of (:text after) "button \"Undo\"")) "numbering restarts in document order")))

(deftest parse-ref-forms
  (is (= "e7" (snap/parse-ref "e7")))
  (is (= "e7" (snap/parse-ref " @e7 ")))
  (is (= "e7" (snap/parse-ref "ref=e7")))
  (is (nil? (snap/parse-ref "#e7")))
  (is (nil? (snap/parse-ref "button")))
  (is (nil? (snap/parse-ref nil))))

(deftest stale-ref-recovery
  (let [nodes (:mutated fixture)
        state (:state (run {}))]
    (testing "a unique role+name is recovered to its live backend id"
      (is (= 34 (snap/recover (get-in state [:refs "e12"]) nodes))))
    (testing "an entry that was ambiguous at snapshot time is refused"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"several elements matched"
                            (snap/recover (get-in state [:refs "e11"]) nodes))))
    (testing "a name that now matches several elements is refused"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"several elements now match"
                            (snap/recover {:role "button" :name "Save"} nodes))))
    (testing "a vanished element asks for a new snapshot"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no longer on the page"
                            (snap/recover {:role "button" :name "Gone"} nodes))))))

(deftest empty-input
  (is (= "(empty page)" (:text (snap/snapshot nil "d" [] {}))))
  (is (= "(no interactive elements)" (:text (snap/snapshot nil "d" [] {:interactive true})))))

(deftest pure-tree-helpers
  (testing "noise wrappers splice their children up a level"
    (let [tree {:role "RootWebArea" :name ""
                :children [{:role "generic" :name "" :children
                            [{:role "button" :name "Go" :backend-id 9 :children []}]}]}
          [state tagged] (snap/assign-refs nil "d" tree)]
      (is (= "- button \"Go\" [ref=e1]" (snap/render (snap/shape tagged {}))))
      (is (= {"e1" {:backend-id 9 :role "button" :name "Go"}} (:refs state)))))
  (testing "render-line escapes names as EDN strings"
    (is (= "- link \"say \\\"hi\\\"\"" (snap/render-line {:role "link" :name "say \"hi\""} 0 false)))))
