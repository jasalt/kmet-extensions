(ns watcher.core
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [kmet.libs.concurrent :as concurrent]
            [watcher.files :as files]))

(defn- notify!
  "Flash a watcher notice long enough to read (the host default is ~1s)."
  [api message type]
  (ext/ui-notify api message type {:duration-ms 5000}))

(defn- ask [ctx k] (when-let [f (k ctx)] (f)))

(defn idle? [ctx]
  (and (ask ctx :is-idle) (not (ask ctx :has-pending-messages))))

(defn status!
  "Show watcher state in the footer; skip the host call when unchanged."
  [api state]
  (let [{:keys [config pending active error status]} @state
        text (str "watcher " (cond error "error" active "dispatching"
                                   (seq pending) "queued" (:enabled config) "on" :else "off"))]
    (when (not= text status)
      (swap! state assoc :status text)
      (ext/ui-set-status api "watcher" text))))

(defn tick!
  "Serialize scans, commands and lifecycle transitions. Always refresh queued markers.
  A manual request that cannot send yet stays requested until the agent is idle.
  Returns :sent, :waiting, :none or :error; nil when skipped."
  [api state manual?]
  (locking state
    (let [{:keys [ctx cwd config processed active ended? compacting? cache signature changed-at requested?]} @state
          manual? (or manual? requested?)]
      ;; An active batch keeps ticking so cleanup runs even when polling is disabled.
      (when (and cwd config (or manual? (:enabled config) active))
        (let [idle (and (not compacting?) (idle? ctx))
              done? (and active ended? idle)
              _ (when (and done? (:removeHandledMarkerComments config)) (files/clean! cwd config active))
              pending (vec (remove (comp processed :id) (files/scan cwd config (or cache (atom {})))))
              sig (mapv #(select-keys % [:id :line :context]) pending)
              now (System/currentTimeMillis)
              changed-at (if (= sig signature) changed-at now)
              actionable? (some #(not= :context (:action %)) pending)
              ready? (and actionable? (or manual? (:enabled config))
                          (or done? (not active)) idle
                          (or manual? (>= (- now changed-at) (:debounceMs config))))
              text (when ready? (files/prompt pending config))
              result (cond (not actionable?) :none text :sent ready? :error :else :waiting)]
          (swap! state merge {:pending pending :signature sig :changed-at changed-at
                              :requested? (and manual? (= result :waiting))}
                 (when done? {:active nil :ended? false})
                 (cond text {:active pending :ended? false :last-batch pending :error nil}
                       ready? {:error "Batch exceeds maxPromptBytes; narrow roots or increase the limit."}))
          ;; :active is recorded before sending so a synchronous agent-end is not lost.
          (let [result (if text
                         (try
                           (ext/send-user-message api text {:deliver-as :follow-up})
                           (swap! state #(-> % (update :processed into (map :id pending)) (assoc :pending [])))
                           :sent
                           (catch Exception e
                             (swap! state assoc :active nil :error (ex-message e))
                             :error))
                         result)]
            (status! api state)
            result))))))

(defn check!
  "Scan now and send actionable markers, or report why nothing was sent."
  [api state]
  (case (tick! api state true)
    :none (notify! api "Watcher: no AI! or AI? markers found." :info)
    :waiting (notify! api "Watcher: agent busy; markers will be sent when idle." :info)
    :error (notify! api (str "Watcher: " (:error @state)) :error)
    nil))

(defn stop! [api state]
  (swap! state assoc :loop nil :status nil)
  (ext/ui-set-status api "watcher" nil))

(defn start-loop!
  "Poll until :loop no longer holds this loop's token (stop or restart)."
  [api state]
  (let [token (Object.)
        mine? #(identical? token (:loop @state))]
    (swap! state assoc :loop token)
    (concurrent/spawn
     #(while (mine?)
        (Thread/sleep (max 100 (get-in @state [:config :pollMs] 1000)))
        (try
          (locking state (when (mine?) (tick! api state false)))
          (catch Exception e
            (swap! state assoc :error (ex-message e))
            (status! api state)))))))

(declare command!)

(defn session! [api state ctx]
  (locking state
    (stop! api state)
    (let [cwd (str (fs/absolutize (:cwd ctx)))
          paths (files/paths (ext/get-agent-dir api) cwd)
          config (files/config paths)]
      (swap! state assoc :ctx ctx :cwd cwd :paths paths :config config :processed #{}
             :pending [] :active nil :ended? false :compacting? false :signature nil
             :last-batch nil :error nil :cache (atom {}) :requested? false)
      (some-> (:unbind @state) (apply []))
      (swap! state assoc :unbind
             (when-let [key-id (and (:has-ui ctx) (:shortcut config))]
               (ext/register-shortcut! api key-id {:description "Watcher: check for AI markers"
                                                   :handler #(command! api state % "check")})))
      (when-not (:scanOnStart config)
        (swap! state assoc :processed (set (map :id (files/scan cwd config (:cache @state))))))
      ;; Print mode supports manual scans only.
      (when (:has-ui ctx) (start-loop! api state))
      (status! api state))))

(def usage "Usage: /watcher status|config|start|stop|check|clear|retry|global start|stop|project start|stop")

(defn command! [api state ctx args]
  (locking state
    (try
      (when-not (:cwd @state) (session! api state ctx))
      (swap! state assoc :ctx ctx)
      (let [parts (str/split (str/trim (or args "")) #"\s+")
            [command action] parts
            scoped? (#{"global" "project"} command)
            op (if scoped? action command)]
        (cond
          (and (#{"start" "stop"} op) (= (count parts) (if scoped? 2 1)))
          (do
            (files/set-enabled! (get (:paths @state) (if (= command "global") :global :project)) (= op "start"))
            (swap! state assoc :config (files/config (:paths @state)) :error nil :cache (atom {}))
            (when (and (:has-ui ctx) (not (:loop @state))) (start-loop! api state))
            (status! api state)
            (notify! api (str "Watcher enabled: " (get-in @state [:config :enabled])) :info))

          (and (= 1 (count parts)) (#{"" "status" "config"} command))
          (notify! api
                   (pr-str (assoc (select-keys @state [:cwd :paths :config :error])
                                  :running? (some? (:loop @state))
                                  :queued (count (:pending @state))
                                  :active (count (:active @state))
                                  :last-trigger (mapv #(select-keys % [:path :line :instruction])
                                                      (:last-batch @state)))) :info)

          (#{["check"] ["scan"]} parts) (check! api state)
          (= parts ["clear"]) (do (swap! state assoc :pending [] :processed #{} :signature nil :error nil :requested? false)
                                    (notify! api "Queue and ledger cleared; unchanged markers may trigger on the next scan." :info))
          (= parts ["retry"])
          (if (:active @state)
            (notify! api "Wait for the active watcher batch to finish." :warning)
            (do (swap! state update :processed #(apply disj % (map :id (:last-batch @state))))
                (check! api state)))
          :else (notify! api usage :warning)))
      (catch Exception e (notify! api (ex-message e) :error)))))

;; The host calls shutdown on unload with only api, so init's state is kept here.
(defonce runtime (atom nil))

(defn shutdown [api]
  (when-let [state @runtime]
    (stop! api state)
    (some-> (:unbind @state) (apply [])))
  (reset! runtime nil))

(defn init [api]
  (let [state (atom {:processed #{}})]
    (reset! runtime state)
    (ext/register-command! api
                           {:name "watcher" :description "Watch saved AI!, AI? and AI. comments"
                            :handler (fn [ctx args] (command! api state ctx args))})
    (ext/on-event api :session-start (fn [_ ctx]
                                     (try (session! api state ctx)
                                          (catch Exception e
                                            (swap! state assoc :error (ex-message e))
                                            (notify! api (str "Watcher: " (ex-message e)) :error)))))
    (ext/on-event api :agent-end (fn [_ _] (swap! state assoc :ended? true)))
    (ext/on-event api :session-before-compact (fn [_ _] (swap! state assoc :compacting? true)))
    (doseq [event [:compaction-end :session-compact-failed]]
      (ext/on-event api event (fn [_ _] (swap! state assoc :compacting? false))))
    (ext/on-event api :session-shutdown (fn [_ _] (stop! api state)))
    state))

(def plugin {:name :ext/watcher :apply (fn [_ api] (init api) nil)})
