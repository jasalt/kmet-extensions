;; Copyright (c) 2026 Jarkko Saltiola; SPDX-License-Identifier: MIT
(ns session-migrate.core
  "Claude Code → kmet session migration through the public extension contract."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [kmet.libs.json :as json]
            [session-migrate.claude :as claude]
            [session-migrate.io :as mio]
            [session-migrate.writer :as writer]))

(def usage "Usage: /session-migrate <inspect|save|import> claude <JSONL path or UUID>")
(def ^:private uuid-pattern #"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
(def ^:private active-lifecycle (atom nil))

(defn resolve-source
  "Resolve one explicit literal path or an unambiguous Claude project UUID."
  [cwd source]
  (if (re-matches uuid-pattern source)
    (let [root (or (System/getenv "CLAUDE_CONFIG_DIR") (str (fs/path (fs/home) ".claude")))
          matches (fs/glob (fs/path root "projects") (str "*/" source ".jsonl"))]
      (when-not (= 1 (count matches))
        (mio/fail! (str "Claude UUID resolved to " (count matches) " files; supply an explicit path")))
      (str (first matches)))
    (let [path (fs/expand-home source)]
      (str (fs/normalize (fs/absolutize (if (fs/absolute? path) path (fs/path cwd path))))))))

(defn- arguments [args]
  (let [[_ action format path] (re-matches #"(?s)\s*(\S+)\s+(\S+)\s+(.+?)\s*" (or args ""))
        path (when path
               (if (and (>= (count path) 2)
                        (or (and (str/starts-with? path "\"") (str/ends-with? path "\""))
                            (and (str/starts-with? path "'") (str/ends-with? path "'"))))
                 (subs path 1 (dec (count path))) path))]
    (when-not (and (contains? #{"inspect" "save" "import"} action) (= "claude" format) (seq path))
      (mio/fail! (str usage "; only Claude → kmet is supported")))
    {:action action :source path}))

(defn- run-command! [api ctx args cancelled?]
  (let [{:keys [action source]} (arguments args)
        ;; No account data, runtime config or recorded tools are evaluated.
        transcript (claude/read-claude (resolve-source (:cwd ctx) source) cancelled?)]
    (if (= "inspect" action)
      (do (ext/ui-notify api (json/generate-string (:report transcript)) :info)
          (:report transcript))
      (do
        (when-not ((:is-idle ctx)) (mio/fail! "Wait for kmet to be idle before importing"))
        (when ((:has-pending-messages ctx)) (mio/fail! "Drain pending messages before importing"))
        (when (and (= "import" action) (not= :interactive (:mode ctx)))
          (mio/fail! "Session switching requires interactive mode; use save instead"))
        (let [dir (writer/default-directory (ext/get-agent-dir api) (:cwd ctx))
              result (writer/write-session! transcript dir (:cwd ctx) cancelled?)]
          (ext/ui-notify api (str "Saved imported session: " (:path result)
                                  "; manifest: " (:manifest-path result)) :info)
          (when (= "import" action)
            (let [switched ((:switch-session ctx) (:path result))]
              (when (or (nil? switched) (:cancelled switched))
                (ext/ui-notify api (str "Session switch cancelled; imported file retained at " (:path result))
                               :warning))))
          result)))))

(defn init
  "Register one awaited command. Loading schedules no work and changes no files."
  [api]
  (let [lifecycle (atom {:busy? false :closed? false})]
    ;; Each registration captures its own generation, so re-init cannot
    ;; uncancel a callback belonging to an unloaded generation.
    (reset! active-lifecycle lifecycle)
    (ext/register-command!
     api {:name "session-migrate" :description usage :argument-hint "<inspect|save|import> claude <path|UUID>"
          :handler
          (fn [ctx args]
            (let [state @lifecycle]
            ;; Fail rather than block: replacement events can re-enter commands.
              (when (or (:busy? state) (:closed? state)
                        (not (compare-and-set! lifecycle state (assoc state :busy? true))))
                (mio/fail! "Session migration busy or extension unloaded"))
              (try
                (let [signal (when-let [get-signal (:signal ctx)] (get-signal))
                      cancelled? #(or (:closed? @lifecycle) (and signal @signal))]
                  (run-command! api ctx args cancelled?))
                (catch Exception e
                ;; UI errors are visible in interactive mode; ex-data contains
                ;; only controlled categories, never transcript excerpts.
                  (ext/ui-notify api (str "Session migration failed: " (ex-message e)) :error)
                  {:error (ex-message e)})
                (finally (swap! lifecycle assoc :busy? false)))))})))

(defn shutdown
  "Cancel in-flight bounded processing on unload. No worker threads to drain."
  [_api]
  (when-let [lifecycle @active-lifecycle]
    (swap! lifecycle assoc :closed? true)))
