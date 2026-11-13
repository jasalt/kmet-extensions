(ns clojure.tools.logging
  "Shim: tools.logging needs `agent`, which kmet's SCI context lacks. Etaoin's
  log calls are diagnostics: debug/info are dropped, warnings and errors go
  through kmet.libs.log."
  (:require [kmet.libs.log :as log]))

(defn- split-ex [args]
  (if (instance? Throwable (first args)) [(first args) (rest args)] [nil args]))

(defn- report! [level ex msg]
  (let [msg (str "etaoin " (name level) ": " msg)]
    (if ex (log/log! msg ex) (log/log! msg))))

(defn log-plain [level args]
  (let [[ex args] (split-ex args)] (report! level ex (apply str args))))

(defn log-format [level args]
  (let [[ex [fmt & more]] (split-ex args)] (report! level ex (apply format fmt more))))

(defmacro debug [& _] nil)
(defmacro debugf [& _] nil)
(defmacro trace [& _] nil)
(defmacro tracef [& _] nil)
(defmacro info [& _] nil)
(defmacro infof [& _] nil)
(defmacro warn [& args] `(log-plain :warn [~@args]))
(defmacro warnf [& args] `(log-format :warn [~@args]))
(defmacro error [& args] `(log-plain :error [~@args]))
(defmacro errorf [& args] `(log-format :error [~@args]))
