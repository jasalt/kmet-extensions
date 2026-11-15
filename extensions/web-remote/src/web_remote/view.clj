(ns web-remote.view
  "Server-rendered HTML. A tiny hiccup subset (`html`) escapes every string, so
  session text never reaches the page as markup. Messages render as plain text
  in <pre>; no markdown."
  (:require [clojure.string :as str]
            [kmet.libs.json :as json]))

;; ─── hiccup → HTML ─────────────────────────────────────────────────────────

(defn escape [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(def ^:private void-tags #{:meta :link :input :br :hr})

(declare html)

(defn- attrs [m]
  (apply str (for [[k v] m :when (and (some? v) (not (false? v)))]
               (if (true? v)
                 (str " " (name k))
                 (str " " (name k) "=\"" (escape v) "\"")))))

(defn- element
  "`:tag#id.class1.class2` plus an optional attribute map; a :class attr is
  appended to the shorthand classes."
  [[tag & more]]
  (let [[m children] (if (map? (first more)) [(first more) (rest more)] [{} more])
        [_ tag id classes] (re-matches #"([^#.]+)(?:#([^.]+))?((?:\.[^.]+)*)" (name tag))
        classes (str/join " " (remove str/blank? (concat (str/split (or classes "") #"\.")
                                                         [(:class m)])))
        m (concat (when id [[:id id]])
                  (when (seq classes) [[:class classes]])
                  (dissoc m :id :class))]
    (if (void-tags (keyword tag))
      (str "<" tag (attrs m) ">")
      (str "<" tag (attrs m) ">" (apply str (map html children)) "</" tag ">"))))

(defn html
  "Render hiccup: vectors are elements, seqs splice, nil renders nothing,
  `[:raw s]` is trusted markup and everything else is escaped text."
  [x]
  (cond
    (nil? x) ""
    (and (vector? x) (= :raw (first x))) (str (second x))
    (vector? x) (element x)
    (seq? x) (apply str (map html x))
    :else (escape x)))

;; ─── content helpers ───────────────────────────────────────────────────────

(def max-chars 20000)

(defn clip
  "At most N chars of S, keeping the tail (the newest output)."
  ([s] (clip s max-chars))
  ([s n] (let [s (str s)]
           (if (> (count s) n) (str "…" (subs s (- (count s) n))) s))))

(defn clip-head
  ([s] (clip-head s max-chars))
  ([s n] (let [s (str s)]
           (if (> (count s) n) (str (subs s 0 n) "…") s))))

(defn content-text
  "Plain text of a message :content: a string or a vector of blocks."
  [content]
  (cond
    (string? content) content
    (sequential? content)
    (str/join "\n" (keep (fn [b]
                           (cond
                             (string? b) b
                             (not (map? b)) nil
                             (= :image (keyword (:type b))) "[image]"
                             (contains? b :text) (:text b)
                             (contains? b :content) (content-text (:content b))))
                         content))
    (nil? content) ""
    :else (pr-str content)))

(defn- args-text [args]
  (if (map? args)
    (str/join "\n" (for [[k v] args] (str (name k) ": " (if (string? v) v (pr-str v)))))
    (str args)))

;; ─── transcript ────────────────────────────────────────────────────────────

(defmulti entry-view (fn [e] (keyword (:role e))))

(defn- block [class label & body]
  [:div {:class (str "msg " class)}
   (when label [:div.label label])
   body])

(defmethod entry-view :user [e]
  (block "user" "you" [:pre (clip-head (content-text (:content e)))]))

(defmethod entry-view :assistant [e]
  (let [text (content-text (:content e))]
    (block "assistant" "assistant"
           (when (seq (:thinking e))
             [:details [:summary "thinking"] [:pre.thinking (clip (:thinking e))]])
           (when (seq text) [:pre text])
           (for [tc (:tool-calls e)]
             [:div.tool-call [:span.tool (:name tc)] [:pre.args (clip-head (args-text (:arguments tc)) 4000)]])
           (when-let [err (:error-message e)] [:pre.error err]))))

(defmethod entry-view :tool [e]
  (block (str "tool-result" (when (:is-error e) " error")) (str "result: " (:tool-name e))
         [:details [:summary (clip-head (first (str/split-lines (str (content-text (:content e)) " "))) 160)]
          [:pre (clip (content-text (:content e)) 8000)]]))

(defmethod entry-view :bash [e]
  (block "bash" (str "$ " (:command e)) [:pre (clip (:output e) 8000)]))

(defmethod entry-view :custom-message [e]
  (when (not (false? (:display e)))
    (block "custom" (str (:custom-type e)) [:pre (clip-head (content-text (:content e)))])))

(defmethod entry-view :compaction [e]
  (block "note" "compaction" [:details [:summary "summary"] [:pre (clip-head (:summary e))]]))

(defmethod entry-view :branch-summary [e]
  (block "note" "branch summary" [:details [:summary "summary"] [:pre (clip-head (:summary e))]]))

(defmethod entry-view :default [_] nil)

(def max-entries 200)

(defn transcript [entries]
  (let [shown (take-last max-entries entries)
        hidden (- (count entries) (count shown))]
    (html [:div#transcript
           (when (pos? hidden) [:div.note (str hidden " earlier entries not shown")])
           (map entry-view shown)])))

;; ─── live region ───────────────────────────────────────────────────────────

(defn live
  "The in-flight assistant message and running tools."
  [{:keys [text thinking tools]}]
  (html [:div#live
         (when (seq thinking)
           (block "assistant live" "thinking…" [:pre.thinking (clip thinking 4000)]))
         (when (seq text)
           (block "assistant live" "assistant…" [:pre (clip text)]))
         (for [[_ {:keys [name args output]}] (sort-by (comp :started val) tools)]
           (block "tool-call live" (str "running: " name)
                  [:pre.args (clip-head (args-text args) 2000)]
                  (when (seq output) [:pre (clip output 4000)])))]))

;; ─── status ────────────────────────────────────────────────────────────────

(defn status
  [{:keys [running? compacting? queued]}]
  (html [:div#status
         [:span.state {:class (if (or running? compacting?) "busy" "idle")}
          (cond compacting? "compacting" running? "running" :else "idle")]
         (when (pos? (or queued 0)) [:span (str queued " queued")])
         (when (or running? compacting?)
           [:button {:data-on:click "@post('/abort')"} "Abort"])]))

(defn activity-text
  "The working-line text, worded like the TUI's status indicator."
  [{:keys [kind reason attempt max-attempts seconds]}]
  (case kind
    :waiting-for-model "Waiting for model"
    :retry (str "Retrying (" attempt "/" max-attempts ") in " seconds "s")
    :compaction (case reason
                  :manual "Compacting context"
                  :overflow "Context overflow detected, auto-compacting"
                  "Auto-compacting")
    :branch-summary "Summarizing branch"
    "Working"))

(defn activity
  "The working line above the composer: a CSS spinner and the phase text,
  empty when idle."
  [data]
  (html [:div#activity
         (when data
           [:div {:class (str "activity " (name (:kind data)))}
            [:span.spinner {:aria-hidden "true"}]
            [:span (activity-text data)]])]))

(defn format-tokens
  "999 → 999, 1234 → 1.2k, 12345 → 12k, 1234567 → 1.2M (the TUI footer's)."
  [n]
  (let [n (long n)]
    (cond
      (< n 1000) (str n)
      (< n 10000) (format "%.1fk" (/ n 1000.0))
      (< n 1000000) (str (Math/round (/ n 1000.0)) "k")
      (< n 10000000) (format "%.1fM" (/ n 1000000.0))
      :else (str (Math/round (/ n 1000000.0)) "M"))))

(defn- context-part [{:keys [tokens context-window]}]
  (let [pct (when (and tokens context-window (pos? context-window))
              (* 100.0 (/ tokens context-window)))
        text (cond pct (str (format "%.1f" pct) "%/" (format-tokens context-window))
                   context-window (str "?/" (format-tokens context-window))
                   tokens (str (format-tokens tokens) " tokens")
                   :else "? tokens")]
    [:span {:class (cond (and pct (> pct 90)) "ctx-error" (and pct (> pct 70)) "ctx-warning")} text]))

(defn statusline
  "The TUI footer below the composer: mark + cwd (branch) • session name, then
  usage stats, context use and provider/model • thinking."
  [{:keys [mark cwd branch session-name usage hit-rate context model reasoning? thinking]}]
  (let [{:keys [input output cache-read cache-write cost]} usage
        stat (fn [prefix n] (when (pos? (or n 0)) (str prefix (format-tokens n))))
        stats (remove nil? [(stat "↑" input) (stat "↓" output) (stat "R" cache-read) (stat "W" cache-write)
                            (when (and hit-rate (pos? (+ (or cache-read 0) (or cache-write 0))))
                              (str "CH" (format "%.1f" hit-rate) "%"))
                            (when (pos? (or cost 0)) (str "$" (format "%.3f" (double cost))))])]
    (html [:div#statusline
           [:div [:span.mark (str "К" mark)] " "
            (str cwd (when branch (str " (" branch ")")) (when (seq session-name) (str " • " session-name)))]
           [:div (when (seq stats) (str (str/join " " stats) " ")) (context-part context)
            "  " (str (or model "no-model")
                      (when reasoning? (str " • " (if (= :off thinking) "thinking off" (some-> thinking name)))))]])))

(defn notice [text]
  (html [:div#notice (when (seq text) text)]))

;; ─── page shell ────────────────────────────────────────────────────────────

(def ^:private css "
*{box-sizing:border-box}body{margin:0;font:14px/1.4 system-ui,sans-serif;background:#111;color:#ddd;display:flex;flex-direction:column;height:100vh}
#status{display:flex;gap:1em;flex-wrap:wrap;align-items:center;padding:.4em .8em;background:#222;border-bottom:1px solid #333}
.state{font-weight:bold}.busy{color:#fc6}.idle{color:#7c7}.notice,#notice,.error{color:#f88}
main{flex:1;overflow-y:auto;padding:.5em .8em}
.msg{margin:.4em 0;padding:.3em .6em;border-left:3px solid #555}
.user{border-color:#58f}.assistant{border-color:#7c7}.tool-call,.tool-result,.bash{border-color:#a8a}.note,.custom{border-color:#888}.error{border-color:#f66}
.live{opacity:.85}.label{font-size:.8em;color:#999}.tool{font-weight:bold}
pre{white-space:pre-wrap;word-break:break-word;margin:.2em 0;font:13px/1.35 ui-monospace,monospace}
.thinking,.args{color:#aaa}details summary{cursor:pointer;color:#bbb}
#activity{min-height:1.5em}.activity{display:flex;gap:.5em;align-items:center;color:#aaa}
.spinner{width:.8em;height:.8em;border:2px solid #fc6;border-right-color:transparent;border-radius:50%;animation:spin .8s linear infinite}
.retry .spinner{border-color:#f88;border-right-color:transparent}.retry{color:#f88}
@keyframes spin{to{transform:rotate(360deg)}}
@media (prefers-reduced-motion:reduce){.spinner{animation:none}}
footer{padding:.5em .8em;background:#1a1a1a;border-top:1px solid #333}
#statusline{margin-top:.4em;color:#888;font:12px/1.4 ui-monospace,monospace;overflow-wrap:anywhere}
.mark{color:#8cf;font-style:italic}.ctx-warning{color:#fc6}.ctx-error{color:#f88}
textarea{width:100%;min-height:4em;background:#000;color:#eee;border:1px solid #444;font:14px monospace}
.row{display:flex;gap:.5em;flex-wrap:wrap;align-items:center;margin-top:.3em}
button,select,input{background:#333;color:#eee;border:1px solid #555;padding:.2em .6em}
")

(defn page
  "The full page. SIGNALS seeds the Datastar store; MODELS is
  [{:value \"provider/id\" :label str}], LEVELS the thinking levels."
  [{:keys [signals models levels]}]
  (str
   "<!doctype html>"
   (html
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title "kmet web-remote"]
      [:style [:raw css]]
      [:script {:type "module" :src "/datastar.js"}]
      [:script {:defer true :src "/app.js"}]]
     [:body {:data-signals (json/generate-string signals)
             :data-init "@get('/events', {openWhenHidden: true})"}
      [:div#status]
      [:main#scroll [:div#transcript] [:div#live]]
      [:footer
       [:div#activity]
       [:div#notice]
       [:textarea#composer {:data-bind:prompt true
                   :data-effect "if (!$editing) { $prompt = $draft; $draftbase = $draft; }"
                   :data-on:focus "$editing = true"
                   :data-on:blur "if ($prompt !== $draftbase) { @post('/draft', {requestCancellation: 'disabled'}).finally(() => { $editing = document.activeElement === el; }); } else { $editing = false; }"
                   :data-on:input__debounce.300ms "if (!evt.isComposing && $prompt !== $draftbase) @post('/draft', {requestCancellation: 'disabled'})"
                   :placeholder "Message (Ctrl+Enter to send)"
                   :data-on:keydown "evt.key === 'Enter' && (evt.ctrlKey || evt.metaKey) && (evt.preventDefault(), @post('/prompt'))"}]
       [:div.row
        [:button {:data-on:click "@post('/prompt')"} "Send"]
        [:select {:data-bind:deliver true :title "While running"}
         [:option {:value "steer"} "steer"]
         [:option {:value "follow-up"} "follow-up"]]
        [:button {:data-on:click "@post('/abort')"} "Abort"]
        [:select {:data-bind:model true :data-on:change "@post('/model')" :title "Model"}
         (for [{:keys [value label]} models] [:option {:value value} label])]
        [:select {:data-bind:thinking true :data-on:change "@post('/thinking')" :title "Thinking"}
         (for [l levels] [:option {:value (name l)} (name l)])]
        [:button {:data-on:click "@post('/compact')"} "Compact"]
        [:input {:data-bind:name true :placeholder "session name" :size 14}]
        [:button {:data-on:click "@post('/name')"} "Rename"]
        [:button {:data-on:click "confirm('Start a new session?') && @post('/new')"} "New session"]]
       [:div#statusline]]]])))

(def app-js
  "Keep the transcript pinned to the bottom unless the user scrolled up."
  "(() => {
  const el = document.getElementById('scroll');
  let pinned = true;
  el.addEventListener('scroll', () => { pinned = el.scrollHeight - el.scrollTop - el.clientHeight < 40; });
  new MutationObserver(() => { if (pinned) el.scrollTop = el.scrollHeight; })
    .observe(el, {childList: true, subtree: true, characterData: true});
})();
")
