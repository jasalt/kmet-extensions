(ns imgview.core
  "Display existing images through show_image and /imgcat, /imgshow, /imgboth."
  (:require [clojure.string :as str]
            [imgview.codec :as codec]
            [imgview.utils :as utils]
            [kmet.extension :as ext]
            [kmet.libs.reakt :as r]
            [kmet.libs.terminal-image :as terminal-image]
            [kmet.tui.hiccup :as h]
            [kmet.tui.theme :as theme]))

(def soft-max-bytes
  "Warn-only context-size threshold; images are not resized or rejected above it."
  (* 8 1024 1024))

(def ^:private modes #{"terminal" "browser" "both"})
(def ^:private inline-mimes #{"image/png" "image/jpeg" "image/gif"})

(defn- inline? [mode] (contains? #{"terminal" "both"} mode))
(defn- browser? [mode] (contains? #{"browser" "both"} mode))

(defn- byte-label [n]
  (let [s (str n)]
    (str/replace s #"\B(?=(\d{3})+(?!\d))" ",")))

(defn- image-label [image]
  (str (:source-label image) " (" (:mime-type image) ", "
       (byte-label (count (:bytes image))) " bytes)"))

(defn- image-details [source mode image]
  {:source source :resolved (:source-label image) :mime-type (:mime-type image)
   :bytes (count (:bytes image)) :mode mode})

(defn- validate-input! [{:keys [source mode caption]}]
  (when-not (and (string? source) (not (str/blank? source)))
    (throw (ex-info "image source is empty" {:type :imgview/invalid-source})))
  (when-not (contains? modes mode)
    (throw (ex-info (str "invalid mode: " mode "; expected terminal, browser, or both")
                    {:type :imgview/invalid-mode})))
  (when (and (some? caption) (or (not (string? caption)) (> (count caption) 200)))
    (throw (ex-info "caption must be a string of at most 200 characters"
                    {:type :imgview/invalid-caption}))))

(defn- resolve-supported! [source ctx signal]
  (let [image (utils/resolve-image source (:cwd ctx) signal)]
    (when-not (utils/supported-image-mime? (:mime-type image))
      (throw (ex-info (str "Refusing to show " (:source-label image)
                           ": detected MIME " (:mime-type image) " is not a supported image type.")
                      {:type :imgview/unsupported-mime :mime-type (:mime-type image)})))
    image))

(defn- browser-result [image mode signal]
  (when (browser? mode)
    (try
      (utils/open-viewer! image signal)
      (catch Exception e
        ;; Cancellation is not a browser-only warning: it cancels the whole call.
        (utils/check-cancelled! signal)
        {:browser-error (ex-message e)}))))

(defn- inline-note [image mode]
  (when (inline? mode)
    (cond
      (not (contains? inline-mimes (:mime-type image)))
      (str "Note: kmet cannot render " (:mime-type image)
           " inline without conversion; the attachment will show a placeholder. "
           "Use mode='browser' only if the user requests a browser view.")
      (not (:images (terminal-image/get-capabilities)))
      (str "Note: this terminal does not advertise inline image support. "
           "Kmet will show an attachment placeholder; use mode='browser' if the user requests it."))))

(defn- summary [image mode caption browser]
  (str/join "\n"
            (remove nil?
                    [(str "Showed " (image-label image) " via mode=" mode ".")
                     (when (seq caption) (str "Caption: " caption))
                     (when-let [path (:browser-path browser)] (str "Browser: opened " path "."))
                     (when-let [error (:browser-error browser)] (str "Browser open failed: " error "."))
                     (when (> (count (:bytes image)) soft-max-bytes)
                       (str "Note: image is " (byte-label (count (:bytes image))) " bytes (> "
                            (byte-label soft-max-bytes)
                            " soft cap); inline rendering still works but the encoded form is large in context."))
                     (inline-note image mode)])))

(defn- execute [args on-update signal ctx]
  (try
    (let [{:keys [source caption] :as input} (assoc args :mode (or (:mode args) "terminal"))
          mode (:mode input)]
      (validate-input! input)
      (utils/check-cancelled! signal)
      (when on-update
        (on-update {:content (str "Loading " source "...")
                    :details {:source source :mode mode}}))
      (let [image (resolve-supported! source ctx signal)
            browser (browser-result image mode signal)]
        (utils/check-cancelled! signal)
        ;; Kmet's tool/UI/provider pipeline expects text in :content and
        ;; attachments in :images (not Pi's mixed content vector).
        (cond-> {:content (summary image mode caption browser)
                 :details (merge (image-details source mode image) browser
                                 (when (some? caption) {:caption caption}))
                 ;; Browser-only failure has not displayed anything. In both
                 ;; mode preserve the useful inline image as partial success.
                 :is-error (boolean (and (= mode "browser") (:browser-error browser)))}
          (inline? mode) (assoc :images [{:data (codec/encode-base64 (:bytes image))
                                          :mime-type (:mime-type image)}]))))
    (catch Exception e
      {:content (str "show_image failed: " (ex-message e))
       :is-error true :details {:type (or (:type (ex-data e)) :imgview/load-failed)}})))

(defn- render-message [message]
  (h/root
   (fn [_]
     (let [thm (r/tracked-deref theme/theme-atom)
           image (get-in message [:details :image])
           content (:content message)
           label (cond
                   (string? content) content
                   (seq content) (str/join "\n" (keep :text content))
                   :else (str "imgview: " (or (get-in message [:details :resolved]) "<image>")))
           show? (and (string? (:data image)) (string? (:mime-type image)))]
       [:container
        [:text {:padding-x 0 :padding-y 0 :text (theme/fg thm :accent label)}]
        (when show? [:spacer {:lines 1}])
        (when show?
          [:image {:base64-data (:data image) :mime-type (:mime-type image)
                   :theme {:fallback-color (partial theme/fg thm :muted)}
                   :max-width-cells 60}])]))))

(defn- user-command! [api command mode ctx args]
  (let [source (str/trim (or args ""))]
    (if (str/blank? source)
      (ext/ui-notify api (str "Usage: /" command " <path|url|data:uri>") :warning)
      (try
        (let [image (resolve-supported! source ctx nil)
              browser (browser-result image mode nil)]
          (when-let [error (:browser-error browser)]
            (ext/ui-notify api (str "imgview: failed to open browser: " error) :error))
          (when (inline? mode)
            (ext/send-message! api
                               {:custom-type "imgview-image"
                                :content (str "imgview: " (image-label image))
                                :display true
                                :details (assoc (image-details source mode image)
                                                :image {:data (codec/encode-base64 (:bytes image))
                                                        :mime-type (:mime-type image)})}
                               {:deliver-as :follow-up :trigger-turn false}))
          (when (or (inline? mode) (:browser-path browser))
            (ext/ui-notify api
                           (str "imgview: " (:source-label image) "\n"
                                "mime=" (:mime-type image) " bytes=" (byte-label (count (:bytes image)))
                                " mode=" mode
                                (when-let [path (:browser-path browser)] (str "\nbrowser=" path)))
                           :info)))
        (catch Exception e
          (ext/ui-notify api (str "imgview: " (ex-message e)) :error))))))

(defn init
  "Register the image tool, transcript renderer, and commands; launch nothing on load."
  [api]
  (ext/register-message-renderer! api "imgview-image" render-message)
  (ext/register-tool! api
                      {:name "show_image" :label "Show image"
                       :description "Display an image to the user. Renders inline in supported terminals and/or opens it in the user's default browser. Use this whenever you want the user to see a screenshot, generated diagram, local image, or image at a URL."
                       :prompt-snippet "show_image — display an image to the user inline in the terminal and/or in the browser"
                       :prompt-guidelines
                       ["Use show_image when the user asks to view, see, or display an image, screenshot, plot, or diagram."
                        "Default to mode='terminal'. Do NOT use mode='browser' or mode='both' unless the user explicitly asks to open the image in a browser or asks to zoom into a high-resolution image. Browser mode launches an external window and is disruptive — never pick it on your own initiative."
                        "Pass source as the literal path or URL the user gave you; do not paraphrase. For local files, ~ and relative paths are fine."
                        "Add a short caption when the image's relevance is not obvious from context."]
                       :parameters {:type "object" :required ["source"]
                                    :properties
                                    {:source {:type "string" :minLength 1
                                              :description "Local image path (absolute, relative to cwd, or ~/...), HTTP(S) URL, or data URI."}
                                     :mode {:type "string" :enum ["terminal" "browser" "both"]
                                            :description "terminal (default, preferred) shows an inline attachment. browser/both launch an external window: use only on explicit user request."}
                                     :caption {:type "string" :maxLength 200
                                               :description "Optional short note explaining the image."}}}
                       :contextual? true :execute execute})
  (doseq [[command mode description]
          [["imgcat" "terminal" "Render an image inline in the terminal"]
           ["imgshow" "browser" "Open an image in the default browser"]
           ["imgboth" "both" "Render an image inline AND open it in the browser"]]]
    (ext/register-command! api
                           {:name command :description (str description ": /" command " <path|url|data:uri>")
                            :argument-hint "<path|url|data:uri>"
                            :handler (fn [ctx args] (user-command! api command mode ctx args))})))
