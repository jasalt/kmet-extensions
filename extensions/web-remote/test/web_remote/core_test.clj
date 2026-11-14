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
    (cmd ctx "start 70000")
    (is (= ["web-remote: warning" core/usage] (last (chat))))
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

(deftest server-end-to-end
  (let [{:keys [api calls st ctx branch leaf running? aborted]} (fixture)
        port (do (core/start! api st ctx 0) (get-in @st [:server :port]))
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
