(ns imgview.core-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [imgview.core :as imgview]
            [imgview.fixtures :as f]
            [imgview.utils :as utils]
            [kmet.extension :as ext]
            [kmet.libs.terminal-image :as terminal-image]
            [kmet.tui.hiccup :as h]
            [kmet.tui.protocols :as protocols]))

(def ^:private fixture (atom nil))

(use-fixtures :each
  (fn [test-fn]
    (f/with-scratch
      (fn []
        (let [{:keys [api state]} (ext/create-nullable-api)
              launches (atom [])]
          (imgview/init api)
          (reset! fixture {:state state :launches launches})
          (try
            (with-redefs [utils/temp-root (constantly (f/path "browser-viewers"))
                          utils/open-in-browser! (fn [path]
                                                   (swap! launches conj path)
                                                   {:command "test-opener" :args [path]})
                          terminal-image/get-capabilities (constantly {:images nil})]
              (test-fn))
            (finally (reset! fixture nil))))))))

(defn- state [] @(:state @fixture))
(defn- calls [] (:ui-calls (state)))
(defn- notice [] (last (calls)))
(defn- result-text [result] (:content result))
(defn- execute! [args & [on-update signal ctx]]
  ((get-in (state) [:tools "show_image" :execute])
   args on-update (or signal (atom false)) (or ctx {:cwd (:cwd @f/context)})))
(defn- command! [name args & [ctx]]
  ((get-in (state) [:commands name :handler])
   (or ctx {:cwd (:cwd @f/context) :mode :interactive}) args))

