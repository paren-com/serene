(ns paren.serene.schema
  (:require
   [clojure.spec.alpha :as s]
   [clojure.walk :as walk]
   #?@(:clj [[clojure.data.json :as json]
             [clojure.java.io :as io]
             [org.httpkit.client :as http]]))
  #?(:cljs (:require-macros
            [paren.serene.schema :refer [slurp-query]])))

(s/def ::Directive (s/keys
                     :req-un [::__typename
                              ::args
                              ::description
                              ::locations
                              ::name]))

(s/def ::EnumValue (s/keys
                     :req-un [::__typename
                              ::deprecationReason
                              ::description
                              ::isDeprecated
                              ::name]))

(s/def ::Field (s/keys
                 :req-un [::__typename
                          ::args
                          ::deprecationReason
                          ::description
                          ::isDeprecated
                          ::name
                          ::type]))

(s/def ::InputValue (s/keys
                      :req-un [::__typename
                               ::defaultValue
                               ::description
                               ::name
                               ::type]))

(s/def ::Type (s/and
                (s/keys
                  :req-un [::__typename
                           ::description
                           ::enumValues
                           ::fields
                           ::inputFields
                           ::interfaces
                           ::kind
                           ::name
                           ::possibleTypes])
                (s/conformer
                  (fn ensure-typename-field
                    [{:as m
                      :keys [fields kind]}]
                    (if (and
                          (= kind :OBJECT)
                          (->> fields
                            (filter #(= (:name %) :__typename))
                            first
                            nil?))
                      (update m :fields conj {:__typename :__Field
                                              :args []
                                              :deprecationReason nil
                                              :description nil
                                              :isDeprecated false
                                              :name :__typename
                                              :type {:__typename :__Type
                                                     :kind :NON_NULL
                                                     :name nil
                                                     :ofType  {:__typename :__Type
                                                               :kind :SCALAR
                                                               :name :String
                                                               :ofType nil}}})
                      m)))))

(s/def ::TypeRef (s/and
                   (s/keys
                     :req-un [::__typename
                              ::kind
                              ::name]
                     :opt-un [::ofType])
                   (fn not-a-type [m]
                     (empty?
                       (select-keys m [:enumValues
                                       :fields
                                       :inputFields
                                       :interfaces
                                       :possibleTypes])))))

(s/def ::__typename simple-keyword?)

(s/def ::args (s/coll-of ::InputValue :kind vector?))

(s/def ::defaultValue (s/nilable string?))

(s/def ::deprecationReason (s/nilable string?))

(s/def ::description (s/nilable string?))

(s/def ::enumValues (s/nilable (s/coll-of ::EnumValue
                                 :kind vector?)))

(s/def ::fields (s/nilable (s/coll-of ::Field
                             :kind vector?)))

(s/def ::inputFields (s/nilable (s/coll-of ::InputValue
                                  :kind vector?)))

(s/def ::interfaces (s/nilable (s/coll-of ::TypeRef
                                 :kind vector?)))

(s/def ::isDeprecated boolean?)

(s/def ::kind simple-keyword?)

(s/def ::locations (s/coll-of #{:ARGUMENT_DEFINITION
                                :ENUM
                                :ENUM_VALUE
                                :FIELD
                                :FIELD_DEFINITION
                                :FRAGMENT_DEFINITION
                                :FRAGMENT_SPREAD
                                :INLINE_FRAGMENT
                                :INPUT_FIELD_DEFINITION
                                :INPUT_OBJECT
                                :INTERFACE
                                :MUTATION
                                :OBJECT
                                :QUERY
                                :SCALAR
                                :SCHEMA
                                :SUBSCRIPTION
                                :UNION}
                     :kind vector?))

(s/def ::name (s/nilable simple-keyword?))

(s/def ::ofType (s/nilable ::TypeRef))

(s/def ::possibleTypes (s/nilable (s/coll-of ::TypeRef
                                    :kind vector?)))

(s/def ::type ::TypeRef)

(s/def ::queryType (s/keys :req-un [::name]))

(s/def ::mutationType (s/nilable (s/keys :req-un [::name])))

(s/def ::subsriptionType (s/nilable (s/keys :req-un [::name])))

(s/def ::directives (s/coll-of ::Directive
                      :kind vector?))

(s/def ::types (s/coll-of ::Type :kind vector?))

(s/def ::__schema (s/keys
                    :req-un [::__typename
                             ::queryType
                             ::mutationType
                             ::subscriptionType
                             ::directives
                             ::types]))

(s/def ::Schema ::__schema)

(s/def ::data (s/keys
                :req-un [::__schema]))

(s/def ::errors (s/nilable empty?))

(s/def ::response (s/keys
                    :req-un [::data]
                    :opt-un [::errors]))

(defn ^:private normalize
  "* keywordizes all keys and some specific values
  * replaces non-serializable collections with serializable collections
  * converts sets and sequential collections into sorted vectors "
  [form]
  (->> form
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
                       (or
                         (set? x)
                         (sequential? x)) (->> x (sort-by :name) vec)
                       :else x)))))

(s/def ::schema (s/and
                  (s/conformer normalize)
                  (s/or
                    :response ::response
                    :data ::data
                    :Schema ::Schema)
                  (s/conformer
                    #(case (key %)
                       :response (-> % val :data :__schema)
                       :data (-> % val :__schema)
                       :Schema (-> % val)))))

#?(:clj (defmacro ^:private slurp-query []
          (-> "paren/serene/IntrospectionQuery.graphql"
            io/resource
            slurp)))

(def query (slurp-query))

#?(:clj (defn fetch
          ([url]
           (fetch url {}))
          ([url opts]
           (-> {:url url
                :method :post
                :body (json/write-str {:query query})}
             (merge opts)
             http/request
             deref
             :body
             (json/read-str :key-fn keyword)))))
