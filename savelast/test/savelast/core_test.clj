(ns savelast.core-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kmet.extension :as ext]
            [savelast.core :as savelast]))

(def ^:private fixture (atom nil))

(use-fixtures :each
  (fn [test-fn]
    (fs/create-dirs "target")
    (let [dir (str (fs/absolutize (fs/create-temp-dir {:prefix "savelast-test-" :dir "target"})))
          cwd (str (fs/path dir "project"))
          entries (atom [])
          {:keys [api state]} (ext/create-nullable-api)
          ;; The command must use ctx.session, not a captured launch session.
          api (assoc-in api [:session :get-branch]
                        (constantly [{:role :assistant :content "wrong session"}]))]
      (fs/create-dirs cwd)
      (savelast/init api)
      (reset! fixture {:dir dir :cwd cwd :entries entries :state state})
      (try (test-fn)
           (finally
             (fs/delete-tree dir)
             (reset! fixture nil))))))

(defn- command! [args & [ctx]]
  ((get-in @(:state @fixture) [:commands "savelast" :handler])
   (merge {:mode :interactive
           :cwd (:cwd @fixture)
           :session {:get-branch (fn [] @(:entries @fixture))}}
          ctx)
   args))

(defn- notice [] (last (:ui-calls @(:state @fixture))))
(defn- path [& parts] (str (apply fs/path (:cwd @fixture) parts)))
(defn- entries! [entries] (reset! (:entries @fixture) entries))

(deftest registers-only-the-original-command
  (let [state @(:state @fixture)
        command (get-in state [:commands "savelast"])]
    (is (= #{"savelast"} (set (keys (:commands state)))))
    (is (= "[path]" (:argument-hint command)))
    (is (str/includes? (:description command) "Save the last agent message"))
    (is (empty? (:tools state)))
    (is (empty? (:handlers state)))
    (is (empty? (:ui-calls state)))))

(deftest saves-only-valid-text-blocks-of-the-latest-assistant
  (entries! [{:role :assistant :content "old response"}
             {:role :user :content "question"}
             {:role :assistant
              :content [nil 42 "not a block"
                        {:type :thinking :thinking "private" :text "private"}
                        {:type :tool-call :name "write" :text "tool payload"}
                        {:type :image :text "image payload"}
                        {:type :text :text "  # Résumé 🐈\n"}
                        {:type :text :text nil}
                        {:type :text :text 123}
                        {:type :text :text ""}
                        {:type :text :text "Last paragraph.  "}]}
             {:role :tool :content "tool result"}
             {:role :custom-message :content "extension message"}
             {:role :user :content "next question"}])
  (command! "notes/result.md")
  (is (= "  # Résumé 🐈\n\n\nLast paragraph.  "
         (slurp (path "notes/result.md") :encoding "UTF-8")))
  (is (= [:notify (str "Saved to: " (path "notes/result.md")) :info] (notice)))
  (is (= 6 (count @(:entries @fixture))) "saving does not change the transcript"))

(deftest preserves-string-content-and-does-not-append-a-newline
  (let [text " \nA plain response — 決定\t\r\n  "]
    (entries! [{:role :assistant :content text}])
    (command! "plain.txt")
    (is (= text (slurp (path "plain.txt") :encoding "UTF-8")))))

(deftest defaults-to-an-epoch-millisecond-markdown-filename
  (entries! [{:role :assistant :content [{:type :text :text "default output"}]}])
  (doseq [[i args] (map-indexed vector ["" " \t\n " nil])]
    (let [cwd (path (str "default-" i))
          before (System/currentTimeMillis)]
      (command! args {:cwd cwd})
      (let [after (System/currentTimeMillis)
            files (fs/list-dir cwd)
            file (first files)
            match (re-matches #"(\d+)\.md" (fs/file-name file))]
        (is (= 1 (count files)))
        (is (some? match))
        (is (<= before (parse-long (second match)) after))
        (is (= "default output" (slurp (str file) :encoding "UTF-8")))
        (is (= [:notify (str "Saved to: " file) :info] (notice)))))))

(deftest resolves-and-normalizes-paths-against-the-command-cwd
  (entries! [{:role :assistant :content "path output"}])
  (let [relative "../exports/unused/../résumé with spaces.md"
        target (str (fs/path (:dir @fixture) "exports/résumé with spaces.md"))]
    (command! (str " \t" relative "  "))
    (is (= "path output" (slurp target :encoding "UTF-8")))
    (is (= [:notify (str "Saved to: " target) :info] (notice))))
  (let [absolute (str (fs/path (:dir @fixture) "absolute/nested/out.md"))]
    (command! absolute)
    (is (= "path output" (slurp absolute :encoding "UTF-8")))
    (is (= [:notify (str "Saved to: " absolute) :info] (notice)))))

(deftest overwrites-existing-files-and-reads-fresh-session-content
  (spit (path "existing.md") "longer old file contents\n")
  (entries! [{:role :assistant :content "first response"}])
  (command! "existing.md")
  (is (= "first response" (slurp (path "existing.md"))))
  (entries! [{:role :assistant :content "new"}])
  (command! "existing.md")
  (is (= "new" (slurp (path "existing.md")))))

(deftest warns-without-creating-files-when-no-assistant-exists
  (doseq [entries [[] [{:role :user :content "question"}
                       {:role :tool :content "output"}
                       {:role :custom-message :content "notice"}]]]
    (entries! entries)
    (command! "missing/not-saved.md")
    (is (= [:notify "No agent message found to save" :warning] (notice)))
    (is (not (fs/exists? (path "missing")))))
  (is (empty? (fs/list-dir (:cwd @fixture)))))

(deftest does-not-fall-back-to-older-text-when-the-latest-assistant-is-textless
  (doseq [[i content] (map-indexed vector
                                   [nil {} 42 "" " \t\n" []
                                    [{:type :thinking :thinking "private"}
                                     {:type :tool-call :name "bash"}]
                                    [nil {:type :text :text nil} {:type :text :text 123}]
                                    [{:type :text :text " "} {:type :text :text "\t"}]])]
    (entries! [{:role :assistant :content "earlier text must not be saved"}
               {:role :assistant :content content}
               {:role :tool :content "not assistant text"}])
    (command! (str "empty-" i "/output.md"))
    (is (= [:notify "Last agent message has no text content to save" :warning] (notice)))
    (is (not (fs/exists? (path (str "empty-" i)))))))

(deftest reports-mkdir-and-write-failures
  (entries! [{:role :assistant :content "response"}])
  (testing "a parent path that is an existing file"
    (spit (path "blocked") "keep")
    (command! "blocked/output.md")
    (is (= :notify (first (notice))))
    (is (= :error (last (notice))))
    (is (str/starts-with? (second (notice)) "Failed to write file: "))
    (is (= "keep" (slurp (path "blocked")))))
  (testing "a target that is an existing directory"
    (fs/create-dirs (path "directory"))
    (command! "directory")
    (is (= :error (last (notice))))
    (is (str/starts-with? (second (notice)) "Failed to write file: "))
    (is (fs/directory? (path "directory")))))
