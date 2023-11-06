(ns paren.serene.compiler.transducers
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as sg]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [paren.serene.compiler :as compiler]
   [paren.serene.schema :as schema]))

;; Takes a function (or map) that will receive a spec schema key and
;; should return a spec name, a collection of spec names, or `nil`.
;; Returns a transducer that will add aliased specs.
(defmethod compiler/transducer :alias [_ alias-fn]
  (mapcat (fn [spec]
            (->> spec
              ::compiler/schema-key
              alias-fn
              list
              flatten
              (map (partial assoc spec
                     ::alias? true
                     ::compiler/form (::compiler/name spec)
                     ::compiler/name))
              (cons spec)))))

;; Takes a function (or map) that will receive a spec schema key
;; and should return a new prefx to replace `*ns*` or `nil`.
;; Returns a transducer that aliases default spec names.
(defmethod compiler/transducer :prefix [_ prefix-fn]
  (let [prefix-fn (if (simple-ident? prefix-fn)
                    (constantly prefix-fn)
                    prefix-fn)]
    (compiler/transducer :alias (fn [k]
                                  (let [prefix (prefix-fn k)]
                                    (when prefix
                                      (keyword
                                        (->> [(name prefix) (namespace k)]
                                          (remove nil?)
                                          (str/join "."))
                                        (name k))))))))

;; Takes a function (or map) that will receive a spec schema key and
;; should return a spec form or `nil`.
;; Returns a transducer that combines specs with `s/and`.
(defmethod compiler/transducer :extend [_ extend-fn]
  (map (fn [{:as spec
             ::keys [alias?]
             ::compiler/keys [form schema-key]}]
         (if-let [ext (and
                        (not alias?)
                        (extend-fn schema-key))]
           (assoc spec ::compiler/form `(s/and ~ext ~form))
           spec))))

;; Takes a function (or map) that will receive an object type spec name and
;; should return a boolean or integer to indicate whether the spec for that
;; object should have a custom test.check generator that generates all fields.
;; For integer, nested fields will be generated to that depth.
;; For boolean, the default depth of `s/*recursion-limit*` will be used.
(defmethod compiler/transducer :gen-object-fields [_ gen-fn]
  (let [gen-fn (if (ifn? gen-fn)
                 gen-fn
                 (constantly gen-fn))]
    (map (fn [spec]
           (let [node (get-in compiler/*schema* (::compiler/schema-path spec))
                 depth (and
                         (not (::alias? spec))
                         (= (:kind node) :OBJECT)
                         (-> spec ::compiler/schema-key gen-fn))
                 depth (when depth
                         (if (boolean? depth)
                           `s/*recursion-limit*
                           depth))]
             (if depth
               (assoc spec ::compiler/form
                 `(let [spec# ~(::compiler/form spec)]
                    (reify
                      s/Specize
                      (~'specize* [this#]
                       this#)
                      (~'specize* [this# _#]
                       this#)
                      s/Spec
                      (~'conform* [_# m#]
                       (s/conform* spec# m#))
                      (~'unform* [_# m#]
                       (s/unform* spec# m#))
                      (~'explain* [_# path# via# in# x#]
                       (s/explain* spec# path# via# in# x#))
                      (~'gen* [_# overrides# path# rmap#]
                       (let [path# (conj path# ~(::compiler/name spec))
                             rmap# (update rmap# ~(::compiler/name spec) (fnil inc 0))
                             rmap# (update rmap# ::depth (fnil inc 0))]
                         (if (<= (::depth rmap#) ~depth)
                           (->> ~(->> node
                                   :fields
                                   (map (juxt :name (comp ::compiler/name ::compiler/spec)))
                                   (into (sorted-map)))
                             (mapcat
                               (fn [[k# v#]]
                                 [k# (sg/delay
                                       (#'s/gensub v# overrides# path# rmap# v#))]))
                             (apply sg/hash-map))
                           (sg/hash-map :__typename (sg/return ~(-> node :name name))))))
                      (~'with-gen* [_# gfn#]
                       (s/with-gen* spec# gfn#))
                      (~'describe* [_#]
                       (s/describe* spec#)))))
               spec))))))
