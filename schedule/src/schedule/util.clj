(ns schedule.util
  "Compatibility helpers for the extension's isolated SCI core."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn parse-integer
  "Parse a signed decimal integer, returning nil for malformed or overflowing input."
  [s]
  (when (and (string? s) (re-matches #"[+-]?\d+" s))
    (try
      (let [negative? (= \- (first s))
            digits (str/replace (str/replace s #"^[+-]" "") #"^0+(?=\d)" "")
            n (edn/read-string (str (when negative? "-") digits))]
        (when (integer? n) n))
      (catch Exception _ nil))))

(defn uuid
  "Generate an opaque ID using the class exposed by both host loaders."
  []
  (str (java.util.UUID/randomUUID)))