(deftest registrations-and-agent-guidance
  (is (= #{"show_image"} (set (keys (:tools (state))))))
  (is (= #{"imgcat" "imgshow" "imgboth"} (set (keys (:commands (state))))))
  (is (= #{"imgview-image"} (set (keys (:message-renderers (state))))))
  (is (empty? (calls)))
  (is (empty? @(:launches @fixture)))
  (is (empty? (:handlers (state))))
  (let [tool (get-in (state) [:tools "show_image"])]
    (is (:contextual? tool))
    (is (= ["source"] (get-in tool [:parameters :required])))
    (is (= ["terminal" "browser" "both"] (get-in tool [:parameters :properties :mode :enum])))
    (is (= 200 (get-in tool [:parameters :properties :caption :maxLength])))
    (is (some #(str/includes? % "never pick it on your own initiative") (:prompt-guidelines tool)))))

(deftest terminal-default-returns-a-host-native-image-attachment
  (let [updates (atom [])
        result (execute! {:source "pixel.png" :caption "A tiny screenshot"} #(swap! updates conj %))]
    (is (not (:is-error result)))
    (is (= [{:data f/png-data :mime-type "image/png"}] (:images result)))
    (is (string? (:content result)))
    (is (= "terminal" (get-in result [:details :mode])))
    (is (= (count f/png) (get-in result [:details :bytes])))
    (is (= (f/path "pixel.png") (get-in result [:details :resolved])))
    (is (= "A tiny screenshot" (get-in result [:details :caption])))
    (is (str/includes? (result-text result) "Caption: A tiny screenshot"))
    (is (str/includes? (result-text result) "placeholder"))
    (is (= [{:content "Loading pixel.png..."
             :details {:source "pixel.png" :mode "terminal"}}] @updates))
    (is (empty? @(:launches @fixture)))
    (is (empty? (calls)))))

(deftest browser-and-both-modes-are-explicit
  (doseq [mode ["browser" "both"]]
    (let [before (count @(:launches @fixture))
          result (execute! {:source f/data-uri :mode mode})
          browser-path (get-in result [:details :browser-path])]
      (is (not (:is-error result)))
      (is (= (inc before) (count @(:launches @fixture))))
      (is (= browser-path (last @(:launches @fixture))))
      (is (fs/regular-file? browser-path))
      (is (str/includes? (slurp browser-path) (str "data:image/png;base64," f/png-data)))
      (is (str/includes? (result-text result) (str "Browser: opened " browser-path)))
      (is (str/starts-with? (get-in result [:details :open-command]) "test-opener "))
      (is (= (if (= mode "both") 1 0) (count (:images result))))
      (is (not (contains? (:details result) :image))))))

(deftest browser-failures-never-claim-a-successful-launch
  (with-redefs [utils/open-in-browser! (fn [_] (throw (ex-info "missing opener" {:type :test/open-failed})))]
    (doseq [mode ["browser" "both"]]
      (let [result (execute! {:source f/data-uri :mode mode})]
        (is (= (= mode "browser") (:is-error result)))
        (is (= "missing opener" (get-in result [:details :browser-error])))
        (is (nil? (get-in result [:details :browser-path])))
        (is (str/includes? (result-text result) "Browser open failed: missing opener"))
        (is (not (str/includes? (result-text result) "Browser: opened")))
        (is (= (if (= mode "both") 1 0) (count (:images result))))))))

(deftest invalid-source-mode-and-caption-are-tool-errors
  (doseq [[args pattern]
          [[{} #"image source is empty"]
           [{:source " "} #"image source is empty"]
           [{:source f/data-uri :mode "surprise"} #"invalid mode"]
           [{:source f/data-uri :caption 42} #"caption"]
           [{:source f/data-uri :caption (apply str (repeat 201 "a"))} #"caption"]
           [{:source "missing.png"} #"cannot read"]
           [{:source "."} #"not a regular file"]
           [{:source "data:not-a-uri"} #"malformed data:"]
           [{:source "data:text/plain,ordinary%20text"} #"not a supported image type"]]]
    (let [result (execute! args)]
      (is (:is-error result))
      (is (re-find pattern (result-text result)))
      (is (nil? (:images result)))))
  (is (empty? @(:launches @fixture))))

(deftest cancellation-including-browser-launch-is-a-real-tool-error
  (let [updates (atom [])
        result (execute! {:source f/data-uri :mode "both"} #(swap! updates conj %) (atom true))]
    (is (:is-error result))
    (is (= :imgview/cancelled (get-in result [:details :type])))
    (is (empty? @updates))
    (is (empty? @(:launches @fixture))))
  (let [signal (atom false)]
    (with-redefs [utils/open-viewer! (fn [& _]
                                       (reset! signal true)
                                       (throw (ex-info "cancelled during viewer creation" {:type :test/cancelled})))]
      (let [result (execute! {:source f/data-uri :mode "both"} nil signal)]
        (is (:is-error result))
        (is (= :imgview/cancelled (get-in result [:details :type])))
        (is (nil? (:images result)))))))

(deftest oversize-is-only-a-warning-and-terminal-support-is-a-hint
  ;; Lower the threshold, not allocate an 8 MiB fixture.
  (with-redefs [imgview/soft-max-bytes 1
                terminal-image/get-capabilities (constantly {:images :kitty})]
    (let [result (execute! {:source f/data-uri})]
      (is (not (:is-error result)))
      (is (str/includes? (result-text result) "soft cap"))
      (is (not (str/includes? (result-text result) "does not advertise")))
      (is (= "image/png" (:mime-type (first (:images result)))))))
  (let [result (execute! {:source "data:image/svg+xml,%3Csvg%3E%3C/svg%3E"})]
    (is (not (:is-error result)))
    (is (str/includes? (result-text result) "without conversion"))
    (is (= "image/svg+xml" (:mime-type (first (:images result)))))
    (is (empty? @(:launches @fixture)))))

(deftest slash-commands-warn-on-missing-args-and-load-failures
  (doseq [name ["imgcat" "imgshow" "imgboth"]]
    (command! name " \t")
    (is (= [:notify (str "Usage: /" name " <path|url|data:uri>") :warning] (notice))))
  (doseq [source ["missing.png" "data:text/plain,not%20an%20image"]]
    (command! "imgcat" source)
    (is (= :error (last (notice))))
    (is (str/starts-with? (second (notice)) "imgview:")))
  (is (not-any? #(= :send-message! (first %)) (calls)))
  (is (empty? @(:launches @fixture))))

(deftest imgcat-persists-the-image-in-details-without-triggering-an-agent-turn
  (command! "imgcat" "  pixel.png  ")
  (let [[send message opts] (first (calls))]
    (is (= :send-message! send))
    (is (= "imgview-image" (:custom-type message)))
    (is (:display message))
    (is (= {:deliver-as :follow-up :trigger-turn false} opts))
    (is (= {:data f/png-data :mime-type "image/png"} (get-in message [:details :image])))
    (is (str/includes? (:content message) (f/path "pixel.png")))
    (is (string? (:content message))))
  (is (= :info (last (notice))))
  (is (empty? @(:launches @fixture))))

(deftest imgshow-is-browser-only-and-imgboth-adds-an-inline-message
  (command! "imgshow" f/data-uri)
  (is (= 1 (count @(:launches @fixture))))
  (is (= 1 (count (calls))))
  (is (= :info (last (notice))))
  (is (str/includes? (second (notice)) "browser="))
  (command! "imgboth" "pixel.png")
  (is (= 2 (count @(:launches @fixture))))
  (is (= 1 (count (filter #(= :send-message! (first %)) (calls)))))
  (is (str/includes? (second (notice)) "mode=both")))

(deftest command-browser-failure-still-allows-inline-output-for-both
  (with-redefs [utils/open-in-browser! (fn [_] (throw (ex-info "no opener" {:type :test/missing-opener})))]
    (command! "imgshow" f/data-uri)
    (is (= [[:notify "imgview: failed to open browser: no opener" :error]] (calls)))
    (command! "imgboth" f/data-uri)
    (is (= :error (last (nth (calls) 1))))
    (is (= :send-message! (first (nth (calls) 2))))
    (is (= :info (last (notice))))
    (is (not (str/includes? (second (notice)) "browser=")))))

(deftest tool-and-commands-read-the-current-cwd
  (let [other-cwd (f/path "other")
        other-data (byte-array (concat (take 8 f/png) [1 2 3]))]
    (fs/create-dirs other-cwd)
    (fs/write-bytes (fs/path other-cwd "pixel.png") other-data)
    (let [result (execute! {:source "pixel.png"} nil nil {:cwd other-cwd})]
      (is (= (str (fs/path other-cwd "pixel.png")) (get-in result [:details :resolved])))
      (is (= 11 (get-in result [:details :bytes]))))
    (command! "imgcat" "pixel.png" {:cwd other-cwd})
    (is (= 11 (get-in (second (first (calls))) [:details :bytes])))))

(deftest custom-renderer-is-owned-reactive-and-placeholder-safe
  (command! "imgcat" f/data-uri)
  (let [message (second (first (calls)))
        renderer (get-in (state) [:message-renderers "imgview-image"])
        component (renderer message)]
    (try
      (let [lines (protocols/render component 100)
            counters (h/counters)]
        (is (some #(str/includes? % "imgview: <data uri>") lines))
        (is (some #(str/includes? % "[Image:") lines))
        (is (= lines (protocols/render component 100)))
        (is (= (dissoc counters :bodies-skipped) (dissoc (h/counters) :bodies-skipped))
            "idle renders do not run bodies or rebuild the tree"))
      (finally (protocols/dispose component))))
  (testing "renderer also handles older/missing details"
    (let [component ((get-in (state) [:message-renderers "imgview-image"]) {:content [] :details {:resolved "old"}})]
      (try (is (str/includes? (str/join (protocols/render component 10)) "imgview:"))
           (finally (protocols/dispose component)))))
  (testing "supported terminals use the shared image component and max-width 60"
    (with-redefs [terminal-image/get-capabilities (constantly {:images :kitty})]
      (let [component ((get-in (state) [:message-renderers "imgview-image"])
                       {:content "pixel" :details {:image {:data f/png-data :mime-type "image/png"}}})]
        (try
          (let [lines (protocols/render component 100)]
            (is (some terminal-image/is-image-line lines))
            (is (str/includes? (str/join lines) "c=60")))
          (finally (protocols/dispose component)))))))
