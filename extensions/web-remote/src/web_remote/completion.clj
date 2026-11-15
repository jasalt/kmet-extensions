(ns web-remote.completion
  "Request-local completion menus; the TUI provider owns matching and edits."
  (:require [clojure.string :as str]
            [kmet.extension :as ext]
            [kmet.libs.json :as json]
            [kmet.tui.autocomplete :as ac]
            [web-remote.http :as http]
            [web-remote.view :as view]))

(defn capture!
  "Capture the provider chain at server start without replacing it. Providers
  registered after this factory are not visible until its next rebuild."
  [api state]
  (when-not (:completion-registered? @state)
    (ext/ui-add-autocomplete-provider api
      (fn [provider] (swap! state assoc :completion-provider provider) nil))
    (swap! state assoc :completion-registered? true)))

(defn- editor-state [text cursor]
  (when (and (string? text) (integer? cursor) (<= 0 cursor (count text)))
    (let [lines (str/split text #"\n" -1)
          before (str/split (subs text 0 cursor) #"\n" -1)]
      {:lines lines :line (dec (count before)) :col (count (last before))})))

(defn- menu [ticket text cursor items]
  (view/html
    [:div#completions {:role "listbox"
                      :data-show (str "$editing && $prompt === " (json/generate-string text) " && $cursor === " cursor " && $completionOpen")}
     (for [[i item] (map-indexed vector items)]
       [:button {:type "button" :role "option"
                 :data-class:selected (str "$completionIndex === " i)
                 :data-attr:aria-selected (str "$completionIndex === " i)
                 :data-on:mousedown "evt.preventDefault()"
                 :data-on:click (str "@post('/complete/apply', {payload: {ticket: '" ticket "', index: " i "}})")}
        [:span (or (:label item) (:value item))]
        (when-let [d (:description item)] [:small d])])]))

(defn suggest!
  [state {:keys [prompt cursor force]}]
  (let [provider (:completion-provider @state)
        {:keys [lines line col] :as ed} (editor-state prompt cursor)
        suggestions (when (and provider ed)
                      (ac/get-suggestions provider lines line col
                                          {:force (and (true? force)
                                                       (ac/should-trigger-file-completion provider lines line col))}))
        items (vec (take 50 (:items suggestions)))
        ticket (http/random-token)]
    ;; Keep provider + original items together: a later provider rebuild cannot
    ;; apply an item using a different provider. Bound memory across clients.
    (swap! state update :completion-tickets
           (fn [tickets]
             (conj (vec (take-last 15 tickets))
                   [ticket {:provider provider :editor ed :text prompt :cursor cursor :items items :prefix (:prefix suggestions)}])))
    (http/sse-response
      (http/patch-elements (menu ticket prompt (or cursor 0) items))
      (http/patch-signals (json/generate-string {:completionIndex 0 :completionOpen (boolean (seq items))})))))

(defn apply!
  [state {:keys [ticket index]}]
  (if-let [{:keys [provider editor text cursor items prefix]} (get (into {} (:completion-tickets @state)) ticket)]
    (if (and (integer? index) (<= 0 index) (< index (count items)))
      (let [{:keys [lines line col]} editor
            result (ac/apply-completion provider lines line col (nth items index) prefix)
            result-cursor (+ (reduce + 0 (map #(inc (count %)) (take (:cursor-line result) (:lines result))))
                      (:cursor-col result))]
        (http/sse-response
          (http/patch-signals (json/generate-string
                               {:completionResult {:before text :beforeCursor cursor :text (str/join "\n" (:lines result))
                                                   :cursor result-cursor :ticket ticket}}))))
      (http/sse-response))
    (http/sse-response)))
