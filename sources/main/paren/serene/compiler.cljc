(ns paren.serene.compiler
  (:refer-clojure :exclude [alias compile])
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [paren.serene.schema :as schema]
   #?(:cljs [cljs.reader :refer [read-string]])))

(defn ^:private prewalk
  "Like clojure.walk/prewalk except that function signature is
  `[root-form path-to-form form]` instead of `[form]`."
  [f root-form]
  ((fn prewalk* [path form]
     (let [new-form (f root-form path form)]
       (cond
         (map? new-form) (->> new-form
                           (map (fn [[k sub-form]]
                                  [k (prewalk* (conj path k) sub-form)]))
                           (into (empty new-form)))
         (set? new-form) (->> new-form
                           (map (fn [sub-form]
                                  (prewalk* (conj path sub-form) sub-form)))
                           (into (empty new-form)))
         (sequential? new-form) (->> new-form
                                  (map-indexed (fn [i sub-form]
                                                 (prewalk* (conj path i) sub-form)))
                                  (into (empty new-form)))
         :else new-form)))
   []
   root-form))

(defn ^:private namespace? [x]
  (instance?
    #?(:clj clojure.lang.Namespace
       :cljs cljs.core.Namespace)
    x))

(defn ^:private add-spec-nodes
  [schema {:as config
           :keys [alias
                  namespace]}]
  (let [alias (fn alias-wrapper [kw]
                (let [ret (alias kw)]
                  (cond
                    (nil? ret) #{}
                    (keyword? ret) #{ret}
                    (set? ret) ret)))
        base-ns (name
                  (if (namespace? namespace)
                    (ns-name namespace)
                    namespace))
        ->spec-name (fn ->spec-name [segments]
                      (let [segs (map name segments)
                            ns (->> segs
                                 (cons base-ns)
                                 butlast
                                 (str/join "."))
                            n (last segs)]
                        (keyword ns n)))
        ->spec (fn [segments]
                 (let [name (->spec-name segments)]
                   {:kind :spec
                    :name name
                    :aliases (alias name)}))]
    (prewalk
      (fn [schema path node]
        (case (:kind node)
          (:enum-type-definition
           :input-object-type-definition
           :interface-type-definition
           :object-type-definition
           :scalar-type-definition
           :union-type-definition) (assoc node
                                     :spec (->spec [(:name node)]))
          :field-definition (let [type-def (get-in schema (drop-last 2 path))]
                              (assoc node
                                :spec (->spec [(:name type-def) (:name node)])
                                :arguments-spec (->spec [(:name type-def) (:name node) :__args])))
          :input-value-definition (let [type-def (get-in schema (drop-last 4 path))
                                        field-def (get-in schema (drop-last 2 path))]
                                    (assoc node :spec (->spec [(:name type-def) (:name field-def) (:name node)])))
          :enum-value-definition (let [type-def (get-in schema (drop-last 2 path))]
                                   (assoc node :spec (->spec [(:name type-def) (:name node)])))
          node))
      schema)))

