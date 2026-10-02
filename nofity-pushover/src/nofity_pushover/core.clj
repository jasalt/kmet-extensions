(ns nofity-pushover.core
  "One-way human alerts, ported from the Pi pushover-human extension."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [kmet.libs.http :as http]
            [kmet.libs.json :as json]
            [kmet.libs.oauth :as oauth]))

(def ^:private status-key "nofity-pushover")
(def ^:private endpoint "https://api.pushover.net/1/messages.json")

(defn- trimmed [value]
  (when (some? value) (not-empty (str/trim (str value)))))

(defn- config-path [api]
  (str (fs/path (ext/get-agent-dir api) "nofity-pushover.json")))

(defn- credentials [values]
  (when-let [user-key (trimmed (:userKey values))]
    (when-let [app-token (trimmed (:appToken values))]
      {:user-key user-key :app-token app-token :device (trimmed (:device values))})))

(defn- getenv [name] (System/getenv name))

(defn- env-config []
  (credentials {:userKey (getenv "PUSHOVER_USER_KEY")
                :appToken (getenv "PUSHOVER_APP_TOKEN")
                :device (getenv "PUSHOVER_DEVICE")}))

(defn- load-config [api]
  (or (env-config)
      (let [path (config-path api)]
        (when (fs/exists? path)
          (try
            (let [parsed (json/parse-string (slurp path) true)]
              (credentials (or (:pushover parsed) parsed)))
            (catch Exception _
              ;; Parser errors can contain the credential-bearing source text.
              (ext/ui-notify api (str "nofity-pushover: failed to read " path
                                      "; check file permissions and JSON syntax.") :error)
              nil))))))

(defn- missing-credentials [api]
  (str "Pushover credentials are not configured. Set PUSHOVER_USER_KEY + PUSHOVER_APP_TOKEN or create "
       (config-path api) "."))

(defn- redact [text config]
  (reduce (fn [result secret]
            (let [encoded (oauth/url-encode secret)]
              (-> result
                  (str/replace secret "<redacted>")
                  (str/replace encoded "%3Credacted%3E")
                  (str/replace (str/replace encoded "+" "%20") "%3Credacted%3E"))))
          (str text)
          (sort-by count > (remove str/blank? [(:user-key config) (:app-token config)]))))

(defn- host-name [api]
  (or (trimmed (getenv "HOSTNAME"))
      (trimmed (getenv "COMPUTERNAME"))
      (trimmed (getenv "HOST"))
      ;; Hostname is best-effort metadata, not a reason to fail a sent alert.
      (try
        (let [result (ext/exec api "hostname" [] {:timeout-ms 1000})]
          (when (= 0 (:exit result)) (trimmed (:out result))))
        (catch Exception _ nil))
      "unknown host"))

