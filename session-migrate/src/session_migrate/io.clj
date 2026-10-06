;; Copyright (c) 2026 Jarkko Saltiola; SPDX-License-Identifier: MIT
(ns session-migrate.io
  "Bounded, strict UTF-8 source reads and portable integrity helpers."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.libs.aws-sigv4 :as hash]
            [kmet.libs.json :as json]))

(def max-bytes (* 256 1024 1024))
(def max-line-bytes (* 32 1024 1024))
(def max-json-depth 256)

(defn fail!
  "Throw a content-free migration error. Never attach raw source data."
  [message]
  (throw (ex-info message {:type :session-migrate-error})))

(defn check-cancel!
  "Check a caller/lifecycle cancellation predicate between bounded steps."
  [cancelled?]
  (when (cancelled?)
    (throw (ex-info "Session migration cancelled" {:type :session-migrate-cancelled}))))

(defn sha256
  "SHA-256 of an already validated UTF-8 string, through the shared host library."
  [s]
  (hash/sha256-hex s))

(defn valid-utf8?
  "Reject overlong sequences, surrogate encodings, truncation and >U+10FFFF.
  Validate the original bytes before the host's permissive String decoder."
  ([bytes] (valid-utf8? bytes (constantly false)))
  ([bytes cancelled?]
   (let [n (alength bytes)
         byte-at #(bit-and 255 (aget bytes %))
         continuation? #(<= 128 % 191)]
     (loop [i 0 checkpoint 0]
       (let [next-checkpoint (if (>= i checkpoint)
                               (do (check-cancel! cancelled?) (+ i 65536)) checkpoint)]
         (if (= i n)
           true
           (let [b (byte-at i)
                 width (cond (< b 128) 1 (<= 194 b 223) 2
                             (<= 224 b 239) 3 (<= 240 b 244) 4 :else 0)]
             (if (or (zero? width) (> (+ i width) n))
               false
               (let [tail (mapv byte-at (range (inc i) (+ i width)))
                     first-tail (first tail)]
                 (if (and (every? continuation? tail)
                          (or (not= b 224) (>= first-tail 160))
                          (or (not= b 237) (<= first-tail 159))
                          (or (not= b 240) (>= first-tail 144))
                          (or (not= b 244) (<= first-tail 143)))
                   (recur (+ i width) next-checkpoint)
                   false))))))))))

(defn- single-object? [line cancelled?]
  ;; kmet.libs.json/read-str accepts trailing data. Find the outer object's
  ;; closing brace outside strings, then require only JSON whitespace after it.
  (let [s (str/trim line)]
    (when (str/starts-with? s "{")
      (loop [i 0 depth 0 quoted? false escaped? false]
        (when (< i (count s))
          (when (zero? (bit-and i 4095)) (check-cancel! cancelled?))
          (let [c (nth s i)]
            (cond
              escaped? (recur (inc i) depth quoted? false)
              (and quoted? (= c \\)) (recur (inc i) depth true true)
              (= c \") (recur (inc i) depth (not quoted?) false)
              quoted? (recur (inc i) depth true false)
              (contains? #{\{ \[} c)
              (do (when (>= depth max-json-depth) (fail! "Source JSON nesting exceeds 256 levels"))
                  (recur (inc i) (inc depth) false false))
              (contains? #{\} \]} c)
              (if (= depth 1)
                (boolean (re-matches #"[ \t\r\n]*" (subs s (inc i))))
                (recur (inc i) (dec depth) false false))
              :else (recur (inc i) depth false false))))))))

(defn parse-records
  "Parse LF-delimited JSON objects, rejecting trailing JSON and oversized lines.
  Keys stay strings so arbitrary tool argument keys survive without rewriting."
  [text cancelled?]
  (vec
   (keep-indexed
    (fn [i line]
      (check-cancel! cancelled?)
      (when (> (alength (.getBytes line "UTF-8")) max-line-bytes)
        (fail! "Source record exceeds 32 MiB limit"))
      (when-not (str/blank? line)
        (try
          (when-not (single-object? line cancelled?) (fail! "Trailing JSON data"))
          (let [row (json/parse-string line)]
            (when-not (map? row) (fail! "Source record must be a JSON object"))
            row)
          (catch Exception e
            (if (= :session-migrate-cancelled (:type (ex-data e))) (throw e)
                (fail! (str "Invalid JSON object at line " (inc i))))))))
    (str/split text #"\n" -1))))

(defn- bounded-bytes [path cancelled?]
  ;; Bound the actual stream too: a stat-only bound would allow a growing or
  ;; replaced file to allocate unbounded memory before the post-read check.
  (with-open [input (io/input-stream path)
              output (java.io.ByteArrayOutputStream.)]
    (let [buffer (byte-array 65536)]
      (loop [total 0]
        (check-cancel! cancelled?)
        (let [n (.read input buffer)]
          (if (neg? n)
            (.toByteArray output)
            (let [next-total (+ total n)]
              (when (> next-total max-bytes) (fail! "Source exceeds 256 MiB limit"))
              (.write output buffer 0 n)
              (recur next-total))))))))

(defn read-source
  "Read and hash one regular source file. No shell, credentials or network."
  [path cancelled?]
  (check-cancel! cancelled?)
  (when-not (and (fs/regular-file? path) (<= (fs/size path) max-bytes))
    (fail! "Source must be a regular file of at most 256 MiB"))
  (let [bytes (bounded-bytes path cancelled?)]
    (check-cancel! cancelled?)
    (when (> (alength bytes) max-bytes) (fail! "Source exceeds 256 MiB limit"))
    (when-not (valid-utf8? bytes cancelled?) (fail! "Source is not valid UTF-8"))
    ;; String's UTF-8 constructor is shared on bb/Jolt; validation above makes
    ;; it lossless. Do not slurp again: hash and records must use one snapshot.
    (let [text (String. bytes "UTF-8")]
      {:sha256 (sha256 text) :records (parse-records text cancelled?)})))

(defn timestamp
  "Normalize a native RFC3339 timestamp, or use fallback and count invalid input."
  [value fallback omissions]
  (if (and (string? value) (seq value))
    (try (str (java.time.Instant/parse value))
         (catch Exception _ (swap! omissions update :invalid-timestamp (fnil inc 0)) fallback))
    fallback))

(defn now
  "Wall-clock timestamp for the new native identity (not a duration clock)."
  []
  (str (java.time.Instant/now)))
