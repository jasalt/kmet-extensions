(ns imgview.codec
  "Portable image payload encoding, using kmet's shared base64 implementation."
  (:require [clojure.string :as str]
            [kmet.libs.crypto :as crypto]))

(defn encode-base64
  "Standard padded base64 for image blocks and browser data URIs."
  [bytes]
  (let [s (str/replace (crypto/base64url bytes) #"[-_]" {"-" "+" "_" "/"})]
    (str s (apply str (repeat (mod (- (count s)) 4) "=")))))

(defn decode-base64
  "Decode standard base64, accepting whitespace and omitted padding."
  [s]
  (crypto/base64url-decode (-> s
                               (str/replace #"\s+" "")
                               (str/replace #"[+/]" {"+" "-" "/" "_"}))))

(defn- codepoint-octets [cp]
  (cond
    (< cp 0x80) [cp]
    (< cp 0x800) [(bit-or 0xc0 (bit-shift-right cp 6))
                  (bit-or 0x80 (bit-and cp 0x3f))]
    (< cp 0x10000) [(bit-or 0xe0 (bit-shift-right cp 12))
                    (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3f))
                    (bit-or 0x80 (bit-and cp 0x3f))]
    :else [(bit-or 0xf0 (bit-shift-right cp 18))
           (bit-or 0x80 (bit-and (bit-shift-right cp 12) 0x3f))
           (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3f))
           (bit-or 0x80 (bit-and cp 0x3f))]))

(defn- utf8-octets [s]
  (loop [i 0 result []]
    (if (>= i (count s))
      result
      (let [c (int (nth s i))
            low (when (< (inc i) (count s)) (int (nth s (inc i))))
            pair? (and (<= 0xd800 c 0xdbff) low (<= 0xdc00 low 0xdfff))
            cp (cond
                 pair? (+ 0x10000 (bit-shift-left (- c 0xd800) 10) (- low 0xdc00))
                 (<= 0xd800 c 0xdfff) 0xfffd
                 :else c)]
        (recur (+ i (if pair? 2 1)) (into result (codepoint-octets cp)))))))

(defn utf8-bytes
  "Encode text as UTF-8 without charset interop (including surrogate pairs)."
  [s]
  (byte-array (map unchecked-byte (utf8-octets s))))

(defn decode-uri-payload
  "Decode a non-base64 data URI to bytes; '+' stays literal, unlike form encoding."
  [payload]
  (byte-array
   (map unchecked-byte
        (mapcat (fn [part]
                  (if (str/starts-with? part "%")
                    (if (= 3 (count part))
                      [(reduce (fn [n c]
                                 (+ (* n 16) (str/index-of "0123456789abcdef" (str c))))
                               0 (str/lower-case (subs part 1)))]
                      (throw (ex-info "malformed percent escape in data: URI"
                                      {:type :imgview/invalid-data-uri})))
                    (utf8-octets part)))
                (re-seq #"(?s)%[0-9a-fA-F]{2}|[^%]+|%" payload)))))