(defn- priority-value [value]
  (if (contains? #{-2 -1 0 1} value) (str value) "0"))

(defn- limit-text [text limit]
  (subs text 0 (min limit (count text))))

(defn- throw-if-cancelled [signal]
  (when (and signal @signal)
    (throw (ex-info "Pushover notification cancelled." {:type :pushover-cancelled}))))

(defn- send-pushover [config {:keys [title message url urlTitle priority]} signal]
  (when (or (not (string? message)) (str/blank? message))
    (throw (ex-info "Notification message cannot be empty." {:type :pushover-invalid-message})))
  (throw-if-cancelled signal)
  (let [fields (cond-> {"token" (:app-token config)
                        "user" (:user-key config)
                        "title" (limit-text (or (not-empty title) "kmet needs a human decision") 250)
                        "message" (limit-text message 1024)
                        "priority" (priority-value priority)}
                 (:device config) (assoc "device" (:device config))
                 (seq url) (assoc "url" (limit-text url 512))
                 (seq urlTitle) (assoc "url_title" (limit-text urlTitle 100)))
        body (str/join "&" (map (fn [[key value]]
                                  (str (oauth/url-encode key) "=" (oauth/url-encode value))) fields))
        response (try
                   (http/request {:url endpoint :method :post :body body
                                  :headers {"Content-Type" "application/x-www-form-urlencoded"}
                                  :throw? false :timeout-ms 15000 :signal signal
                                  :follow-redirects false})
                   (catch Exception e
                     (throw (ex-info (str "Pushover request failed: " (redact (ex-message e) config))
                                     {:type :pushover-transport-error}))))
        status (:status response)]
    (throw-if-cancelled signal)
    (when-not (and (integer? status) (<= 200 status 299))
      (let [response-body (str/trim (or (:body response) ""))]
        (throw (ex-info (str "Pushover failed: " status
                             (when (seq response-body) (str " - " (redact response-body config))))
                        {:type :pushover-http-error :status status}))))
    status))

(defn- context-signal [ctx]
  ;; Command contexts expose a boolean accessor, not the tool's abort atom.
  (when-let [cancelled? (:signal ctx)]
    (atom (boolean (cancelled?)))))

(defn init
  "Register notify_human and a manual test command; never send automatic alerts."
  [api]
  (let [config (atom nil)
        get-config (fn [] (or @config (reset! config (load-config api))))]
    (ext/on-event api :session-start
                  (fn [_ _]
                    (reset! config (load-config api))
                    (ext/ui-set-status api status-key
                                       (if @config "human notify: ready" "human notify: no creds"))
                    (when-not @config
                      (ext/ui-notify api (str "nofity-pushover: no credentials; set PUSHOVER_USER_KEY + PUSHOVER_APP_TOKEN or create "
                                              (config-path api) ".") :warning))))
    (ext/register-tool! api
                        {:name "notify_human"
                         :label "Notify human"
                         :description "Send a one-way Pushover notification to the human operator when work is blocked on a human decision, approval, credentials, access, or missing external evidence. This only alerts the human; it does not wait for or collect a reply. Use sparingly and continue independent work when possible."
                         :prompt-snippet "Use notify_human to send a one-way Pushover alert for human decisions/access/approval blockers; do not use it for routine progress."
                         :parameters {:type "object"
                                      :properties {"message" {:type "string"
                                                              :description "Concise decision/access question or blocker summary for the human. Include project/task context and the exact action needed."}
                                                   "title" {:type "string" :description "Notification title. Defaults to kmet needs a human decision."}
                                                   "url" {:type "string" :description "Optional relevant URL, issue, PR, or local dashboard link."}
                                                   "urlTitle" {:type "string" :description "Short label for the optional URL."}
                                                   "priority" {:type "integer" :enum [-2 -1 0 1]
                                                               :description "Pushover priority: -2 silent, -1 quiet, 0 normal, 1 high. Defaults to 0."}}
                                      :required ["message"]}
                         :contextual? true
                         :execute (fn [params _on-update signal _ctx]
                                    (let [credentials (get-config)]
                                      (when-not credentials
                                        (throw (ex-info (missing-credentials api) {:type :pushover-missing-credentials})))
                                      (let [status (send-pushover credentials params signal)]
                                        (ext/ui-set-status api status-key (str "human notify: sent (" status ")"))
                                        {:content (str "Pushover notification sent (" status ") to "
                                                       (or (:device credentials) "default device(s)") " from " (host-name api) ".")
                                         :details {:status status :device (:device credentials)}})))})
    (ext/register-command! api
                           {:name "notify-human-test"
                            :description "Send a test one-way Pushover notification."
                            :handler (fn [ctx args]
                                       (try
                                         (let [credentials (get-config)]
                                           (when-not credentials
                                             (throw (ex-info (missing-credentials api) {:type :pushover-missing-credentials})))
                                           (let [status (send-pushover credentials
                                                                       {:title "kmet human notification test"
                                                                        :message (or (trimmed args) (str "Test from kmet on " (host-name api) "."))}
                                                                       (context-signal ctx))]
                                             (ext/ui-notify api (str "Pushover test sent (" status ").") :info)))
                                         (catch Exception e
                                           (ext/ui-notify api (str "Pushover test failed: " (ex-message e)) :error))))})))

(defn shutdown
  "Remove this extension's footer status; registered capabilities unload automatically."
  [api]
  (ext/ui-set-status api status-key nil))
