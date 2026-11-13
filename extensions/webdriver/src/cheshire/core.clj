(ns cheshire.core
  "Shim: the slice of Cheshire Etaoin uses, over kmet.libs.json."
  (:require [kmet.libs.json :as json]))

(defn parse-string
  ([s] (json/parse-string s))
  ([s keywords?] (json/parse-string s (boolean keywords?))))

(defn generate-string
  ([x] (json/generate-string x))
  ([x _opts] (json/generate-string x)))

(defn generate-stream
  ([x ^java.io.Writer w] (.write w ^String (json/generate-string x)) w)
  ([x w _opts] (generate-stream x w)))
