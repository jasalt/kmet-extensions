;; Run from a current kmet checkout:
;; bb ../kmet-extensions/scripts/startup-smoke.bb
(require '[babashka.fs :as fs]
         '[kmet.app.extensions.core :as extensions]
         '[kmet.app.test-support :as support])

(let [root (fs/parent (fs/parent (fs/absolutize *file*)))
      manifests (sort (fs/glob root "*/src/extension.edn"))]
  (assert (= 7 (count manifests)) "Expected seven source extensions")
  (support/with-composition
   (fn []
     (doseq [manifest manifests]
       (try
         (dotimes [_ 2]
           (let [result (extensions/load-extension! (str (fs/parent manifest)))]
             (assert (nil? (:error result)) (str manifest ": " (:error result)))
             (assert (false? (:pending result)) (str manifest " did not activate"))
             (assert (= 1 (count (extensions/get-loaded-extensions)))))
           (extensions/unload-all-extensions!)
           (assert (empty? (extensions/get-loaded-extensions))))
         (println "PASS" (str (fs/relativize root manifest)))
         (finally
           (extensions/unload-all-extensions!)))))))
