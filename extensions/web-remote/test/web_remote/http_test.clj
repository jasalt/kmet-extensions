(ns web-remote.http-test
  (:require [clojure.test :refer [deftest is testing]]
            [web-remote.http :as http]))

(defn- stream [s] (java.io.ByteArrayInputStream. (.getBytes s "UTF-8")))

;; ─── http ──────────────────────────────────────────────────────────────────

(deftest request-parsing
  (let [r (http/read-request (stream (str "POST /prompt?a=1&b=x%20y HTTP/1.1\r\nHost: h\r\n"
                                          "Cookie: k=v; other=w\r\nContent-Length: 2\r\n\r\n{}")))]
    (is (= ["POST" "/prompt" {"a" "1" "b" "x y"} "{}"] ((juxt :method :path :query :body) r)))
    (is (= {"k" "v" "other" "w"} (:cookies r))))
  (is (nil? (http/read-request (stream ""))))
  (testing "limits"
    (doseq [[raw status] [["garbage\r\n\r\n" 400]
                          [(str "GET / HTTP/1.1\r\nContent-Length: 999999999\r\n\r\n") 413]
                          [(str "GET /" (apply str (repeat 9000 "a")) " HTTP/1.1\r\n\r\n") 431]
                          [(str "GET / HTTP/1.1\r\n" (apply str (repeat 70 "X: y\r\n")) "\r\n") 431]
                          ["POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n" 411]]]
      (is (= status (try (http/read-request (stream raw)) nil
                         (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))
          (subs raw 0 (min 30 (count raw)))))))

(deftest sse-format
  (is (= "event: datastar-patch-elements\ndata: elements <div id=\"a\">\ndata: elements x</div>\n\n"
         (http/patch-elements "<div id=\"a\">\r\nx</div>")))
  (is (= "event: datastar-patch-signals\ndata: signals {\"a\":1}\n\n" (http/patch-signals "{\"a\":1}"))))
