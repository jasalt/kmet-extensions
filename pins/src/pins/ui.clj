(ns pins.ui
  "Borderless full-width pin browser and recent-message picker."
  (:require [kmet.libs.reakt :as r]
            [kmet.tui.hiccup :as h]
            [kmet.tui.keys :as keys]
            [kmet.tui.protocols :as p]
            [kmet.tui.terminal :as terminal]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]
            [pins.model :as model]))

(def overlay-options
  {:width "100%" :max-height "80%" :anchor :top-left :margin 0
   :border :none :padding-x 0 :padding-y 0})

(defn terminal-size [tui]
  (if-let [term (some-> tui :terminal deref)]
    {:width (terminal/columns term) :height (terminal/rows term)}
    {:width 80 :height 24}))

(defn viewport-height [rows] (max 1 (quot (* 4 rows) 5)))

(defn browser-layout
  "Reserve content first on tiny terminals; normal chrome never clips the footer."
  [height pin-count]
  (let [header (if (>= height 3) 1 0)
        help (if (>= height 6) 1 0)
        position (if (>= height 4) 1 0)
        gap (if (>= height 8) 1 0)
        fixed (+ header help position (* 2 gap))
        list (if (>= height 5) (max 1 (min pin-count 6 (quot (- height fixed) 3))) 0)]
    {:header header :help help :position position :gap gap :list list
     :content (- height fixed list)}))

(defn- text-row [key text]
  [:truncated-text {:key key :text text :padding-x 0 :padding-y 0}])

(defn- window-start [selected rows total]
  (max 0 (min (- selected (quot rows 2)) (- total rows))))

(defn- position-text [pin {:keys [scroll count rows]}]
  (str "#" (:id pin) " · "
       (if (<= count rows) "all visible"
           (str (inc scroll) "–" (min (+ scroll rows) count) "/" count))))

(defn make-browser
  "Own three reactive roots and window the shared Markdown renderer's lines.
   The immutable pin snapshot cannot accidentally change during a modal visit."
  [pins initial-index size-fn close]
  (let [selected (atom (max 0 (min initial-index (dec (count pins)))))
        scroll (atom 0)
        layout (atom (browser-layout (viewport-height (:height (size-fn))) (count pins)))
        metrics (atom nil)
        disposed? (atom false)
        top (h/root
             (fn [_]
               (let [th (r/tracked-deref theme/theme-atom)
                     index (r/tracked-deref selected)
                     {:keys [header list gap]} (r/tracked-deref layout)
                     start (window-start index list (count pins))]
                 [:container
                  (when (pos? header) (text-row :title (theme/fg th :accent (str "📌 " (count pins) " pins"))))
                  (for [i (range start (+ start list))
                        :let [pin (nth pins i)]]
                    (text-row (:id pin)
                              (theme/fg th (if (= index i) :accent :muted)
                                        (str (if (= index i) "❯" " ") " #" (:id pin) " · " (:label pin)))))
                  (when (pos? gap) [:spacer {:lines gap}])])))
        content (h/root
                 (fn [_]
                   [:markdown {:text (:text (nth pins (r/tracked-deref selected)))
                               :padding-x 0
                               :theme (theme/get-markdown-theme (r/tracked-deref theme/theme-atom))}]))
        bottom (h/root
                (fn [_]
                  (let [th (r/tracked-deref theme/theme-atom)
                        pin (nth pins (r/tracked-deref selected))
                        {:keys [gap help position]} (r/tracked-deref layout)
                        m (r/tracked-deref metrics)]
                    [:container
                     (when (pos? gap) [:spacer {:lines gap}])
                     (when (pos? help) (text-row :keys (theme/fg th :accent "↑↓ scroll · PgUp/PgDn pin · g/G top/bottom · q/Esc/Enter close")))
                     (when (pos? position) (text-row :position (theme/fg th :muted (position-text pin m))))])))
        render! (fn [width]
                  (if @disposed? []
                      (let [height (viewport-height (:height (size-fn)))
                            dims (browser-layout height (count pins))
                            _ (reset! layout dims)
                            lines (vec (p/render content width))
                            count-lines (count lines)
                            content-rows (:content dims)
                            offset (max 0 (min @scroll (- count-lines content-rows)))
                            end (min count-lines (+ offset content-rows))
                            window (subvec lines offset end)]
                        (reset! scroll offset)
                        (reset! metrics {:scroll offset :count count-lines :rows content-rows})
                        (into [] (concat (p/render top width)
                                         (map #(u/truncate-to-width % width) window)
                                         (repeat (- content-rows (count window)) "")
                                         (p/render bottom width))))))
        max-scroll (fn [] (max 0 (- (:count @metrics) (:rows @metrics))))]
    {:render render!
     :handle-input
     (fn [data]
       (when-not @disposed?
         ;; Keys can arrive before the mount frame. Materialize metrics once.
         (when-not @metrics (render! (:width (size-fn))))
         (cond
           (or (keys/matches-key? data "escape") (keys/matches-key? data "enter")
               (keys/matches-key? data "q")) (close nil)
           (keys/matches-key? data "up") (swap! scroll #(max 0 (dec %)))
           (keys/matches-key? data "down") (swap! scroll #(min (max-scroll) (inc %)))
           (keys/matches-key? data "pageUp") (do (swap! selected #(max 0 (dec %))) (reset! scroll 0))
           (keys/matches-key? data "pageDown") (do (swap! selected #(min (dec (count pins)) (inc %))) (reset! scroll 0))
           (keys/matches-key? data "g") (reset! scroll 0)
           (or (= data "G") (keys/matches-key? data "shift+g")) (reset! scroll (max-scroll)))))
     :invalidate (fn [] (doseq [root [top content bottom]] (p/invalidate root)))
     :dispose (fn []
                (when (compare-and-set! disposed? false true)
                  (doseq [root [top content bottom]] (p/dispose root))))}))

(defn make-picker
  "Return the candidate by numeric identity, not its possibly duplicated preview label."
  [candidates size-fn close]
  (let [height (atom (:height (size-fn)))
        list-ref (h/ref)
        disposed? (atom false)
        items (mapv (fn [i {:keys [text ago]}]
                      {:value i :label (str (if (= ago 1) "last" (str ago " back"))
                                            " · " (model/preview text 80))})
                    (range) candidates)
        root (h/root
              (fn [_]
                (let [rows (viewport-height (r/tracked-deref height))
                      th (r/tracked-deref theme/theme-atom)]
                  [:container
                   (when (>= rows 3) (text-row :title (theme/fg th :accent "Pin which message?")))
                   [:select-list {:ref list-ref :items items
                                  ;; SelectList adds one scroll-info row when clipped.
                                  :height (max 1 (- rows (if (>= rows 3) 2 0) 1))
                                  :theme (theme/get-select-list-theme th)
                                  :on-select (fn [item] (close (nth candidates (:value item))))
                                  :on-escape #(close nil)}]
                   (when (>= rows 3) (text-row :keys (theme/fg th :muted "↑↓ choose · Enter pin · Esc cancel")))])))]
    {:render (fn [width]
               (if @disposed? []
                   (do (reset! height (:height (size-fn)))
                       (mapv #(u/truncate-to-width % width)
                             (take (viewport-height @height) (p/render root width))))))
     :handle-input (fn [data]
                     (when-not @disposed?
                       (when-let [list (h/materialize-ref! root list-ref)]
                         (p/handle-input list data))))
     :invalidate #(p/invalidate root)
     :dispose #(when (compare-and-set! disposed? false true) (p/dispose root))}))
