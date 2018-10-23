(ns paren.serene.parser
  (:require
   [clojure.spec.alpha :as s]))

(defmulti parse
  "Takes a parse method, schema source, and options map.
  Returns a schema."
  (fn [method source options]
    {:pre [(s/valid? keyword? method)
           (s/valid? map? options)]}
    method))