(defn ^:private topo-sort-specs [spec-map]
  (let [sort-aliases (fn sort-aliases [sorted unsorted]
                       (let [no-deps (->> unsorted
                                       (remove (fn [[k v]]
                                                 (contains? unsorted v)))
                                       (into {}))
                             sorted' (concat sorted no-deps)
                             unsorted' (apply dissoc unsorted (keys no-deps))]
                         (if (seq unsorted')
                           (sort-aliases sorted' unsorted')
                           sorted')))
        aliases (->> spec-map
                  (filter #(-> % val keyword?))
                  (into {}))]
    (concat
      (apply dissoc spec-map (keys aliases))
      (sort-aliases [] aliases))))

(defn ^:private create-spec-fn-map
  "Takes a schema with `:spec` nodes and returns a pre-evaluated map of `:def`
  and `:undef` functions for defining specs and undefining specs respectively."
  [schema]
  (let [and-directives (fn [spec & nodes]
                         (or
                           (some->> nodes
                             (mapcat :directives)
                             seq
                             (filter #(= (:name %) :serene))
                             (mapcat :arguments)
                             (filter #(= (:name %) :spec))
                             (map (comp read-string :value))
                             (cons spec)
                             (cons `s/and))
                           spec))
        type-name->node (->> schema
                          :types
                          (map (fn [node]
                                 [(:name node) node]))
                          (into {}))
        interface-name->object-names (->> schema
                                       :types
                                       (filter (comp not-empty :interfaces))
                                       (reduce
                                         (fn [m type-def]
                                           (reduce
                                             (fn [m interface]
                                               (update m (:name interface) conj (:name type-def)))
                                             m
                                             (:interfaces type-def)))
                                         {}))
        type-name->spec-name (fn [type-name]
                               (-> type-name type-name->node :spec :name))
        type->spec (fn [t]
                     (->> t
                       (walk/postwalk (fn [x]
                                        (if (map? x)
                                          (let [{:keys [kind type name]} x]
                                            (if (= kind :non-null-type)
                                              (assoc type :non-null? true)
                                              x))
                                          x)))
                       ((fn type->spec [{:keys [non-null? kind type name]}]
                          (let [spec (case kind
                                       :list-type `(s/coll-of ~(type->spec type) :kind sequential?)
                                       :named-type (let [named-type-node (type-name->node name)]
                                                     (if (= (:kind named-type-node) :interface-type-definition)
                                                       (->> named-type-node
                                                         :name
                                                         interface-name->object-names
                                                         (mapcat (juxt identity type-name->spec-name))
                                                         (cons `s/or))
                                                       (type-name->spec-name name))))]
                            (if non-null?
                              spec
                              `(s/nilable ~spec)))))))
        type->spec-name (fn [t]
                          (loop [{:keys [kind type name]} t]
                            (if (= kind :named-type)
                              (type-name->spec-name name)
                              (recur type))))
        nodes->keys-spec (fn [nodes {:keys [input?]}]
                           (->> nodes
                             (reduce
                               (fn [m node]
                                 (let [k (if (or
                                               (contains? node :default-value)
                                               (not= (-> node :type :kind) :non-null-type))
                                           :opt-un
                                           :req-un)
                                       k (if input? k :opt-un)]
                                   (update m k conj (-> node :spec :name))))
                               {:opt-un []
                                :req-un []})
                             (apply concat)
                             (cons `s/keys)))
        specs (atom {})
        add-spec! #(swap! specs assoc %1 %2)]
    (->> schema
      (prewalk
        (fn [schema path node]
          (case (:kind node)
            :spec (let [{:keys [name aliases]} node]
                    (doseq [alias aliases]
                      (add-spec! alias name)))
            :scalar-type-definition (add-spec!
                                      (-> node :spec :name)
                                      (and-directives (case (:name node)
                                                        :Int `integer?
                                                        :Float `float?
                                                        :String `string?
                                                        :Boolean `boolean?
                                                        :ID `string?
                                                        `any?)
                                        node))
            :enum-type-definition (add-spec!
                                    (-> node :spec :name)
                                    (and-directives
                                      (->> node
                                        :values
                                        (mapcat (juxt :name (comp :name :spec)))
                                        (cons `s/or))
                                      node))
            :enum-value-definition (add-spec!
                                     (-> node :spec :name)
                                     (and-directives
                                       (-> node :name name hash-set)
                                       node))
            :union-type-definition (add-spec!
                                     (-> node :spec :name)
                                     (and-directives
                                       (->> node
                                         :members
                                         (map :name)
                                         (mapcat (fn [k]
                                                   [k (type-name->spec-name k)]))
                                         (cons `s/or))
                                       node))
            :input-value-definition (add-spec! (-> node :spec :name) (and-directives (-> node :type type->spec) node))
            :field-definition (let [spec (if (= (:name node) :__typename)
                                           (-> node :spec :name namespace (str/split #"\.") last hash-set)
                                           (-> node :type type->spec))]
                                (add-spec! (-> node :spec :name) (and-directives spec node))
                                (add-spec! (-> node :arguments-spec :name) (-> node :arguments (nodes->keys-spec {:input? true}))))
            (:input-object-type-definition
             :interface-type-definition
             :object-type-definition) (let [input? (= (:kind node) :input-object-type-definition)
                                            spec (nodes->keys-spec (:fields node) {:input? input?})]
                                        (add-spec! (-> node :spec :name) (and-directives spec node)))
            nil)
          node)))
    (let [spec-set (into (sorted-set) (keys @specs))
          sorted-specs (topo-sort-specs @specs)
          defs (map
                 (fn [[k v]]
                   `(s/def ~k ~v))
                 sorted-specs)
          undefs (map
                   (fn [[k v]]
                     `(s/def ~k nil))
                   (reverse sorted-specs))]
      `(let [spec-set# ~spec-set]
         {:kind :spec-fns
          :def (fn def-specs# [] ~@defs spec-set#)
          :undef (fn undef-specs# [] ~@undefs spec-set#)}))))

(s/def ::namespace (s/or
                     :namespace namespace?
                     :ident simple-ident?))

(s/def ::alias (s/fspec
                 :args (s/cat :keyword qualified-keyword?)
                 :ret (s/nilable
                        (s/or
                          :keyword qualified-keyword?
                          :set (s/coll-of qualified-keyword? :kind set?)))
                 :fn (fn [{:keys [args ret]}]
                       (case (first ret)
                         :keyword (not= (second ret) (:keyword args))
                         :set (not (contains? (second ret) (:keyword args)))
                         nil true))))

(s/def ::config (s/keys :req-un [::alias ::namespace]))

(s/def ::def fn?)

(s/def ::undef fn?)

(s/def ::spec-fns (s/keys :req-un [::def ::undef]))

(s/def ::compiled-and-evaluated-schema (s/merge
                                         ::schema/schema
                                         (s/keys :req [::spec-fns])))

(defn compile
  [schema config]
  {:pre [(s/valid? ::schema/schema schema)
         (s/valid? ::config config)]
   #?@(:clj [:post [(s/valid? ::compiled-and-evaluated-schema (eval %))]])}
  (let [schema (add-spec-nodes schema config)
        spec-fns (create-spec-fn-map schema)]
    (assoc schema ::spec-fns spec-fns)))
