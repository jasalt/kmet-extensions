(ns pins.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [kmet.extension :as ext]
            [pins.core :as pins]
            [pins.model :as model]))

(def ^:private fixture (atom nil))

(use-fixtures :each
  (fn [test-fn]
    (let [{:keys [api state]} (ext/create-nullable-api)
          live (atom {:entries [] :branch []})
          dialogs (atom [])
          saved (atom [])
          fail? (atom false)
          session {:get-entries (fn [type] (is (= model/state-type type)) (:entries @live))
                   :get-branch (fn [] (:branch @live))
                   :append-entry! (fn [type data]
                                    (when @fail? (throw (ex-info "disk full" {:type :test/save-failed})))
                                    (swap! saved conj [type data])
                                    (swap! live update :entries conj {:data data})
                                    "entry-id")}
          api (-> api (assoc :session session)
                  (assoc-in [:ui :chat-info]
                            (fn [label content] (swap! state update :ui-calls conj [:chat-info label content]))))
          custom (fn [factory opts]
                   (let [p (promise)
                         component (atom nil)
                         close (fn [value]
                                 (when-not (realized? p)
                                   (when-let [c @component] ((:dispose c)))
                                   (deliver p value)))
                         c (factory {:terminal (atom nil)} nil nil close)]
                     (reset! component c)
                     (swap! dialogs conj {:component c :promise p :opts opts})
                     p))
          api (assoc-in api [:ui :custom] custom)]
      (pins/init api)
      (reset! fixture {:api api :state state :live live :dialogs dialogs
                       :saved saved :fail? fail? :session session})
      (try (test-fn)
           (finally (pins/shutdown api) (reset! fixture nil))))))

(defn- command! [args & [mode]]
  ((get-in @(:state @fixture) [:commands "pin" :handler])
   {:mode (or mode :interactive) :session (:session @fixture)} args))
(defn- calls [] (:ui-calls @(:state @fixture)))
(defn- notice [] (last (calls)))
(defn- snapshot [] (second (last @(:saved @fixture))))
(defn- assistant! [text] (swap! (:live @fixture) update :branch conj {:role :assistant :content text}))
(defn- dialog [] (last @(:dialogs @fixture)))
(defn- saved-count [] (count @(:saved @fixture)))

