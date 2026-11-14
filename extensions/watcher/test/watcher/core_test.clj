(ns watcher.core-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [watcher.core :as core]
            [watcher.files :as files]
            [watcher.parser :as parser]))

(deftest parsing
  (doseq [prefix ["#" "//" "--" ";" ";;;"]]
    (is (= [:edit :ask :context]
           (mapv :action (parser/parse (str prefix " ai! fix\n" prefix " why AI?\n" prefix " AI. anchor") "x" "AI")))))
  (doseq [text ["// OpenAI!" "// -ai!" "// some-ai?" "// bare AI" "// middle AI! ignored"
                "x = \"// AI!\"" "x = '# AI?'" "x = `// AI!`" "x; AI!"]]
    (is (empty? (parser/parse text "x" "AI")) text))
  (is (= 1 (count (parser/parse "(foo) ; AI! fix" "x" "AI"))))
  (is (= 1 (count (parser/parse "// A+! fix" "x" "A+"))))
  (is (= (:id (first (parser/parse "// AI! fix" "x" "AI")))
         (:id (first (parser/parse "\n// ai! FIX" "x" "AI"))))))

(deftest cleanup
  (let [s "// unrelated\r\nx(); // AI! fix\r\n// AI. context\r\n"
        ids (set (map :id (parser/parse s "x" "AI")))]
    (is (= "// unrelated\r\nx();\r\n" (parser/cleanup s "x" "AI" ids)))
    (is (= "// AI! changed\n" (parser/cleanup "// AI! changed\n" "x" "AI" ids)))))

