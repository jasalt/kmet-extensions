;; Copyright (c) 2026 Jarkko Saltiola; SPDX-License-Identifier: MIT
;; Test-only provider/probe extension. Loaded only in an isolated integration HOME.
(ns migration-probe
  (:require [kmet.extension :as ext]
            [kmet.libs.json :as json]))

(defn init [api]
  (ext/models-register-provider!
   api :migration-local
   {:base-url (System/getenv "MIGRATION_TEST_URL") :api :openai-completions
    :api-key "dummy-not-a-credential" :auth-header true
    :models [{:id "migration-test" :name "Offline migration test" :reasoning false
              :input [:text :image] :context-window 100000 :max-tokens 256
              :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}}]})
  (ext/register-command!
   api {:name "migration-probe" :description "Test-only runtime/session checkpoint"
        :handler
        (fn [ctx args]
          (let [s (:session api)]
            (spit (System/getenv "MIGRATION_TEST_PROBES")
                  (str (json/generate-string
                        {:tag args :cwd (:cwd ctx) :idle ((:is-idle ctx))
                         :commands (mapv :name (ext/get-commands api))
                         :name ((:get-name s)) :leaf ((:get-leaf-id s))
                         :branch ((:get-branch s))}) "\n") :append true)))}))
