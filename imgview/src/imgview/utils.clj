(ns imgview.utils
  "Resolve image sources and launch self-contained browser viewers."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [imgview.codec :as codec]
            [kmet.libs.http :as http]))

(def ^:private mime-extensions
  {"image/png" "png" "image/jpeg" "jpg" "image/gif" "gif"
   "image/webp" "webp" "image/bmp" "bmp" "image/avif" "avif"
   "image/svg+xml" "svg"})

(def ^:private extension-mimes
  {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg"
   "gif" "image/gif" "webp" "image/webp" "bmp" "image/bmp"
   "avif" "image/avif" "svg" "image/svg+xml"})

(defn supported-image-mime?
  "Accept the seven upstream MIME types; this does not imply terminal support."
  [mime]
  (contains? mime-extensions (str/lower-case (or mime ""))))

(defn extension-for-mime [mime]
  (get (assoc mime-extensions "image/jpg" "jpg") (str/lower-case (or mime "")) "bin"))

(defn sniff-mime
  "Magic bytes first, then a path/URL extension hint, then octet-stream."
  [bytes & [hint]]
  (let [head (mapv #(bit-and % 0xff) (take 256 bytes))
        starts? (fn [offset signature]
                  (= signature (vec (take (count signature) (drop offset head)))))
        ascii (apply str (map char head))
        hint (if (re-find #"(?i)^https?://" (or hint ""))
               (first (str/split hint #"[?#]" 2)) hint)
        extension (some->> hint (re-find #"\.([^./\\]+)$") second str/lower-case)]
    (cond
      (starts? 0 [0x89 0x50 0x4e 0x47]) "image/png"
      (starts? 0 [0xff 0xd8 0xff]) "image/jpeg"
      (starts? 0 [0x47 0x49 0x46 0x38]) "image/gif"
      (and (starts? 0 [0x52 0x49 0x46 0x46])
           (starts? 8 [0x57 0x45 0x42 0x50])) "image/webp"
      (starts? 0 [0x42 0x4d]) "image/bmp"
      (and (starts? 4 [0x66 0x74 0x79 0x70])
           (or (starts? 8 [0x61 0x76 0x69 0x66])
               (starts? 8 [0x61 0x76 0x69 0x73]))) "image/avif"
      (re-find #"(?i)^\s*(?:<\?xml|<svg[\s>])" ascii) "image/svg+xml"
      :else (get extension-mimes extension "application/octet-stream"))))

(defn expand-home [path]
  (cond
    (= path "~") (str (fs/home))
    (str/starts-with? path "~/") (str (fs/path (fs/home) (subs path 2)))
    :else path))

(defn check-cancelled!
  "Do not load or launch a browser after the run's abort atom fires."
  [signal]
  (when (and signal @signal)
    (throw (ex-info "image viewing cancelled" {:type :imgview/cancelled}))))

(defn temp-root
  "Viewer/download scratch root; honors TMPDIR and Termux PREFIX before the host default."
  []
  (str (fs/path (or (not-empty (System/getenv "TMPDIR"))
                    (when-let [prefix (not-empty (System/getenv "PREFIX"))]
                      (fs/path prefix "tmp"))
                    (fs/temp-dir))
                "kmet-imgview")))

(defn- http-bytes [source signal]
  ;; :stream + io/copy preserves arbitrary binary data on both HTTP transports.
  ;; Download files are private, short-lived, and deleted even on cancellation.
  (let [response (http/get source {:as :stream :signal signal :throw? true
                                   :follow-redirects :normal})]
    (try
      (check-cancelled! signal)
      (when-not (<= 200 (:status response) 299)
        (throw (ex-info (str "failed to fetch " source ": HTTP " (:status response))
                        {:type :imgview/http-error :status (:status response)})))
      (let [root (temp-root)
            _ (fs/create-dirs root)
            file (fs/create-temp-file {:dir root :prefix "download-" :suffix ".bin"})]
        (try
          (with-open [out (io/output-stream (str file))]
            (io/copy (:body response) out))
          (check-cancelled! signal)
          {:bytes (fs/read-all-bytes file)
           :declared (get-in response [:headers "content-type"])}
          (finally (fs/delete-if-exists file))))
      (finally (http/close! response)))))

(defn- resolved [bytes mime label]
  {:bytes bytes :mime-type (str/lower-case mime)
   :source-label label :extension (extension-for-mime mime)})

(defn resolve-image
  "Load a local file (cwd/~), HTTP(S) URL, or data URI. HTTP uses the host's proxy-aware boundary."
  [source cwd & [signal]]
  (check-cancelled! signal)
  (when-not (and (string? source) (not (str/blank? source)))
    (throw (ex-info "image source is empty" {:type :imgview/invalid-source})))
  (let [source (str/trim source)
        image
        (cond
          (str/starts-with? source "data:")
          (let [[_ declared base64? payload] (re-matches #"(?s)data:([^;,]+)?(;base64)?,(.*)" source)]
            (when-not payload
              (throw (ex-info "malformed data: URI" {:type :imgview/invalid-data-uri})))
            (let [bytes (try
                          (if base64? (codec/decode-base64 payload) (codec/decode-uri-payload payload))
                          (catch Exception e
                            (throw (ex-info (str "malformed data: URI: " (ex-message e))
                                            {:type :imgview/invalid-data-uri} e))))
                  sniffed (sniff-mime bytes)]
              (resolved bytes (if (= sniffed "application/octet-stream")
                                (or declared "application/octet-stream") sniffed)
                        "<data uri>")))

          (re-find #"(?i)^https?://" source)
          (let [{:keys [bytes declared]} (http-bytes source signal)
                sniffed (sniff-mime bytes source)
                mime (if (= sniffed "application/octet-stream")
                       (or (some-> declared (str/split #";" 2) first str/trim not-empty)
                           "application/octet-stream") sniffed)]
            (resolved bytes mime source))

          :else
          (let [expanded (expand-home source)
                path (str (fs/normalize (fs/absolutize
                                         (if (fs/absolute? expanded) expanded
                                             (fs/path (or cwd (fs/cwd)) expanded)))))
                bytes (try
                        (when-not (fs/regular-file? path)
                          (throw (ex-info "not a regular file" {:type :imgview/not-a-file})))
                        (fs/read-all-bytes path)
                        (catch Exception e
                          (throw (ex-info (str "cannot read " path ": " (ex-message e))
                                          {:type :imgview/read-failed :path path} e))))]
            (resolved bytes (sniff-mime bytes path) path)))]
    (check-cancelled! signal)
    image))

(defn- escape-html [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn temp-html-for-image
  "Write a private self-contained viewer; it survives extension unload so the browser can read it."
  [image root]
  (fs/create-dirs root)
  (let [file (str (fs/absolutize (fs/create-temp-file {:dir root :prefix "imgview-" :suffix ".html"})))
        label (escape-html (:source-label image))
        mime (escape-html (:mime-type image))
        data (codec/encode-base64 (:bytes image))]
    (try
      (spit file
            (str "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
                 "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                 "<title>imgview: " label "</title>"
                 "<style>html,body{margin:0;height:100%;background:#111;color:#ddd;font:13px system-ui}"
                 ".wrap{display:flex;flex-direction:column;height:100%}"
                 "header{padding:8px 12px;background:#1a1a1a}code{color:#9cf}"
                 "main{flex:1;display:flex;align-items:center;justify-content:center;overflow:auto;padding:12px}"
                 "img{max-width:100%;max-height:100%;box-shadow:0 4px 20px #0008}</style>"
                 "</head><body><div class=\"wrap\"><header>imgview · <code>" label "</code> · "
                 (count (:bytes image)) " bytes · " mime "</header><main><img src=\"data:" mime
                 ";base64," data "\" alt=\"" label "\"></main></div></body></html>\n")
            :encoding "UTF-8")
      file
      (catch Exception e
        (fs/delete-if-exists file)
        (throw e)))))

(defn browser-command
  "The OS opener argv for an HTML viewer; no shell interpolation on Unix."
  [target os-name]
  (let [os (str/lower-case os-name)]
    (cond
      (str/starts-with? os "windows") ["cmd" "/c" "start" "" target]
      (or (str/includes? os "mac") (str/includes? os "darwin")) ["open" target]
      :else ["xdg-open" target])))

(defn open-in-browser!
  "Start the OS browser handler without waiting for the browser to exit. Missing openers throw."
  [target]
  (let [argv (browser-command target (System/getProperty "os.name"))]
    (proc/process argv {:in "" :out :discard :err :discard})
    {:command (first argv) :args (vec (rest argv))}))

(defn open-viewer!
  "Write and launch an HTML viewer. Only a successful launch returns browser-path."
  [image & [signal]]
  (check-cancelled! signal)
  (let [path (temp-html-for-image image (temp-root))]
    (check-cancelled! signal)
    (let [{:keys [command args]} (open-in-browser! path)]
      {:browser-path path :open-command (str/join " " (cons command args))})))
