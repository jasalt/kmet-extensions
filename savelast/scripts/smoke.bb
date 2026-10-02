;; Run from a kmet checkout:
;; bb ../kmet-extensions/savelast/scripts/smoke.bb [path/to/packed.jar]
(require '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[kmet.app.commands :as commands]
         '[kmet.app.extensions :as extensions]
         '[kmet.app.session :as session]
         '[kmet.config :as config])

(fs/create-dirs "target")
(let [dir (str (fs/absolutize (fs/create-temp-dir {:prefix "savelast-smoke-" :dir "target"})))
      artifact (str (fs/normalize
                     (fs/absolutize (or (first *command-line-args*)
                                        (fs/path (fs/parent *file*) "../src")))))
      cwd (str (fs/path dir "project"))
      calls (atom [])
      live-context (atom {:mode :interactive :has-ui true :cwd cwd})
      check (fn [ok message] (when-not ok (throw (ex-info message {:type :smoke-failure}))))
      command! (fn [args]
                 (let [before (count @calls)]
                   ((:extension-handler (commands/find-command "savelast"))
                    (extensions/build-extension-context) args)
                   (check (= (inc before) (count @calls)) "Expected exactly one notification")))
      notice #(last @calls)]
  (fs/create-dirs cwd)
  (extensions/set-ui-registry!
   {:build-context (fn [] @live-context)
    :notify (fn [& args] (swap! calls conj (vec (cons :notify args))))})
  (try
    (with-redefs [config/get-agent-dir (constantly dir)]
      (let [loaded (extensions/load-extension! artifact)]
        (check (nil? (:error loaded)) (str "Load failed: " (:error loaded)))
        (check (= :sci (:loader-kind loaded)) "Expected real isolated SCI loader"))
      (check (some? (commands/find-command "savelast")) "Command was not registered")
      (check (empty? @calls) "Loading caused unexpected UI output")
      (let [sess (session/create-session (str (fs/path dir "sessions")) {:cwd cwd})
            prompt (session/append-entry sess {:role :user :content "question"})]
        (extensions/set-session! sess)
        (command! "missing/no-message.md")
        (check (= [:notify "No agent message found to save" :warning] (notice)) "Missing-message warning absent")
        (check (empty? (fs/list-dir cwd)) "Missing message created a file or directory")
        (let [kept (session/append-entry
                    sess {:role :assistant
                          :content [{:type :thinking :thinking "private"}
                                    {:type :text :text " # Kept\n"}
                                    {:type :tool-call :name "bash" :arguments {:command "secret"}}
                                    {:type :text :text "Second — 🐈  "}]})
              expected " # Kept\n\nSecond — 🐈  "
              target (str (fs/path cwd "notes/result.md"))]
          (session/append-entry sess {:role :assistant :content "abandoned branch"})
          (session/branch! sess (:id kept))
          (session/append-entry sess {:role :tool :tool-name "bash" :content "not assistant text"})
          (let [entries-before @(:entries sess)]
            (command! "  notes/result.md  ")
            (check (= expected (slurp target :encoding "UTF-8")) "Wrong message or non-text blocks saved")
            (check (= entries-before @(:entries sess)) "Command changed session history"))
          (check (= [:notify (str "Saved to: " target) :info] (notice)) "Success notification incorrect")
          (command! "")
          (let [files (filter fs/regular-file? (fs/list-dir cwd))
                file (first files)]
            (check (= 1 (count files)) "Expected one default output file")
            (check (some? (re-matches #"\d+\.md" (fs/file-name file))) "Wrong default filename")
            (check (= expected (slurp (str file) :encoding "UTF-8")) "Default output text incorrect"))
          (session/append-entry sess {:role :assistant
                                      :content [{:type :thinking :thinking "private"}
                                                {:type :tool-call :name "bash"}]})
          (command! "textless/not-saved.md")
          (check (= [:notify "Last agent message has no text content to save" :warning] (notice))
                 "Textless latest message fell back to older text")
          (check (not (fs/exists? (fs/path cwd "textless"))) "Textless message created directories")
          (session/branch! sess (:id prompt))
          (command! "missing/still-no-message.md")
          (check (= [:notify "No agent message found to save" :warning] (notice))
                 "Branch with no assistant saved an abandoned response")
          (session/branch! sess (:id kept))
          (spit (str (fs/path cwd "blocked")) "keep")
          (command! "blocked/output.md")
          (check (= :error (last (notice))) "Filesystem failure was not reported")
          (check (str/starts-with? (second (notice)) "Failed to write file: ") "Wrong filesystem error")
          (check (= "keep" (slurp (str (fs/path cwd "blocked")))) "Filesystem failure changed existing file")))
      (let [new-cwd (str (fs/path dir "other-project"))
            sess (session/create-session (str (fs/path dir "other-sessions")) {:cwd new-cwd})
            target (str (fs/path new-cwd "new.md"))]
        (session/append-entry sess {:role :assistant :content "new session — 決定"})
        (extensions/set-session! sess)
        (swap! live-context assoc :cwd new-cwd)
        (command! "new.md")
        (check (= "new session — 決定" (slurp target :encoding "UTF-8")) "Session/cwd switch used stale state")
        (check (not (fs/exists? (fs/path cwd "new.md"))) "Save leaked the launch cwd")
        (session/append-entry sess {:role :assistant :content "short"})
        (command! "new.md")
        (check (= "short" (slurp target)) "Existing file was not truncated on overwrite")
        (extensions/unload-all-extensions!)
        (check (nil? (commands/find-command "savelast")) "Unload leaked the command")
        (let [loaded (extensions/load-extension! artifact)]
          (check (nil? (:error loaded)) (str "Reload failed: " (:error loaded))))
        (command! "after-reload.md")
        (check (= "short" (slurp (str (fs/path new-cwd "after-reload.md")))) "Reload did not restore the command")))
    (finally
      (extensions/unload-all-extensions!)
      (extensions/set-session! nil)
      (extensions/clear-ui-registry!)
      (fs/delete-tree dir)))
  (check (nil? (commands/find-command "savelast")) "Unload leaked the command")
  (check (empty? (extensions/get-loaded-extensions)) "Unload leaked an extension")
  (println "SCI load, writes, branching, warnings, failure, session/cwd switch, reload, and unload smoke passed."))