(deftest filesystem-and-runtime
  (let [dir (str (fs/create-temp-dir)) outside (str (fs/create-temp-dir))
        state (atom {:processed #{}})
        busy (atom true) pending (atom false) sent (atom [])
        ctx {:cwd dir :has-ui false :is-idle #(not @busy) :has-pending-messages #(deref pending)}
        api {:get-agent-dir (constantly outside)
             :send-user-message (fn [text opts] (swap! sent conj [text opts]))
             :ui {:set-status (fn [& _]) :notify (fn [& _])}}]
    (try
      (spit (str dir "/a.clj") "; AI! fix\n")
      (fs/create-dirs (str dir "/node_modules"))
      (spit (str dir "/node_modules/ignored") "# AI! ignore")
      (spit (str dir "/binary") "\u0000# AI! ignore")
      (spit (str outside "/secret") "# AI! ignore")
      (fs/create-sym-link (str dir "/link") (str outside "/secret"))
      (is (= ["a.clj"] (mapv :path (files/scan dir files/defaults))))
      (core/session! api state ctx)
      (core/tick! api state true)
      (is (empty? @sent))
      (is (= 1 (count (:pending @state))))
      ;; A queued request edited away is never delivered.
      (spit (str dir "/a.clj") "; ordinary\n")
      (reset! busy false)
      (core/tick! api state true)
      (is (empty? @sent))
      (spit (str dir "/a.clj") "; AI! fix\n")
      (reset! pending true)
      (core/tick! api state true)
      (is (empty? @sent))
      (reset! pending false)
      (core/tick! api state true)
      (is (= 1 (count @sent)))
      (core/tick! api state true)
      (is (= 1 (count @sent)))
      (swap! state assoc :ended? true)
      (core/tick! api state true)
      (is (= "" (slurp (str dir "/a.clj"))))
      (is (nil? (:active @state)))
      (spit (str dir "/a.clj") "; AI. context only\n")
      (core/tick! api state true)
      (is (= 1 (count @sent)))
      (core/command! api state ctx "stop")
      (is (false? (get-in @state [:config :enabled])))
      (is (false? (:enabled (files/read-config (get-in @state [:paths :project])))))
      (core/command! api state ctx "global start")
      (is (false? (get-in @state [:config :enabled])) "project wins")
      (finally (core/stop! api state) (fs/delete-tree dir) (fs/delete-tree outside)))))

(deftest dispatch-failure-and-disabled-mode
  (let [dir (str (fs/create-temp-dir))
        calls (atom 0)
        api {:send-user-message (fn [& _] (swap! calls inc) (throw (ex-info "rejected" {})))
             :ui {:set-status (fn [& _])}}
        state (atom {:cwd dir :ctx {:is-idle (constantly true)} :processed #{}
                     :config (assoc files/defaults :enabled false)})]
    (try
      (spit (str dir "/x") "# AI! fix")
      (core/tick! api state false)
      (is (zero? @calls))
      (core/tick! api state true)
      (is (= 1 @calls))
      (is (empty? (:processed @state)))
      (is (nil? (:active @state)))
      (swap! state assoc-in [:config :maxPromptBytes] 1)
      (core/tick! api state true)
      (is (= 1 @calls))
      (is (seq (:pending @state)))
      (finally (fs/delete-tree dir)))))

(deftest unload-stops-polling
  ;; The host calls <plugin-ns>/shutdown on unload; a started loop must stop.
  (let [dir (str (fs/create-temp-dir)) ticks (atom 0) handlers (atom {})
        api {:get-agent-dir (constantly dir)
             :register-command! (fn [_])
             :on-event (fn [event f] (swap! handlers assoc event f))
             :ui {:set-status (fn [& _]) :notify (fn [& _])}}]
    (try
      (fs/create-dirs (str dir "/.kmet/extensions"))
      (spit (str dir "/.kmet/extensions/watcher.json") "{\"pollMs\":100}")
      (core/init api)
      ((:session-start @handlers) nil {:cwd dir :has-ui true :is-idle #(do (swap! ticks inc) true)})
      (Thread/sleep 350)
      (is (pos? @ticks) "loop polls before unload")
      (is (ifn? (resolve 'watcher.core/shutdown)) "host unload hook exists")
      (some-> (resolve 'watcher.core/shutdown) (apply [api]))
      (let [before @ticks]
        (Thread/sleep 350)
        (is (= before @ticks) "no polling after unload"))
      (finally
        (some-> (:session-shutdown @handlers) (apply [nil nil]))
        (fs/delete-tree dir)))))

(deftest status-only-on-change
  (let [calls (atom []) api {:ui {:set-status (fn [k t] (swap! calls conj t))}}
        state (atom {:config {:enabled true}})]
    (core/status! api state)
    (core/status! api state)
    (is (= ["watcher on"] @calls) "unchanged status is not resent")
    (swap! state assoc :error "x")
    (core/status! api state)
    (core/stop! api state)
    (swap! state dissoc :error)
    (core/status! api state)
    (is (= ["watcher on" "watcher error" nil "watcher on"] @calls) "stop clears and forgets")))

(deftest glob-regex
  (is (= "^(?:.*/)?\\Qa\\E[^/]*\\Q.c\\E[^/]\\Q/\\E.*$" (str (files/glob-regex "**/a*.c?/**"))))
  (doseq [[glob yes no] [["**/*" "a/b" nil] ["**/.git/**" ".git/x" "x.git/y"]
                         ["src/*.clj" "src/a.clj" "src/a/b.clj"] ["a?c" "abc" "a/c"]
                         ["a.b" "a.b" "axb"] ["x**" "x/y/z" "y"]]]
    (is (re-matches (files/glob-regex glob) yes) glob)
    (when no (is (not (re-matches (files/glob-regex glob) no)) glob))))

(deftest ignore-globs
  ;; `dir/**` globs are enforced by directory pruning, so only the rest run per file.
  (is (= ["**/*.log"] (files/file-globs ["**/x/**" "**/*.log"])))
  (let [dir (str (fs/create-temp-dir))
        cfg (assoc files/defaults :ignore ["**/x/**" "**/*.log"])]
    (try
      (doseq [p ["x/a" "y/x/b" "y/c.log" "keep"]]
        (fs/create-dirs (fs/parent (fs/path dir p)))
        (spit (str (fs/path dir p)) "# AI! fix"))
      (is (= ["keep"] (mapv :path (files/scan dir cfg))))
      (is (empty? (files/scan dir (assoc cfg :roots ["x"]))) "ignored root is pruned")
      (finally (fs/delete-tree dir)))))

(deftest prompt-limits
  (let [ms [(assoc (first (parser/parse "# AI! fix é" "a" "AI")) :context (files/snippet ["# AI! fix é"] 1 12))]]
    (is (string? (files/prompt ms files/defaults)))
    (is (nil? (files/prompt ms (assoc files/defaults :maxPromptBytes 10))))))

(deftest overlapping-context-merged
  (let [src (str/join "\n" (concat ["; AI. ctx"] (repeat 5 "x") ["; why AI?"] (repeat 20 "y") ["; AI! far"]))
        lines (parser/lines src)
        ms (mapv #(assoc % :context (files/snippet lines (:line %) 3)) (parser/parse src "f" "AI"))
        text (files/prompt ms files/defaults)
        other (files/prompt (concat ms (map #(assoc % :path "g") ms)) files/defaults)]
    (is (= 1 (count (re-seq #"(?m)^4: " text))) "shared lines sent once")
    (is (re-find #"f:1 \[context\] AI\. ctx\nf:7 \[ask\] why AI\?\n1: ; AI\. ctx" text))
    (is (re-find #"\n10: y\n\nf:28 \[edit\] AI! far\n25: " text) "distant marker gets its own section")
    (is (= 2 (count (re-seq #"(?m)^4: " other))) "same lines in different files do not merge")))

(defn -main [& _]
  (let [result (run-tests 'watcher.core-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
