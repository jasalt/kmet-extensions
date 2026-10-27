(ns nofity-pushover.core-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kmet.extension :as ext]
            [kmet.libs.http :as http]
            [kmet.libs.json :as json]
            [nofity-pushover.core :as notify]))

(def ^:private fixture (atom nil))

(use-fixtures :each
  (fn [test-fn]
    (fs/create-dirs "target")
    (let [dir (str (fs/create-temp-dir {:prefix "pushover-test-" :dir "target"}))
          {:keys [api state]} (ext/create-nullable-api {:agent-dir dir})]
      (reset! fixture {:api api :state state :dir dir})
      (with-redefs-fn {(ns-resolve 'nofity-pushover.core 'getenv) (constantly nil)}
        #(try
           (test-fn)
           (finally
             (notify/shutdown api)
             (fs/delete-tree dir)
             (reset! fixture nil)))))))

(defn- configure! [value]
  (spit (str (fs/path (:dir @fixture) "nofity-pushover.json")) (json/generate-string value)))

(defn- start-session! []
  ((first (get-in @(:state @fixture) [:handlers :session-start])) {} {:mode :interactive}))

(defn- execute! [params & [signal]]
  ((get-in @(:state @fixture) [:tools "notify_human" :execute]) params nil signal {}))

(defn- test-command! [args & [ctx]]
  ((get-in @(:state @fixture) [:commands "notify-human-test" :handler]) (or ctx {}) args))

