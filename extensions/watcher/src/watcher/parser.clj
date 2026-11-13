(ns watcher.parser
  "Line-comment parser adapted from pi-watcher. Not a language parser."
  (:require [clojure.string :as str]))

(def eol #"\r\n|\n|\r")

(defn lines [s] (str/split s eol -1))

(defn line-comment [^String line]
  (loop [i 0 quote nil escaped? false]
    (when (< i (.length line))
      (let [c (.charAt line i)]
        (cond
          quote (recur (inc i) (if (and (= c quote) (not escaped?)) nil quote)
                       (and (= c \\) (not escaped?)))
          (#{\' \" \`} c) (recur (inc i) c false)
          :else
          (if-let [prefix (cond
                           (= c \#) "#"
                           (.startsWith line "//" i) "//"
                           (.startsWith line "--" i) "--"
                           (and (= c \;) (or (zero? i) (str/blank? (subs line 0 i))
                                                             (re-matches #"\s" (str (nth line (dec i))))))
                           (re-find #"^;+" (subs line i)))]
            {:start i :prefix prefix :kind (if (str/starts-with? prefix ";") ";" prefix)
             :text (str/trim (subs line (+ i (count prefix))))}
            (recur (inc i) nil false)))))))

(defn parse [source path marker]
  (let [comments (mapv line-comment (lines source))
        token (str (java.util.regex.Pattern/quote marker) "[!?.]")
        pattern (re-pattern (str "(?i)(?:^(" token ")(?=$|[^A-Za-z0-9_])|\\s(" token ")$)"))]
    (vec
     (keep-indexed
      (fn [i comment]
        (when-let [match (and comment (re-find pattern (:text comment)))]
          (let [text (or (nth match 1) (nth match 2))
                kind (:kind comment)
                start (loop [n i] (if (and (pos? n) (= kind (:kind (nth comments (dec n))))) (recur (dec n)) n))
                end (loop [n i] (if (and (< (inc n) (count comments)) (= kind (:kind (nth comments (inc n))))) (recur (inc n)) n))
                block (str/join "\n" (map (comp str/lower-case :text) (subvec comments start (inc end))))]
            {:path path :line (inc i) :start start :end end :instruction (:text comment)
             :action (case (last text) \! :edit \? :ask :context)
             :id [path block]})))
      comments))))

(defn cleanup
  "Reparse before removing only unchanged handled marker lines; preserve inline code."
  [source path marker ids]
  (let [handled (filter #(contains? ids (:id %)) (parse source path marker))
        line-nos (set (map :line handled))
        sep (or (re-find eol source) "\n")]
    (str/join sep
              (keep-indexed
               (fn [i line]
                 (if (contains? line-nos (inc i))
                   (let [before (subs line 0 (:start (line-comment line)))]
                     (when-not (str/blank? before) (str/trimr before)))
                   line))
               (lines source)))))
