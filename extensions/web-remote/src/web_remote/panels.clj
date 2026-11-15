(ns web-remote.panels
  "Request-local browser equivalents of public interactive capabilities."
  (:require [clojure.string :as str]
            [kmet.extension :as ext]
            [kmet.libs.json :as json]
            [kmet.libs.theme :as theme]
            [web-remote.http :as http]
            [web-remote.view :as view]))

(defn- call [m k & args] (when-let [f (get m k)] (apply f args)))
(defn- session [api k & args] (apply call (ext/session api) k args))
(defn- button [label path data]
  [:button {:type "button" :data-on:click
            (str "@post('" path "', {payload: " (json/generate-string data) "})")} label])
(defn- shell [title & children]
  (into [:section#panel {:aria-label title}
         [:header [:strong title] (button "Close" "/panel" {:panel "close"})]] children))
(defn- response [html] (http/sse-response (http/patch-elements (view/html html))))
(defn- message [text] (response (shell "Result" [:p text])))
(defn- idle! [state]
  (let [ctx (:ctx @state)]
    (when-not (and (call ctx :is-idle) (not (call ctx :has-pending-messages)))
      (throw (ex-info "Agent is busy; abort or wait first." {:type :web-remote/busy})))))

(defn- settings [api state]
  (shell "Settings"
    [:p "Live controls only; host changes are not persisted. Browser display controls do not change the TUI."]
    [:details {:open true} [:summary "Model / thinking"]
     (for [m (sort-by (juxt :provider :id) (call (ext/models api) :get-available))]
       (let [key (str (name (:provider m)) "/" (:id m))]
         (button key "/model" {:model key})))
     [:p (for [level ["off" "minimal" "low" "medium" "high" "xhigh" "max"]]
           (button level "/thinking" {:thinking level}))]]
    [:details [:summary "Active tools"]
     [:p "Session-only selection. Refresh after changes made in the TUI."]
     (let [active (set (ext/get-active-tools api))]
       (for [tool (sort-by :name (ext/get-all-tools api))]
         [:div (button (str (if (contains? active (:name tool)) "✓ " "○ ") (:name tool))
                       "/panel/action" {:action "tool" :value (:name tool)
                                         :enabled (not (contains? active (:name tool)))})
          [:small (view/clip-head (:description tool) 240)]]))]
    [:details [:summary "Display"]
     [:p "Browser thinking"]
     (for [mode ["visible" "hidden"]]
       [:button {:data-on:click (str "$webThinking = '" mode "'")} mode])
     [:p "Browser tool results"]
     (for [mode ["visible" "hidden"]]
       [:button {:data-on:click (str "$webTools = '" mode "'")} mode])
     (when (get-in @state [:ctx :has-ui])
       [:div [:p "TUI thinking display"]
        (for [mode ["visible" "stream-last" "hidden"]]
          (button mode "/panel/action" {:action "thinking-display" :value mode}))
        [:p (str "TUI tool display: " (ext/ui-get-tool-display-mode api))]
        (for [mode ["quiet" "collapsed" "expanded"]]
          (button mode "/panel/action" {:action "tool-display" :value mode}))])]
    [:details [:summary "TUI theme"]
     [:p "Changes the TUI only, not browser CSS. Not persisted."]
     (for [n (sort (keys (theme/get-all-themes)))]
       (button n "/panel/action" {:action "theme" :value n}))]))

(defn- tree [api]
  (shell "Session branch"
    [:p "Only the active branch is enumerable. You can also enter a known ID from another branch."]
    [:div {:data-signals:target "''" :data-signals:label "''"
           :data-signals:summarize "false" :data-signals:instructions "''"}
     [:input {:data-bind:target true :placeholder "Target entry ID" :aria-label "Target entry ID"}]
     [:input {:data-bind:label true :placeholder "Bookmark label (empty clears)" :aria-label "Bookmark label"}]
     [:label [:input {:type "checkbox" :data-bind:summarize true}] " Summarize abandoned branch"]
     [:input {:data-bind:instructions true :placeholder "Summary instructions (optional)"}]
     (for [[a title] [["navigate" "Navigate"] ["fork" "Fork from user message"] ["label" "Set bookmark"]]]
       [:button {:data-on:click (str "@post('/panel/action', {payload: {action: '" a "', target: $target, label: $label, summarize: $summarize, instructions: $instructions}})")} title])]
    [:ol (for [e (take-last 300 (session api :get-branch))]
           [:li [:code (:id e)] " " [:strong (some-> (:role e) name)] " "
            [:span (view/clip-head (view/content-text (:content e)) 180)]
            (when-let [label (session api :get-label (:id e))] [:em (str " [" label "]")])
            [:button {:data-on:click (str "$target = " (json/generate-string (:id e)))} "Select"]])]))