(defn- calls [] (:ui-calls @(:state @fixture)))
(defn- notices [] (filter #(= :notify (first %)) (calls)))

(defn- form-fields [request]
  (into {} (map (fn [entry]
                  (let [[key value] (str/split entry #"=" 2)]
                    [(java.net.URLDecoder/decode key "UTF-8")
                     (java.net.URLDecoder/decode value "UTF-8")]))
                (str/split (:body request) #"&"))))

(defn- error-from [f]
  (try (f) nil (catch Exception e e)))

(deftest registers-original-agent-entrypoint
  (notify/init (:api @fixture))
  (let [state @(:state @fixture)
        tool (get-in state [:tools "notify_human"])]
    (is (= #{"notify_human"} (set (keys (:tools state)))))
    (is (= #{"notify-human-test"} (set (keys (:commands state)))))
    (is (= #{:session-start} (set (keys (:handlers state)))))
    (is (= "Notify human" (:label tool)))
    (is (:contextual? tool))
    (is (= ["message"] (get-in tool [:parameters :required])))
    (is (= [-2 -1 0 1] (get-in tool [:parameters :properties "priority" :enum])))
    (is (= #{"message" "title" "url" "urlTitle" "priority"}
           (set (keys (get-in tool [:parameters :properties])))))
    (is (str/includes? (:prompt-snippet tool) "do not use it for routine progress")))
  (notify/shutdown (:api @fixture))
  (is (= [:set-status "nofity-pushover" nil] (last (calls)))))

(deftest environment-takes-precedence-without-automatic-sends
  (configure! {:pushover {:userKey "file-user" :appToken "file-token" :device "file-device"}})
  (let [requests (atom [])]
    (with-redefs-fn {(ns-resolve 'nofity-pushover.core 'getenv)
                     {"PUSHOVER_USER_KEY" " env-user " "PUSHOVER_APP_TOKEN" " env-token "
                      "PUSHOVER_DEVICE" " env-phone " "HOSTNAME" "test-host"}
                     #'http/request (fn [request] (swap! requests conj request) {:status 200 :body "{}"})}
      #(do
         (notify/init (:api @fixture))
         (start-session!)
         (is (= [[:set-status "nofity-pushover" "human notify: ready"]] (calls)))
         (is (empty? @requests) "session start never alerts the human")
         (let [result (execute! {:message "Approve the deployment?"})]
           (is (= {:status 200 :device "env-phone"} (:details result)))
           (is (str/includes? (:content result) "env-phone from test-host")))
         (is (= {"token" "env-token" "user" "env-user" "device" "env-phone"
                 "message" "Approve the deployment?" "title" "kmet needs a human decision" "priority" "0"}
                (form-fields (first @requests))))))))

(deftest json-formats-and-partial-environment
  (let [requests (atom [])]
    (with-redefs-fn {(ns-resolve 'nofity-pushover.core 'getenv)
                     {"PUSHOVER_USER_KEY" "partial-user" "PUSHOVER_DEVICE" "ignored"}
                     #'http/request (fn [request] (swap! requests conj request) {:status 200 :body "{}"})}
      #(doseq [value [{:pushover {:userKey " file-user " :appToken " file-token " :device " phone "}}
                      {:userKey " file-user " :appToken " file-token " :device " phone "}]]
         (configure! value)
         (notify/init (:api @fixture))
         (start-session!)
         (execute! {:message "Need access"})
         (let [fields (form-fields (last @requests))]
           (is (= "file-user" (get fields "user")))
           (is (= "file-token" (get fields "token")))
           (is (= "phone" (get fields "device"))))))))

(deftest missing-invalid-and-late-credentials
  (let [requests (atom [])]
    (with-redefs [http/request (fn [request] (swap! requests conj request) {:status 200 :body "{}"})]
      (notify/init (:api @fixture))
      (start-session!)
      (is (= [:set-status "nofity-pushover" "human notify: no creds"] (first (calls))))
      (is (= :warning (last (last (notices)))))
      (let [e (error-from #(execute! {:message "Need credentials"}))]
        (is (= :pushover-missing-credentials (:type (ex-data e))))
        (is (str/includes? (ex-message e) (str (fs/path (:dir @fixture) "nofity-pushover.json")))))
      (test-command! "")
      (is (= :error (last (last (notices)))))
      (configure! {:userKey "incomplete"})
      (start-session!)
      (is (= :pushover-missing-credentials
             (:type (ex-data (error-from #(execute! {:message "Need credentials"}))))))
      (spit (str (fs/path (:dir @fixture) "nofity-pushover.json")) "{private-config-secret-not-valid-json")
      (start-session!)
      (is (some #(and (= :notify (first %)) (= :error (last %))
                      (str/includes? (second %) "failed to read")) (calls)))
      (is (not (str/includes? (pr-str (calls)) "private-config-secret")))
      (is (empty? @requests))
      (testing "missing config is retried when credentials appear without reload"
        (configure! {:userKey "user" :appToken "token"})
        (let [result (execute! {:message "Now configured"})]
          (is (= {:status 200 :device nil} (:details result)))
          (is (str/includes? (:content result) "default device(s)")))
        (is (= 1 (count @requests)))))))

(deftest payload-encoding-limits-priority-and-abort
  (configure! {:userKey "u &+" :appToken "t/=+"})
  (let [requests (atom [])
        signal (atom false)]
    (with-redefs [http/request (fn [request] (swap! requests conj request) {:status 200 :body "{}"})]
      (notify/init (:api @fixture))
      (execute! {:title (apply str (repeat 260 "t"))
                 :message (str "決定 & +?\n" (apply str (repeat 1100 "m")))
                 :url (apply str (repeat 600 "u")) :urlTitle (apply str (repeat 110 "l"))
                 :priority -1} signal)
      (let [request (first @requests)
            fields (form-fields request)]
        (is (= "https://api.pushover.net/1/messages.json" (:url request)))
        (is (= :post (:method request)))
        (is (= "application/x-www-form-urlencoded" (get-in request [:headers "Content-Type"])))
        (is (= false (:throw? request)))
        (is (= false (:follow-redirects request)))
        (is (= 15000 (:timeout-ms request)))
        (is (identical? signal (:signal request)))
        (is (= "u &+" (get fields "user")))
        (is (= "t/=+" (get fields "token")))
        (is (= 250 (count (get fields "title"))))
        (is (= 1024 (count (get fields "message"))))
        (is (str/starts-with? (get fields "message") "決定 & +?\n"))
        (is (= 512 (count (get fields "url"))))
        (is (= 100 (count (get fields "url_title"))))
        (is (not (contains? fields "device"))))
      (doseq [priority [-2 -1 0 1 2 nil]]
        (execute! {:message "Need approval" :priority priority})
        (is (= (if (contains? #{-2 -1 0 1} priority) (str priority) "0")
               (get (form-fields (last @requests)) "priority"))))
      (let [before (count @requests)]
        (doseq [message [nil "" " \n\t"]]
          (is (= :pushover-invalid-message
                 (:type (ex-data (error-from #(execute! {:message message})))))))
        (reset! signal true)
        (is (= :pushover-cancelled
               (:type (ex-data (error-from #(execute! {:message "Cancelled"} signal))))))
        (is (= before (count @requests)))))))

(deftest http-and-transport-errors-redact-credentials
  (configure! {:userKey "abc def" :appToken "abc def/long"})
  (notify/init (:api @fixture))
  (let [leak "abc def/long abc def abc+def%2Flong abc%20def%2Flong abc+def abc%20def"]
    (doseq [send [(fn [_] {:status 400 :body (str "denied " leak)})
                  (fn [_] (throw (ex-info leak {:type :transport-error :body leak})))]]
      (with-redefs [http/request send]
        (let [e (error-from #(execute! {:message "Need approval"}))]
          (is (some? e))
          (is (str/includes? (ex-message e) "redacted"))
          (is (not (str/includes? (ex-message e) "abc")))
          (is (not (str/includes? (pr-str (ex-data e)) "abc"))))
        (test-command! "Test")
        (is (= :error (last (last (notices)))))
        (is (not (str/includes? (second (last (notices))) "abc")))))
    (is (not-any? #(and (= :set-status (first %)) (str/includes? (or (nth % 2) "") "sent")) (calls)))))

(deftest manual-test-message-and-session-config-refresh
  (configure! {:userKey "first-user" :appToken "token" :device "phone"})
  (let [requests (atom [])]
    (with-redefs-fn {(ns-resolve 'nofity-pushover.core 'getenv) {"COMPUTERNAME" "windows-host"}
                     #'http/request (fn [request] (swap! requests conj request) {:status 200 :body "{}"})}
      #(do
         (notify/init (:api @fixture))
         (start-session!)
         (test-command! "")
         (is (= "Test from kmet on windows-host." (get (form-fields (last @requests)) "message")))
         (is (= "kmet human notification test" (get (form-fields (last @requests)) "title")))
         (is (= [:notify "Pushover test sent (200)." :info] (last (notices))))
         (test-command! "  Custom test  ")
         (is (= "Custom test" (get (form-fields (last @requests)) "message")))
         (let [before (count @requests)]
           (test-command! "Abort" {:signal (constantly true)})
           (is (= before (count @requests)))
           (is (= :error (last (last (notices))))))
         (configure! {:userKey "second-user" :appToken "new-token"})
         (start-session!)
         (execute! {:message "New session"})
         (is (= "second-user" (get (form-fields (last @requests)) "user")))
         (is (= [:set-status "nofity-pushover" "human notify: sent (200)"] (last (calls))))))))
