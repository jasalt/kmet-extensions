;; Copyright (c) 2026 Jarkko Saltiola and xhluca; SPDX-License-Identifier: MIT
(ns session-migrate.claude
  "Claude Code active-graph projection. Adapted from xhluca/session-migrate."
  (:require [clojure.string :as str]
            [kmet.libs.crypto :as crypto]
            [session-migrate.io :as mio]))

(defn- value [row key] (get row (name key)))
(defn- string-value [row key]
  (let [v (value row key)] (when (and (string? v) (seq v)) v)))
(defn- conversation? [row]
  (and (contains? #{"user" "assistant"} (value row :type))
       (map? (value row :message))
       (not (true? (value row :isMeta)))))

(defn- preserved-back-edge?
  [boundary logical seen index records]
  (let [metadata (value boundary :compactMetadata)
        segment (value metadata :preservedSegment)
        messages (value metadata :preservedMessages)
        anchor (string-value segment :anchorUuid)
        head (string-value segment :headUuid)
        tail (string-value segment :tailUuid)
        anchor-row (get records (get index anchor))
        declared (or (value messages :allUuids) (value messages :uuids))]
    (and anchor head tail (= logical tail)
         (true? (value anchor-row :isCompactSummary))
         (= (value anchor-row :parentUuid) (value boundary :uuid))
         (sequential? declared) (seq declared)
         (every? seen declared) (some #{head} declared) (some #{tail} declared)
         (loop [cursor tail path #{}]
           (cond
             (= cursor anchor) (contains? path head)
             (or (nil? cursor) (contains? path cursor) (not (contains? seen cursor))
                 (not (contains? index cursor))) false
             :else (recur (string-value (nth records (get index cursor)) :parentUuid)
                          (conj path cursor)))))))

(defn active-branch
  "Follow UUID ancestry, not append order. Never flatten forks or subagents."
  [records cancelled?]
  (let [index (reduce-kv
               (fn [acc i row]
                 (mio/check-cancel! cancelled?)
                 (if-let [id (string-value row :uuid)]
                   (do (when (contains? acc id) (mio/fail! "Duplicate Claude record UUID"))
                       (assoc acc id i))
                   acc)) {} records)
        candidates (vec (keep-indexed (fn [i row]
                                        (when (and (conversation? row)
                                                   (not (true? (value row :isSidechain)))) i)) records))
        recorded-leaf (some #(when (= "last-prompt" (value % :type))
                               (string-value % :leafUuid)) (reverse records))
        leaf (or recorded-leaf (string-value (get records (peek candidates)) :uuid))]
    (when (empty? candidates)
      (mio/fail! "No parent Claude conversation; sidechain/subagent-only transcripts are unsupported"))
    (if-not leaf
      (do (when (some #(or (string-value (nth records %) :uuid)
                           (string-value (nth records %) :parentUuid)) candidates)
            (mio/fail! "Incomplete Claude UUID graph"))
          candidates)
      (loop [cursor leaf seen #{} path []]
        (mio/check-cancel! cancelled?)
        (if-not cursor
          (vec (reverse path))
          (do
            (when (contains? seen cursor) (mio/fail! "Claude ancestry cycle"))
            (when-not (contains? index cursor) (mio/fail! "Claude graph references a missing UUID"))
            (let [i (get index cursor)
                  row (nth records i)
                  seen (conj seen cursor)
                  path (conj path i)
                  parent (string-value row :parentUuid)
                  boundary? (and (= "system" (value row :type))
                                 (= "compact_boundary" (value row :subtype)))
                  logical (when (and (not parent) boundary?) (string-value row :logicalParentUuid))]
              (if (and logical (contains? seen logical)
                       (preserved-back-edge? row logical seen index records))
                (vec (reverse path))
                (recur (or parent logical) seen path)))))))))

(defn- count! [counts key] (swap! counts update key (fnil inc 0)))

(defn- image [block]
  (let [source (value block :source)
        mime (string-value source :media_type)
        data (string-value source :data)]
    (when (and (= "base64" (value source :type)) data
               (contains? #{"image/png" "image/jpeg" "image/gif" "image/webp"} mime)
               (re-matches #"[A-Za-z0-9+/]+={0,2}" data)
               (zero? (mod (count data) 4)))
      (try
        (crypto/base64url-decode (-> data (str/replace "+" "-") (str/replace "/" "_")))
        {:type :image :data data :mime-type mime}
        (catch Exception _ nil)))))

(defn- blocks [content]
  (cond
    (string? content) [{"type" "text" "text" content}]
    (sequential? content) content
    :else (mio/fail! "Unsupported Claude message content")))

(defn- content-text [content]
  (str/join "\n" (keep #(when (= "text" (value % :type)) (value % :text)) (blocks content))))

(defn portable-result
  "kmet's canonical result separates joined text from ordered images. Cross-media
  interleaving is a counted transformation, not claimed lossless portability."
  [content preserved omitted]
  (let [source (cond (string? content) [{"type" "text" "text" content}]
                     (sequential? content) content
                     :else (do (count! omitted :tool-result-content) []))
        projected (reduce
                   (fn [acc b]
                     (cond
                       (string? b) (update acc :texts conj b)
                       (and (= "text" (value b :type)) (string? (value b :text)))
                       (update acc :texts conj (value b :text))
                       (= "image" (value b :type))
                       (if-let [img (image b)]
                         (do (count! preserved :images) (update acc :images conj img))
                         (do (count! omitted :tool-result-block) acc))
                       :else (do (count! omitted :tool-result-block) acc)))
                   {:texts [] :images []} source)]
    (when (or (> (count (:texts projected)) 1) (seq (:images projected)))
      (count! omitted :tool-result-layout-normalized))
    {:text (str/join "\n" (:texts projected)) :images (:images projected)}))

(defn- message-entries
  [row stamp calls results preserved omitted cancelled?]
  (let [message (value row :message)
        role (or (string-value message :role) (value row :type))
        content (blocks (value message :content))
        flush-message
        (fn [acc]
          (if (or (seq (:content acc)) (seq (:tool-calls acc)))
            (let [entry (cond-> {:role (keyword role) :content (:content acc) :timestamp stamp}
                          (= role "assistant")
                          (assoc :provider :anthropic :model (or (string-value message :model) "unknown")
                                 :tool-calls (:tool-calls acc)
                                 :stop-reason (if (seq (:tool-calls acc)) :tool-use :stop)))]
              (count! preserved :messages)
              (-> acc (update :entries conj entry) (assoc :content [] :tool-calls [])))
            acc))]
    (:entries
     (flush-message
      (reduce
       (fn [acc b]
         (mio/check-cancel! cancelled?)
         (case (value b :type)
           "text" (if (string? (value b :text))
                    (do (count! preserved :text-blocks)
                        (update acc :content conj {:type :text :text (value b :text)}))
                    (mio/fail! "Malformed Claude text block"))
           ("thinking" "redacted_thinking") (do (count! omitted :private-thinking) acc)
           "document" (do (count! omitted :document) acc)
           "tool_use"
           (let [id (string-value b :id) tool (string-value b :name) args (value b :input)]
             (when-not (and (= role "assistant") id tool (map? args) (not (contains? @calls id)))
               (mio/fail! "Invalid or duplicate Claude tool call"))
             (doseq [v (tree-seq coll? seq args)]
               (mio/check-cancel! cancelled?)
               ;; The public kmet JSON boundary decodes fractional/exponent
               ;; numbers as binary floating point. Report this normalization,
               ;; and never persist ##Inf/##NaN as supposedly valid JSON args.
               (when (float? v)
                 (when-not (< Double/NEGATIVE_INFINITY v Double/POSITIVE_INFINITY)
                   (mio/fail! "Tool argument number is outside the finite JSON range"))
                 (count! omitted :tool-argument-float-normalized)))
             (swap! calls assoc id tool)
             (count! preserved :tool-calls)
             (update acc :tool-calls conj {:id id :name tool :arguments args}))
           "tool_result"
           (let [id (string-value b :tool_use_id)]
             (when-not (and (= role "user") id (contains? @calls id) (not (contains? @results id)))
               (mio/fail! "Orphan or duplicate Claude tool result"))
             (swap! results conj id)
             (let [acc (flush-message acc)
                   {:keys [text images]} (portable-result (value b :content) preserved omitted)
                   entry (cond-> {:role :tool :timestamp stamp :tool-name (get @calls id)
                                  :is-error (true? (value b :is_error))
                                  :content [{:type :tool_result :tool_use_id id :content text}]}
                           (seq images) (assoc :images images))]
               (count! preserved :tool-results) (count! preserved :messages)
               (update acc :entries conj entry)))
           "image" (if-let [img (when (= role "user") (image b))]
                     (do (count! preserved :images) (update acc :content conj img))
                     (do (count! omitted :unsupported-image) acc))
           (do (count! omitted :unknown-content-block) acc)))
       {:entries [] :content [] :tool-calls []} content)))))

(defn project
  "Project a parsed snapshot into native EDNL entry drafts plus content-free counts."
  [{:keys [records sha256]} cancelled?]
  (let [selected (active-branch records cancelled?)
        selected-set (set selected)
        selected-rows (mapv records selected)
        session-ids (set (keep #(string-value % :sessionId) selected-rows))
        summary-parents (set (keep #(when (true? (value % :isCompactSummary))
                                      (string-value % :parentUuid)) selected-rows))
        title-for (fn [type field] (some #(when (= type (value % :type))
                                            (string-value % field)) (reverse records)))
        title (or (title-for "custom-title" :customTitle) (title-for "ai-title" :aiTitle))
        calls (atom {}) results (atom #{}) preserved (atom {}) omitted (atom {})
        fallback (mio/now)]
    (when (> (count session-ids) 1) (mio/fail! "Claude active graph contains mixed session IDs"))
    (doseq [[i row] (map-indexed vector records)]
      (when (and (not (selected-set i))
                 (not (contains? #{"custom-title" "ai-title" "last-prompt" "queue-operation"} (value row :type))))
        (count! omitted :inactive-or-metadata-record)))
    (let [entries
          (vec
           (mapcat
            (fn [row]
              (mio/check-cancel! cancelled?)
              (when (true? (value row :isSidechain)) (mio/fail! "Claude active graph includes a sidechain"))
              (let [stamp (mio/timestamp (value row :timestamp) fallback omitted)
                    message (value row :message)
                    role (or (string-value message :role) (value row :type))]
                (cond
                  (and (= "system" (value row :type)) (= "compact_boundary" (value row :subtype)))
                  (do (when-not (summary-parents (value row :uuid))
                        (count! omitted :compaction-without-summary)) [])
                  (or (not (conversation? row)) (true? (value row :isMeta)))
                  (do (count! omitted :active-metadata-record) [])
                  (true? (value row :isCompactSummary))
                  (let [text (content-text (value message :content))]
                    (when (str/blank? text) (mio/fail! "Claude compact summary has no portable text"))
                    (count! preserved :compactions) (count! omitted :compaction-metadata)
                    [{:role :compaction :timestamp stamp :summary text :tokens-before 0}])
                  (not (contains? #{"user" "assistant"} role))
                  (do (count! omitted :privileged-or-unknown-role) [])
                  :else
                  (do (when (or (some? (value row :toolUseResult)) (some? (value row :sourceToolAssistantUUID)))
                        (count! omitted :tool-result-metadata))
                      (message-entries row stamp calls results preserved omitted cancelled?))))) selected-rows))]
      (when (empty? entries) (mio/fail! "Source has no resumable conversation context"))
      (when (some #(not (contains? @results %)) (keys @calls))
        (mio/fail! "Source has an unresolved tool call; import a completed transcript"))
      {:entries entries :title title
       :report {:source-format "claude" :source-sha256 sha256 :records (count records)
                :selected-records (count selected) :preserved @preserved :omitted @omitted}})))

(defn read-claude
  "Validate a Claude Code transcript without changing source or active session."
  ([path] (read-claude path (constantly false)))
  ([path cancelled?] (project (mio/read-source path cancelled?) cancelled?)))
