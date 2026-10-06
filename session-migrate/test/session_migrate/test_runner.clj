;; Copyright (c) 2026 Jarkko Saltiola; SPDX-License-Identifier: MIT
(ns session-migrate.test-runner
  (:require [clojure.test :as t]
            [session-migrate.claude-test]
            [session-migrate.core-test]
            [session-migrate.writer-test]))

(defn -main [& _]
  (let [result (t/run-tests 'session-migrate.claude-test 'session-migrate.core-test
                            'session-migrate.writer-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
