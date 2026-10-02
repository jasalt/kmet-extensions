(ns pins.model
  "Branch-local pin snapshots and command parsing; no cached session state."
  (:require [clojure.string :as str]))

(def state-type "pin-state")
(def pick-count 10)
(def empty-state {:pins [] :next-id 1})

(defn extract-text
  "Only assistant text, not thinking, tool calls, or image attachments."
  [content]
  (str/trim
   (cond
     (string? content) content
     (sequential? content) (str/join "\n"
                                     (keep #(when (and (map? %) (= :text (:type %))
                                                       (string? (:text %)))
                                              (:text %)) content))
     :else "")))

(defn auto-label [text]
  (let [line (or (first (remove str/blank? (str/split-lines text))) "")
        cleaned (str/trim (str/replace line #"^[#*\-|>`\s]+" ""))]
    (cond
      (str/blank? cleaned) "pin"
      (> (count cleaned) 42) (str (subs cleaned 0 42) "…")
      :else cleaned)))

(defn preview [text max-chars]
  (let [flat (str/trim (str/replace text #"\s+" " "))]
    (if (> (count flat) max-chars) (str (subs flat 0 max-chars) "…") flat)))

(defn recent-assistants
  "Newest ten nonempty assistant texts on the active branch. :ago counts textless assistants too."
  [branch]
  (->> branch reverse (filter #(= :assistant (:role %)))
       (map-indexed (fn [i entry] {:text (extract-text (:content entry)) :ago (inc i)}))
       (remove #(str/blank? (:text %))) (take pick-count) vec))

(defn- valid-pin? [pin]
  (and (map? pin) (pos-int? (:id pin)) (string? (:label pin))
       (string? (:text pin)) (nat-int? (:pinned-at pin))))

(defn restore-state
  "Last snapshot wins. Invalid pins fail visibly rather than silently discarding durable data.
   Repair a missing/stale next-id above the highest existing ID."
  [entries]
  (if-let [entry (last entries)]
    (let [{:keys [pins next-id]} (:data entry)
          pins (or pins [])]
      (when-not (and (map? (:data entry)) (vector? pins) (every? valid-pin? pins)
                     (= (count pins) (count (set (map :id pins)))))
        (throw (ex-info "Invalid saved pin-state; no pins were changed" {:type :pins/invalid-state})))
      {:pins pins :next-id (max (inc (reduce max 0 (map :id pins)))
                                (if (pos-int? next-id) next-id 1))})
    empty-state))

(defn add-pin [state text label now]
  (let [pin {:id (:next-id state) :label (if (str/blank? label) (auto-label text) (str/trim label))
             :text text :pinned-at now}]
    (-> state (update :pins conj pin) (update :next-id inc))))

(defn remove-pin [state id]
  (update state :pins #(into [] (remove (fn [pin] (= id (:id pin)))) %)))

(defn parse-id [s]
  (when (and (string? s) (<= (count s) 18) (re-matches #"[0-9]+" s))
    (let [id (reduce (fn [acc ch] (+ (* acc 10) (- (int ch) (int \0)))) 0 s)]
      (when (pos? id) id))))

(defn parse-command
  "Subcommands are case-insensitive. No-argument subcommands followed by text are free labels."
  [args]
  (let [args (str/trim (or args ""))
        [word rest] (str/split args #"\s+" 2)
        sub (str/lower-case word)
        rest (or rest "")]
    (if (and (contains? #{"pick" "show" "list" "rm" "clear" "help"} sub)
             (or (str/blank? rest) (contains? #{"show" "list" "rm"} sub)))
      {:action (if (= sub "list") :show (keyword sub)) :arg (str/trim rest)}
      {:action :pin :label args})))
