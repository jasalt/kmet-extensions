(ns web-remote.core
  "Mirror the live kmet session in a local browser page (HTML + SSE + Datastar)
  and accept input from it. Event handlers only update the state atom; one
  render thread debounces, renders the regions and wakes the SSE clients,
  each of which sends only the regions that changed since its last send."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.json :as json]
            [web-remote.http :as http]
            [web-remote.view :as view]))

(def cookie-name "kmet_web_remote")
(def tick-ms 50)
(def ping-ms 15000)
(def thinking-levels [:off :minimal :low :medium :high :xhigh :max])

(defn- ask [ctx k & args] (when-let [f (get ctx k)] (apply f args)))

(defn model-key [m] (when m (str (some-> (:provider m) name) "/" (:id m))))

;; ─── state ─────────────────────────────────────────────────────────────────
;; {:ctx :server :token :datastar :loop (token) :clients #{queue}
;;  :dirty #{:live :status :transcript} :live {:text :thinking :tools}
;;  :running? :compacting? :queued :model :notice :leaf
;;  :phase (:waiting-for-model | :working | nil)
;;  :transient {:kind (:retry | :compaction | :branch-summary) …} or nil
;;  :regions {"status" html "transcript" html "live" html …} :signals json}

(defn- dirty! [state & regions]
  (swap! state update :dirty (fnil into #{}) regions))

(defn- live-reset [s] (assoc s :live {:text nil :thinking nil :tools {}}))

;; ─── event handlers (cheap: atom updates only) ─────────────────────────────
;; The transcript needs no event: the render loop re-renders it whenever the
;; session leaf moves, which covers every append (messages, tools, bash, …).

(defn- on-message-update [state {:keys [message delta]}]
  (swap! state
         (fn [s]
           (case (:type delta)
             :text (assoc-in s [:live :text] (view/content-text (:content message)))
             :thinking (assoc-in s [:live :thinking] (:thinking message))
             s)))
  (dirty! state :live))

(defn- on-tool [state {:keys [type tool-call-id tool-name args content]}]
  (swap! state update-in [:live :tools]
         (fn [tools]
           (case type
             :tool-execution-start (assoc tools tool-call-id {:name tool-name :args args
                                                              :started (concurrent/monotonic-ms)})
             :tool-execution-update (if (contains? tools tool-call-id)
                                      (assoc-in tools [tool-call-id :output] (view/content-text content))
                                      tools)
             :tool-execution-end (dissoc tools tool-call-id))))
  (dirty! state :live))

;; The working line mirrors the TUI's status indicator: a run phase
;; (:waiting-for-model until the first token of each LLM call, then :working)
;; under an optional transient indicator (retry countdown, compaction,
;; branch summary) that wins while it is up.

(defn- phase! [phase]
  (fn [state _]
    (when-not (= phase (:phase @state))
      (swap! state assoc :phase phase)
      (dirty! state :activity))))

(defn- transient! [state t]
  (swap! state assoc :transient t)
  (dirty! state :activity :status))

(defn- clear-transient!
  "Clear the transient indicator when KIND is the one showing (a stale end
  event must not clear a later indicator)."
  [state kind]
  (when (= kind (get-in @state [:transient :kind]))
    (transient! state nil)))

(defn- retry-indicator [{:keys [attempt max-attempts delay-ms]}]
  {:kind :retry :attempt attempt :max-attempts max-attempts
   :deadline (+ (concurrent/monotonic-ms) (or delay-ms 0))})

(defn- error-notice! [state text]
  (swap! state assoc :notice text)
  (dirty! state :notice))

(def ^:private activity-handlers
  {:agent-start (phase! :waiting-for-model)
   :turn-start (phase! :waiting-for-model)
   :message-update (phase! :working)
   :message-end (phase! :working)
   :tool-execution-start (phase! :working)
   :status (fn [state {:keys [status]}]
             ((phase! (if (= :thinking status) :waiting-for-model :working)) state nil))
   :auto-retry-start (fn [state e] (transient! state (retry-indicator e)))
   :auto-retry-end (fn [state _] (clear-transient! state :retry))
   :summarization-retry-scheduled (fn [state e] (transient! state (retry-indicator e)))
   :summarization-retry-attempt-start
   (fn [state {:keys [source reason]}]
     (transient! state (if (= :branch-summary source)
                         {:kind :branch-summary}
                         {:kind :compaction :reason reason})))
   :summarization-retry-finished (fn [state _] (clear-transient! state :retry))
   :compaction-start (fn [state {:keys [reason]}]
                       (swap! state assoc :compacting? true)
                       (transient! state {:kind :compaction :reason reason}))
   :compaction-end (fn [state {:keys [aborted reason error-message]}]
                     (swap! state assoc :compacting? false)
                     (clear-transient! state :compaction)
                     (dirty! state :transcript)
                     (cond
                       error-message (error-notice! state (str "Compaction failed: " error-message))
                       aborted (error-notice! state (if (= :manual reason)
                                                      "Compaction cancelled."
                                                      "Auto-compaction cancelled."))))
   :error (fn [state {:keys [message]}]
            (when message (error-notice! state (str "Error: " message))))
   :loop-guard (fn [state {:keys [details]}]
                 (error-notice! state (or details "Stopped: repeat-loop guard tripped")))})

(defn- reset-live [state _]
  (swap! state live-reset)
  (dirty! state :live :status :transcript))

(def handlers
  "event type → (fn [state event])"
  {:message-update on-message-update
   :message-start (fn [state _] (swap! state update :live assoc :text nil :thinking nil) (dirty! state :live))
   :message-end (fn [state _] (swap! state update :live assoc :text nil :thinking nil) (dirty! state :live))
   :tool-execution-start on-tool
   :tool-execution-update on-tool
   :tool-execution-end on-tool
   :agent-start (fn [state _] (swap! state assoc :running? true) (dirty! state :status))
   :agent-settled (fn [state _]
                    ;; a terminal error ends the run under a retry countdown
                    (swap! state #(cond-> (-> % live-reset (assoc :running? false :phase nil))
                                    (= :retry (get-in % [:transient :kind])) (assoc :transient nil)))
                    (dirty! state :status :live :transcript :activity))
   :queue-update (fn [state {:keys [steering follow-up]}]
                   (swap! state assoc :queued (+ (count steering) (count follow-up)))
                   (dirty! state :status))
   :model-select (fn [state {:keys [model]}] (swap! state assoc :model model) (dirty! state :status))
   :thinking-level-select (fn [state _] (dirty! state :status))
   :session-info-changed (fn [state _] (dirty! state :status))
   :context-replaced (fn [state _] (dirty! state :transcript))
   :session-tree reset-live
   :session-start (fn [state e]
                    (swap! state assoc :running? false :queued 0 :phase nil :transient nil)
                    (dirty! state :activity)
                    (reset-live state e))})

(defn- combine
  "Merge handler maps; a type in several maps runs every handler in order."
  [& maps]
  (apply merge-with (fn [f g] (fn [state e] (f state e) (g state e))) maps))

(def all-handlers (combine handlers activity-handlers))

(defn subscribe! [api state]
  (doseq [[type f] all-handlers]
    (ext/on-event api type (fn [event _ctx]
                             (when (:server @state) (f state event))))))

;; ─── rendering ─────────────────────────────────────────────────────────────

(defn- session-call [api k & args]
  (try (some-> (ext/session api) (get k) (apply args)) (catch Exception _ nil)))

(defn- thinking [api] (try (ext/get-thinking-level api) (catch Exception _ nil)))

(defn- status-data [api state]
  (let [{:keys [ctx running? compacting? queued model]} @state
        idle (ask ctx :is-idle)]
    {:model (model-key model)
     :thinking (thinking api)
     ;; events can be missed across a reload; the ctx's live idle check wins
     :running? (if (some? idle) (not idle) running?)
     :compacting? compacting?
     :queued queued
     :session-name (session-call api :get-name)}))

(defn activity-data
  "What the working line shows: the transient indicator when one is up, else
  the run phase while the agent is busy, else nil (idle)."
  [state]
  (let [{:keys [ctx transient phase running?]} @state
        idle (ask ctx :is-idle)
        busy? (if (some? idle) (not idle) running?)]
    (cond
      (= :retry (:kind transient))
      (assoc transient :seconds (max 0 (long (Math/ceil (/ (- (:deadline transient) (concurrent/monotonic-ms))
                                                           1000.0)))))
      transient transient
      busy? {:kind (or phase :working)})))

(defn- render-region [api state region]
  (case region
    :status (view/status (status-data api state))
    :live (view/live (:live @state))
    :notice (view/notice (:notice @state))
    :activity (view/activity (activity-data state))
    :transcript (view/transcript (or (session-call api :get-branch) []))))

(defn- signals [api state]
  (json/generate-string {:model (or (model-key (:model @state)) "")
                         :thinking (some-> (thinking api) name)}))

(defn render!
  "Render the dirty regions — status always (it reads the live idle check),
  the transcript whenever the session leaf moved — and answer true when any
  region or signal changed."
  [api state]
  (let [leaf (session-call api :get-leaf-id)
        _ (when (not= leaf (:leaf @state)) (swap! state assoc :leaf leaf) (dirty! state :transcript))
        [old _] (swap-vals! state assoc :dirty #{})
        ;; status and activity always: they read the live idle check, and
        ;; the retry countdown ticks; unchanged renders send nothing
        regions (into {} (for [r (into (:dirty old) [:status :activity])]
                           [(name r) (render-region api state r)]))
        sig (signals api state)
        before (select-keys @state [:regions :signals])]
    (swap! state #(-> % (update :regions merge regions) (assoc :signals sig)))
    (not= before (select-keys @state [:regions :signals]))))

(defn- wake! [state msg]
  (doseq [q (:clients @state)] (concurrent/offer! q msg)))

(defn start-loop! [api state]
  (let [token (Object.)]
    (swap! state assoc :loop token)
    (concurrent/spawn
     (fn []
       (loop [last-ping (concurrent/monotonic-ms)]
         (when (identical? token (:loop @state))
           (Thread/sleep tick-ms)
           (when (try (render! api state) (catch Exception _ false))
             (wake! state :render))
           (let [now (concurrent/monotonic-ms)]
             (if (>= (- now last-ping) ping-ms)
               (do (wake! state :ping) (recur now))
               (recur last-ping)))))))))

(defn stream-client!
  "Serve one SSE client until it disconnects or the server stops: send every
  region on connect, then only the regions that changed."
  [state send!]
  (let [q (concurrent/bounded-queue 1)]
    (swap! state update :clients (fnil conj #{}) q)
    (try
      (loop [sent {} sent-signals nil]
        (let [{:keys [regions] sig :signals} @state]
          (doseq [[id html] regions :when (not= html (get sent id))]
            (send! (http/patch-elements html)))
          (when (and sig (not= sig sent-signals))
            (send! (http/patch-signals sig)))
          (let [{msg :value} (concurrent/take-dropped! q)]
            (cond
              (= msg concurrent/closed) nil
              (= msg :ping) (do (send! ": ping\n\n") (recur regions sig))
              :else (recur regions sig)))))
      (catch Exception _ nil)
      (finally (swap! state update :clients disj q)
               (concurrent/close! q)))))

;; ─── input actions ─────────────────────────────────────────────────────────
;; Each answers a one-shot SSE body Datastar applies to the page.

(defn- notice! [state text]
  (swap! state assoc :notice text)
  (dirty! state :notice)
  (http/sse-response (http/patch-elements (view/notice text))))

(defn- ok! [state & events]
  (swap! state assoc :notice nil)
  (dirty! state :notice :status)
  (apply http/sse-response (http/patch-elements (view/notice nil)) events))

(defn- idle? [state]
  (let [{:keys [ctx]} @state]
    (and (ask ctx :is-idle) (not (ask ctx :has-pending-messages)))))

(defn- action-prompt [api state {:keys [prompt deliver]}]
  (let [text (str/trim (str prompt))]
    (if (str/blank? text)
      (notice! state "Empty message.")
      (let [running? (not (ask (:ctx @state) :is-idle))]
        (ext/send-user-message api text
                               (cond-> {:expand-prompt-templates? true}
                                 running? (assoc :deliver-as (if (= "follow-up" deliver) :follow-up :steer))))
        (ok! state (http/patch-signals "{\"prompt\":\"\"}"))))))

(defn- action-model [api state {:keys [model]}]
  (let [m (some #(when (= model (model-key %)) %)
                (ask (ext/models api) :get-available))]
    (cond
      (nil? m) (notice! state (str "Unknown model: " model))
      (ext/set-model api m) (do (swap! state assoc :model m) (ok! state))
      :else (notice! state (str "Could not switch to " model " (no auth?)")))))

(defn- action-thinking [api state {:keys [thinking]}]
  (if-let [level (some #{(keyword (str thinking))} thinking-levels)]
    (do (ext/set-thinking-level api level) (ok! state))
    (notice! state "Unknown thinking level.")))

(defn- action-compact [_ state _]
  (ask (:ctx @state) :compact
       {:on-error (fn [e]
                    (swap! state assoc :notice (str "Compaction failed: " (ex-message e)))
                    (dirty! state :notice))})
  (ok! state))

(defn- action-name [api state {:keys [name]}]
  (if (str/blank? (str name))
    (notice! state "Empty session name.")
    (do (session-call api :set-name! (str/trim name))
        (dirty! state :status)
        (ok! state (http/patch-signals "{\"name\":\"\"}")))))

(defn- action-new [_ state _]
  (if (idle? state)
    (if (:cancelled (ask (:ctx @state) :new-session))
      (notice! state "New session cancelled.")
      (ok! state))
    (notice! state "Agent is busy; abort first.")))

(defn- action-abort [_ state _]
  (ask (:ctx @state) :abort)
  (ok! state))

(def actions
  {"/prompt" action-prompt "/abort" action-abort "/model" action-model
   "/thinking" action-thinking "/compact" action-compact "/name" action-name
   "/new" action-new})

;; ─── routing ───────────────────────────────────────────────────────────────

(defn- host-port
  "HOST:PORT as it appears in a Host header or URL (IPv6 literals bracketed)."
  [host port]
  (str (if (str/includes? host ":") (str "[" host "]") host) ":" port))

(defn allowed-hosts
  "Host header values the server answers to — IP literals of the bound
  address (every interface for a wildcard bind), localhost for loopback and
  the name the user asked to bind — never arbitrary names, so DNS rebinding
  stays out."
  [{:keys [host port loopback? wildcard? requested]}]
  (let [hosts (cond
                wildcard? (concat ["127.0.0.1" "::1" "localhost"] (http/interface-addresses))
                loopback? [host requested "localhost"]
                :else [host requested])]
    (set (map #(host-port % port) hosts))))

(defn- allowed-host? [state value]
  (contains? (get-in @state [:server :allowed-hosts]) value))

(defn- forbidden [] {:status 403 :headers {"Content-Type" "text/plain"} :body "forbidden"})

(def csp
  ;; Datastar evaluates expressions with Function(), hence 'unsafe-eval'
  (str "default-src 'self'; style-src 'unsafe-inline'; script-src 'self' 'unsafe-eval'; "
       "frame-ancestors 'none'; form-action 'none'; base-uri 'none'"))

(defn- page [api state]
  (let [models (->> (ask (ext/models api) :get-available)
                    (map (fn [m] {:value (model-key m) :label (model-key m)}))
                    (sort-by :label))]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8" "Content-Security-Policy" csp}
     :body (view/page {:signals {:prompt "" :deliver "steer" :name ""
                                 :model (or (model-key (:model @state)) "")
                                 :thinking (some-> (thinking api) name)}
                       :models models
                       :levels thinking-levels})}))

(defn- parse-signals [body]
  (let [m (try (json/parse-string body true) (catch Exception _ nil))]
    (if (map? m) m {})))

(defn- same-origin? [state origin host]
  (and (string? origin) (= origin (str "http://" host))
       (allowed-host? state host)))

(defn handle
  "Route one request. Every route needs the session cookie except the token
  exchange; POSTs also need a same-origin Origin header. The Host check
  defeats DNS rebinding."
  [api state {:keys [method path query headers cookies body]}]
  (let [{:keys [token]} @state
        host (get headers "host")]
    (cond
      (not (allowed-host? state host)) (forbidden)

      (and (= "GET" method) (= "/" path) (contains? query "token"))
      (if (http/constant-time= token (get query "token"))
        {:status 303 :headers {"Location" "/"
                               "Set-Cookie" (str cookie-name "=" token "; Path=/; HttpOnly; SameSite=Strict")}}
        (forbidden))

      (not (http/constant-time= token (get cookies cookie-name))) (forbidden)

      (= "GET" method)
      (case path
        "/" (page api state)
        "/datastar.js" {:status 200 :headers {"Content-Type" "text/javascript; charset=utf-8"}
                        :body (:datastar @state)}
        "/app.js" {:status 200 :headers {"Content-Type" "text/javascript; charset=utf-8"}
                   :body view/app-js}
        "/events" {:sse (fn [send!] (stream-client! state send!))}
        {:status 404 :body "not found"})

      (= "POST" method)
      (let [action (get actions path)]
        (cond
          (not (same-origin? state (get headers "origin") host)) (forbidden)
          (nil? action) {:status 404 :body "not found"}
          :else (action api state (parse-signals body))))

      :else {:status 405 :body "method not allowed"})))

;; ─── lifecycle ─────────────────────────────────────────────────────────────

(defn- datastar-source [api]
  (or (try (some-> (io/resource "web_remote/datastar.js") slurp) (catch Exception _ nil))
      (when-let [dir (:extension-dir api)]
        (let [f (fs/path dir "web_remote" "datastar.js")]
          (when (fs/exists? f) (slurp (str f)))))
      (throw (ex-info "datastar.js not found" {:type :web-remote/missing-asset}))))

(defn urls
  "Login URLs: the bound address, or for a wildcard bind loopback first and
  then each interface address."
  [state]
  (when-let [{:keys [requested port wildcard?]} (:server @state)]
    (for [h (if wildcard? (cons "127.0.0.1" (http/interface-addresses)) [requested])]
      (str "http://" (host-port h port) "/?token=" (:token @state)))))

(defn url [state] (first (urls state)))

(defn parse-address
  "`PORT`, `HOST`, `HOST:PORT` or `[IPv6]:PORT` → {:host :port}, else nil.
  A missing host is 127.0.0.1, a missing port 0 (any free port)."
  [s]
  (let [s (str/trim (str s))
        [_ v6 v6-port host host-port] (re-matches #"\[([0-9A-Fa-f:.]+)\](?::(\d+))?|([^:\[\]\s]*)(?::(\d+))?" s)
        host (or v6 (not-empty host))
        raw-port (or v6-port host-port)
        port (cond (nil? raw-port) 0 :else (http/parse-nat raw-port))]
    (cond
      (str/blank? s) {:host "127.0.0.1" :port 0}
      (and (nil? v6) (some->> host (re-matches #"\d+")) (nil? raw-port))
      (parse-address (str ":" host))                ; bare port
      (and (or v6 host raw-port) port (<= 0 port 65535))
      {:host (or host "127.0.0.1") :port port})))

(defn start!
  "Start the server on HOST:PORT (port 0 = any free port). Answers the
  login URLs."
  [api state ctx {:keys [host port]}]
  (if (:server @state)
    (urls state)
    (let [datastar (datastar-source api)]
      (swap! state #(-> % live-reset
                        (assoc :ctx ctx :token (http/random-token) :datastar datastar
                               :model (:model ctx) :regions {} :signals nil :leaf nil
                               :dirty #{:status :live :transcript :notice :activity} :notice nil
                               :running? false :compacting? false :queued 0
                               :phase nil :transient nil)))
      (let [server (assoc (http/start! {:host host :port port :handler #(handle api state %)})
                          :requested host)]
        (swap! state assoc :server (assoc server :allowed-hosts (allowed-hosts server))))
      (start-loop! api state)
      (let [{:keys [host port]} (:server @state)]
        (ext/ui-set-status api "web-remote" (str "web " (host-port host port))))
      (urls state))))

(defn exposed?
  "True when the server listens beyond loopback."
  [state]
  (not (get-in @state [:server :loopback?])))

(defn stop! [api state]
  (when-let [server (:server @state)]
    (swap! state assoc :server nil :loop nil :token nil)
    (doseq [q (:clients @state)] (concurrent/close! q))
    (http/stop! server)
    (try (ext/ui-set-status api "web-remote" nil) (catch Exception _ nil))
    true))

(def usage "/web-remote start [port|host:port] | stop | status | url")

(defn- notice-label [type]
  (case type :warning "web-remote: warning" :error "web-remote: error" "web-remote"))

(defn say!
  "Report TEXT as an ephemeral chat notice (in the live transcript, never sent
  to the LLM or persisted). Without a UI layout, chat-info is a no-op, so
  fall back to a flash notification."
  [api ctx text type]
  (if (:has-ui ctx)
    (ext/ui-chat-info api (notice-label type) text)
    (ext/ui-notify api text type)))

(defn command! [api state ctx args]
  (let [[cmd arg & more] (str/split (str/trim (or args "")) #"\s+")
        say! (fn [text type] (say! api ctx text type))]
    (try
      (cond
        (seq more) (say! usage :warning)

        (= "start" cmd)
        (if-let [address (parse-address (or arg ""))]
          (let [started (str/join "\n" (start! api state ctx address))]
            (say! (str "Started:\n" started) :info)
            (when (exposed? state)
              (say! (str "Listening beyond loopback over plain HTTP: anyone on the network who "
                         "sees the token controls this agent. Use only on a trusted network.")
                    :warning)))
          (say! usage :warning))

        (and (= "stop" cmd) (nil? arg))
        (say! (if (stop! api state) "Stopped." "Not running.") :info)

        (and (#{"" "status" "url"} cmd) (nil? arg))
        (if (:server @state)
          (do (swap! state assoc :ctx ctx)
              (say! (str (str/join "\n" (urls state)) "\n(" (count (:clients @state)) " clients)") :info))
          (say! (str "Not running. " usage) :info))

        :else (say! usage :warning))
      (catch Exception e
        (say! (ex-message e) :error)))))

(defonce ^:private runtime (atom nil))

(defn init [api]
  (let [state (atom {})]
    (reset! runtime {:api api :state state})
    (ext/register-command! api {:name "web-remote"
                                :description "Mirror this session in a local browser page"
                                :handler (fn [ctx args] (command! api state ctx args))})
    (subscribe! api state)
    (ext/on-event api :session-shutdown (fn [{:keys [reason]} _]
                                          (when (= :quit reason) (stop! api state))))
    state))

(defn shutdown [_api]
  (when-let [{:keys [api state]} @runtime]
    (stop! api state))
  (reset! runtime nil))

(def plugin {:name :ext/web-remote :apply (fn [_ api] (init api) nil)})
