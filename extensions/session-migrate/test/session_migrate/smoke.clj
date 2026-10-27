;; Copyright (c) 2026 Jarkko Saltiola; SPDX-License-Identifier: MIT
(ns session-migrate.smoke
  "Real isolated extension-loader lifecycle tests, not registration-only evidence."
  (:require [babashka.fs :as fs]
            [kmet.app.commands :as commands]
            [kmet.app.extensions :as extensions]
            [kmet.app.session :as session]
            [kmet.config :as cfg]
            [session-migrate.test-support :refer [with-temp root native-fixture]]))

(defn -main [& _]
  (with-temp [dir]
    (with-redefs [cfg/get-agent-dir (constantly dir)]
      (try
        (dotimes [_ 2]
          (let [loaded (extensions/load-extension! (str (fs/path root "src")))]
            (assert (nil? (:error loaded)) (str "Extension load failed: " (:error loaded))))
          (let [handler (:extension-handler (commands/find-command "session-migrate"))
                ctx (extensions/build-extension-context)
                report (handler ctx (str "inspect claude " native-fixture))
                saved (handler ctx (str "save claude " native-fixture))
                imported (session/load-session (:path saved))]
            (assert (= 3 (get-in report [:preserved :tool-calls])))
            (assert (= "repair-event-window-boundary" (session/get-session-name imported)))
            (assert (= 11 (count (mapcat session/context-messages (session/build-context imported)))))
            (assert (nil? (extensions/get-session))))
          (extensions/unload-all-extensions!)
          (assert (nil? (commands/find-command "session-migrate"))))
        (println "Real loader/save/context/reload smoke passed")
        (finally (extensions/unload-all-extensions!))))))
