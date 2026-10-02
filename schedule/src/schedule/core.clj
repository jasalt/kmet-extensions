(ns schedule.core
  "Schedule — recurring tasks for kmet agents.

  Port of pi-schedule v0.4.0 (https://github.com/pungggi/pi-schedule) as a
  kmet extension: the 'schedule' tool + skill, a hybrid job store (global
  ~/.kmet/agent/schedule/ + project .kmet/schedule.edn), due-job firing on
  session start and a 30s idle ticker, and the full reliability model —
  idempotency keys, single-flight locks, missed-window policy, fire caps,
  privilege tiers (structural, via the on-tool-call hook), the project
  trust gate, secret redaction for persisted shell output, and an
  append-only run ledger. See README.md for the complete behavior notes.

  State is created fresh per load: the runner (ticker thread, privilege
  stack) dies with shutdown, which runs on unload (/reload) and is
  registered for :session-shutdown (quit)."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [schedule.policy :as policy]
            [schedule.runner :as runner]
            [schedule.store :as store]
            [schedule.tool :as tool]))

(def ^:private skill-resource "skills/schedule/SKILL.md")

(def ^:private runtime (atom nil))

(defn shutdown
  "Teardown on unload (/reload): stop the ticker, clear the privilege stack.
  Registrations (tool, skill, event handlers) are removed automatically."
  [api]
  (when-let [r @runtime]
    (runner/shutdown r))
  (reset! runtime nil)
  (ext/ui-set-status api "schedule" nil))

(defn- load-skill
  "The bundled SKILL.md content — io/resource first (dir and jar/zip installs
  alike), then the extension dir as a fallback for hosts without resource
  shadowing."
  [api]
  (or (try (some-> (io/resource skill-resource) slurp)
           (catch Exception _ nil))
      (try (when-let [dir (:extension-dir api)]
             (when (fs/exists? (str (fs/path dir skill-resource)))
               (slurp (str (fs/path dir skill-resource)))))
           (catch Exception _ nil))))

(defn init
  "Wire the extension: store paths under the host agent dir, the runner with
  its event bindings, the tool, and the bundled skill."
  [api]
  (let [paths (store/paths (ext/get-agent-dir api))
        r (runner/make-runner api paths)
        limiter (policy/create-rate-limiter)]
    (reset! runtime r)
    (runner/attach r)
    (tool/register-tool! api r limiter)
    (when-let [skill (load-skill api)]
      (ext/register-skill! api skill {:location (str "schedule:" skill-resource)}))
    r))
