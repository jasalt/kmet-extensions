;; Copyright (c) 2026 Jarkko Saltiola; SPDX-License-Identifier: MIT
(ns hooks.session-migrate
  (:require [clj-kondo.hooks-api :as api]))

(defn with-temp [{:keys [node]}]
  (let [[_ bindings & body] (:children node)
        dir (first (:children bindings))]
    {:node (api/list-node
            (list* (api/token-node 'let) (api/vector-node [dir (api/token-node nil)]) body))}))
