(ns codex-usage.core
  "Codex account meter ported from the pinned Pi extension."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.crypto :as crypto]
            [kmet.libs.json :as json]
            [kmet.libs.http :as http]
            [kmet.tui.theme :as theme]))

(def ^:private usage-url "https://chatgpt.com/backend-api/wham/usage")
(def ^:private credits-url "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits")
(def ^:private runtime (atom nil))

(defn- fail [message]
  (throw (ex-info message {:type :codex-usage-error})))

(defn- valid-window [w]
  (when (every? #(and (number? %) (<= -1.7976931348623157E308 % 1.7976931348623157E308))
                (map w [:used_percent :limit_window_seconds :reset_at]))
    w))

(defn parse-status
  "Accept finite windows and identify a weekly window within five percent."
  ([payload] (parse-status payload (:account payload)))
  ([payload account]
   (let [primary (some-> payload :rate_limit :primary_window valid-window)
         secondary (some-> payload :rate_limit :secondary_window valid-window)
         weekly (some #(when (and % (<= (max (- (:limit_window_seconds %) 604800) (- 604800 (:limit_window_seconds %))) 30240)) %)
                      [primary secondary])]
     (when-not (or primary weekly)
       (fail "ChatGPT did not return any Codex usage windows"))
     {:account account :primary primary :weekly weekly})))

(defn- percent-left [w] (- 100 (max 0 (min 100 (:used_percent w)))))
(defn- percent [n] (str (if (== n (long n)) (long n) (format "%.1f" (double n))) "%"))
(defn- duration-label [w]
  (let [hours (/ (:limit_window_seconds w) 3600)]
    (if (== hours (long hours)) (str (long hours) "h") "rate")))

(defn- reset-time [w]
  (let [date (-> (java.time.Instant/ofEpochSecond (long (:reset_at w)))
                 (.atZone (java.time.ZoneId/systemDefault)))
        pattern (if (< (:limit_window_seconds w) 86400) "HH:mm" "HH:mm 'on' d MMM")]
    (.format date (java.time.format.DateTimeFormatter/ofPattern pattern))))

(defn- windows [{:keys [primary weekly]}]
  (cond-> []
    primary (conj [(duration-label primary) primary])
    (and weekly (not (identical? weekly primary))) (conj ["w" weekly])))

(defn format-status-line
  "Remaining percentages and local reset times, matching the Pi footer."
  [status]
  (str/join "  " (map (fn [[label w]]
                        (str (percent (percent-left w)) "/" label " (resets " (reset-time w) ")"))
                      (windows status))))

(defn format-status-card
  "Detailed account information and twenty-cell remaining-quota bars."
  [{:keys [account] :as status}]
  (str/join
   "\n"
   (concat ["ChatGPT Codex status"
            "Visit https://chatgpt.com/codex/settings/usage for up-to-date information on rate limits and credits."]
           (when (:email account)
             [(str "Account:       " (:email account)
                   (when (:plan account) (str " (" (str/capitalize (:plan account)) ")")))])
           (map (fn [[label w]]
                  (let [left (percent-left w)
                        filled (long (Math/round (double (/ (* left 20) 100))))]
                    (str (if (= label "w") "Weekly limit: " (str label " limit:      "))
                         "[" (apply str (repeat filled "█")) (apply str (repeat (- 20 filled) "░"))
                         "] " (percent left) " left (resets " (reset-time w) ")")))
                (windows status)))))

