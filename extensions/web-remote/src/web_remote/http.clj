(ns web-remote.http
  "A minimal HTTP/1.1 server on a loopback ServerSocket: one daemon thread per
  connection, bounded request size, a read timeout, `Connection: close` for
  plain replies and long-lived SSE streams. All Java interop of the extension
  lives here."
  (:require [clojure.string :as str]
            [kmet.libs.concurrent :as concurrent]))

(def limits
  {:line 8192 :headers 64 :body (* 256 1024) :read-timeout-ms 10000 :connections 32})

;; ─── request parsing ────────────────────────────────────────────────────────

(defn parse-nat
  "A non-negative integer of at most 9 digits from S, else nil."
  [s]
  (when (and (string? s) (re-matches #"\d{1,9}" s)) (Long/parseLong s)))

(defn- read-line!
  "One CRLF/LF-terminated ISO-8859-1 line, at most MAX bytes; nil at EOF."
  [in max]
  (let [sb (StringBuilder.)]
    (loop []
      (let [b (.read in)]
        (cond
          (neg? b) (when (pos? (.length sb)) (str sb))
          (= b 10) (let [s (str sb)] (if (str/ends-with? s "\r") (subs s 0 (dec (count s))) s))
          (>= (.length sb) max) (throw (ex-info "line too long" {:status 431}))
          :else (do (.append sb (char b)) (recur)))))))

(defn- read-body! [in n]
  (let [buf (byte-array n)]
    (loop [off 0]
      (if (< off n)
        (let [r (.read in buf off (- n off))]
          (if (neg? r)
            (throw (ex-info "truncated body" {:status 400}))
            (recur (+ off r))))
        (String. buf "UTF-8")))))

(defn url-decode [s]
  (java.net.URLDecoder/decode (str s) "UTF-8"))

(defn parse-query
  "\"a=1&b=x%20y\" → {\"a\" \"1\" \"b\" \"x y\"}."
  [q]
  (into {}
        (for [kv (str/split (or q "") #"&")
              :when (seq kv)
              :let [[k v] (str/split kv #"=" 2)]]
          [(url-decode k) (url-decode (or v ""))])))

(defn parse-cookies [header]
  (into {}
        (for [kv (str/split (or header "") #";\s*")
              :let [[k v] (str/split kv #"=" 2)]
              :when (and (seq k) v)]
          [(str/trim k) (str/trim v)])))

(defn read-request
  "Parse one request from IN (an InputStream). Throws ex-info with :status on
  a malformed or oversized request; nil when the peer sent nothing."
  [in]
  (when-let [line (read-line! in (:line limits))]
    (let [[method target version] (str/split line #" ")
          _ (when-not (and method target (some-> version (str/starts-with? "HTTP/1.")))
              (throw (ex-info "bad request line" {:status 400})))
          headers (loop [hs {} n 0]
                    (let [l (read-line! in (:line limits))]
                      (cond
                        (or (nil? l) (= "" l)) hs
                        (>= n (:headers limits)) (throw (ex-info "too many headers" {:status 431}))
                        :else (let [[k v] (str/split l #":" 2)]
                                (recur (assoc hs (str/lower-case (str/trim k)) (str/trim (or v "")))
                                       (inc n))))))
          raw-length (get headers "content-length" "0")
          length (parse-nat raw-length)
          _ (when (and (nil? length) (re-matches #"\d+" raw-length))
              (throw (ex-info "body too large" {:status 413})))
          _ (when (nil? length) (throw (ex-info "bad length" {:status 400})))
          _ (when (> length (:body limits)) (throw (ex-info "body too large" {:status 413})))
          _ (when (get headers "transfer-encoding") (throw (ex-info "chunked unsupported" {:status 411})))
          [path query] (str/split target #"\?" 2)]
      {:method (str/upper-case method)
       :path path
       :query (parse-query query)
       :headers headers
       :cookies (parse-cookies (get headers "cookie"))
       :body (if (pos? length) (read-body! in length) "")})))

;; ─── responses ─────────────────────────────────────────────────────────────

(def ^:private reasons
  {200 "OK" 204 "No Content" 303 "See Other" 400 "Bad Request" 403 "Forbidden"
   404 "Not Found" 405 "Method Not Allowed" 408 "Request Timeout" 411 "Length Required"
   413 "Payload Too Large" 431 "Request Header Fields Too Large" 500 "Internal Server Error"
   503 "Service Unavailable"})

(def ^:private base-headers
  {"Cache-Control" "no-store"
   "X-Content-Type-Options" "nosniff"
   "X-Frame-Options" "DENY"
   "Referrer-Policy" "no-referrer"})

(defn- head-bytes [status headers]
  (.getBytes (str "HTTP/1.1 " status " " (get reasons status "Status") "\r\n"
                  (apply str (for [[k v] (merge base-headers headers)] (str k ": " v "\r\n")))
                  "\r\n")
             "UTF-8"))

(defn write-response!
  "Write a complete RESPONSE {:status :headers :body} and close semantics."
  [out {:keys [status headers body]}]
  (let [body (cond (bytes? body) body
                   (nil? body) (byte-array 0)
                   :else (.getBytes (str body) "UTF-8"))]
    (.write out (head-bytes status (merge headers {"Content-Length" (str (alength body))
                                                   "Connection" "close"})))
    (.write out body)
    (.flush out)))

(defn sse-event
  "Format an SSE event: EVENT name, DATA lines (each prefixed `data: `)."
  [event data-lines]
  (str "event: " event "\n"
       (apply str (for [l data-lines] (str "data: " l "\n")))
       "\n"))

(defn patch-elements
  "A datastar-patch-elements event morphing HTML (top-level elements with ids)."
  [html]
  (sse-event "datastar-patch-elements"
             ;; a lone CR would end an SSE line early
             (map #(str "elements " %) (str/split-lines (str/replace html "\r" "")))))

(defn patch-signals [json]
  (sse-event "datastar-patch-signals" [(str "signals " json)]))

(defn sse-response
  "A one-shot SSE body (for Datastar action replies): EVENTS concatenated."
  [& events]
  {:status 200 :headers {"Content-Type" "text/event-stream; charset=utf-8"} :body (apply str events)})

(defn open-sse!
  "Send SSE headers on OUT and answer a `send!` fn writing raw event text;
  `send!` throws when the peer is gone."
  [socket out]
  (.setSoTimeout socket 0)
  (.write out (head-bytes 200 {"Content-Type" "text/event-stream; charset=utf-8"
                               "Connection" "keep-alive"}))
  (.flush out)
  (fn send! [text]
    (.write out (.getBytes (str text) "UTF-8"))
    (.flush out)))

;; ─── server ────────────────────────────────────────────────────────────────

(defn- serve-connection!
  "HANDLER answers a response map, or {:sse f} where (f send!) streams until it
  returns or send! throws."
  [socket handler]
  (with-open [socket socket]
    (.setSoTimeout socket (int (:read-timeout-ms limits)))
    (let [in (java.io.BufferedInputStream. (.getInputStream socket))
          out (java.io.BufferedOutputStream. (.getOutputStream socket))]
      (try
        (when-let [request (read-request in)]
          (let [response (try (handler request)
                              (catch Exception e
                                (or (some-> (ex-data e) :status (#(hash-map :status % :body (ex-message e))))
                                    {:status 500 :body "internal error"})))]
            (if-let [stream (:sse response)]
              (stream (open-sse! socket out))
              (write-response! out response))))
        (catch java.net.SocketTimeoutException _
          (try (write-response! out {:status 408 :body "timeout"}) (catch Exception _ nil)))
        (catch clojure.lang.ExceptionInfo e
          (when-let [status (:status (ex-data e))]
            (try (write-response! out {:status status :body (ex-message e)}) (catch Exception _ nil))))
        (catch java.io.IOException _ nil)))))

(defn loopback-literal? [a]
  (boolean (or (str/starts-with? a "127.") (#{"::1" "0:0:0:0:0:0:0:1"} a))))

(defn wildcard-literal? [a]
  (contains? #{"0.0.0.0" "::" "0:0:0:0:0:0:0:0"} a))

(defn- link-local-literal? [a]
  (or (str/starts-with? a "169.254.") (str/starts-with? (str/lower-case a) "fe80:")))

(defn- literal [addr]
  (str/replace (.getHostAddress addr) #"%.*$" ""))

(defn start!
  "Listen on HOST:PORT (HOST defaults to 127.0.0.1; PORT 0 = ephemeral) and
  serve each connection with HANDLER on its own daemon thread. Answers
  {:host :port :loopback? :wildcard? :socket :connections}; :host is the
  bound address literal."
  [{:keys [host port handler]}]
  (let [addr (java.net.InetAddress/getByName (or host "127.0.0.1"))
        ss (java.net.ServerSocket. (int (or port 0)) 50 addr)
        connections (atom #{})]
    (concurrent/spawn
     (fn []
       (loop []
         (when-let [socket (try (.accept ss) (catch java.io.IOException _ nil))]
           (if (>= (count @connections) (:connections limits))
             (with-open [socket socket]
               (try (write-response! (.getOutputStream socket) {:status 503 :body "busy"})
                    (catch Exception _ nil)))
             (do (swap! connections conj socket)
                 (concurrent/spawn
                  (fn []
                    (try (serve-connection! socket handler)
                         (finally (swap! connections disj socket)))))))
           (recur)))))
    {:host (literal addr)
     :port (.getLocalPort ss)
     :loopback? (loopback-literal? (literal addr))
     :wildcard? (wildcard-literal? (literal addr))
     :socket ss
     :connections connections}))

(defn interface-addresses
  "Best-effort non-loopback address literals of this host (IPv4 first): the
  default route's source address (a UDP connect sends no packet) plus the
  local hostname's addresses. Babashka has no java.net.NetworkInterface."
  []
  (let [route (try (with-open [s (java.net.DatagramSocket.)]
                     (.connect s (java.net.InetAddress/getByName "192.0.2.1") 9)
                     [(literal (.getLocalAddress s))])
                   (catch Exception _ nil))
        local (try (mapv literal (java.net.InetAddress/getAllByName
                                  (.getHostName (java.net.InetAddress/getLocalHost))))
                   (catch Exception _ nil))]
    (->> (concat route local)
         (remove #(or (loopback-literal? %) (wildcard-literal? %) (link-local-literal? %)))
         (sort-by #(if (str/includes? % ":") 1 0))
         distinct
         vec)))

(defn stop!
  "Close the listening socket and every open connection (ends SSE streams)."
  [{:keys [socket connections]}]
  (try (.close socket) (catch Exception _ nil))
  (doseq [c @connections] (try (.close c) (catch Exception _ nil))))

(defn random-token []
  (let [bytes (byte-array 24)]
    (.nextBytes (java.security.SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) bytes)))

(defn constant-time= [a b]
  (and (string? a) (string? b)
       (java.security.MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8"))))
