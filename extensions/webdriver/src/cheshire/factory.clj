(ns cheshire.factory
  "Shim: Etaoin binds a Jackson factory; kmet.libs.json has no size cap to lift.")

(def ^:dynamic *json-factory* nil)

(defn make-json-factory [_opts] ::factory)
