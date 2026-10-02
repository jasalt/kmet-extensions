;; Run from a kmet checkout, offline — no network, no real modal, no sleeps:
;; TMPDIR="$PWD/target" bb ../kmet-extensions/pins/scripts/smoke.bb [path/to/packed.jar]
(require '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[kmet.app.commands :as commands]
         '[kmet.app.extensions :as extensions]
         '[kmet.app.session :as session]
         '[kmet.app.tools.registry :as tools]
         '[kmet.tui.utils :as u])

(fs/create-dirs "target")
(let [dir (str (fs/absolutize (fs/create-temp-dir {:prefix "pins-smoke-" :dir "target"})))
      artifact (str (fs/normalize (fs/absolutize (or (first *command-line-args*)
                                                     (fs/path (fs/parent *file*) "../src")))))
      cwd (str (fs/path dir "project"))
      calls (atom [])
      dialogs (atom [])
      live-context (atom {:mode :interactive :has-ui true :cwd cwd})
      check (fn [ok message] (when-not ok (throw (ex-info message {:type :smoke-failure}))))
      command! (fn [args] ((:handler (commands/find-command "pin"))
                           (extensions/build-extension-context) args))
      notice #(last @calls)
      dialog-count #(count @dialogs)
      before-tools (set (keys (tools/get-all-tools)))
      expected-first-pins (atom nil)
      sessions (atom nil)
      sess #(or @sessions (do (reset! sessions (session/create-session (str (fs/path dir "sessions")) {:cwd cwd}))
                              @sessions))
      entries #(vec (session/get-custom-entries (sess) "pin-state"))
      snapshot #(or (:data (last (entries))) {:pins [] :next-id 1})
      pins #(mapv :text (:pins (snapshot)))
      pin-ids #(mapv :id (:pins (snapshot)))
      render! (fn [component width] (mapv u/strip-ansi-codes ((:render component) width)))]
  (fs/create-dirs cwd)
  (extensions/set-ui-registry!
   {:build-context (fn [] @live-context)
    :notify (fn [& args] (swap! calls conj (vec (cons :notify args))))
    :chat-info (fn [& args] (swap! calls conj (vec (cons :chat-info args))))
    :custom (fn [factory opts]
              (let [p (promise)
                    component (atom nil)
                    close (fn [value]
                            (when-not (realized? p)
                              (when-let [c @component] ((:dispose c)))
                              (deliver p value)))]
                (reset! component (factory {:terminal (atom nil)} nil nil close))
                (swap! dialogs conj {:component @component :promise p :opts opts})
                p))})
  (extensions/set-session! (sess))
  (try
    (let [loaded (extensions/load-extension! artifact)]
      (check (nil? (:error loaded)) (str "Load failed: " (:error loaded)))
      (check (= :sci (:loader-kind loaded)) "Expected real isolated SCI loader"))
    (check (some? (commands/find-command "pin")) "Command was not registered")
    (check (= before-tools (set (keys (tools/get-all-tools)))) "Pins registered a tool")
    (check (empty? @calls) "Loading caused unexpected UI output")
    (check (zero? (dialog-count)) "Loading opened a modal")
    (check (= #{"pick" "show" "list" "rm" "clear" "help"}
              (set (mapv :value ((:get-argument-completions (commands/find-command "pin")) ""))))
           "Wrong subcommand completions")
    (check (= ["show"] (mapv :value ((:get-argument-completions (commands/find-command "pin")) "sh")))
           "Prefix completions broken")
    ;; Interactive: pin (auto + free label) and verify branch storage.
    (let [kept (session/append-entry (sess) {:role :assistant
                                             :content [{:type :thinking :thinking "secret"}
                                                       {:type :text :text "  # Branch A\nOne "}
                                                       {:type :tool-call :name "bash"}]})
          _ (session/branch! (sess) (:id kept))]
      (command! "")
      (check (= ["# Branch A\nOne"] (pins)) "Wrong assistant text pinned")
      (check (= "Branch A" (:label (first (:pins (snapshot))))) "Wrong auto label")
      (check (= :info (last (notice))) "Pin success was not reported as info")
      (command! "my own label")
      (check (= "my own label" (:label (second (:pins (snapshot))))) "Free label lost")
      (check (= [1 2] (pin-ids)) "IDs not monotonic")
      (check (= :custom (:role (last (session/get-branch (sess))))) "Pin state is not a session entry")
      (check (= "pin-state" (:custom-type (last (session/get-branch (sess))))) "Wrong state type"))
    ;; Viewer and picker modals: geometry, content, close, pick flow.
    (doseq [args ["show" "list" "show 2"]]
      (let [before (dialog-count)]
        (command! args)
        (check (= (inc before) (dialog-count)) "Modal was not opened")
        (let [{:keys [component opts]} (last @dialogs)]
          (check (:overlay opts) "Modal was not an overlay")
          (check (= "100%" (get-in opts [:overlay-options :width])) "Viewer is not full width")
          (check (= "80%" (get-in opts [:overlay-options :max-height])) "Viewer is not 80% height")
          (check (= :top-left (get-in opts [:overlay-options :anchor])) "Viewer is not top anchored")
          (check (= :none (get-in opts [:overlay-options :border])) "Viewer has a border")
          (let [lines (render! component 100)]
            (check (some #(str/includes? % "Branch A") lines) "Pinned text missing from viewer"))
          ((:dispose component))
          (check (= [] ((:render component) 100)) "Disposed viewer still renders"))))
    (session/append-entry (sess) {:role :assistant :content "Newest answer"})
    (let [before (dialog-count)]
      (command! "pick")
      (check (= (inc before) (dialog-count)) "Picker was not opened"))
    (let [{:keys [component]} (last @dialogs)
          lines (render! component 100)]
      (check (some #(str/includes? % "Newest answer") lines) "Picker missing newest answer"))
    ((:handle-input (:component (last @dialogs))) "\u001b[B")
    ((:handle-input (:component (last @dialogs))) "\r")
    (check (realized? (:promise (last @dialogs))) "Picker selection did not close the modal")
    (check (= ["# Branch A\nOne" "# Branch A\nOne" "# Branch A\nOne"] (pins))
           "Picked message was not pinned")
    (command! "pick")
    ((:handle-input (:component (last @dialogs))) "\u001b")
    (check (= ["# Branch A\nOne" "# Branch A\nOne" "# Branch A\nOne"] (pins))
           "Cancelled picker pinned a message")
    ;; rm / clear / parse failures.
    (command! "rm 1")
    (check (= ["# Branch A\nOne" "# Branch A\nOne"] (pins)) "rm did not remove pin #1")
    (let [before (count (entries))]
      (doseq [args ["rm" "rm 9" "show 9" "show nonsense"]]
        (command! args)
        (check (= :error (last (notice))) "Invalid argument was not an error"))
      (check (= before (count (entries))) "Invalid argument mutated the session"))
    (command! "clear")
    (check (= [] (pins)) "clear did not empty the pins")
    (check (= :info (last (notice))) "Clear success was not reported as info")
    (command! "after-clear")
    (check (= [1] (pin-ids)) "IDs did not restart at 1 after clear")
    ;; Branch-local persistence: each branch restores its own snapshot.
    (let [first-snapshot (snapshot)
          _ (reset! expected-first-pins (mapv :text (:pins first-snapshot)))
          before-all (count (session/get-all-custom-entries (sess) "pin-state"))
          root-id (:id (first (session/get-branch (sess))))
          leaf-a (:id (last (session/get-branch (sess))))
          _ (session/branch! (sess) root-id)
          second (session/append-entry (sess) {:role :assistant :content "Second branch answer"})]
      (session/branch! (sess) (:id second))
      (command! "on second branch")
      (check (= ["Second branch answer"] (pins))
             "Branch switch did not use the new branch's own state")
      (session/branch! (sess) leaf-a)
      (check (= @expected-first-pins (pins))
             "First branch state was corrupted by the second branch")
      (command! "back on branch A")
      (check (= (conj @expected-first-pins "Newest answer") (pins))
             "Restored first-branch state did not continue from its own snapshot")
      (check (= (+ before-all 2) (count (session/get-all-custom-entries (sess) "pin-state")))
             "Branch states were not both persisted"))
    ;; /reload survival: unload/reload keeps the command and the branch state.
    (extensions/unload-all-extensions!)
    (check (nil? (commands/find-command "pin")) "Unload leaked the command")
    (let [calls-before (count @calls)]
      (check (= calls-before (count @calls)) "Unload emitted UI output"))
    (let [loaded (extensions/load-extension! artifact)]
      (check (nil? (:error loaded)) (str "Reload failed: " (:error loaded))))
    (command! "after reload")
    (check (some? (commands/find-command "pin")) "Reload lost the command")
    (check (= (conj @expected-first-pins "Newest answer" "Newest answer") (pins))
           "Reload lost the branch-local pins")
    ;; Headless/print mode: modal commands warn, mutations still work.
    (let [dialogs-before (dialog-count)]
      (swap! live-context assoc :mode :print)
      (command! "show")
      (check (= :warning (last (notice))) "Headless show did not warn")
      (command! "pick")
      (check (= :warning (last (notice))) "Headless pick did not warn")
      (command! "clear")
      (check (= [] (pins)) "Headless clear failed")
      (check (= dialogs-before (dialog-count)) "Headless mode opened a modal"))
    (extensions/unload-all-extensions!)
    (check (nil? (commands/find-command "pin")) "Unload leaked the command")
    (finally
      (extensions/unload-all-extensions!)
      (extensions/set-session! nil)
      (extensions/clear-ui-registry!)
      (fs/delete-tree dir)))
  (println "SCI load, pin/pick/show/rm/clear, viewer geometry, branch persistence, reload, and unload smoke passed (offline)."))
