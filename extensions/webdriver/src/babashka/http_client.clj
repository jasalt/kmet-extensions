(ns babashka.http-client
  "Shim: the slice of babashka.http-client Etaoin uses, over kmet.libs.http.
  WebDriver is a local control channel, so the proxy is bypassed."
  (:require [kmet.libs.http :as http]))

(def default-timeout-ms 60000)

(defn client [opts] opts)

(defn request [{:keys [uri method body headers client]}]
  (http/request {:url uri
                 :method method
                 :body body
                 :headers (merge {"Content-Type" "application/json"
                                  "Accept" "application/json"}
                                 headers)
                 :timeout (or (get-in client [:request :timeout]) default-timeout-ms)
                 :throw? false
                 :proxy :none}))
