(require '[babashka.fs :as fs] '[babashka.classpath :as cp])
(let [root (fs/parent (fs/parent (fs/absolutize *file*)))
      host (fs/absolutize (or (System/getenv "KMET_CHECKOUT")
                              (str (fs/path root "../../../kmet-agent"))))
      sources (fs/glob (fs/path host "modules") "*/src")]
  (when (empty? sources)
    (throw (ex-info "Set KMET_CHECKOUT to a kmet-agent checkout" {:path (str host)})))
  (cp/add-classpath (clojure.string/join java.io.File/pathSeparator
                                        (map str (concat [(fs/path root "src") (fs/path root "test")] sources))))
  (require 'watcher.core-test)
  ((resolve 'watcher.core-test/-main)))
