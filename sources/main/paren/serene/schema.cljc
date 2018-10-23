(ns paren.serene.schema
  (:require
   [clojure.spec.alpha :as s])
  #?(:cljs (:require-macros
            [paren.serene.schema :refer [of-kind named-kinds]])))

(defn ^:private distrinct-names? [coll]
  (loop [names #{}
         [node & nodes] coll]
    (if-let [{:keys [name]} node]
      (if (contains? names name)
        false
        (recur (conj names name) nodes))
      true)))

(defmacro ^:private named-kinds [kind]
  `(s/and
     (s/coll-of ~kind :kind vector?)
     distrinct-names?))

(defmacro ^:private of-kind [spec kind]
  `(s/and ~spec #(= (:kind %) ~kind)))

(s/def ::kind simple-keyword?)

(s/def ::default-value some?)

(s/def ::description (s/nilable string?))

(s/def ::value any?)

(s/def ::name keyword?)

(s/def ::aliases (s/coll-of qualified-keyword?
                   :kind set?))

(s/def :paren.serene.schema.spec/name qualified-keyword?)

(s/def ::spec (of-kind
                (s/keys
                  :req-un [:paren.serene.schema.spec/name]
                  :opt-un [::aliases])
                :spec))

(s/def ::arguments-spec ::spec)

(s/def ::type (s/or
                :named ::named-type
                :list ::list-type
                :non-null ::non-null-type))

(s/def ::named-type (of-kind
                      (s/keys :req-un [::name])
                      :named-type))

(s/def ::list-type (of-kind
                     (s/keys :req-un [::type])
                     :list-type))

(s/def ::non-null-type (of-kind
                         (s/keys :req-un [::type])
                         :non-null-type))

(s/def ::input-value-definition (of-kind
                                  (s/keys
                                    :req-un [::type
                                             ::name]
                                    :opt-un [::default-value
                                             ::description
                                             ::directives
                                             ::spec])
                                  :input-value-definition))

(s/def ::input-value (of-kind
                       (s/keys
                         :req-un [::name]
                         :opt-un [::value])
                       :input-value))

(s/def ::arguments (named-kinds ::input-value-definition))

(s/def :paren.serene.schema.directive/arguments (named-kinds ::input-value))

(s/def ::directive (of-kind
                     (s/keys
                       :req-un [::name]
                       :opt-un [:paren.serene.schema.directive/arguments])
                     :directive))

(s/def ::directives (named-kinds ::directive))

(s/def ::enum-value-definition (of-kind
                                 (s/keys
                                   :req-un [::name]
                                   :opt-un [::description
                                            ::directives
                                            ::spec])
                                 :enum-value-definition))

(s/def ::values (named-kinds ::enum-value-definition))

(s/def ::field-definition (of-kind
                            (s/keys
                              :req-un [::name
                                       ::type]
                              :opt-un [::arguments
                                       ::default
                                       ::description
                                       ::directives
                                       ::spec
                                       ::arguments-spec])
                            :field-definition))

(s/def ::fields (named-kinds ::field-definition))

(s/def ::interfaces (named-kinds ::named-type))

(s/def ::members (named-kinds ::named-type))

(def ^:private type-def-base (s/keys
                               :req-un [::name
                                        ::kind]
                               :opt-un [::description
                                        ::directives
                                        ::spec]))

(defmulti ^:private type-definition :kind)

(defmethod type-definition :scalar-type-definition [_]
  type-def-base)

(defmethod type-definition :enum-type-definition [_]
  (s/merge
    type-def-base
    (s/keys :req-un [::values])))

(defmethod type-definition :input-object-type-definition [_]
  (s/merge
    type-def-base
    (s/keys :req-un [::fields])))

(defmethod type-definition :object-type-definition [_]
  (s/merge
    type-def-base
    (s/keys
      :req-un [::fields]
      :opt-un [::interfaces])))

(defmethod type-definition :interface-type-definition [_]
  (s/merge
    type-def-base
    (s/keys :req-un [::fields])))

(defmethod type-definition :union-type-definition [_]
  (s/merge
    type-def-base
    (s/keys :req-un [::members])))

(s/def ::type-definition (s/multi-spec type-definition :kind))

(s/def ::types (named-kinds ::type-definition))

(s/def ::query-name simple-keyword?)

(s/def ::mutation-name simple-keyword?)

(s/def ::subscription-name simple-keyword?)

(s/def ::schema (of-kind
                  (s/keys
                    :req-un [::types
                             ::query-name]
                    :opt-un [::mutation-name
                             ::subscription-name])
                  :schema))