(deftest registration-and-completion-have-no-load-side-effects
  (let [s @(:state @fixture)
        completions (get-in s [:commands "pin" :get-argument-completions])]
    (is (= #{"pin"} (set (keys (:commands s)))))
    (is (empty? (:tools s)))
    (is (empty? (:entry-renderers s)))
    (is (empty? (:message-renderers s)))
    (is (empty? (calls)))
    (is (zero? (saved-count)))
    (is (= #{"pick" "show" "list" "rm" "clear" "help"} (set (map :value (completions "")))))
    (is (= ["show"] (map :value (completions "sh"))))
    (is (nil? (completions "unknown")))))

(deftest pin-latest-text-excludes-thinking-and-keeps-free-labels
  (assistant! [{:type :text :text " # A plan\nSteps "} {:type :thinking :text "secret"}])
  (assistant! [{:type :thinking :text "textless latest message"}])
  (command! "")
  (is (= [{:id 1 :label "A plan" :text "# A plan\nSteps"
           :pinned-at (get-in (snapshot) [:pins 0 :pinned-at])}] (:pins (snapshot))))
  (is (pos-int? (get-in (snapshot) [:pins 0 :pinned-at])))
  (is (= :info (last (notice))))
  (command! "pick some answer")
  (is (= "pick some answer" (get-in (snapshot) [:pins 1 :label])))
  (is (= 3 (:next-id (snapshot))))
  (is (not-any? #(contains? #{:send-message! :append-message} (first %)) (calls))))

(deftest missing-assistant-or-pins-warn-without-mutating-state
  (command! "")
  (is (= :warning (last (notice))))
  (command! "pick")
  (is (= :warning (last (notice))))
  (command! "show")
  (is (= [:notify "No pins yet — use /pin first" :warning] (notice)))
  (is (zero? (saved-count))))

(deftest remove-and-clear-snapshot-the-live-branch-and-reset-only-on-clear
  (assistant! "Answer")
  (command! "one")
  (command! "two")
  (command! "rm 1")
  (is (= [2] (map :id (:pins (snapshot)))))
  (command! "three")
  (is (= [2 3] (map :id (:pins (snapshot)))))
  (let [before (saved-count)]
    (doseq [args ["rm" "rm 9" "rm -1" "rm 2.0" "show 99" "show nonsense"]]
      (command! args) (is (= :error (last (notice)))))
    (is (= before (saved-count))))
  (command! "clear")
  (is (= model/empty-state (snapshot)))
  (command! "new")
  (is (= [1] (map :id (:pins (snapshot))))))

(deftest reads-state-after-branch-and-session-switch-without-an-event
  (assistant! "First branch")
  (command! "")
  (let [first-state (snapshot)]
    (reset! (:live @fixture) {:entries [] :branch [{:role :assistant :content "Second branch"}]})
    (command! "")
    (is (= ["Second branch"] (map :text (:pins (snapshot)))))
    (is (= [1] (map :id (:pins (snapshot)))))
    (swap! (:live @fixture) assoc :entries [{:data first-state}])
    (command! "again")
    (is (= ["First branch" "Second branch"] (map :text (:pins (snapshot)))))
    (is (= [1 2] (map :id (:pins (snapshot)))))))

(deftest failed-save-never-reports-a-pin-or-loses-a-snapshot
  (assistant! "Answer")
  (reset! (:fail? @fixture) true)
  (command! "")
  (is (= [:notify "pins: disk full" :error] (notice)))
  (is (zero? (saved-count)))
  (reset! (:fail? @fixture) false)
  (command! "")
  (is (= [1] (map :id (:pins (snapshot)))))
  (swap! (:live @fixture) assoc :entries [{:data {:pins "broken"}}])
  (command! "")
  (is (= :error (last (notice))))
  (is (str/includes? (second (notice)) "Invalid saved"))
  (is (= 1 (saved-count)))
  (command! "clear")
  (is (= model/empty-state (snapshot))))

(deftest show-and-list-open-the-same-immutable-full-width-viewer
  (assistant! "First answer")
  (command! "one")
  (assistant! "Second answer")
  (command! "two")
  (doseq [args ["show 2" "list 2"]]
    (command! args)
    (let [{:keys [component opts]} (dialog)
          lines ((:render component) 120)]
      (is (:overlay opts))
      (is (= "100%" (get-in opts [:overlay-options :width])))
      (is (= "80%" (get-in opts [:overlay-options :max-height])))
      (is (= :none (get-in opts [:overlay-options :border])))
      (is (some #(str/includes? % "Second answer") lines))))
  (is (= 2 (saved-count))))

(deftest help-always-shows-instructions-without-opening-the-browser
  (assistant! "Answer")
  (command! "")
  (command! "help")
  (is (= [:chat-info "Pins" pins/help-text] (notice)))
  (is (empty? @(:dialogs @fixture)))
  (command! "help" :print)
  (is (= [:notify pins/help-text :info] (notice))))

(deftest picker-is-asynchronous-and-cancel-does-not-pin
  (assistant! "Older answer")
  (assistant! "Newest answer")
  (command! "pick")
  (is (zero? (saved-count)))
  (let [{:keys [component promise]} (dialog)]
    ((:handle-input component) "\u001b[B")
    ((:handle-input component) "\r")
    (is (realized? promise)))
  (is (= ["Older answer"] (map :text (:pins (snapshot)))))
  (command! "pick")
  ((:handle-input (:component (dialog))) "\u001b")
  (is (= 1 (saved-count))))

(deftest headless-mutations-work-but-modal-commands-do-not-block
  (assistant! "Answer")
  (command! "" :print)
  (command! "show" :print)
  (is (= :warning (last (notice))))
  (command! "pick" :print)
  (is (= :warning (last (notice))))
  (is (empty? @(:dialogs @fixture)))
  (is (= 1 (saved-count))))

(deftest session-events-and-shutdown-close-live-modals-idempotently
  (assistant! "Answer")
  (command! "")
  (doseq [event [:session-start :session-tree :session-shutdown]]
    (command! "show")
    (let [{:keys [component promise]} (dialog)]
      (doseq [handler (get-in @(:state @fixture) [:handlers event])] (handler {} {}))
      (is (realized? promise))
      (is (= [] ((:render component) 80)))))
  (command! "show")
  (pins/shutdown (:api @fixture))
  (is (realized? (:promise (dialog))))
  (pins/shutdown (:api @fixture)))
