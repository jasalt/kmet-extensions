(ns web-remote.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.extension :as ext]
            [kmet.libs.json :as json]
            [web-remote.core :as core]))

;; ─── helpers ───────────────────────────────────────────────────────────────

(defn- raw-request
  "Send RAW to 127.0.0.1:PORT and answer the whole response text."
  [port raw]
  (with-open [s (java.net.Socket. "127.0.0.1" (int port))]
    (.setSoTimeout s 5000)
    (let [out (.getOutputStream s)]
      (.write out (.getBytes raw "UTF-8"))
      (.flush out)
      (slurp (.getInputStream s)))))

(defn- request [port {:keys [method path cookie origin body host]
                      :or {method "GET" host (str "127.0.0.1:" port)}}]
  (raw-request port (str method " " path " HTTP/1.1\r\nHost: " host "\r\n"
                         (when cookie (str "Cookie: " cookie "\r\n"))
                         (when origin (str "Origin: " origin "\r\n"))
                         (when body (str "Content-Type: application/json\r\nContent-Length: "
                                         (count (.getBytes body "UTF-8")) "\r\n"))
                         "\r\n" body)))

(defn- status-of [response] (parse-long (second (str/split response #" " 3))))

;; ─── core ──────────────────────────────────────────────────────────────────

(defn- fixture []
  (let [{:keys [api state]} (ext/create-nullable-api)
        branch (atom [{:role :user :content "hello"}])
        leaf (atom 1)
        running? (atom false)
        aborted (atom 0)
        api (-> api
                (assoc-in [:session :get-branch] (fn [] @branch))
                (assoc-in [:session :get-leaf-id] (fn [] @leaf))
                (assoc-in [:ui :chat-info]
                          (fn [label content] (swap! state update :ui-calls conj [:chat-info label content])))
                (assoc-in [:models :get-available]
                          (fn [] [{:provider :p :id "m1"} {:provider :p :id "m2"}])))
        ctx {:has-ui true :is-idle (fn [] (not @running?)) :has-pending-messages (fn [] false)
             :abort (fn [] (swap! aborted inc)) :model {:provider :p :id "m1"}}
        st (core/init api)]
    {:api api :calls state :st st :ctx ctx :branch branch :leaf leaf :running? running? :aborted aborted}))

(deftest command-and-registration
  (let [{:keys [api calls st ctx]} (fixture)
        cmd (get-in @calls [:commands "web-remote" :handler])
        chat #(keep (fn [[k label text]] (when (= :chat-info k) [label text])) (:ui-calls @calls))
        last-text #(second (last (chat)))]
    (is (contains? (:handlers @calls) :message-update))
    (cmd ctx "status")
    (is (str/includes? (last-text) "Not running"))
    (doseq [bad ["70000" "0.0.0.0:99999" "a:b:c" "host:x"]]
      (cmd ctx (str "start " bad))
      (is (= ["web-remote: warning" core/usage] (last (chat))) bad))
    (cmd ctx "start")
    (is (re-find #"http://127\.0\.0\.1:\d+/\?token=\S+" (last-text)))
    (is (some #(= [:set-status "web-remote"] (take 2 %)) (:ui-calls @calls)))
    (cmd ctx "url")
    (is (str/includes? (last-text) (core/url st)))
    (cmd ctx "stop")
    (is (nil? (:server @st)))
    (is (= ["web-remote" "Stopped."] (last (chat))))
    (is (not-any? #(= :notify (first %)) (:ui-calls @calls)) "chat notices, not flashes")
    (testing "headless falls back to a flash"
      (cmd (dissoc ctx :has-ui) "status")
      (is (= [:notify (str "Not running. " core/usage) :info]
             (last (:ui-calls @calls)))))
    (core/shutdown api)))

(deftest address-parsing
  (is (= {:host "127.0.0.1" :port 0} (core/parse-address "")))
  (is (= {:host "127.0.0.1" :port 8082} (core/parse-address "8082")))
  (is (= {:host "127.0.0.1" :port 8082} (core/parse-address ":8082")))
  (is (= {:host "0.0.0.0" :port 8082} (core/parse-address "0.0.0.0:8082")))
  (is (= {:host "localhost" :port 0} (core/parse-address "localhost")))
  (is (= {:host "::" :port 8082} (core/parse-address "[::]:8082")))
  (is (= {:host "::1" :port 0} (core/parse-address "[::1]")))
  (is (nil? (core/parse-address "0.0.0.0:65536")))
  (is (nil? (core/parse-address "::1")) "IPv6 needs brackets")
  (is (nil? (core/parse-address "h:")))) 

(deftest allowed-hosts
  (is (= #{"127.0.0.1:1" "localhost:1"}
         (core/allowed-hosts {:host "127.0.0.1" :requested "127.0.0.1" :port 1 :loopback? true})))
  (is (= #{"[::1]:1" "localhost:1"}
         (core/allowed-hosts {:host "::1" :requested "::1" :port 1 :loopback? true})))
  (is (= #{"10.0.0.2:1" "box.lan:1"}
         (core/allowed-hosts {:host "10.0.0.2" :requested "box.lan" :port 1})))
  (let [wild (core/allowed-hosts {:host "0.0.0.0" :requested "0.0.0.0" :port 1 :wildcard? true})]
    (is (every? wild ["127.0.0.1:1" "[::1]:1" "localhost:1"]))
    (is (not (wild "0.0.0.0:1")))
    (is (not (wild "evil.example:1")))))

(deftest wildcard-bind
  (let [{:keys [api calls st ctx]} (fixture)
        cmd (get-in @calls [:commands "web-remote" :handler])
        chat #(keep (fn [[k label text]] (when (= :chat-info k) [label text])) (:ui-calls @calls))]
    (try
      (cmd ctx "start 0.0.0.0:0")
      (let [{:keys [port wildcard? loopback?]} (:server @st)
            [[_ started] [warn-label warning]] (take-last 2 (chat))]
        (is (and wildcard? (not loopback?)))
        (is (str/starts-with? (core/url st) (str "http://127.0.0.1:" port "/?token=")))
        (is (str/includes? started (core/url st)))
        (is (= "web-remote: warning" warn-label))
        (is (str/includes? warning "beyond loopback"))
        (is (some #(= [:set-status "web-remote" (str "web 0.0.0.0:" port)] %) (:ui-calls @calls)))
        (is (= 303 (status-of (request port {:path (str "/?token=" (:token @st))}))))
        (is (= 403 (status-of (request port {:path (str "/?token=" (:token @st)) :host (str "0.0.0.0:" port)})))))
      (finally (core/stop! api st)))))

(deftest working-line
  (let [{:keys [api calls st ctx running?]} (fixture)
        fire! (fn [type & {:as e}]
                (doseq [h (get-in @calls [:handlers type])] (h (assoc e :type type) {})))
        line #(some-> (core/activity-data st) ((juxt :kind :attempt :reason)))]
    (core/start! api st ctx {:host "127.0.0.1" :port 0})
    (try
      (is (nil? (core/activity-data st)) "idle shows nothing")
      (reset! running? true)
      (fire! :agent-start)
      (is (= [:waiting-for-model nil nil] (line)))
      (fire! :message-update :message {:content "x"} :delta {:type :text})
      (is (= [:working nil nil] (line)))
      (fire! :turn-start)
      (is (= [:waiting-for-model nil nil] (line)))
      (fire! :status :status :executing)
      (is (= [:working nil nil] (line)))
      (testing "retry countdown wins over the phase, then gives it back"
        (fire! :auto-retry-start :attempt 1 :max-attempts 3 :delay-ms 5000 :error-message "boom")
        (is (= [:retry 1 nil] (line)))
        (is (<= 4 (:seconds (core/activity-data st)) 5))
        (fire! :auto-retry-end)
        (is (= [:working nil nil] (line))))
      (testing "compaction, and a stale retry end leaves it alone"
        (fire! :compaction-start :reason :overflow)
        (fire! :auto-retry-end)
        (is (= [:compaction nil :overflow] (line)))
        (fire! :compaction-end :reason :overflow :error-message "too big")
        (is (= [:working nil nil] (line)))
        (is (= "Compaction failed: too big" (:notice @st))))
      (testing "errors surface as a notice; settling clears the line"
        (fire! :auto-retry-start :attempt 3 :max-attempts 3 :delay-ms 1)
        (fire! :error :message "rate limited")
        (is (= "Error: rate limited" (:notice @st)))
        (reset! running? false)
        (fire! :agent-settled :outcome :error)
        (is (nil? (core/activity-data st))))
      (testing "rendered into the #activity region"
        (reset! running? true)
        (fire! :agent-start)
        (core/render! api st)
        (is (str/includes? (get-in @st [:regions "activity"]) "Waiting for model"))
        (reset! running? false)
        (fire! :agent-settled :outcome :completed)
        (core/render! api st)
        (is (= "<div id=\"activity\"></div>" (get-in @st [:regions "activity"]))))
      (finally (core/stop! api st)))))

(deftest shared-draft
  (let [{:keys [api st]} (fixture)
        editor (atom "TUI draft")
        writes (atom [])
        api (-> api
                (assoc-in [:ui :get-editor-text] #(deref editor))
                (assoc-in [:ui :set-editor-text] #(do (swap! writes conj %) (reset! editor %))))]
    (core/sync-draft! api st)
    (is (= "TUI draft" (:draft @st)))
    (is (core/write-draft! api st {:prompt "web draft" :draftbase "TUI draft"}))
    (is (= "web draft" @editor))
    (is (core/write-draft! api st {:prompt "web draft" :draftbase "TUI draft"}))
    (is (= ["web draft"] @writes) "identical text must not reset the cursor")
    (reset! editor "new TUI text")
    (is (false? (core/write-draft! api st {:prompt "stale browser" :draftbase "web draft"})))
    (is (= "new TUI text" @editor))
    (is (= "new TUI text" (:draft @st)))
    (core/render! api st)
    (is (= "new TUI text" (:draft (json/parse-string (:signals @st) true))))))

(deftest statusline-data
  (is (= {:input 70 :output 5 :cache-read 20 :cache-write 10 :cost 0.5}
         (core/usage-of {:prompt_tokens 100 :completion_tokens 5 :cost {:total 0.5}
                         :prompt_tokens_details {:cached_tokens 20 :cache_write_tokens 10}})) "OpenAI chat")
  (is (= {:input 7 :output 3 :cache-read 4 :cache-write 1 :cost 0.0}
         (core/usage-of {:input_tokens 7 :output_tokens 3 :cache_read_input_tokens 4
                         :cache_creation_input_tokens 1})) "Anthropic input excludes cache")
  (is (nil? (core/usage-of {:foo 1})))
  (let [{:keys [api st ctx branch leaf]} (fixture)]
    (swap! branch conj {:role :assistant :usage {:input 100 :output 10 :cache-read 300}}
           {:role :assistant :usage {:input 50 :output 5 :cache-read 50 :cost {:total 0.25}}})
    (core/start! api st (assoc ctx :cwd "/nonexistent-dir"
                               :model {:provider :p :id "m1" :reasoning true}
                               :get-context-usage (fn [] {:tokens 500 :context-window 1000 :percent 50}))
                 {:host "127.0.0.1" :port 0})
    (try
      (let [d (core/statusline-data api st)]
        (is (= {:input 150 :output 15 :cache-read 350 :cache-write 0 :cost 0.25} (:usage d)))
        (is (= 50.0 (:hit-rate d)) "the latest assistant message's rate")
        (is (= "p/m1" (:model d)))
        (is (:reasoning? d))
        (is (nil? (:branch d)) "no git repo, no branch"))
      (swap! leaf inc)
      (core/render! api st)
      (is (str/includes? (get-in @st [:regions "statusline"]) "50.0%/1.0k"))
      (finally (core/stop! api st)))))

(deftest server-end-to-end
  (let [{:keys [api calls st ctx branch leaf running? aborted]} (fixture)
        port (do (core/start! api st ctx {:host "127.0.0.1" :port 0}) (get-in @st [:server :port]))
        token (:token @st)
        cookie (str core/cookie-name "=" token)
        origin (str "http://127.0.0.1:" port)
        post (fn [path body] (request port {:method "POST" :path path :cookie cookie :origin origin :body body}))]
    (try
      (testing "auth"
        (is (= 403 (status-of (request port {:path "/"}))))
        (is (= 403 (status-of (request port {:path "/?token=wrong"}))))
        (is (= 403 (status-of (request port {:path "/" :cookie cookie :host "evil.example"}))) "DNS rebinding")
        (let [r (request port {:path (str "/?token=" token)})]
          (is (= 303 (status-of r)))
          (is (str/includes? r (str "Set-Cookie: " cookie "; Path=/; HttpOnly; SameSite=Strict"))))
        (is (= 403 (status-of (request port {:method "POST" :path "/abort" :cookie cookie :body "{}"}))) "no Origin")
        (is (= 403 (status-of (request port {:method "POST" :path "/abort" :cookie cookie :body "{}"
                                             :origin "http://evil.example"})))))
      (testing "page and assets"
        (let [r (request port {:path "/" :cookie cookie})]
          (is (= 200 (status-of r)))
          (is (str/includes? r "Content-Security-Policy"))
          (is (str/includes? r "data-bind:prompt"))
          (is (str/includes? r "p/m2")))
        (is (str/includes? (request port {:path "/datastar.js" :cookie cookie}) "Datastar v1"))
        (is (= 404 (status-of (request port {:path "/nope" :cookie cookie})))))
      (testing "actions"
        (let [r (post "/prompt" (json/generate-string {:prompt "  do it  " :deliver "steer"}))]
          (is (= 200 (status-of r)))
          (is (str/includes? r "datastar-patch-signals")))
        (reset! running? true)
        (post "/prompt" (json/generate-string {:prompt "more" :deliver "follow-up"}))
        (is (= [[:send-user-message "do it" {:expand-prompt-templates? true}]
                [:send-user-message "more" {:expand-prompt-templates? true :deliver-as :follow-up}]]
               (filterv #(= :send-user-message (first %)) (:ui-calls @calls))))
        (is (str/includes? (post "/prompt" "{\"prompt\":\"  \"}") "Empty message."))
        (post "/abort" "{}")
        (is (= 1 @aborted))
        (is (str/includes? (post "/new" "{}") "busy"))
        (reset! running? false)
        (post "/model" "{\"model\":\"p/m2\"}")
        (is (= [[:set-model {:provider :p :id "m2"}]] (:model-calls @calls)))
        (is (str/includes? (post "/model" "{\"model\":\"x/y\"}") "Unknown model"))
        (post "/thinking" "{\"thinking\":\"high\"}")
        (is (= :high (:thinking @calls)))
        (post "/name" "{\"name\":\"web\"}")
        (is (= "web" (:session-name @calls))))
      (testing "sse stream: full render, then only changed regions"
        (with-open [s (java.net.Socket. "127.0.0.1" (int port))]
          (.setSoTimeout s 5000)
          (let [out (.getOutputStream s)
                rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s) "UTF-8"))
                read-until (fn [pred]
                             (loop [acc []]
                               (let [line (.readLine rdr)]
                                 (if (or (nil? line) (pred line)) (conj acc line) (recur (conj acc line))))))]
            (.write out (.getBytes (str "GET /events HTTP/1.1\r\nHost: 127.0.0.1:" port "\r\nCookie: " cookie "\r\n\r\n")))
            (.flush out)
            (let [first-batch (str/join "\n" (read-until #(str/includes? (str %) "hello")))]
              (is (str/includes? first-batch "text/event-stream"))
              (is (str/includes? first-batch "hello")))
            (doseq [h (get-in @calls [:handlers :message-update])]
              (h {:type :message-update :message {:role :assistant :content [{:type :text :text "str<e>aming"}]}
                  :delta {:type :text :content "x"}} {}))
            (is (some #(str/includes? (str %) "str&lt;e&gt;aming") (read-until #(str/includes? (str %) "aming"))))
            (swap! branch conj {:role :assistant :content [{:type :text :text "final answer"}]})
            (swap! leaf inc)
            (is (some #(str/includes? (str %) "final answer") (read-until #(str/includes? (str %) "final answer")))))))
      (finally (core/stop! api st)))
    (is (thrown? java.net.ConnectException (java.net.Socket. "127.0.0.1" (int port))))))
