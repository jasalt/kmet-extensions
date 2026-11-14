(require '[babashka.fs :as fs] '[babashka.classpath :as cp] '[clojure.test :as t])
(let [root (fs/parent (fs/parent (fs/absolutize *file*)))
      host (fs/absolutize (or (System/getenv "KMET_CHECKOUT")
                              (str (fs/path root "../../../kmet-agent"))))
      sources (fs/glob (fs/path host "modules") "*/src")]
  (when (empty? sources)
    (throw (ex-info "Set KMET_CHECKOUT to a kmet-agent checkout" {:path (str host)})))
  (cp/add-classpath (clojure.string/join java.io.File/pathSeparator
                                        (map str (concat [(fs/path root "src") (fs/path root "test")] sources))))
  (let [nss (->> (fs/glob (fs/path root "test") "**_test.clj")
                 (map #(-> (str (fs/relativize (fs/path root "test") %))
                           (clojure.string/replace #"\.clj$" "")
                           (clojure.string/replace "/" ".")
                           (clojure.string/replace "_" "-")
                           symbol))
                 sort)]
    (apply require nss)
    (let [{:keys [fail error]} (apply t/run-tests nss)]
      (System/exit (if (pos? (+ fail error)) 1 0)))))
