(ns imgview.utils-test
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [imgview.codec :as codec]
            [imgview.fixtures :as f]
            [imgview.utils :as utils]
            [kmet.libs.http :as http]))

(use-fixtures :each f/with-scratch)

(deftest payload-codecs
  (testing "standard padded base64, binary round trips, whitespace and unpadded forms"
    (doseq [bytes [(byte-array []) (f/octets (range 256)) f/png (codec/utf8-bytes "Résumé 決定 🐈")]]
      (let [encoded (codec/encode-base64 bytes)]
        (is (zero? (mod (count encoded) 4)))
        (is (not (re-find #"[-_]" encoded)))
        (is (= (vec bytes) (vec (codec/decode-base64 encoded))))
        (is (= (vec bytes) (vec (codec/decode-base64 (str/replace encoded #"=" "")))))))
    (is (= (vec f/png) (vec (codec/decode-base64 (str "\n" f/png-data " \t"))))))
  (testing "UTF-8, percent decoding and literal plus signs"
    (is (= [0x52 0xc3 0xa9 0x73 0x75 0x6d 0xc3 0xa9 0x20 0xf0 0x9f 0x90 0x88]
           (mapv #(bit-and % 0xff) (codec/utf8-bytes "Résumé 🐈"))))
    (is (= (vec (codec/utf8-bytes "<svg>🐈+é</svg>"))
           (vec (codec/decode-uri-payload "%3Csvg%3E%F0%9F%90%88+é%3C%2Fsvg%3E"))))
    (is (= [137 80 78 71] (mapv #(bit-and % 0xff) (codec/decode-uri-payload "%89PNG"))))
    (doseq [payload ["%" "%GG" "%0" "%12%q1"]]
      (is (thrown-with-msg? Exception #"malformed percent escape" (codec/decode-uri-payload payload))))))

(deftest magic-mime-detection
  (doseq [[bytes mime]
          [[f/png "image/png"]
           [(f/octets [0xff 0xd8 0xff 0xe0]) "image/jpeg"]
           [(codec/utf8-bytes "GIF89a") "image/gif"]
           [(codec/utf8-bytes "RIFF1234WEBP") "image/webp"]
           [(codec/utf8-bytes "BM") "image/bmp"]
           [(codec/utf8-bytes "1234ftypavif") "image/avif"]
           [(codec/utf8-bytes "1234ftypavis") "image/avif"]
           [(codec/utf8-bytes " \n<svg xmlns=\"x\"></svg>") "image/svg+xml"]
           [(codec/utf8-bytes "<?xml version=\"1.0\"?><svg/>") "image/svg+xml"]]]
    (is (= mime (utils/sniff-mime bytes)))
    (is (= mime (utils/sniff-mime bytes "wrong.bmp")))))

(deftest mime-fallback-and-supported-types
  (doseq [[extension mime] [["PNG" "image/png"] ["jpg" "image/jpeg"] ["jpeg" "image/jpeg"]
                            ["gif" "image/gif"] ["webp" "image/webp"] ["bmp" "image/bmp"]
                            ["avif" "image/avif"] ["svg" "image/svg+xml"]]]
    (is (= mime (utils/sniff-mime (f/octets [1 2 3]) (str "file." extension))))
    (is (utils/supported-image-mime? (str/upper-case mime))))
  (is (= "image/jpeg" (utils/sniff-mime (f/octets [1 2]) "https://x.test/a.jpeg?token=1#part")))
  (doseq [bytes [(byte-array []) (f/octets [1 2 3 4]) (codec/utf8-bytes "<html>not an image</html>")
                 (codec/utf8-bytes "RIFF1234WAVE") (codec/utf8-bytes "1234ftypheic")]]
    (is (= "application/octet-stream" (utils/sniff-mime bytes))))
  (doseq [mime [nil "" "text/plain" "application/pdf" "image/tiff"]]
    (is (not (utils/supported-image-mime? mime))))
  (doseq [[mime extension] [["IMAGE/PNG" "png"] ["image/jpeg" "jpg"] ["image/svg+xml" "svg"]
                            ["image/avif" "avif"] ["application/zip" "bin"]]]
    (is (= extension (utils/extension-for-mime mime)))))

(deftest file-resolution-and-live-cwd
  (doseq [source ["pixel.png" " ./unused/../pixel.png " (f/path "pixel.png")]]
    (let [image (utils/resolve-image source (:cwd @f/context))]
      (is (= "image/png" (:mime-type image)))
      (is (= (vec f/png) (vec (:bytes image))))
      (is (= (f/path "pixel.png") (:source-label image)))
      (is (= "png" (:extension image)))))
  (with-redefs [fs/home (constantly (:cwd @f/context))]
    (is (= (:cwd @f/context) (utils/expand-home "~")))
    (is (= (f/path "pixel.png") (utils/expand-home "~/pixel.png")))
    (is (= "~someone/file.png" (utils/expand-home "~someone/file.png")))
    (is (= "relative/path" (utils/expand-home "relative/path")))
    (is (= "image/png" (:mime-type (utils/resolve-image "~/pixel.png" "/different-cwd")))))
  (doseq [source [nil "" " \t\n"]]
    (is (thrown-with-msg? Exception #"image source is empty" (utils/resolve-image source (:cwd @f/context)))))
  (is (thrown-with-msg? Exception #"cannot read.*missing" (utils/resolve-image "missing.png" (:cwd @f/context))))
  (is (thrown-with-msg? Exception #"not a regular file" (utils/resolve-image "." (:cwd @f/context))))
  (spit (f/path "text.txt") "not an image")
  (is (= "application/octet-stream" (:mime-type (utils/resolve-image "text.txt" (:cwd @f/context))))))

(deftest data-uri-resolution
  (let [image (utils/resolve-image (str " " f/data-uri " \n") (:cwd @f/context))]
    (is (= "image/png" (:mime-type image)))
    (is (= "<data uri>" (:source-label image)))
    (is (= (vec f/png) (vec (:bytes image))))
    (is (= "png" (:extension image))))
  (testing "magic bytes override a misleading declaration"
    (is (= "image/png" (:mime-type (utils/resolve-image (str "data:text/plain;base64," f/png-data) "."))))
    (is (= "image/png" (:mime-type (utils/resolve-image (str "data:;base64," f/png-data) ".")))))
  (testing "unencoded SVG, percent-encoded binary, empty content and declared MIME fallback"
    (is (= "image/svg+xml" (:mime-type (utils/resolve-image "data:image/svg+xml,%3Csvg%3Eé+🐈%3C/svg%3E" "."))))
    (is (= "image/png" (:mime-type (utils/resolve-image "data:,%89PNG" "."))))
    (is (= "image/webp" (:mime-type (utils/resolve-image "data:IMAGE/WEBP;base64,AQID" "."))))
    (is (= "application/octet-stream" (:mime-type (utils/resolve-image "data:," ".")))))
  (doseq [source ["data:not-a-uri" "data:image/png;charset=utf-8,abc" "data:image/png;base64,???"
                  "data:image/svg+xml,%GG" "data:,%"]]
    (is (thrown-with-msg? Exception #"malformed data:" (utils/resolve-image source ".")))))

(deftest http-fetch-is-binary-safe-and-closes-streams
  (let [calls (atom [])
        closed (atom [])
        signal (atom false)
        root (f/path "scratch")]
    (with-redefs [utils/temp-root (constantly root)
                  http/get (fn [url opts]
                             (swap! calls conj [url opts])
                             {:status 200 :headers {"content-type" "text/plain"}
                              :body (io/input-stream f/png)})
                  http/close! (fn [response]
                                (swap! closed conj response)
                                (with-open [_ (:body response)] nil))]
      (let [source "https://example.test/misleading.jpg?x=1"
            image (utils/resolve-image source (:cwd @f/context) signal)
            [url opts] (first @calls)]
        (is (= source url))
        (is (= :stream (:as opts)))
        (is (= :normal (:follow-redirects opts)))
        (is (:throw? opts))
        (is (identical? signal (:signal opts)))
        (is (= source (:source-label image)))
        (is (= "image/png" (:mime-type image)))
        (is (= (vec f/png) (vec (:bytes image))))
        (is (= 1 (count @closed)))
        (is (empty? (fs/list-dir root)))))))

(deftest http-mime-fallback-and-failures
  (let [closed (atom 0)
        root (f/path "downloads")]
    (with-redefs [utils/temp-root (constantly root)
                  http/close! (fn [response]
                                (swap! closed inc)
                                (with-open [_ (:body response)] nil))]
      (doseq [[source declared expected]
              [["http://x.test/opaque" "IMAGE/SVG+XML; charset=utf-8" "image/svg+xml"]
               ["https://x.test/photo.PNG?download=1" "text/plain" "image/png"]
               ["https://x.test/opaque" nil "application/octet-stream"]]]
        (with-redefs [http/get (fn [& _] {:status 200 :headers {"content-type" declared}
                                          :body (io/input-stream (f/octets [1 2 3 255]))})]
          (is (= expected (:mime-type (utils/resolve-image source "."))))))
      (with-redefs [http/get (fn [& _] {:status 404 :headers {} :body (io/input-stream (byte-array []))})]
        (is (thrown-with-msg? Exception #"HTTP 404" (utils/resolve-image "https://x.test/missing" "."))))
      (is (= 4 @closed))
      (is (empty? (fs/list-dir root))))))

(deftest cancellation-prevents-and-cleans-work
  (let [signal (atom true)
        fetched (atom 0)
        closed (atom 0)
        root (f/path "cancelled")]
    (with-redefs [utils/temp-root (constantly root)
                  http/get (fn [& _]
                             (swap! fetched inc)
                             (reset! signal true)
                             {:status 200 :headers {} :body (io/input-stream f/png)})
                  http/close! (fn [response]
                                (swap! closed inc)
                                (with-open [_ (:body response)] nil))]
      (is (thrown-with-msg? Exception #"cancelled" (utils/resolve-image "https://x.test/a" "." signal)))
      (is (zero? @fetched))
      (reset! signal false)
      (is (thrown-with-msg? Exception #"cancelled" (utils/resolve-image "https://x.test/a" "." signal)))
      (is (= 1 @closed))
      (is (not (fs/exists? root))))))

(deftest html-viewer-embeds-image-and-escapes-labels
  (let [root (f/path "viewers/nested")
        label "test/<source>&\"'.png — 🐈"
        image {:bytes f/png :mime-type "image/png" :source-label label}
        path (utils/temp-html-for-image image root)
        second-path (utils/temp-html-for-image image root)
        html (slurp path :encoding "UTF-8")]
    (is (fs/absolute? path))
    (is (str/ends-with? path ".html"))
    (is (not= path second-path))
    (is (str/includes? html (str "data:image/png;base64," f/png-data)))
    (is (str/includes? html "test/&lt;source&gt;&amp;&quot;&#39;.png — 🐈"))
    (is (not (str/includes? html "test/<source>")))
    (is (str/includes? html "<title>imgview:"))
    (is (str/includes? html "max-width:100%"))
    (when-not (fs/windows?)
      (is (= #{"OWNER_READ" "OWNER_WRITE"} (set (map str (fs/posix-file-permissions path))))))))

(deftest browser-argv-and-launching
  (let [target (f/path "with spaces & symbols.html")]
    (is (= ["open" target] (utils/browser-command target "Mac OS X")))
    (is (= ["open" target] (utils/browser-command target "Darwin")))
    (is (= ["cmd" "/c" "start" "" target] (utils/browser-command target "Windows 11")))
    (is (= ["xdg-open" target] (utils/browser-command target "Linux")))
    (let [calls (atom [])]
      (with-redefs [proc/process (fn [argv opts] (swap! calls conj [argv opts]) {})]
        (let [result (utils/open-in-browser! target)
              [argv opts] (first @calls)]
          (is (= argv (into [(:command result)] (:args result))))
          (is (= target (last argv)))
          (is (= {:in "" :out :discard :err :discard} opts)))))
    (with-redefs [proc/process (fn [& _] (throw (ex-info "opener unavailable" {:type :test/open-failed})))]
      (is (thrown-with-msg? Exception #"opener unavailable" (utils/open-in-browser! target))))))

(deftest open-viewer-orders-write-and-launch-and-obeys-cancellation
  (let [root (f/path "browser")
        image {:bytes f/png :mime-type "image/png" :source-label "pixel"}
        launches (atom [])]
    (with-redefs [utils/temp-root (constantly root)
                  utils/open-in-browser! (fn [path]
                                           (is (fs/regular-file? path))
                                           (swap! launches conj path)
                                           {:command "xdg-open" :args [path]})]
      (is (thrown-with-msg? Exception #"cancelled" (utils/open-viewer! image (atom true))))
      (is (empty? @launches))
      (is (not (fs/exists? root)))
      (let [result (utils/open-viewer! image)]
        (is (= (first @launches) (:browser-path result)))
        (is (str/starts-with? (:open-command result) "xdg-open "))))))
