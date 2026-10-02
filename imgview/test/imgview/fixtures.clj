(ns imgview.fixtures
  (:require [babashka.fs :as fs]
            [imgview.codec :as codec]))

(def png-data "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jBNsAAAAASUVORK5CYII=")
(def png (codec/decode-base64 png-data))
(def data-uri (str "data:image/png;base64," png-data))
(def context (atom nil))

(defn with-scratch [test-fn]
  (fs/create-dirs "target")
  (let [dir (str (fs/absolutize (fs/create-temp-dir {:dir "target" :prefix "imgview-test-"})))
        cwd (str (fs/path dir "project"))]
    (fs/create-dirs cwd)
    (fs/write-bytes (fs/path cwd "pixel.png") png)
    (reset! context {:dir dir :cwd cwd})
    (try (test-fn)
         (finally (fs/delete-tree dir) (reset! context nil)))))

(defn octets [xs] (byte-array (map unchecked-byte xs)))
(defn path [& parts] (str (apply fs/path (:cwd @context) parts)))
