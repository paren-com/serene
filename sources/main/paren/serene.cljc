(ns paren.serene
  (:require
   [clojure.spec.alpha :as s]
   [paren.serene.compiler :as compiler]
   [paren.serene.introspection :as introspection])
  #?(:cljs (:require-macros
            [paren.serene])))

(def introspection-query introspection/query)

(defmacro def-specs
  [introspection-response & {:as options}]
  (let [resp (eval introspection-response)
        opts (eval options)]
    (compiler/compile resp opts)))