(defn open! [api state {:keys [panel]}]
  (response
    (case panel
      "close" [:section#panel]
      "tree" (tree api)
      "resume" (shell "Resume session"
                 [:p "Session listing is unavailable. Enter a known absolute session file path on the host."]
                 [:input {:data-bind:sessionpath true :placeholder "Absolute session path" :aria-label "Session path"}]
                 [:button {:data-on:click "@post('/panel/action', {payload: {action: 'resume', value: $sessionpath}})"} "Resume"])
      "commands" (shell "Commands"
                   [:p "Commands opening native dialogs still need the TUI unless a browser equivalent is documented in kmet-api.md."]
                   (for [c (sort-by :name (ext/get-commands api))]
                     [:div [:code (str "/" (:name c))] " " (:description c)]))
      (settings api state))))

(defn- require-entry [api target]
  (or (session api :get-entry target)
      (throw (ex-info "Unknown entry ID." {:type :web-remote/unknown-entry}))))
(defn- capability! [state k & args]
  (if-let [f (get (:ctx @state) k)] (apply f args)
    (throw (ex-info (str (name k) " is unavailable in this front end.") {:type :web-remote/unavailable}))))
(defn- tool! [api value enabled]
  (when-not (some #(= value (:name %)) (ext/get-all-tools api))
    (throw (ex-info "Unknown tool." {:type :web-remote/unknown-tool})))
  (let [active (set (ext/get-active-tools api))]
    (ext/set-active-tools api (vec ((if enabled conj disj) active value)))))

(defn- session-action! [api state {:keys [action target value label summarize instructions]}]
  (idle! state)
  (when-not (= action "resume") (require-entry api target))
  (case action
    "label" (do (session api :set-label! target (not-empty (str/trim (str label)))) {:cancelled false})
    "navigate" (capability! state :navigate-tree target
                            {:summarize (true? summarize) :label (not-empty label)
                             :custom-instructions (not-empty instructions)})
    "fork" (do (when-not (= :user (:role (require-entry api target)))
                  (throw (ex-info "Choose a user message to fork." {:type :web-remote/not-user})))
                (capability! state :fork target))
    "resume" (if (str/blank? value)
               (throw (ex-info "Enter a session path." {:type :web-remote/missing-path}))
               (capability! state :switch-session value))))

(defn- display! [api action value]
  (let [[allowed setter] (if (= action "thinking-display")
                           [#{"visible" "stream-last" "hidden"} ext/ui-set-thinking-mode]
                           [#{"quiet" "collapsed" "expanded"} ext/ui-set-tool-display-mode])]
    (when-not (contains? allowed value)
      (throw (ex-info "Unknown display mode." {:type :web-remote/invalid-mode})))
    (setter api (keyword value))
    (message "TUI display changed.")))

(defn action! [api state {:keys [action value enabled] :as data}]
  (try
    (case action
      "tool" (do (idle! state) (tool! api value (true? enabled)) (open! api state {:panel "settings"}))
      "theme" (let [result (ext/ui-set-theme api value)]
                (message (if (:success result) "TUI theme changed." (or (:error result) "Theme unavailable."))))
      ("thinking-display" "tool-display") (display! api action value)
      (if (contains? #{"navigate" "fork" "label" "resume"} action)
        (let [result (session-action! api state data)]
          (if (:cancelled result) (message "Operation cancelled.")
            (do (swap! state update :dirty (fnil into #{}) [:transcript :statusline])
                (if (= action "resume") (message "Session resumed.") (open! api state {:panel "tree"})))))
        (message "Unknown action.")))
    (catch Exception e (message (or (ex-message e) "Operation failed.")))))

(def command-panels
  {"/settings" "settings" "/tools" "settings" "/model" "settings" "/thinking" "settings"
   "/theme" "settings" "/tree" "tree" "/fork" "tree" "/resume" "resume"})
