;; Copyright (c) 2026 Jarkko Saltiola; SPDX-License-Identifier: MIT
(ns session-migrate.claude-test
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [session-migrate.claude :as sut]
            [session-migrate.io :as mio]
            [session-migrate.test-support :refer [with-temp source! message native-fixture]]))

(deftest native-produced-claude
  (let [{:keys [entries title report]} (sut/read-claude native-fixture)]
    (is (= "repair-event-window-boundary" title))
    (is (= "9103243215e8cfb960495d2e0097c2c6d5787fe6dc93e2166fad7c7ebe827c3c" (:source-sha256 report)))
    (is (= {:text-blocks 5 :images 1 :messages 11 :tool-calls 3 :tool-results 3} (:preserved report)))
    (is (= 3 (get-in report [:omitted :private-thinking])))
    (is (= 1 (get-in report [:omitted :document])))
    (is (= 3 (count (filter #(= :tool (:role %)) entries))))
    (is (every? #(not (contains? % :thinking)) entries))
    (is (not (str/includes? (pr-str entries) ":document")))
    (is (not (str/includes? (pr-str report) title)))))

(deftest graph-order-and-inactive-fork
  (with-temp [dir]
    (let [path (source! dir [(message "u" nil "user" "hello")
                             (message "r" "a" "user" [{:type "tool_result" :tool_use_id "call" :content "ok"}])
                             (message "a" "u" "assistant" [{:type "tool_use" :id "call" :name "Read"
                                                            :input {:n 9007199254740993 :nested {:precise 10}}}])
                             (message "fork" "u" "assistant" "EXCLUDED")
                             {:type "last-prompt" :leafUuid "r"}])
          {:keys [entries report]} (sut/read-claude path)]
      (is (= [:user :assistant :tool] (mapv :role entries)))
      (is (= 9007199254740993 (get-in entries [1 :tool-calls 0 :arguments "n"])))
      (is (= 1 (get-in report [:omitted :inactive-or-metadata-record])))
      (is (not (str/includes? (pr-str entries) "EXCLUDED"))))))

(deftest invalid-graphs-and-tools
  (with-temp [dir]
    (doseq [[label rows]
            {:duplicate [(message "a" nil "user" "x") (message "a" nil "assistant" "y")]
             :cycle [(message "a" "b" "user" "x") (message "b" "a" "assistant" "y")]
             :missing [(message "a" "gone" "user" "PRIVATE")]
             :leaf [(message "a" nil "user" "x") {:type "last-prompt" :leafUuid "gone"}]
             :sidechain [(assoc (message "a" nil "user" "x") :isSidechain true)]
             :sidechain-leaf [(message "a" nil "user" "x")
                              (assoc (message "s" "a" "assistant" "x") :isSidechain true)
                              {:type "last-prompt" :leafUuid "s"}]
             :mixed [(message "a" nil "user" "x") (assoc (message "b" "a" "assistant" "x") :sessionId "other")]
             :incomplete [(message "a" nil "user" "x") (message nil nil "assistant" "x")]
             :orphan [(message "a" nil "user" [{:type "tool_result" :tool_use_id "gone" :content "x"}])]
             :unresolved [(message "a" nil "assistant" [{:type "tool_use" :id "c" :name "Bash" :input {}}])]
             :duplicate-call [(message "a" nil "assistant" [{:type "tool_use" :id "c" :name "Read" :input {}}
                                                            {:type "tool_use" :id "c" :name "Read" :input {}}])]
             :duplicate-result [(message "a" nil "assistant" [{:type "tool_use" :id "c" :name "Read" :input {}}])
                                (message "b" "a" "user" [{:type "tool_result" :tool_use_id "c" :content "x"}
                                                         {:type "tool_result" :tool_use_id "c" :content "y"}])]}]
      (testing (name label)
        (try (sut/read-claude (source! dir rows)) (is false "invalid source accepted")
             (catch Exception e
               (is (= :session-migrate-error (:type (ex-data e))))
               (is (not (str/includes? (ex-message e) "PRIVATE")))))))))

(deftest compaction-preserved-loop-and-legacy
  (with-temp [dir]
    (let [summary (assoc (message "s" "b" "user" "SUMMARY") :isCompactSummary true)
          boundary {:type "system" :subtype "compact_boundary" :uuid "b" :logicalParentUuid "tail"
                    :compactMetadata {:preservedSegment {:anchorUuid "s" :headUuid "head" :tailUuid "tail"}
                                      :preservedMessages {:allUuids ["head" "tail"]}}}
          tail [(message "head" "s" "user" "head") (message "tail" "head" "assistant" "tail")]
          {:keys [entries report]} (sut/read-claude (source! dir (into [boundary summary] tail)))]
      (is (= [:compaction :user :assistant] (mapv :role entries)))
      (is (= 1 (get-in report [:preserved :compactions])))
      (is (thrown-with-msg? Exception #"cycle"
                            (sut/read-claude (source! dir (into [(dissoc boundary :compactMetadata) summary] tail))))))
    (is (= 2 (count (:entries (sut/read-claude (source! dir [(message nil nil "user" "x")
                                                             (message nil nil "assistant" "y")]))))))))

(deftest portable-results-and-omissions
  (let [preserved (atom {}) omitted (atom {})
        img {"type" "image" "source" {"type" "base64" "media_type" "image/png" "data" "aGVsbG8="}}
        result (sut/portable-result [{"type" "text" "text" ""} img {"type" "text" "text" "last"}
                                     {"type" "tool_reference" "tool_name" "not a capability"}] preserved omitted)]
    (is (= "\nlast" (:text result)))
    (is (= ["aGVsbG8="] (mapv :data (:images result))))
    (is (= 1 (:tool-result-layout-normalized @omitted)))
    (is (= 1 (:tool-result-block @omitted))))
  (with-temp [dir]
    (let [rows [(message "a" nil "user" [{:type "text" :text "kept"}
                                         {:type "image" :source {:type "url" :url "https://example.invalid"}}
                                         {:type "thinking" :thinking "SECRET" :signature "SIGNED"}
                                         {:type "redacted_thinking" :data "SECRET"}
                                         {:type "document"} {:type "unknown"}])
                {:type "ai-title" :aiTitle "old"} {:type "ai-title" :aiTitle "new"}]
          s (sut/read-claude (source! dir rows))]
      (is (= "new" (:title s)))
      (is (= {:unsupported-image 1 :private-thinking 2 :document 1 :unknown-content-block 1}
             (get-in s [:report :omitted])))
      (is (not (str/includes? (pr-str s) "SECRET")))
      (is (= "custom" (:title (sut/read-claude
                               (source! dir (conj rows {:type "custom-title" :customTitle "custom"}
                                                  {:type "ai-title" :aiTitle "ignored"})))))))))

(deftest floating-point-normalization-is-counted-and-finite
  (with-temp [dir]
    (let [path (source! dir [(message "a" nil "assistant"
                                      [{:type "tool_use" :id "f" :name "Read" :input {:decimal 1.25}}])
                             (message "b" "a" "user"
                                      [{:type "tool_result" :tool_use_id "f" :content "ok"}])])]
      (is (= 1 (get-in (sut/read-claude path) [:report :omitted :tool-argument-float-normalized])))
      (spit path (str/replace (slurp path) "1.25" "1e400"))
      (is (thrown-with-msg? Exception #"finite JSON range" (sut/read-claude path))))))

(deftest malformed-utf8-json-bounds-and-cancellation
  (with-temp [dir]
    (doseq [data ["null\n" "[]\n" "{" "{} {}\n" "{}garbage" "{\"secret\":NaN}\n"]]
      (let [path (str (fs/path dir "bad.jsonl"))]
        (spit path data :encoding "UTF-8")
        (is (thrown? Exception (sut/read-claude path)))))
    (is (thrown? Exception
                 (mio/parse-records (str "{\"x\":" (apply str (repeat 256 "[")) "0"
                                         (apply str (repeat 256 "]")) "}") (constantly false))))
    (is (thrown? Exception (sut/read-claude dir)))
    (is (thrown-with-msg? Exception #"cancelled" (sut/read-claude native-fixture (constantly true))))
    (with-redefs [mio/max-bytes 1] (is (thrown? Exception (sut/read-claude native-fixture))))
    (with-redefs [mio/max-bytes 1 fs/size (constantly 1)]
      (is (thrown-with-msg? Exception #"Source exceeds" (sut/read-claude native-fixture))))
    (with-redefs [mio/max-line-bytes 1] (is (thrown? Exception (sut/read-claude native-fixture))))
    (doseq [bytes [[255] [192 128] [226 130] [237 160 128] [244 144 128 128]]]
      (let [path (str (fs/path dir "utf8.jsonl"))]
        (with-open [out (io/output-stream path)] (.write out (byte-array (map unchecked-byte bytes))))
        (is (thrown-with-msg? Exception #"UTF-8" (sut/read-claude path))))
      (is (false? (mio/valid-utf8? (byte-array (map unchecked-byte bytes))))))
    (let [calls (atom 0)]
      (is (thrown-with-msg? Exception #"cancelled"
                            (mio/parse-records (str "{\"long\":\"" (apply str (repeat 8192 "x")) "\"}")
                                               #(>= (swap! calls inc) 3)))))
    (is (mio/valid-utf8? (.getBytes "Résumé 決定 🐈" "UTF-8")))
    (is (= [{"text" "valid } [ \" \\ \u2028"}]
           (mio/parse-records "{\"text\":\"valid } [ \\\" \\\\ \u2028\"}\r\n" (constantly false))))))
