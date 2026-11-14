(ns webdriver.snapshot
  "Accessibility snapshots with element refs, as pure functions over data.

  Modelled on vercel-labs/agent-browser (cli/src/native/snapshot.rs and
  element.rs). The input is the node list of Chrome DevTools'
  `Accessibility.getFullAXTree`. The output is an indented outline in which
  actionable and named nodes carry a stable ref:

      - navigation \"Primary\" [ref=e1]
        - link \"Home\" [ref=e2]
      - button \"Save\" [ref=e7]

  Ref state is plain data and is threaded through `snapshot`. Refs stay stable
  for a DOM node across snapshots of the same document and reset when the page
  navigates (the loader id changes):

      {:doc \"<loader id>\" :next 8
       :refs {\"e7\" {:backend-id 33 :role \"button\" :name \"Save\" :nth 0}}
       :by-backend {33 \"e7\"}}

  The namespace does no I/O. webdriver.core fetches the tree and resolves refs
  to DOM elements."
  (:require [clojure.string :as str]))

;; ─── Role tables (agent-browser snapshot.rs) ──────────────────────────────

(def interactive-roles
  #{"button" "link" "textbox" "checkbox" "radio" "combobox" "listbox" "menuitem"
    "menuitemcheckbox" "menuitemradio" "option" "searchbox" "slider" "spinbutton"
    "switch" "tab" "treeitem" "Iframe"})

(def cell-roles #{"cell" "gridcell" "columnheader" "rowheader"})

(def content-roles
  "Get a ref only when they have a name."
  #{"heading" "listitem" "article" "region" "main" "navigation" "form"})

(def ^:private transparent-roles
  "Wrappers that are never printed; their children move up a level."
  #{"RootWebArea" "WebArea" "none" "presentation" "InlineTextBox" "LineBreak" "ListMarker"
    "MenuListPopup"})

;; ─── Normalizing CDP nodes ────────────────────────────────────────────────

(defn clean-text
  "Map NBSP to a space, drop zero-width characters, and collapse whitespace."
  [s]
  (-> (str s)
      (str/replace \u00A0 \space)
      (str/replace #"[\uFEFF\u200B\u200C\u200D\u2060]" "")
      (str/replace #"\s+" " ")))

(defn- ax-value [v] (some-> v :value))

(defn- normalize-role [role]
  (if (= "IframePresentational" role) "Iframe" (str role)))

(def ^:private state-props
  #{"level" "checked" "expanded" "selected" "disabled" "required" "url"})

(defn ->node
  "One CDP AXNode as a plain map; ignored nodes keep only their structure."
  [ax]
  (let [role (normalize-role (ax-value (:role ax)))
        ignored? (and (:ignored ax) (not= "RootWebArea" role))]
    (cond-> {:id (:nodeId ax)
             :role (if ignored? "none" role)
             :name (if ignored? "" (str (ax-value (:name ax))))
             :backend-id (:backendDOMNodeId ax)
             :child-ids (vec (:childIds ax))}
      (not ignored?)
      (into (keep (fn [{:keys [name value]}]
                    (when (contains? state-props name)
                      [(keyword name) (:value value)])))
            (:properties ax))
      (and (not ignored?) (some? (ax-value (:value ax))))
      (assoc :value (str (ax-value (:value ax)))))))

(defn- merge-text-runs
  "Join adjacent StaticText children (text split by inline markup) into one."
  [children]
  (->> children
       (partition-by #(= "StaticText" (:role %)))
       (mapcat (fn [run]
                 (if (and (= "StaticText" (:role (first run))) (next run))
                   [(assoc (first run) :name (apply str (map :name run)) :children [])]
                   run)))))

(defn- assemble [index id]
  (let [node (index id)
        children (->> (:child-ids node)
                      (keep #(when (index %) (assemble index %)))
                      merge-text-runs
                      vec)]
    (-> node (dissoc :child-ids) (assoc :children children))))

(defn ax->tree
  "Nested node maps from a flat CDP AXNode list; nil for an empty list."
  [ax-nodes]
  (when (seq ax-nodes)
    (let [nodes (mapv ->node ax-nodes)
          index (into {} (map (juxt :id identity)) nodes)
          child? (into #{} (mapcat :child-ids) nodes)
          roots (remove (comp child? :id) nodes)]
      (if (= 1 (count roots))
        (assemble index (:id (first roots)))
        {:role "RootWebArea" :name "" :children (mapv #(assemble index (:id %)) roots)}))))

;; ─── Choosing and assigning refs ──────────────────────────────────────────

(defn ref-worthy?
  "Should NODE be addressable? Interactive and table-cell roles always are;
  landmark and content roles are when they have a name."
  [{:keys [role name backend-id]}]
  (and (some? backend-id)
       (or (interactive-roles role)
           (cell-roles role)
           (and (content-roles role) (not (str/blank? name))))))

(defn- preorder [tree] (tree-seq (comp seq :children) :children tree))

(defn- reset-for [state doc]
  (if (= doc (:doc state))
    state
    {:doc doc :next 1 :refs {} :by-backend {}}))

(defn assign-refs
  "Give every ref-worthy node in TREE a ref. Nodes that had a ref in this document
  keep it; new nodes get the next number. Duplicate role+name pairs record their
  :nth occurrence, which makes them unrecoverable by role/name alone.
  Returns [state' tree'], where tree' has :ref on the ref'd nodes."
  [state doc tree]
  (let [worthy (filter ref-worthy? (preorder tree))
        counts (frequencies (map (juxt :role :name) worthy))
        state (reduce
               (fn [st [{:keys [backend-id role name]} nth]]
                 (let [ref (or (get-in st [:by-backend backend-id]) (str "e" (:next st)))
                       entry (cond-> {:backend-id backend-id :role role :name name}
                               (> (counts [role name]) 1) (assoc :nth nth))]
                   (cond-> (-> st
                               (assoc-in [:refs ref] entry)
                               (assoc-in [:by-backend backend-id] ref))
                     (not (get-in st [:by-backend backend-id])) (update :next inc))))
               (reset-for state doc)
               (map vector worthy
                    ;; nth occurrence of each role+name, in document order
                    (second (reduce (fn [[seen acc] {:keys [role name]}]
                                      [(update seen [role name] (fnil inc 0))
                                       (conj acc (get seen [role name] 0))])
                                    [{} []] worthy))))
        tag (fn tag [node]
              (cond-> (update node :children #(mapv tag %))
                (ref-worthy? node) (assoc :ref (get-in state [:by-backend (:backend-id node)]))))]
    [state (tag tree)]))

;; ─── Shaping the tree ─────────────────────────────────────────────────────

(defn- noise? [{:keys [role name ref children]}]
  (or (transparent-roles role)
      (str/blank? role)
      (and (= "StaticText" role) (str/blank? (clean-text name)))
      (and (#{"generic" "LabelText" "paragraph"} role) (not ref) (str/blank? name))
      (and (= "generic" role) (not ref) (<= (count children) 1))))

(defn flatten-noise
  "Splice wrapper/blank nodes out of NODE, lifting their children."
  [node]
  (let [children (into [] (mapcat (fn [c] (let [c (flatten-noise c)]
                                            (if (noise? c) (:children c) [c]))))
                       (:children node))
        ;; text that only repeats its parent's name or value adds nothing
        children (if (and (= 1 (count children))
                          (= "StaticText" (:role (first children)))
                          (contains? #{(:name node) (:value node)} (:name (first children))))
                   []
                   children)]
    (assoc node :children children)))

(defn- splice-unless
  "Keep descendants that satisfy KEEP?; splice out the rest, re-parenting their
  children to the nearest kept ancestor."
  [keep? node]
  (let [children (into [] (mapcat (fn [c] (let [c (splice-unless keep? c)]
                                            (if (keep? c) [c] (:children c)))))
                       (:children node))]
    (assoc node :children children)))

(defn- informative?
  "Has a ref, a value or a name — i.e. says something beyond structure."
  [{:keys [ref value name]}]
  (or ref (seq value) (not (str/blank? (clean-text name)))))

(defn- limit-depth [node depth]
  (if (neg? depth)
    nil
    (assoc node :children (into [] (keep #(limit-depth % (dec depth))) (:children node)))))

(defn shape
  "Prune TREE for display. OPTS: :interactive (only ref'd nodes),
  :compact (drop unnamed structure such as list/row/main, lifting children),
  :depth."
  [tree {:keys [interactive compact depth]}]
  (let [root (flatten-noise tree)
        root (cond
               interactive (splice-unless :ref root)
               ;; agent-browser keeps unnamed ancestors of matches; splicing
               ;; them is what makes compact output actually shorter
               compact (splice-unless informative? root)
               :else root)]
    (cond-> root depth (limit-depth (inc depth)))))

;; ─── Rendering ────────────────────────────────────────────────────────────

(defn- attrs [{:keys [level checked expanded selected disabled required ref url]} urls?]
  (cond-> []
    level (conj (str "level=" level))
    (some? checked) (conj (str "checked=" checked))
    (some? expanded) (conj (str "expanded=" expanded))
    selected (conj "selected")
    disabled (conj "disabled")
    required (conj "required")
    ref (conj (str "ref=" ref))
    (and urls? url) (conj (str "url=" url))))

(defn render-line
  "One outline line for NODE at INDENT, e.g. `- button \"Save\" [ref=e7]`."
  [node indent urls?]
  (let [name (str/trim (clean-text (:name node)))
        as (attrs node urls?)
        value (some-> (:value node) clean-text str/trim)]
    (str (apply str (repeat indent "  "))
         "- " (:role node)
         (when (seq name) (str " " (pr-str name)))
         (when (seq as) (str " [" (str/join ", " as) "]"))
         (when (and (seq value) (not= value name)) (str ": " value)))))

(defn render
  "Outline text for a shaped TREE. Its root (the page) is not printed."
  ([tree] (render tree {}))
  ([tree {:keys [urls]}]
   (let [lines (fn lines [node indent]
                 (cons (render-line node indent urls)
                       (mapcat #(lines % (inc indent)) (:children node))))]
     (str/join "\n" (mapcat #(lines % 0) (:children tree))))))

;; ─── Subtree selection ────────────────────────────────────────────────────

(defn subtrees
  "Topmost nodes of TREE whose backend id is in IDS, i.e. the AX roots of a
  DOM subtree. Returns a synthetic root holding them; nil if none match."
  [tree ids]
  (let [found (fn found [node]
                (if (contains? ids (:backend-id node))
                  [node]
                  (mapcat found (:children node))))
        roots (vec (found tree))]
    (when (seq roots)
      {:role "RootWebArea" :name "" :children roots})))

;; ─── Entry point ──────────────────────────────────────────────────────────

(defn snapshot
  "Pure snapshot. Returns {:state state' :text outline :refs n}.
  STATE is the previous ref state (nil to start). DOC identifies the current
  document. OPTS adds :within (a set of DOM backend ids that limits the output
  to that subtree) to the shape/render options. Refs are assigned over the
  whole page, so they do not depend on display options."
  [state doc ax-nodes opts]
  (let [tree (or (ax->tree ax-nodes) {:role "RootWebArea" :name "" :children []})
        [state tree] (assign-refs state doc tree)
        scoped (if-let [ids (:within opts)]
                 (or (subtrees tree ids)
                     (throw (ex-info "No accessibility node inside the selected element" {})))
                 tree)
        text (render (shape scoped opts) opts)
        shown (count (re-seq #"\[ref=|, ref=" text))]
    {:state state
     :refs shown
     :text (cond
             (seq text) text
             (:interactive opts) "(no interactive elements)"
             :else "(empty page)")}))

;; ─── Ref lookup ───────────────────────────────────────────────────────────

(defn parse-ref
  "\"e7\", \"@e7\" or \"ref=e7\" → \"e7\"; anything else → nil."
  [s]
  (some->> (some-> s str str/trim) (re-matches #"(?:@|ref=)?(e\d+)") second))

(defn recover
  "Backend id of the only live node matching ENTRY's role and name, or an
  ex-info explaining why no safe match exists (agent-browser
  find_node_id_by_role_name)."
  [{:keys [role name nth] :as _entry} ax-nodes]
  (when nth
    (throw (ex-info (str "Stale ref for " role " " (pr-str name)
                         " cannot be recovered: several elements matched when the snapshot was taken."
                         " Take a new snapshot.")
                    {:type :webdriver/stale-ref})))
  (let [matches (->> ax-nodes
                     (map ->node)
                     (filter #(and (= role (:role %)) (= name (:name %)) (:backend-id %))))]
    (case (count matches)
      1 (:backend-id (first matches))
      0 (throw (ex-info (str "Element " role " " (pr-str name) " is no longer on the page. Take a new snapshot.")
                        {:type :webdriver/stale-ref}))
      (throw (ex-info (str "Stale ref for " role " " (pr-str name)
                           " cannot be recovered: several elements now match. Take a new snapshot.")
                      {:type :webdriver/stale-ref})))))
