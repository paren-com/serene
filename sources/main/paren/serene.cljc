(ns paren.serene
  (:require
   [clojure.spec.alpha :as s]
   [paren.serene.compiler :as compiler]
   [paren.serene.introspection :as introspection]
   #?(:clj [clojure.java.io :as io]))
  #?(:cljs (:require-macros
            [paren.serene])))

#?(:clj (def introspection-query
          (-> "paren/serene/IntrospectionQuery.graphql"
            io/resource
            slurp)))

(defmacro def-specs
  [introspection-response & {:as options}]
  (let [resp (eval introspection-response)
        opts (eval options)]
    (compiler/compile resp opts)))
