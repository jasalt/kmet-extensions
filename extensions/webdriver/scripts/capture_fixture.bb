;; Regenerate test/fixtures/ax.edn from test/fixtures/page.html with a real
;; Chromium (chromedriver + chromium on PATH). Plain bb, outside kmet:
;;   cd extensions/webdriver && bb --config /dev/null scripts/capture_fixture.bb
(require '[babashka.deps :as deps])
(deps/add-deps '{:deps {etaoin/etaoin {:mvn/version "1.1.43"}}})
(require '[babashka.fs :as fs]
         '[clojure.pprint :as pprint]
         '[clojure.walk :as walk]
         '[etaoin.api :as e])

(def root (fs/parent (fs/parent (fs/absolutize *file*))))
(def page (str (fs/path root "test/fixtures/page.html")))

(defn cdp [d cmd params]
  (:value (e/execute {:driver d :method :post
                      :path [:session (:session d) :goog :cdp :execute]
                      :data {:cmd cmd :params params}})))

(defn- portable
  "Replace the checkout-specific page URL so the fixture is machine-independent."
  [data]
  (let [url (str "file://" page)]
    (walk/postwalk #(if (= url %) "file:///fixture/page.html" %) data)))

(defn- backend-ids [node]
  (into #{(:backendNodeId node)}
        (mapcat backend-ids)
        (concat (:children node) (:shadowRoots node))))

(let [d (e/chrome {:headless true :args ["--no-sandbox"]})]
  (try
    (e/go d (str "file://" page))
    (let [loader (get-in (cdp d "Page.getFrameTree" {}) [:frameTree :frame :loaderId])
          initial (:nodes (cdp d "Accessibility.getFullAXTree" {}))
          doc (cdp d "DOM.getDocument" {:depth 0})
          form (cdp d "DOM.querySelector" {:nodeId (get-in doc [:root :nodeId]) :selector "#form"})
          form-ids (backend-ids (:node (cdp d "DOM.describeNode" {:nodeId (:nodeId form) :depth -1 :pierce true})))
          _ (e/js-execute d "const b=document.createElement('button');b.type='button';b.textContent='Undo';
                              document.querySelector('form').prepend(b);")
          mutated (:nodes (cdp d "Accessibility.getFullAXTree" {}))]
      (spit (str (fs/path root "test/fixtures/ax.edn"))
            (with-out-str
              (pprint/pprint (portable
                              {:loader-id loader
                               :initial initial
                               :form-backend-ids (into (sorted-set) form-ids)
                               :mutated mutated}))))
      (println "wrote ax.edn:" (count initial) "/" (count mutated) "nodes"))
    (finally (e/quit d))))
