(ns paren.serene
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [paren.serene.compiler :as compiler]
   [paren.serene.introspection :as introspection]
   #?(:clj [clojure.java.io :as io]))
  #?(:cljs (:require-macros
            [paren.serene])))

#?(:clj (def introspection-query
          (-> "paren/serene/IntrospectionQuery.graphql" io/resource slurp)))

(defn schema
  ([introspection-response]
   (schema introspection-response {}))
  ([introspection-response options]
   {:pre [(map? introspection-response)
          (map? options)]}
   (let [resp (->> introspection-response
                walk/keywordize-keys
                (walk/postwalk (fn [x]
                                 (cond
                                   (map? x) (->> x
                                              (map (fn [[k v]]
                                                     [k
                                                      (case k
                                                        (:__typename :kind :name) (keyword v)
                                                        :locations (mapv keyword v)
                                                        v)]))
                                              (into (sorted-map)))
                                   (seq? x) (vec x)
                                   :else x))))
         cfg (merge
               {:alias (constantly #{})
                :prefix *ns*
                :specs {}}
               options)
         compiled (compiler/compile resp cfg)]
     compiled)))

(defmacro defschema
  [name introspection-response & {:as options}]
  (let [resp (eval introspection-response)
        opts (eval options)
        compiled-schema (schema resp opts)
        ret (if (:def-specs? opts)
              `(let [ret# ~compiled-schema]
                 (def-specs ret#)
                 ret#)
              compiled-schema)]
    `(def ~name ~ret)))

(s/def ::def-specs fn?)

(s/def ::undef-specs fn?)

(s/def ::evaluated-schema (s/keys
                            :req-un [::def-specs
                                     ::undef-specs
                                     ::introspection/response]))

(defn undef-specs
  [evaluated-schema]
  {:pre [(s/valid? ::evaluated-schema evaluated-schema)]}
  ((:undef-specs evaluated-schema)))

(defn def-specs
  [evaluated-schema]
  {:pre [(s/valid? ::evaluated-schema evaluated-schema)]}
  (try
    ((:def-specs evaluated-schema))
    (catch #?(:clj Throwable :cljs :default) e
      (undef-specs evaluated-schema)
      (throw e))))
