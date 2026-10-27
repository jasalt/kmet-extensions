;; Copyright (c) 2026 Jarkko Saltiola; SPDX-License-Identifier: MIT
(ns session-migrate.writer
  "Native kmet EDNL publication. Fresh identities; no source or target overwrites."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.libs.json :as json]
            [session-migrate.io :as mio]))

(defn default-directory
  "Default kmet sessions/<--cwd-->/ layout under the public API's agent directory.
  kmet currently exposes no configured session-directory getter to extensions."
  [agent-dir cwd]
  (str (fs/path agent-dir "sessions"
                (str "--" (-> cwd (str/replace #"^[/\\]" "")
                              (str/replace #"[/\\:]" "-")) "--"))))

(defn native-records
  "Assign fresh identities/parents while retaining native message drafts."
  [transcript cwd session-id created-at cancelled?]
  (let [header {:type :session :version 1 :id session-id :created-at created-at :cwd cwd}
        drafts (cond-> (:entries transcript)
                 (seq (:title transcript))
                 (conj {:role :session_info :name (-> (:title transcript)
                                                      (str/replace #"[\r\n]+" " ") str/trim)}))]
    (when-not (seq (:entries transcript)) (mio/fail! "No resumable conversation context"))
    (into [header]
          (:entries
           (reduce
            (fn [{:keys [parent entries]} draft]
              (mio/check-cancel! cancelled?)
              (let [id (str (java.util.UUID/randomUUID))
                    entry (cond-> (assoc draft :id id :parent-id parent
                                         :timestamp (or (:timestamp draft) created-at))
                            (= :compaction (:role draft)) (assoc :first-kept-id id))]
                {:parent id :entries (conj entries entry)}))
            {:parent nil :entries []} drafts)))))

(defn write-session!
  "Stage complete private files, then publish audit and session using no-replace
  hard links. Ordinary failures roll back; the pair is not crash-atomic. Uses
  the portable host filesystem surface, without a shell or global state."
  ([transcript dir cwd] (write-session! transcript dir cwd (constantly false)))
  ([transcript dir cwd cancelled?]
   (mio/check-cancel! cancelled?)
   (when-not (and (fs/absolute? dir) (fs/absolute? cwd))
     (mio/fail! "Session directory and cwd must be absolute"))
   (let [id (str (java.util.UUID/randomUUID))
         now (mio/now)
         records (native-records transcript cwd id now cancelled?)
         text (apply str (map prn-str records))
         audit (json/generate-string
                {:version 1 :source (:report transcript) :target-format "kmet-ednl-v1"
                 :target-session-id id :initial-target-sha256 (mio/sha256 text)})
         path (str (fs/path dir (str (str/replace now #"[:.]" "-") "_" id ".ednl")))
         manifest (str path ".migration.json")]
     (fs/create-dirs dir)
     (let [stage (fs/create-temp-dir {:dir dir :prefix ".migration-"})
           session-stage (fs/path stage "session.ednl")
           audit-stage (fs/path stage "audit.json")
           published (atom [])]
       (try
         ;; Private directory guards the Jolt spit implementation's own temp
         ;; files too. Never chmod a pre-existing user's session directory.
         (fs/set-posix-file-permissions stage "rwx------")
         (spit (str session-stage) text :encoding "UTF-8")
         (spit (str audit-stage) (str audit "\n") :encoding "UTF-8")
         (doseq [file [session-stage audit-stage]]
           (fs/set-posix-file-permissions file "rw-------"))
         (mio/check-cancel! cancelled?)
         (fs/create-link manifest audit-stage)
         (swap! published conj manifest)
         (fs/create-link path session-stage)
         (swap! published conj path)
         {:path path :manifest-path manifest :session-id id}
         (catch Exception e
           (doseq [file @published] (fs/delete-if-exists file))
           (throw e))
         (finally (fs/delete-tree stage)))))))
