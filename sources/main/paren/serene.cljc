(ns paren.serene
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [paren.serene.compiler :as compiler]
   [paren.serene.parser :as parser]
   #?(:clj [clojure.java.io :as io]))
  #?(:cljs (:require-macros
            [paren.serene])))

#?(:clj (s/fdef schema
          :args (s/cat
                  :parse-method #(contains? (methods parser/parse) %)
                  :schema-source some?
                  :options (s/keys
                             :opt-un [::compiler/alias
                                      ::compiler/namespace]))))

#?(:clj (defn schema
          ([parse-method schema-source]
           (schema parse-method schema-source {}))
          ([parse-method schema-source options]
           (let [schema-source (if (string? schema-source)
                                 (or
                                   (some-> schema-source io/resource slurp)
                                   (let [file (io/file schema-source)]
                                     (when (.isFile file)
                                       (slurp file)))
                                   schema-source)
                                 schema-source)
                 config (merge
                          {:alias (constantly #{})
                           :namespace *ns*}
                          options)
                 parsed-schema (parser/parse parse-method schema-source config)
                 compiled-schema (compiler/compile parsed-schema config)]
             compiled-schema))))

#?(:clj (defn ^:private eval-all [form]
          (walk/prewalk
            (fn [form]
              (cond
                (fn? form) form
                (seq? form) (-> form eval eval-all)
                :else (eval form)))
            form)))

#?(:clj (defmacro defschema
          [name parse-method schema-source & {:as options}]
          (let [schema-source (eval-all schema-source)
                options (if (:alias options)
                          (update options :alias eval-all)
                          options)
                compiled-schema (schema parse-method schema-source options)
                ret (if (:def-specs? options)
                      `(let [ret# ~compiled-schema]
                         (def-specs ret#)
                         ret#)
                      compiled-schema)]
            `(def ~name ~ret))))

(defn undef-specs
  [compiled-schema]
  (s/assert ::compiler/compiled-and-evaluated-schema compiled-schema)
  ((-> compiled-schema ::compiler/spec-fns :undef)))

(defn def-specs
  [compiled-schema]
  (s/assert ::compiler/compiled-and-evaluated-schema compiled-schema)
  (try
    ((-> compiled-schema ::compiler/spec-fns :def))
    (catch #?(:clj Throwable :cljs :default) e
      (undef-specs compiled-schema)
      (throw e))))
