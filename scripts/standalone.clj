(ns kmet-extensions.standalone
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [kmet.app.core :as core]
            [kmet.app.packages :as packages]
            [kmet.libs.archive :as archive]))

(def extension-names
  ["codex-usage" "imgview" "nofity-pushover" "pins"
   "savelast" "schedule" "session-migrate"])

(defn run [args]
  ;; A private per-process directory avoids cache races and stale sources. The
  ;; loader gets real files (including schedule's skill), with no store paths.
  (let [dir (fs/create-temp-dir {:prefix "kmet-extensions-"})
        zip (fs/path dir "extensions.zip")
        resolve-items packages/resolve-package-items]
    (try
      (with-open [in (io/input-stream (io/resource "kmet-extensions/sources.zip"))
                  out (io/output-stream (fs/file zip))]
        (io/copy in out))
      (archive/extract-zip! (str zip) (str dir))
      (fs/delete zip)
      (let [paths (mapv #(str (fs/path dir % "src")) extension-names)]
        (with-redefs [packages/resolve-package-items
                      (fn [user-settings & more]
                        (apply resolve-items
                               (update user-settings :extensions
                                       #(vec (distinct (concat paths %))))
                               more))]
          (core/run-process args)))
      (finally
        (fs/delete-tree dir)))))

(defn -main [& args]
  ;; core/-main exits before finally can run; run-process returns an exit code.
  (System/exit (run args)))
