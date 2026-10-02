(ns savelast.core
  "Save the latest assistant message's text without thinking or tool calls."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.extension :as ext]))

(defn- text-content [content]
  (cond
    (string? content) content
    (sequential? content)
    (str/join "\n"
              (keep (fn [block]
                      (when (and (map? block)
                                 (= :text (:type block))
                                 (string? (:text block)))
                        (:text block)))
                    content))
    :else ""))

(defn- target-path [cwd args]
  (let [arg (str/trim (or args ""))
        path (if (str/blank? arg) (str (System/currentTimeMillis) ".md") arg)]
    (str (fs/normalize (fs/absolutize (if (fs/absolute? path)
                                        path
                                        (fs/path cwd path)))))))

(defn- save-last! [api ctx args]
  (if-let [message (some #(when (= :assistant (:role %)) %)
                         (reverse ((:get-branch (:session ctx)))))]
    (let [text (text-content (:content message))]
      (if (str/blank? text)
        (ext/ui-notify api "Last agent message has no text content to save" :warning)
        (try
          (let [target (target-path (:cwd ctx) args)]
            (fs/create-dirs (fs/parent target))
            (spit target text :encoding "UTF-8")
            (ext/ui-notify api (str "Saved to: " target) :info))
          (catch Exception e
            (ext/ui-notify api (str "Failed to write file: " (ex-message e)) :error)))))
    (ext/ui-notify api "No agent message found to save" :warning)))

(defn init
  "Register /savelast; read the command context's live branch and cwd on each call."
  [api]
  (ext/register-command! api
                         {:name "savelast"
                          :description "Save the last agent message to a file (usage: /savelast [path])"
                          :argument-hint "[path]"
                          :handler (fn [ctx args] (save-last! api ctx args))}))
