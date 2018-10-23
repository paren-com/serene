(ns paren.serene.parser.lacinia
  (:require
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.walk :as walk]
   [com.walmartlabs.lacinia.schema :as lacinia.schema]
   [paren.serene.parser :as parser]))

(defn ^:private lacinia-schema->schema
  [edn-schema]
  (let [edn-schema (->> "com/walmartlabs/lacinia/introspection.edn"
                     io/resource
                     slurp
                     read-string
                     (merge-with merge edn-schema {:scalars (->> [:Boolean
                                                                  :Float
                                                                  :ID
                                                                  :Int
                                                                  :String]
                                                              (map (fn [k]
                                                                     [k {:parse (s/conformer any?)
                                                                         :serialize (s/conformer any?)}]))
                                                              (into {}))}))
        {query-name :query
         mutation-name :mutation
         subscription-name :subscription
         :or {query-name :QueryRoot
              mutation-name :MutationRoot
              subscription-name :SubscriptionRoot}} (:roots edn-schema)
        ;; Merge `:queries`, `:mutations`, and `:subscriptions` maps into object maps
        edn-schema (reduce
                     (fn [schema [op-name op-type]]
                       (let [schema (update-in schema [:objects op-name :fields] merge (get schema op-type))]
                         (if (-> schema :objects op-name :fields nil?)
                           (update schema :objects dissoc op-name)
                           schema)))
                     edn-schema
                     {query-name :queries
                      mutation-name :mutations
                      subscription-name :subscriptions})
        edn-schema (s/conform ::lacinia.schema/schema-object edn-schema)
        ->directives (fn [dirs]
                       (->> dirs
                         (map (fn [dir]
                                {:kind :directive
                                 :name (:directive-type dir)
                                 :arguments (->> dir
                                              :directive-args
                                              (map (fn [[k v]]
                                                     {:name k
                                                      :kind :input-value
                                                      :value v})))}))))
        scalars (->> edn-schema
                  :scalars
                  (map (fn [[scalar-name scalar]]
                         (-> scalar
                           (dissoc :parse :serialize)
                           (update :directives ->directives)
                           (assoc
                             :name scalar-name
                             :kind :scalar-type-definition)))))
        enums (->> edn-schema
                :enums
                (map (fn [[enum-name enum]]
                       {:kind :enum-type-definition
                        :name enum-name
                        :directives (->directives (:directives enum))
                        :values (->> enum
                                  :values
                                  (map (fn [[value-type value]]
                                         (case value-type
                                           :bare-value {:kind :enum-value-definition
                                                        :name (keyword value)}
                                           :described {:kind :enum-value-definition
                                                       :name (-> value :enum-value keyword)
                                                       :directives (->directives (:directives value))
                                                       :description (:description value)}))))})))
        unions (->> edn-schema
                 :unions
                 (map (fn [[union-name union]]
                        {:kind :union-type-definition
                         :name union-name
                         :directives (-> union :directives ->directives)
                         :members (->> union
                                    :members
                                    (map (fn [member-name]
                                           {:kind :named-type
                                            :name member-name})))})))
        ->type (fn ->type [[k v]]
                 (case k
                   :base-type {:kind :named-type
                               :name (keyword v)}
                   :wrapped-type {:kind (case (:modifier v)
                                          list :list-type
                                          non-null :non-null-type)
                                  :type (-> v :type ->type)}))
        ->objects (fn [kind objects]
                    (->> objects
                      (map (fn [[obj-name {:as obj
                                           :keys [description
                                                  directives
                                                  fields
                                                  implements]}]]
                             {:kind kind
                              :name obj-name
                              :directives (->directives directives)
                              :description description
                              :fields (->> fields
                                        (map (fn [[field-name
                                                   {:as field
                                                    :keys [args
                                                           description
                                                           directives
                                                           type]}]]
                                               {:kind :field-definition
                                                :name field-name
                                                :arguments (->> args
                                                             (map (fn [[arg-name {:as arg
                                                                                  :keys [description
                                                                                         directives
                                                                                         default-value
                                                                                         type]}]]
                                                                    {:kind :input-value-definition
                                                                     :name arg-name
                                                                     :description description
                                                                     :directives (->directives directives)
                                                                     :default-value default-value
                                                                     :type (->type type)})))
                                                :description description
                                                :directives (->directives directives)
                                                :type (->type type)})))
                              :interfaces (->> implements
                                            (map (fn [interface-name]
                                                   {:kind :named-type
                                                    :name interface-name})))}))))
        objects (->> edn-schema
                  :objects
                  (->objects :object-type-definition)
                  (map (fn [type-def]
                         (update type-def :fields conj {:kind :field-definition
                                                        :name :__typename
                                                        :type {:kind :named-type
                                                               :name :String}}))))
        input-objects (->> edn-schema :input-objects (->objects :input-object-type-definition))
        interfaces (->> edn-schema :interfaces (->objects :interface-type-definition))
        schema (cond-> {:kind :schema
                        :query-name query-name
                        :types (concat
                                 scalars
                                 enums
                                 input-objects
                                 interfaces
                                 objects
                                 unions)}
                 mutation-name (assoc :mutation-name mutation-name)
                 subscription-name (assoc :subscription-name subscription-name))]
    (walk/postwalk
      (fn [x]
        (cond
          (map? x) (cond-> x
                     (-> x :description nil?) (dissoc :description)
                     (-> x :directives empty?) (dissoc :directives)
                     (-> x :arguments empty?) (dissoc :arguments)
                     (-> x :interfaces empty?) (dissoc :interfaces)
                     (-> x :default-value nil?) (dissoc :default-value))
          (seq? x) (vec x)
          :else x))
      schema)))

(defmethod parser/parse :lacinia [_ schema _]
  (let [edn (if (string? schema)
              (read-string schema)
              schema)]
    (assert (lacinia.schema/compile edn))
    (lacinia-schema->schema edn)))
