(ns pins.core
  "Pin assistant text in the session without adding it to model context or the transcript."
  (:require [clojure.string :as str]
            [kmet.extension :as ext]
            [pins.model :as model]
            [pins.ui :as ui]))

(def ^:private runtime (atom nil))

(def ^:private subcommands
  [{:value "pick" :label "pick" :description "Pick a recent assistant message"}
   {:value "show" :label "show" :description "Browse pins, optionally at #n"}
   {:value "list" :label "list" :description "Alias for show"}
   {:value "rm" :label "rm" :description "Remove pin #n"}
   {:value "clear" :label "clear" :description "Remove all pins; restart IDs"}
   {:value "help" :label "help" :description "Show instructions"}])

(def help-text
  (str/join "\n"
            ["/pin [label]    Pin the latest nonempty assistant text"
             "/pin pick       Pick one of the ten latest assistant texts"
             "/pin show [n]   Browse pins, optionally at #n"
             "/pin list [n]   Alias for show"
             "/pin rm <n>     Remove pin #n"
             "/pin clear      Remove all pins (IDs restart at 1)"
             "/pin help       Show these instructions"
             ""
             "Viewer: ↑↓ scroll · PgUp/PgDn switch pin · g/G top/bottom · q/Esc/Enter close"
             "Pins follow the session branch and survive /reload, restart, and /resume."]))

(defn- state [ctx]
  (model/restore-state ((:get-entries (:session ctx)) model/state-type)))

(defn- persist! [ctx snapshot]
  (when-not ((:append-entry! (:session ctx)) model/state-type snapshot)
    (throw (ex-info "No active session; pin state was not saved" {:type :pins/no-session}))))

(defn- report-error! [api error]
  (ext/ui-notify api (str "pins: " (ex-message error)) :error))

(defn- add! [api ctx text label]
  (let [snapshot (model/add-pin (state ctx) text label (System/currentTimeMillis))
        pin (last (:pins snapshot))]
    (persist! ctx snapshot)
    (ext/ui-notify api (str "📌 Pinned as #" (:id pin) " \"" (:label pin)
                            "\" — recall with /pin show " (:id pin)) :info)))

(defn- close-dialog! [active]
  (when-let [close @active] (close nil)))

(defn- open-dialog! [api ctx active factory]
  (if (not= :interactive (:mode ctx))
    (ext/ui-notify api "/pin viewer and picker require interactive TUI mode" :warning)
    (do
      (close-dialog! active)
      (or
       (ext/ui-custom
        api
        (fn [tui _theme _kb host-close]
          (letfn [(close [result]
                    (when (identical? close @active)
                      (reset! active nil)
                      (host-close result)
                      true))]
            (reset! active close)
            (let [component (factory #(ui/terminal-size tui) close)]
              (assoc component :dispose
                     (fn []
                       (compare-and-set! active close nil)
                       ((:dispose component)))))))
        {:overlay true :overlay-options ui/overlay-options})
       (ext/ui-notify api "/pin requires an active TUI" :warning)))))

(defn- browse! [api ctx active arg]
  (let [pins (:pins (state ctx))
        index (if (str/blank? arg) 0
                  (first (keep-indexed #(when (= (model/parse-id arg) (:id %2)) %1) pins)))]
    (cond
      (nil? index) (ext/ui-notify api (str "No pin #" arg) :error)
      (empty? pins) (ext/ui-notify api "No pins yet — use /pin first" :warning)
      :else (open-dialog! api ctx active #(ui/make-browser pins index %1 %2)))))

(defn- pick! [api ctx active]
  (let [candidates (model/recent-assistants ((:get-branch (:session ctx))))]
    (if (empty? candidates)
      (ext/ui-notify api "No assistant messages to pin" :warning)
      (open-dialog! api ctx active
                    (fn [size-fn close]
                      (ui/make-picker candidates size-fn
                                      (fn [candidate]
                                        (when (and (close nil) candidate)
                                          (try (add! api ctx (:text candidate) nil)
                                               (catch Exception e (report-error! api e)))))))))))

(defn- command! [api active ctx args]
  (try
    (let [{:keys [action arg label]} (model/parse-command args)]
      (case action
        :help (if (= :interactive (:mode ctx))
                (ext/ui-chat-info api "Pins" help-text)
                (ext/ui-notify api help-text :info))
        :show (browse! api ctx active arg)
        :pick (pick! api ctx active)
        :clear (do (persist! ctx model/empty-state) (ext/ui-notify api "All pins removed" :info))
        :rm (let [snapshot (state ctx)
                  id (model/parse-id arg)
                  pin (some #(when (= id (:id %)) %) (:pins snapshot))]
              (if pin
                (do (persist! ctx (model/remove-pin snapshot id))
                    (ext/ui-notify api (str "Removed pin #" id " \"" (:label pin) "\"") :info))
                (ext/ui-notify api (if (str/blank? arg) "Usage: /pin rm <n>" (str "No pin #" arg)) :error)))
        :pin (if-let [last-message (first (model/recent-assistants ((:get-branch (:session ctx)))))]
               (add! api ctx (:text last-message) label)
               (ext/ui-notify api "No assistant message to pin" :warning))))
    (catch Exception e (report-error! api e))))

(defn init
  "Read the live branch on each command; only modal lifetime needs in-memory state."
  [api]
  (let [active (atom nil)]
    (reset! runtime active)
    (ext/register-command!
     api {:name "pin" :description "Pin assistant messages and recall them in a full-width viewer (/pin help)"
          :argument-hint "[label|pick|show [n]|list [n]|rm <n>|clear|help]"
          :get-argument-completions (fn [prefix]
                                      (seq (filter #(str/starts-with? (:value %) prefix) subcommands)))
          :handler (fn [ctx args] (command! api active ctx args))})
    (doseq [event [:session-start :session-tree :session-shutdown]]
      (ext/on-event api event (fn [_event _ctx] (close-dialog! active))))))

(defn shutdown
  "Dismiss a live modal before its loader and reactive roots are discarded."
  [_api]
  (when-let [active @runtime] (close-dialog! active))
  (reset! runtime nil))
