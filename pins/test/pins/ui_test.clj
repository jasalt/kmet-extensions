(ns pins.ui-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.tui.hiccup :as h]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]
            [pins.ui :as ui]))

(defn- plain [lines] (mapv u/strip-ansi-codes lines))
(defn- pin [id text] {:id id :label (str "Pin " id) :text text :pinned-at 1})
(defn- contents [prefix n] (str/join "\n\n" (map #(str prefix %) (range n))))
(defn- render! [component width] (plain ((:render component) width)))
(defn- input! [component data] ((:handle-input component) data))

(deftest overlay-is-full-width-top-anchored-and-borderless
  (is (= {:width "100%" :max-height "80%" :anchor :top-left :margin 0
          :border :none :padding-x 0 :padding-y 0} ui/overlay-options))
  (is (= 19 (ui/viewport-height 24)))
  (doseq [height (range 1 80) pin-count [1 2 10 40]]
    (let [{:keys [header help position gap list content]} (ui/browser-layout height pin-count)]
      (is (= height (+ header help position (* 2 gap) list content)))
      (is (pos? content))
      (is (<= 0 list (min pin-count 6))))))

(deftest renders-the-entire-width-without-side-insets-or-border-glyphs
  (let [pins [(pin 1 (apply str (repeat 140 "x")))]
        c (ui/make-browser pins 0 (constantly {:width 140 :height 24}) (fn [_]))]
    (try
      (let [lines (render! c 140)]
        (is (= 19 (count lines)))
        (is (some #(= (apply str (repeat 140 "x")) %) lines))
        (is (str/starts-with? (first lines) "📌"))
        (is (not-any? #(re-find #"[│╭╮╰╯├┤]" %) lines))
        (is (every? #(<= (u/visible-width %) 140) lines)))
      (finally ((:dispose c))))))

(deftest scrolling-pin-switching-and-top-bottom-keys
  (let [c (ui/make-browser [(pin 1 (contents "Alpha " 40)) (pin 2 "Beta only")]
                           0 (constantly {:width 80 :height 20}) (fn [_]))]
    (try
      (is (some #(str/starts-with? % "Alpha 0") (render! c 80)))
      (input! c "\u001b[B")
      (is (some #(str/includes? % "· 2–") (render! c 80)))
      (input! c "\u001b[A")
      (is (some #(str/includes? % "· 1–") (render! c 80)))
      (input! c "G")
      (is (some #(str/includes? % "Alpha 39") (render! c 80)))
      (input! c "g")
      (is (some #(str/starts-with? % "Alpha 0") (render! c 80)))
      (input! c "\u001b[6~")
      (is (some #(str/starts-with? % "Beta only") (render! c 80)))
      (is (str/starts-with? (last (render! c 80)) "#2 · all visible"))
      (input! c "\u001b[6~")
      (is (some #(str/starts-with? % "Beta only") (render! c 80)))
      (input! c "\u001b[5~")
      (is (some #(str/starts-with? % "Alpha 0") (render! c 80)))
      (finally ((:dispose c))))))

(deftest resize-reflows-and-tiny-terminals-keep-content-reachable
  (let [size (atom {:width 130 :height 24})
        c (ui/make-browser [(pin 1 (contents "Wide line here " 80))] 0 #(deref size) (fn [_]))]
    (try
      (render! c 130)
      (input! c "G")
      (doseq [[width height] [[70 40] [11 8] [1 1] [2 2] [4 3] [100 24]]]
        (reset! size {:width width :height height})
        (let [lines (render! c width)]
          (is (= (ui/viewport-height height) (count lines)))
          (is (every? #(<= (u/visible-width %) width) lines))))
      (input! c "g")
      (is (some #(str/starts-with? % "Wide line here 0") (render! c 100)))
      (finally ((:dispose c))))))

(deftest markdown-table-and-code-use-the-shared-renderer
  (let [c (ui/make-browser [(pin 1 "| A | B |\n|---|---|\n| yes | no |\n\n```sh\necho hello\n```")]
                           0 (constantly {:width 120 :height 50}) (fn [_]))]
    (try
      (let [lines (render! c 120)]
        (is (some #(str/includes? % "yes") lines))
        (is (some #(str/includes? % "echo hello") lines))
        (is (some #(str/includes? % "│") lines) "Markdown tables retain their own structure"))
      (finally ((:dispose c))))))

(deftest idle-roots-cache-and-dispose-unwatches-theme
  (let [watches (atom #{})
        add add-watch
        remove remove-watch
        c (ui/make-browser [(pin 1 "Hello **world**")] 0 (constantly {:width 80 :height 24}) (fn [_]))]
    (with-redefs [clojure.core/add-watch (fn [a key f]
                                           (when (identical? a theme/theme-atom) (swap! watches conj key))
                                           (add a key f))
                  clojure.core/remove-watch (fn [a key]
                                              (when (identical? a theme/theme-atom) (swap! watches disj key))
                                              (remove a key))]
      (try
        ((:render c) 80)
        (is (seq @watches))
        (h/reset-counters!)
        ((:render c) 80)
        (is (zero? (:bodies-run (h/counters))))
        ((:dispose c))
        ((:dispose c))
        (is (empty? @watches))
        (is (= [] ((:render c) 80)))
        (finally ((:dispose c)))))))

(deftest escape-enter-q-close-even-before-the-first-frame
  (doseq [key ["q" "\u001b" "\r" "\u001b[113u"]]
    (let [closed (atom [])
          c (ui/make-browser [(pin 1 "text")] 0 (constantly {:width 80 :height 24}) #(swap! closed conj %))]
      (try
        (input! c key)
        (is (= [nil] @closed))
        (finally ((:dispose c)))))))

(deftest picker-keeps-candidate-identity-and-supports-cancel-before-paint
  (let [shared-preview (apply str (repeat 100 "x"))
        candidates [{:text (str shared-preview " A") :ago 1}
                    {:text (str shared-preview " B") :ago 2}]
        choice (atom nil)
        c (ui/make-picker candidates (constantly {:width 120 :height 24}) #(reset! choice %))]
    (try
      (input! c "\u001b[B")
      (input! c "\r")
      (is (= (second candidates) @choice))
      (finally ((:dispose c)))))
  (let [closed (atom [])
        c (ui/make-picker [{:text "x" :ago 1}] (constantly {:width 80 :height 24}) #(swap! closed conj %))]
    (try (input! c "\u001b") (is (= [nil] @closed))
         (finally ((:dispose c))))))
