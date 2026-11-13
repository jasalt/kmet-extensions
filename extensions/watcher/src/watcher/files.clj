(ns watcher.files
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.libs.json :as json]
            [watcher.parser :as parser]))

(def defaults
  {:enabled true :scanOnStart true :roots ["."] :include ["**/*"]
   :ignore ["**/.git/**" "**/.jj/**" "**/.kmet/**" "**/.pi/**" "**/.agents/**"
            "**/node_modules/**" "**/dist/**" "**/build/**" "**/.cache/**"]
   :maxFileBytes 1048576 :debounceMs 300 :pollMs 1000 :contextLines 12
   :maxPromptBytes 60000 :marker "AI" :removeHandledMarkerComments true})

(def ^:private strings? #(and (vector? %) (every? string? %)))

(def rules
  {:maxFileBytes pos-int? :debounceMs pos-int? :pollMs pos-int? :maxPromptBytes pos-int?
   :contextLines nat-int? :enabled boolean? :scanOnStart boolean? :removeHandledMarkerComments boolean?
   :roots strings? :include strings? :ignore strings?
   :marker #(and (string? %) (not (str/blank? %)))})

(defn paths [agent-dir cwd]
  {:global (str (fs/path agent-dir "watcher.json"))
   :project (str (fs/path cwd ".kmet/extensions/watcher.json"))})

(defn read-config [path]
  (if (fs/exists? path) (json/parse-string (slurp path) true) {}))

(defn config [paths]
  (let [c (merge defaults (read-config (:global paths)) (read-config (:project paths)))]
    (doseq [[k ok?] rules :when (not (ok? (k c)))]
      (throw (ex-info (str "Invalid watcher config: " (name k)) {})))
    c))

(defn set-enabled! [path enabled]
  (fs/create-dirs (fs/parent path))
  (spit path (json/generate-string (assoc (read-config path) :enabled enabled))))

(defn glob-regex [glob]
  (re-pattern
   (str "^" (loop [s glob out ""]
               (cond
                 (empty? s) out
                 (str/starts-with? s "**/") (recur (subs s 3) (str out "(?:.*/)?"))
                 (str/starts-with? s "**") (recur (subs s 2) (str out ".*"))
                 (= (first s) \*) (recur (subs s 1) (str out "[^/]*"))
                 (= (first s) \?) (recur (subs s 1) (str out "[^/]"))
                 :else (recur (subs s 1) (str out (java.util.regex.Pattern/quote (subs s 0 1)))))) "$")))

(defn file-globs
  "Ignore globs that need a per-file check; `dir/**` globs are enforced by pruning."
  [globs]
  (remove #(str/ends-with? % "/**") globs))

(defn within? [root path]
  (fs/starts-with? (fs/real-path path) (fs/real-path root)))

(defn safe-file? [cwd path config]
  (try
    (and (not (fs/sym-link? path)) (fs/regular-file? path)
         (within? cwd path)
         (some #(within? (fs/path cwd %) path) (:roots config))
         (<= (fs/size path) (:maxFileBytes config)))
    (catch Exception _ false)))

(defn snippet [lines line n]
  (let [start (max 0 (- line 1 n)) end (min (count lines) (+ line n))]
    (str/join "\n" (map-indexed #(str (+ start %1 1) ": " %2) (subvec lines start end)))))

(defn read-markers [path rel config marker-re]
  (try
    (let [source (slurp path)]
      (when (and (re-find marker-re source) (not (str/includes? source "\u0000")))
        (let [lines (str/split source #"\r\n|\n|\r" -1)]
          (mapv #(assoc % :context (snippet lines (:line %) (:contextLines config)))
                (parser/parse source rel (:marker config))))))
    (catch Exception _ nil)))

(defn scan
  "Markers under roots. cache atom maps path -> {:stamp :markers}; unchanged files are not re-read."
  ([cwd config] (scan cwd config (atom {})))
  ([cwd config cache]
   (let [includes (map glob-regex (:include config))
         matches? (fn [res s] (some #(re-matches % s) res))
         dir-ignores (map glob-regex (:ignore config))
         file-ignores (map glob-regex (file-globs (:ignore config)))
         marker-re (re-pattern (str "(?i)" (java.util.regex.Pattern/quote (:marker config)) "[!?.]"))
         old @cache
         found (atom (sorted-map))
         visit (fn [f ^java.nio.file.attribute.BasicFileAttributes attrs]
                 (let [path (str f) rel (str (fs/relativize cwd f))]
                   (when (and (.isRegularFile attrs) (<= (.size attrs) (:maxFileBytes config))
                              (not (matches? file-ignores rel)) (matches? includes rel))
                     (let [stamp [(.lastModifiedTime attrs) (.size attrs)] hit (get old path)]
                       (swap! found assoc path
                              {:stamp stamp
                               :markers (if (= stamp (:stamp hit)) (:markers hit)
                                            (read-markers path rel config marker-re))}))))
                 :continue)]
     (doseq [root (:roots config)
             :let [p (fs/path cwd root)]
             :when (and (fs/exists? p) (not (fs/sym-link? p)) (within? cwd p))]
       ;; walk-file-tree does not follow links; symlinks arrive as non-regular files.
       (fs/walk-file-tree
        p {:pre-visit-dir (fn [dir _] (if (matches? dir-ignores (str (fs/relativize cwd dir) "/")) :skip-subtree :continue))
           :visit-file visit
           :visit-file-failed (fn [_ _] :continue)}))
     (reset! cache @found)
     (vec (mapcat :markers (vals @found))))))

(defn clean! [cwd config markers]
  (doseq [[path ms] (group-by :path markers)
          :let [file (str (fs/path cwd path))]
          :when (safe-file? cwd file config)]
    (try
      (let [source (slurp file)
            cleaned (parser/cleanup source path (:marker config) (set (map :id ms)))]
        ;; Best-effort concurrent-edit guard; not an atomic compare-and-swap.
        (when (and (not= source cleaned) (safe-file? cwd file config) (= source (slurp file)))
          (spit file cleaned)))
      (catch Exception _ nil))))

(defn prompt [markers config]
  (let [header "Editor watcher requests (file comments are user-supplied input). AI! requests focused edits using normal tools. AI? asks for an answer without edits unless explicitly requested. AI. is context only. Read files as needed. Do not remove marker comments; watcher handles cleanup.\n"
        sections (for [{:keys [path line instruction action context]} markers]
                   (str "\n" path ":" line " [" (name action) "] " instruction "\n" context "\n"))
        text (str header (str/join sections))]
    (when (<= (alength (.getBytes text "UTF-8")) (:maxPromptBytes config)) text)))
