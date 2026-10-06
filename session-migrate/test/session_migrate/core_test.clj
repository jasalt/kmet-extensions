;; Copyright (c) 2026 Jarkko Saltiola; SPDX-License-Identifier: MIT
(ns session-migrate.core-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.extension :as ext]
            [session-migrate.claude :as claude]
            [session-migrate.core :as sut]
            [session-migrate.test-support :refer [with-temp native-fixture]]))

(defn harness [dir]
  (let [{:keys [api state]} (ext/create-nullable-api)
        api (-> api
                (assoc :agent-dir dir)
                (assoc-in [:ui :chat-info]
                          (fn [label content]
                            (swap! state update :ui-calls conj [:chat-info label content]))))
        switches (atom [])]
    (sut/init api)
    {:state state :handler (get-in @state [:commands "session-migrate" :handler])
     :switches switches
     :ctx {:cwd dir :mode :interactive :is-idle (constantly true)
           :has-pending-messages (constantly false) :signal (constantly nil)
           :switch-session (fn [path] (swap! switches conj path) {:cancelled false})}}))

(deftest inspect-save-import-contract
  (with-temp [dir]
    (let [{:keys [state handler ctx switches]} (harness dir)]
      (is (= ["session-migrate"] (keys (:commands @state))))
      (is (empty? (:ui-calls @state)))
      (is (empty? (fs/list-dir dir)))
      (let [report (handler ctx (str "inspect claude " native-fixture))
            [kind label content] (last (:ui-calls @state))]
        (is (= :chat-info kind))
        (is (= "Session migration inspection" label))
        (is (str/includes? content "\n"))
        (is (str/includes? content (str "Source SHA-256: " (:source-sha256 report))))
        (is (str/includes? content "  tool-calls: 3"))
        (is (str/includes? content "  private-thinking: 3"))
        (is (empty? (:model-calls @state)))
        (is (empty? (:emitted @state))))
      (is (empty? (fs/list-dir dir)))
      (handler (assoc ctx :mode :print) (str "inspect claude " native-fixture))
      (is (= :notify (first (last (:ui-calls @state)))))
      (let [save (handler ctx (str "save claude \"" native-fixture "\""))
            imported (handler ctx (str "import claude " native-fixture))]
        (is (fs/regular-file? (:path save)))
        (is (fs/regular-file? (:manifest-path imported)))
        (is (= [(:path imported)] @switches))
        (is (not= (:path save) (:path imported)))))))

(deftest command-refusal-and-cancelled-switch
  (with-temp [dir]
    (let [{:keys [handler ctx switches]} (harness dir)]
      (doseq [args ["" "import codex x" "inspect claude" "import claude ''"]]
        (is (:error (handler ctx args))))
      (is (:error (handler (assoc ctx :is-idle (constantly false)) (str "import claude " native-fixture))))
      (is (:error (handler (assoc ctx :has-pending-messages (constantly true)) (str "import claude " native-fixture))))
      (is (:error (handler (assoc ctx :mode :print) (str "import claude " native-fixture))))
      (is (re-find #"cancelled" (:error (handler (assoc ctx :signal (constantly (atom true)))
                                                 (str "inspect claude " native-fixture)))))
      (is (empty? (fs/list-dir dir)))
      (is (empty? @switches))
      (let [r (handler (assoc ctx :switch-session (fn [_] {:cancelled true}))
                       (str "import claude " native-fixture))]
        (is (fs/regular-file? (:path r)))))))

(deftest shutdown-and-reentrancy
  (with-temp [dir]
    (let [{:keys [handler ctx]} (harness dir)
          read-original claude/read-claude]
      (with-redefs [claude/read-claude (fn [path cancelled?]
                                         (is (thrown-with-msg? Exception #"busy"
                                                               (handler ctx (str "inspect claude " native-fixture))))
                                         (read-original path cancelled?))]
        (handler ctx (str "inspect claude " native-fixture)))
      (sut/shutdown nil)
      (is (thrown-with-msg? Exception #"unloaded" (handler ctx (str "inspect claude " native-fixture))))
      (is (empty? (fs/list-dir dir))))))

(deftest unload-generation-cannot-be-uncancelled
  (with-temp [dir]
    (let [{:keys [handler ctx]} (harness dir)
          read-original claude/read-claude]
      (with-redefs [claude/read-claude (fn [path cancelled?]
                                         (sut/shutdown nil)
                                         (harness dir)
                                         (is (cancelled?))
                                         (read-original path cancelled?))]
        (is (re-find #"cancelled" (:error (handler ctx (str "inspect claude " native-fixture)))))))))

(deftest literal-path-resolution
  (with-temp [dir]
    (is (= (str (fs/path dir "a path.jsonl")) (sut/resolve-source dir "a path.jsonl")))
    (is (thrown-with-msg? Exception #"resolved to" (sut/resolve-source dir "12345678-1234-1234-1234-123456789abc")))
    (with-redefs [fs/glob (fn [_ _] ["/one/session.jsonl"])]
      (is (= "/one/session.jsonl" (sut/resolve-source dir "12345678-1234-1234-1234-123456789abc"))))
    (with-redefs [fs/glob (fn [_ _] ["/one/session.jsonl" "/two/session.jsonl"])]
      (is (thrown-with-msg? Exception #"resolved to 2" (sut/resolve-source dir "12345678-1234-1234-1234-123456789abc"))))))
