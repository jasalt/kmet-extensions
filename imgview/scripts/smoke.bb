;; Run from a kmet checkout with no network or real browser launches:
;; TMPDIR="$PWD/target" bb ../kmet-extensions/imgview/scripts/smoke.bb [path/to/packed.jar]
(require '[babashka.fs :as fs]
         '[babashka.process :as proc]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[kmet.app.commands :as commands]
         '[kmet.app.extensions :as extensions]
         '[kmet.app.session :as session]
         '[kmet.app.tools.core :as tools]
         '[kmet.ai.api.shared :as wire]
         '[kmet.app.ui.tool-execution :as tool-ui]
         '[kmet.config :as config]
         '[kmet.libs.crypto :as crypto]
         '[kmet.libs.http :as http]
         '[kmet.libs.terminal-image :as timg]
         '[kmet.tui.protocols :as protocols])

(fs/create-dirs "target")
(let [dir (str (fs/absolutize (fs/create-temp-dir {:dir "target" :prefix "imgview-smoke-"})))
      artifact (str (fs/normalize (fs/absolutize (or (first *command-line-args*)
                                                     (fs/path (fs/parent *file*) "../src")))))
      png-data "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jBNsAAAAASUVORK5CYII="
      png (crypto/base64url-decode (str/replace png-data #"[+/]" {"+" "-" "/" "_"}))
      data-uri (str "data:image/png;base64," png-data)
      cwd (str (fs/path dir "project"))
      live-context (atom {:mode :interactive :has-ui true :cwd cwd})
      calls (atom [])
      injected (atom [])
      http-calls (atom [])
      http-status (atom 200)
      closed (atom 0)
      launches (atom [])
      launch-fails? (atom false)
      caps (timg/get-capabilities)
      check (fn [ok message] (when-not ok (throw (ex-info message {:type :smoke-failure}))))
      command! (fn [name args]
                 ((:extension-handler (commands/find-command name))
                  (extensions/build-extension-context) args))
      execute! (fn [args & [opts]]
                 (tools/execute-tool "show_image" args
                                     (merge {:ctx (extensions/build-extension-context) :signal (atom false)} opts)))
      text :content
      notice #(last @calls)
      render! (fn [message]
                (let [component ((extensions/get-message-renderer "imgview-image") message)]
                  (check (some? component) "Message renderer returned nothing")
                  (try (protocols/render component 100)
                       (finally (protocols/dispose component)))))]
  (fs/create-dirs cwd)
  (fs/write-bytes (fs/path cwd "pixel.png") png)
  (extensions/set-ui-registry!
   {:build-context (fn [] @live-context)
    :notify (fn [& args] (swap! calls conj (vec (cons :notify args))))})
  (extensions/set-context-sink! #(swap! injected conj %))
  (try
    ;; Shared SCI functions are copied when the base context is built, so
    ;; install all host seams before loading. No extension internals are patched.
    (with-redefs [config/get-agent-dir (constantly dir)
                  fs/temp-dir (constantly dir)
                  proc/process (fn [argv opts]
                                 (check (= {:in "" :out :discard :err :discard} opts) "Opener streams were not discarded")
                                 (swap! launches conj argv)
                                 (when @launch-fails?
                                   (throw (ex-info "test opener unavailable" {:type :test/open-failed})))
                                 {})
                  http/get (fn [url opts]
                             (swap! http-calls conj [url opts])
                             {:status @http-status :headers {"content-type" "image/jpeg"}
                              :body (io/input-stream png)})
                  http/close! (fn [response]
                                (swap! closed inc)
                                (with-open [_ (:body response)] nil))]
      (let [loaded (extensions/load-extension! artifact)]
        (check (nil? (:error loaded)) (str "Load failed: " (:error loaded)))
        (check (= :sci (:loader-kind loaded)) "Expected real isolated SCI loader"))
      (doseq [name ["imgcat" "imgshow" "imgboth"]]
        (check (some? (commands/find-command name)) (str "Missing command: " name)))
      (check (some? (tools/get-tool "show_image")) "Missing show_image tool")
      (check (some? (extensions/get-message-renderer "imgview-image")) "Missing image renderer")
      (check (and (empty? @calls) (empty? @launches)) "Loading caused side effects")
      (extensions/set-session! (session/create-session (str (fs/path dir "sessions")) {:cwd cwd}))
      (timg/set-capabilities! {:images nil})
      (let [updates (atom [])
            result (execute! {:source "pixel.png" :caption "pixel"} {:on-update #(swap! updates conj %)})]
        (check (not (:is-error result)) "Default tool call failed")
        (check (= [{:data png-data :mime-type "image/png"}] (:images result))
               "Tool result did not contain a host-compatible image attachment")
        (check (string? (:content result)) "Tool content was not text")
        (let [message {:content [{:type :tool_result :content (:content result)}] :images (:images result)}
              content (wire/tool-result-content message)]
          (check (= (:content result) (:text (first content))) "Provider text projection failed")
          (check (= (str "data:image/png;base64," png-data) (get-in content [1 :image_url :url]))
                 "Provider image projection failed"))
        (check (= "terminal" (get-in result [:details :mode])) "Wrong default mode")
        (check (= 1 (count @updates)) "Loading update missing")
        (check (empty? @launches) "Default tool call opened a browser")
        ;; Exercise the actual builtin tool-result UI, not a custom tool renderer.
        (let [component (tool-ui/make-tool-execution :name "show_image" :args {:source "pixel.png"}
                                                     :content (text result) :details (:details result))]
          (try
            (tool-ui/tool-execution-set-images! component (:images result))
            (tool-ui/tool-execution-set-error! component false)
            (check (some #(str/includes? % "[Image:") (protocols/render component 100))
                   "Normal tool-result UI did not render the image fallback")
            (finally (protocols/dispose component)))))
      (command! "imgcat" " ")
      (check (= :warning (last (notice))) "Usage warning missing")
      (command! "imgcat" "missing.png")
      (check (= :error (last (notice))) "Missing-file command error absent")
      (command! "imgcat" "pixel.png")
      (let [entry (last (session/get-branch (extensions/get-session)))
            live-message (assoc entry :content [{:type :text :text (:content entry)}])]
        (check (= :custom-message (:role entry)) "Command image was not persisted as a custom message")
        (check (= "imgview-image" (:custom-type entry)) "Wrong custom message type")
        (check (= png-data (get-in entry [:details :image :data])) "Image bytes did not survive persistence")
        (check (= 1 (count @injected)) "Command did not inject its text summary")
        (check (some #(str/includes? % "[Image:") (render! entry)) "Persisted message fallback absent")
        (check (some #(str/includes? % "bytes)") (render! live-message)) "Live text blocks lost the summary")
        (timg/set-capabilities! {:images :kitty})
        (check (some timg/is-image-line (render! entry)) "Supported terminal did not emit image protocol")
        (timg/set-capabilities! {:images nil}))
      (doseq [mode ["browser" "both"]]
        (let [before (count @launches)
              result (execute! {:source data-uri :mode mode})
              path (get-in result [:details :browser-path])]
          (check (not (:is-error result)) (str mode " call failed"))
          (check (= (inc before) (count @launches)) "Explicit browser mode did not launch")
          (check (= path (last (last @launches))) "Opener received the wrong file")
          (check (str/includes? (slurp path :encoding "UTF-8") (str "data:image/png;base64," png-data))
                 "Browser HTML lost original image bytes")
          (check (= (if (= mode "both") 1 0) (count (:images result)))
                 "Browser-only call returned an unnecessary image block")))
      (command! "imgshow" data-uri)
      (check (= 1 (count @injected)) "Browser-only command injected a transcript image")
      (command! "imgboth" "pixel.png")
      (check (= 2 (count @injected)) "Both command omitted its transcript image")
      (reset! launch-fails? true)
      (let [result (execute! {:source data-uri :mode "browser"})]
        (check (:is-error result) "Browser-only failure was reported as success")
        (check (nil? (get-in result [:details :browser-path])) "Failed opener claimed a browser path"))
      (let [result (execute! {:source data-uri :mode "both"})]
        (check (not (:is-error result)) "Both mode discarded useful inline output on browser failure")
        (check (some? (get-in result [:details :browser-error])) "Partial browser failure missing"))
      (reset! launch-fails? false)
      (let [signal (atom false)
            source "https://example.test/pixel.jpg?token=1"
            result (execute! {:source source} {:signal signal})
            [url opts] (last @http-calls)]
        (check (not (:is-error result)) "HTTP image call failed")
        (check (= source url) "HTTP source changed")
        (check (= :stream (:as opts)) "HTTP was not binary safe")
        (check (identical? signal (:signal opts)) "Abort signal not forwarded")
        (check (= "image/png" (get-in result [:details :mime-type])) "HTTP header overrode magic bytes")
        (check (= png-data (get-in result [:images 0 :data])) "HTTP binary payload changed")
        (check (= 1 @closed) "HTTP stream not closed"))
      (reset! http-status 404)
      (check (:is-error (execute! {:source "http://example.test/missing"})) "HTTP failure was not a tool error")
      (check (= 2 @closed) "Error HTTP stream leaked")
      (let [before (count @http-calls)]
        (check (:is-error (execute! {:source "https://example.test/cancelled"} {:signal (atom true)}))
               "Cancelled call was not an error")
        (check (= before (count @http-calls)) "Cancelled call started a fetch"))
      (check (:is-error (execute! {:source "data:text/plain,not%20an%20image"})) "Non-image accepted")
      (let [other-cwd (str (fs/path dir "other-project"))
            other-session (session/create-session (str (fs/path dir "other-sessions")) {:cwd other-cwd})]
        (fs/create-dirs other-cwd)
        (fs/write-bytes (fs/path other-cwd "new.png") png)
        (swap! live-context assoc :cwd other-cwd)
        (extensions/set-session! other-session)
        (command! "imgcat" "new.png")
        (check (= (str (fs/path other-cwd "new.png"))
                  (get-in (last (session/get-branch other-session)) [:details :resolved]))
               "Command captured launch cwd/session"))
      (extensions/unload-all-extensions!)
      (check (nil? (tools/get-tool "show_image")) "Unload leaked tool")
      (check (nil? (extensions/get-message-renderer "imgview-image")) "Unload leaked renderer")
      (doseq [name ["imgcat" "imgshow" "imgboth"]]
        (check (nil? (commands/find-command name)) "Unload leaked command"))
      (let [loaded (extensions/load-extension! artifact)]
        (check (nil? (:error loaded)) (str "Reload failed: " (:error loaded))))
      (check (not (:is-error (execute! {:source data-uri}))) "Reload lost tool behavior"))
    (finally
      (extensions/unload-all-extensions!)
      (extensions/set-context-sink! nil)
      (extensions/set-session! nil)
      (extensions/clear-ui-registry!)
      (timg/set-capabilities! caps)
      ;; Browser launch stubs leave viewers readable until all checks finish.
      (doseq [argv @launches] (fs/delete-if-exists (last argv)))
      (fs/delete-tree dir)))
  (check (empty? (extensions/get-loaded-extensions)) "Unload leaked extension state")
  (println "SCI load, show_image, commands, image rendering, HTTP, cancellation, browser failure, persistence, cwd/session switch, reload and unload passed (offline)."))
