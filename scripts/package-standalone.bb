;; Embed extension sources and replace the host JAR's entry point.
(require '[babashka.fs :as fs]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn put! [archive name input]
  ;; Fixed ZIP timestamps keep builds independent of the clock and file mtimes.
  (let [entry (java.util.zip.ZipEntry. name)]
    (.setTime entry 315532800000)
    (.putNextEntry archive entry)
    (io/copy input archive)
    (.closeEntry archive)))

(let [[host root output] *command-line-args*
      sources (java.io.ByteArrayOutputStream.)]
  (assert (and host root output) "Usage: package-standalone.bb HOST-JAR ROOT OUTPUT")
  (with-open [archive (java.util.zip.ZipOutputStream. sources)]
    (doseq [manifest (sort (fs/glob (fs/path root "extensions") "*/src/extension.edn"))
            path (sort (fs/glob (fs/parent manifest) "**"))
            :when (fs/regular-file? path)]
      (with-open [input (io/input-stream (fs/file path))]
        (put! archive (str/replace (str (fs/relativize root path)) "\\" "/") input))))
  (with-open [original (java.util.zip.ZipFile. (fs/file host))
              archive (java.util.zip.ZipOutputStream. (io/output-stream output))]
    (doseq [entry (enumeration-seq (.entries original))
            :when (not= "META-INF/MANIFEST.MF" (str/upper-case (.getName entry)))]
      (with-open [input (.getInputStream original entry)]
        (put! archive (.getName entry) input)))
    (put! archive "META-INF/MANIFEST.MF"
          "Manifest-Version: 1.0\r\nMain-Class: kmet-extensions.standalone\r\n\r\n")
    (with-open [input (io/input-stream (fs/file (fs/path root "scripts/standalone.clj")))]
      (put! archive "kmet_extensions/standalone.clj" input))
    (put! archive "kmet-extensions/sources.zip" (.toByteArray sources))))
