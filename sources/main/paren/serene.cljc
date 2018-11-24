(ns paren.serene
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [paren.serene.compiler :as compiler]
   [paren.serene.compiler.transducers]
   [paren.serene.schema :as schema]
   #?@(:clj [[clojure.java.io :as io]
             [fipp.clojure :as fipp]]))
  #?(:cljs (:require-macros
            [paren.serene])))

(defmacro def-specs
  "Takes a GraphQL schema and an optional transducer or options map/seq.
  Options/transducers are defined in `paren.serene.compiler.transducers`.
  The transducer will receive spec maps, as defined in `paren.serene.compiler`.
  Returns a topologically sorted vector of `s/def` forms.
  All arguments to `def-specs` are explicitly `eval`ed."
  ([schema]
   `(def-specs ~schema nil))
  ([schema xform]
   (let [resp (eval schema)
         xf (eval xform)]
     (compiler/compile resp xf))))

#?(:clj (def ^:private generated-file-header
          (->> ["; Serene"
                " com.paren/serene"
                " https://github.com/paren-com/serene"
                " Generate clojure.spec with GraphQL and extend GraphQL with clojure.spec"
                ""
                " DO NOT EDIT THIS FILE!"]
            (map (partial str ";;;"))
            (str/join \newline))))

#?(:clj (defn spit-specs
          "Takes a file or file path, a simple symbol, a GraphQL schema, and an optional transducer or options map/seq.
           Spits `s/def` forms defined by `schema` and modified by `xform` to `file` with namespace `ns`."
          ([file ns schema]
           (spit-specs file ns schema nil))
          ([file ns schema xform]
           {:pre [(simple-symbol? ns)]}
           (when (and
                   (-> file io/file .isFile)
                   (not (str/starts-with? (slurp file) generated-file-header)))
             (-> "Unexpected output file format. Delete the file and try again."
               (ex-info {:file file})
               throw))
           (let [specs (binding [*ns* (create-ns ns)]
                         (compiler/compile schema xform))
                 ns-decl (->> specs
                           (tree-seq coll? seq)
                           (sequence (comp
                                       (filter qualified-symbol?)
                                       (map namespace)
                                       (distinct)
                                       (map symbol)
                                       (map vector)))
                           sort
                           (cons :require)
                           (list 'ns ns))
                 forms (cons ns-decl specs)]
             (with-open [writer (io/writer file)]
               (binding [*out* writer]
                 (println generated-file-header)
                 (doseq [form forms]
                   (println)
                   (fipp/pprint
                     form
                     {:symbols (assoc fipp/default-symbols
                                 `s/def (get fipp/default-symbols 'def))
                      :print-length nil
                      :print-level nil
                      :print-meta true
                      :width 100}))))))))
