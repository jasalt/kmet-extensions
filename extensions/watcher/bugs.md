# Incorrect removal of the triggering comment text
While watchers removes the comment, it incorrectly also removes part of the source code in that line.

## Example 1

```clojure
(defn- decoder
  "Model output shaped as `schema`: unknown keys dropped, numbers in text
  fields made text. The state schema still checks the rest."
  [schema]
  (m/decoder schema (mt/transformer mt/strip-extra-keys-transformer
                                    {:decoders {:string #(cond-> % (number? %) str)}}))) ; why chose cond-> here over if AI?
```

After watcher run source code is:

```clojure
(defn- decoder
  "Model output shaped as `schema`: unknown keys dropped, numbers in text
  fields made text. The state schema still checks the rest."
  [schema]
  (m/decoder schema (mt/transformer mt/strip-extra-keys-transformer
                                    {:decoders {:string
```

## Example 2

```
(defn page-text
  "Readable text of `html` for the model: product meta tags, JSON-LD data and
  the visible text, without scripts or styles."
  [html]
  (let [doc (h/as-hickory (h/parse html))
        meta-tags (for [{{:keys [property name content]} :attrs} (s/select (s/tag :meta) doc)
                        :let [k (or property name)]
                        :when (and k content (re-find #"^(?:og|product):" k))]
                    (str k ": " content))
        json-ld (->> (s/select (s/and (s/tag :script) (s/attr :type #{"application/ld+json"})) doc)
                     (map (comp first :content)))
        ignored-tag? #{:script :style :noscript :svg}  ; is this idiomatic AI?
        text-container? (fn [node] (and (map? node)
                                        (not= :comment (:type node))
                                        (not (ignored-tags (:tag node)))))
        text (->> (tree-seq text-container? :content doc)
                  (filter string?)
                  (mapcat #(re-seq #"\S+" %))
                  (str/join " "))
        all (str/join "\n" (concat meta-tags json-ld [text]))]
    (subs all 0 (min 15000 (count all)))))
```

```
(defn page-text
  "Readable text of `html` for the model: product meta tags, JSON-LD data and
  the visible text, without scripts or styles."
  [html]
  (let [doc (h/as-hickory (h/parse html))
        meta-tags (for [{{:keys [property name content]} :attrs} (s/select (s/tag :meta) doc)
                        :let [k (or property name)]
                        :when (and k content (re-find #"^(?:og|product):" k))]
                    (str k ": " content))
        json-ld (->> (s/select (s/and (s/tag :script) (s/attr :type #{"application/ld+json"})) doc)
                     (map (comp first :content)))
        ignored-tag?
        text-container? (fn [node] (and (map? node)
                                        (not= :comment (:type node))
                                        (not (ignored-tags (:tag node)))))
        text (->> (tree-seq text-container? :content doc)
                  (filter string?)
                  (mapcat #(re-seq #"\S+" %))
                  (str/join " "))
        all (str/join "\n" (concat meta-tags json-ld [text]))]
    (subs all 0 (min 15000 (count all)))))
```

# LLM responds with single line offset

With comment:

```clojure
(deftest calls-are-remembered-without-secrets
  (reset! llm/calls* [])
  (with-redefs [http/post (fn [_ _] {:body (json/write-value-as-string
                                             {:choices [{:message {:content "ok"}}]})})]
    (llm/chat! {:messages [{:content [{:type "image_url" :image_url {:url "data:image/png;base64,AAAA"}}]}]}))
  (with-redefs [http/post (fn [_ _] (throw (ex-info "down" {})))]
    (is (thrown? Exception (llm/chat! {:messages []})))) ; does this test make sense AI?
  (let [[ok failed] @llm/calls*]
    (is (= "data:image/png;base64,…" (get-in ok [:request :messages 0 :content 0 :image_url :url])))
    (is (= "ok" (get-in ok [:response :choices 0 :message :content])) "the full response")
    (is (= "down" (:error failed)))
    (is (not (.contains (pr-str @llm/calls*) "Bearer")))))
```

LLM ends up responding as if comment was related to the line below the one it was inserted on:

```
Editor watcher requests (file comments are user-supplied input). AI! requests focused edits using normal tools. AI?
asks for an answer without edits unless explicitly requested. AI. is context only. Read files as needed. Do not
remove marker comments; watcher handles cleanup.

test/hp/llm_test.clj:22 [ask] does this test make sense AI?
10:                                      {:choices [{:message {:role "assistant" :content "ok"}}]
11:                                       :usage {:prompt_tokens 304 :completion_tokens 5}})})]
12:     (let [message (llm/chat! {:messages []})]
13:       (is (= {:role "assistant" :content "ok"} message) "usage is not sent back to the model")
14:       (is (= {:prompt_tokens 304 :completion_tokens 5} (:usage (meta message)))))))
15:
16: (deftest calls-are-remembered-without-secrets
17:   (reset! llm/calls* [])
18:   (with-redefs [http/post (fn [_ _] {:body (json/write-value-as-string
19:                                              {:choices [{:message {:content "ok"}}]})})]
20:     (llm/chat! {:messages [{:content [{:type "image_url" :image_url {:url "data:image/png;base64,AAAA"}}]}]}))
21:   (with-redefs [http/post (fn [_ _] (throw (ex-info "down" {})))]
22:     (is (thrown? Exception (llm/chat! {:messages []})))) ; does this test make sense AI?
23:   (let [[ok failed] @llm/calls*]
24:     (is (= "data:image/png;base64,…" (get-in ok [:request :messages 0 :content 0 :image_url :url])))
25:     (is (= "ok" (get-in ok [:response :choices 0 :message :content])) "the full response")
26:     (is (= "down" (:error failed)))
27:     (is (not (.contains (pr-str @llm/calls*) "Bearer")))))
28:


Yes, line 22 makes sense. It does two jobs:

1. It creates the failed call that line 26 checks. thrown? catches the exception, so the test keeps going to the
   assertions.
2. It checks that chat! still rethrows. Recording the error mustn't swallow it, because callers like the analysis
   need to see the failure.

You could make it stricter with (is (thrown-with-msg? Exception #"down" ...)). But line 26 already checks the
message, so that's optional.

Other things in this test are worth fixing:

• Line 17 clears the real history. reset! llm/calls* empties the live /llm view whenever the test runs in the dev
  REPL. (with-redefs [llm/calls* (atom [])] ...) around the test body would leave it alone, the same way web-test
  handles db*.
• Line 27 is weak. The recorded call never includes headers, so it only fails if someone adds them later. Since the
  project prefers no low-value tests, I'd drop it, and then rename the test to something like
  calls-are-remembered-without-payloads.
• The name is misleading. What the test really checks is that image data is stripped, plus the response and error
  are recorded. "Secrets" only refers to line 27.

I haven't made any edits. Want me to apply the with-redefs change and drop line 27?
```