(defn resolve-connection
  "Use kmet's current resolved model auth, including configured adapter headers."
  [api ctx]
  (let [model (:model ctx)]
    (when-not model (fail "No model selected"))
    (let [auth ((:get-api-key-and-headers (ext/models api)) model)
          token (:api-key auth)]
      (when-not (and (:ok auth) (seq token))
        (fail (or (:error auth) (str "No authentication available for " (:provider model)))))
      (if (= "openai-codex" (name (:provider model)))
        (let [claims (try
                       (let [encoded (second (str/split token #"\."))]
                         (when-not encoded (fail "Invalid Codex access token"))
                         (json/parse-string (String. (crypto/base64url-decode encoded) "UTF-8")))
                       (catch Exception _ (fail "Could not decode Codex access token")))
              account (get claims "https://api.openai.com/auth")]
          (when-not (seq (get account "chatgpt_account_id"))
            (fail "Codex access token has no ChatGPT account ID"))
          {:native? true
           :account {:email (get claims "email") :plan (get account "chatgpt_plan_type")}
           :headers {"Authorization" (str "Bearer " token)
                     "ChatGPT-Account-Id" (get account "chatgpt_account_id") "originator" "pi"}})
        (let [base (:base-url model)]
          (when-not (seq base) (fail "Selected provider has no base URL"))
          {:native? false :base-url (str/replace base #"/$" "")
           :headers (assoc (:headers auth) "Authorization" (str "Bearer " token))})))))

(defn- request-json [connection url & [body]]
  (let [response (http/request
                  (cond-> {:url url :method (if body :post :get) :timeout-ms 15000
                           :throw? false :headers (:headers connection)}
                    body (assoc :body (json/generate-string body)
                                :headers (assoc (:headers connection) "Content-Type" "application/json"))))]
    (when-not (<= 200 (:status response) 299)
      (fail (str "Codex request failed (HTTP " (:status response) ")"
                 (when-not (str/blank? (:body response)) (str ": " (str/trim (:body response)))))))
    (json/parse-string (:body response) true)))

(defn fetch-status
  "Fetch native WHAM usage or the selected adapter's /codex/usage endpoint."
  [api ctx]
  (let [c (resolve-connection api ctx)
        payload (request-json c (if (:native? c) usage-url (str (:base-url c) "/codex/usage")))]
    (if (:native? c) (parse-status payload (:account c)) (parse-status payload))))

(defn- credit-time [s]
  (try
    (str (-> (java.time.Instant/parse s) str (subs 0 16) (str/replace "T" " ")) " UTC")
    (catch Exception _ s)))

(defn- command-output [api ctx label message & [type]]
  (if (= :interactive (:mode ctx))
    (ext/ui-chat-info api (str label (when (= :error type) " error")) message)
    (ext/ui-notify api message (or type :info))))

(defn- reset-command [api ctx args refresh]
  (try
    (let [id (str/trim (or args ""))
          c (resolve-connection api ctx)]
      (when (re-find #"\s" id) (fail "Usage: /codex-reset [reset-id]"))
      (if (str/blank? id)
        (let [payload (request-json c (if (:native? c) credits-url (str (:base-url c) "/codex/resets")))
              credits (sort-by #(or (:expires_at %) "9999") (:credits payload))]
          (command-output api ctx "Codex resets"
                          (if (empty? credits) "No banked rate-limit reset credits."
                              (str/join "\n" (concat ["Banked Codex rate-limit resets:"]
                                                     (map #(str (:id %) "  " (:status %) "  granted " (credit-time (:granted_at %))
                                                                "  expires " (if (:expires_at %) (credit-time (:expires_at %)) "never/unknown")) credits)
                                                     ["Activate one with /codex-reset <reset-id>."])))))
        (let [payload (request-json c (if (:native? c) (str credits-url "/consume") (str (:base-url c) "/codex/reset"))
                                    (cond-> {:credit_id id} (:native? c) (assoc :redeem_request_id (str (java.util.UUID/randomUUID)))))
              result (or (not-empty (:result payload)) (not-empty (:status payload)) "reset")]
          (when-not (contains? #{"reset" "already_redeemed" "nothing_to_reset" "no_credit"} result)
            (fail (str "Activate Codex reset returned unexpected result: " result)))
          (command-output api ctx "Codex resets"
                          (str "Reset " id (if (= result "reset") " activated" (str " result: " result))
                               " (" (or (:rate_limit_windows_reset payload) 0) " rate-limit windows reset)."))
          (refresh ctx false))))
    (catch Exception e (command-output api ctx "Codex resets" (ex-message e) :error))))

(defn shutdown
  "Invalidate in-flight requests and wake the daemon immediately on unload."
  [api]
  (when-let [st @runtime]
    (locking st
      (swap! st assoc :stopped? true :context nil)
      (swap! st update :generation inc))
    (async/close! (:stop @st)))
  (reset! runtime nil)
  (ext/ui-set-status api "codex-usage" nil))

(defn init
  "Register commands and event refreshes without replacing any builtin UI."
  [api]
  (let [st (atom {:generation 0 :context nil :stopped? false :stop (async/chan)})
        reserve (fn [ctx]
                  (locking st
                    (when-not (:stopped? @st)
                      (:generation (swap! st #(-> % (assoc :context ctx) (update :generation inc)))))))
        refresh (fn [ctx report? & [reserved]]
                  (let [g (or reserved (reserve ctx))]
                    (when g
                      (try
                        (let [status (fetch-status api ctx)]
                          (locking st
                            (when (and (= g (:generation @st)) (not (:stopped? @st)))
                              (ext/ui-set-status api "codex-usage"
                                                 (theme/fg (theme/get-current-theme) :dim (format-status-line status)))
                              status)))
                        (catch Exception e
                          (locking st
                            (when (and (= g (:generation @st)) (not (:stopped? @st)))
                              (ext/ui-set-status api "codex-usage" nil)
                              (when report? (command-output api ctx "Codex usage" (ex-message e) :error)))
                            nil))))))
        background (fn [ctx]
                     (when-let [g (reserve ctx)]
                       (concurrent/spawn #(refresh ctx false g))))]
    (reset! runtime st)
    (ext/register-command! api {:name "codex-usage" :description "Show ChatGPT Codex account and rate-limit usage"
                                :handler (fn [ctx _] (when-let [s (refresh ctx true)]
                                                       (command-output api ctx "Codex usage" (format-status-card s))))})
    (ext/register-command! api {:name "codex-reset" :description "List banked Codex resets or activate an exact reset ID"
                                :handler (fn [ctx args] (reset-command api ctx args refresh))})
    (doseq [event [:session-start :agent-settled :model-select]]
      (ext/on-event api event
                    (fn [_ ctx]
                      (locking st
                        (swap! st #(-> % (assoc :context ctx) (update :generation inc)))
                        (when (= event :model-select) (ext/ui-set-status api "codex-usage" nil))
                        (background ctx)))))
    (ext/on-event api :session-shutdown (fn [_ _] (shutdown api)))
    (concurrent/spawn
     (fn []
       (loop []
         (let [[_ channel] (async/alts!! [(:stop @st) (async/timeout 300000)])]
           (when (and (not= channel (:stop @st)) (not (:stopped? @st)))
             (when-let [[ctx g] (locking st
                                  (when-let [ctx (:context @st)]
                                    (when-let [g (reserve ctx)] [ctx g])))]
               (refresh ctx false g))
             (recur))))))))
