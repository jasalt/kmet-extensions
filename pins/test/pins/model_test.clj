(ns pins.model-test
  (:require [clojure.test :refer [deftest is]]
            [pins.model :as model]))

(deftest extracts-only-trimmed-text
  (is (= "hello" (model/extract-text "  hello  ")))
  (is (= "one\ntwo"
         (model/extract-text [{:type :text :text " one"}
                              {:type :thinking :text "secret"}
                              {:type :image :data "image"}
                              {:type :tool_call :name "bash"}
                              nil {:type :text :text 42}
                              {:type :text :text "two "}])))
  (doseq [content [nil 3 {} [] [{:type :thinking :text "hidden"}]]]
    (is (= "" (model/extract-text content)))))

(deftest auto-label-and-preview
  (is (= "Plan" (model/auto-label "\n  ## **Plan\nNext")))
  (is (= "pin" (model/auto-label "#* | ` > -")))
  (is (= (str (apply str (repeat 42 "a")) "…")
         (model/auto-label (apply str (repeat 43 "a")))))
  (is (= "one two three" (model/preview "one\n two\tthree" 80)))
  (is (= "abcdef…" (model/preview "abcdefghi" 6)))
  (is (= "abc" (model/preview "abc" 3))))

(deftest latest-ten-texts-skip-textless-assistants-but-count-their-distance
  (let [branch (concat (for [i (range 12)] {:role :assistant :content (str i)})
                       [{:role :user :content "not assistant"}
                        {:role :assistant :content [{:type :thinking :text "hidden"}]}])
        candidates (model/recent-assistants branch)]
    (is (= 10 (count candidates)))
    (is (= {:text "11" :ago 2} (first candidates)))
    (is (= {:text "2" :ago 11} (last candidates))))
  (is (empty? (model/recent-assistants []))))

(deftest restores-last-snapshot-and-keeps-ids-monotonic
  (let [one (model/add-pin model/empty-state "text" nil 123)
        two (model/add-pin one "text" " my label " 124)
        removed (model/remove-pin two 1)]
    (is (= {:id 1 :label "text" :text "text" :pinned-at 123} (first (:pins one))))
    (is (= "my label" (get-in two [:pins 1 :label])))
    (is (= two (model/restore-state [{:data one} {:data two}])))
    (is (= {:pins [(second (:pins two))] :next-id 3} removed))
    (is (= 3 (:next-id (model/restore-state [{:data (dissoc removed :next-id)}]))))
    (is (= 3 (:next-id (model/restore-state [{:data (assoc removed :next-id 1)}]))))
    (is (= 3 (:id (last (:pins (model/add-pin removed "new" nil 125)))))))
  (is (= model/empty-state (model/restore-state [])))
  (is (= model/empty-state (model/restore-state [{:data model/empty-state}]))))

(deftest corrupt-state-is-not-silently-discarded
  (let [pin {:id 1 :label "x" :text "x" :pinned-at 1}]
    (doseq [data [nil "broken" {:pins "broken"} {:pins [pin pin]}
                  {:pins [(dissoc pin :text)]} {:pins [(assoc pin :id 0)]}]]
      (try (model/restore-state [{:data data}])
           (is false "Expected structured invalid-state error")
           (catch Exception e (is (= :pins/invalid-state (:type (ex-data e)))))))))

(deftest subcommands-and-free-labels
  (doseq [[args expected]
          [[nil {:action :pin :label ""}]
           ["  my   label  " {:action :pin :label "my   label"}]
           ["PiCk" {:action :pick :arg ""}]
           ["pick some answer" {:action :pin :label "pick some answer"}]
           ["help topics" {:action :pin :label "help topics"}]
           ["clear plans" {:action :pin :label "clear plans"}]
           [" show  02 " {:action :show :arg "02"}]
           ["list" {:action :show :arg ""}]
           ["list 3" {:action :show :arg "3"}]
           ["rm" {:action :rm :arg ""}]]]
    (is (= expected (model/parse-command args))))
  (doseq [id ["0" "-1" "1.5" "1e2" "abc" "" (apply str (repeat 30 "9"))]]
    (is (nil? (model/parse-id id))))
  (is (= 1 (model/parse-id "01")))
  (is (= 12 (model/parse-id "12"))))
