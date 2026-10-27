(ns schedule.privilege
  "Structural privilege enforcement for scheduled turns.

  Port of pi-schedule src/privilege.ts, adapted to kmet's tool surface and
  the on-tool-call hook (which carries {:block true :reason str :terminate
  bool} results straight into the loop's batch-stop rule).

  read_only is strict by default: a known-read allowlist — any tool not on
  it fails closed. kmet has no web/search or terminal tools in its core, so
  the allowlist is read plus the opt-in grep/find/ls extension tools;
  run_code and clojure_eval are deliberately excluded (arbitrary-execution
  surfaces no per-tool hook can fence). KMET_SCHEDULE_PRIVILEGE_MODE=legacy
  relaxes read_only to the core blocklist (edit/write/bash). suggest blocks
  the exec surfaces but keeps drafting tools open. Blocks set :terminate —
  a scheduled turn that has wandered off-contract into a mutating tool has
  no productive path inside the fence, so a fully-blocked batch ends the
  turn without a wasted follow-up model call (kmet's loop already honors
  the hint; a mixed batch with allowed reads still reports its findings).

  Deliveries reserve their exact prompt before submission. The host's
  before-agent-start hook activates the matching tier only when that prompt
  actually starts; settling clears only the active tier, not queued ones.
  Matching registered prompts, rather than trusting textual tier headers,
  prevents ordinary user prompts from selecting privileges."
  (:require [schedule.util :as util]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [schedule.policy :as policy]))

(def mutate-tools
  "Core mutating tools blocked under read_only (legacy mode's blocklist)."
  #{"edit" "write" "bash"})

(def exec-surfaces
  "Arbitrary-execution surfaces blocked under suggest (drafting tools stay
  open): the shell plus the script/eval tools."
  #{"bash" "run_code" "clojure_eval"})

(def read-only-allow-tools
  "Tools allowed under tier=read_only in strict mode. Deliberately excludes
  every exec surface (run_code can shell out through babashka.process, which
  no per-tool hook covers) — unknown names fail closed."
  #{"read" "grep" "find" "ls"})

(def schedule-mutate-actions
  "schedule tool actions that persist state / trigger fires / grant trust —
  blocked under read_only and suggest (a fired read_only turn must not be
  able to persist a shell job = read_only → mutate-shell escalation).
  list/history stay allowed."
  #{"create" "cancel" "enable" "disable" "run_now" "trust"})

(def max-depth
  "Defensive ceiling for the tier stack (pi PrivilegeGuard.MAX_DEPTH)."
  16)

(defn make-privilege
  "A fresh privilege state atom {:stack [tier ...]} — created per extension
  load; unregistered hooks die with the load."
  []
  (atom {:stack [] :pending []}))

(defn legacy-mode?
  "True when KMET_SCHEDULE_PRIVILEGE_MODE=legacy (read_only relaxes to the
  core-tool blocklist)."
  []
  (= "legacy" (System/getenv "KMET_SCHEDULE_PRIVILEGE_MODE")))

(defn- schedule-mutation?
  "True when the tool call is the schedule tool with a mutating action."
  [tool-name args]
  (let [action (or (get args "action") (get args :action))]
    (and (= "schedule" (str/lower-case (str tool-name)))
         (not (contains? #{:list :history} (policy/as-keyword action))))))

(defn- block
  [reason]
  {:block true :reason reason :terminate true})

(defn attach
  "Register the tool-call hook and the settle popper on API for the runner's
  privilege STATE (pi PrivilegeGuard.attach)."
  [api state]
  (ext/on-before-agent-start api
                             (fn [{:keys [prompt]}]
                               (swap! state
                                      (fn [s]
                                        (let [idx (first (keep-indexed (fn [i entry]
                                                                         (when (= prompt (:prompt entry)) i))
                                                                       (:pending s)))
                                              entry (when (some? idx) (nth (:pending s) idx))]
                                          (assoc s :stack (if entry [(:tier entry)] [])
                                                 :pending (if entry
                                                            (into (subvec (:pending s) 0 idx)
                                                                  (subvec (:pending s) (inc idx)))
                                                            (:pending s))))))
                               nil))
  (ext/on-tool-call api
                    (fn [{:keys [tool-name args]}]
                      (let [tier (peek (:stack @state))]
                        (when (and tier (not= :mutate tier))
                          (let [tool (str/lower-case (str tool-name))
                                schedule-read? (and (= "schedule" tool)
                                                    (contains? #{:list :history}
                                                               (policy/as-keyword (or (:action args) (get args "action")))))]
                            (cond
                              (schedule-mutation? tool-name args)
                              (block (str "[schedule] blocked schedule " (or (get args "action") (get args :action))
                                          ": active scheduled job is tier=" (name tier)
                                          " (schedule mutations need tier=mutate; list/history are allowed)"))

                              (and (= :read-only tier) (contains? mutate-tools tool))
                              (block (str "[schedule] blocked " tool-name ": active scheduled job is tier=read_only"))

                              (and (= :read-only tier)
                                   (not (legacy-mode?))
                                   (not schedule-read?)
                                   (not (contains? read-only-allow-tools tool)))
                              (block (str "[schedule] blocked " tool-name ": active scheduled job is tier=read_only, and "
                                          tool-name " is not on the read-only allowlist (unknown tools fail closed). "
                                          "Set KMET_SCHEDULE_PRIVILEGE_MODE=legacy to relax to the core-tool blocklist."))

                              (and (= :suggest tier) (contains? exec-surfaces tool))
                              (block (str "[schedule] blocked " tool-name ": active scheduled job is tier=suggest (no shell/exec)"))

                              :else nil))))))
  (ext/on-event api :agent-settled
                (fn [_event _ctx]
                  (swap! state assoc :stack [])))
  nil)

(defn- policy-tier
  "Keyword for a tier value (keyword or string, e.g. read_only/read-only)."
  [tier]
  (if (keyword? tier) tier (policy/as-keyword tier)))

(defn enter!
  "Push TIER after a successful delivery that started an agent turn
  (multi-fire follow-ups stack). Trims oldest-first at MAX-DEPTH."
  [state tier]
  (swap! state update :stack
         (fn [stack]
           (let [stack' (if (>= (count stack) max-depth)
                          (subvec (vec stack) (- (count stack) max-depth -1))
                          (vec stack))]
             (conj stack' (policy-tier tier))))))

(defn reserve!
  "Reserve the privilege for an exact prompt before submitting it to the host."
  [state prompt tier]
  (let [token (util/uuid)]
    (swap! state update :pending conj {:token token :prompt prompt :tier (policy/as-tier tier)})
    token))

(defn cancel!
  "Remove a reservation when submission fails."
  [state token]
  (swap! state update :pending #(filterv (fn [entry] (not= token (:token entry))) %))
  nil)

(defn clear!
  "Clear the tier stack (session shutdown / unload / tests)."
  [state]
  (reset! state {:stack [] :pending []})
  nil)

(defn depth
  "Active scheduled-turn depth."
  [state]
  (count (:stack @state)))

(defn scheduled-turn-active?
  "True while a scheduled delivery is the active agent turn — gates implicit
  project trust on create (a fired turn must not be able to unlock its own
  project's gate)."
  [state]
  (or (pos? (depth state)) (boolean (seq (:pending @state)))))
