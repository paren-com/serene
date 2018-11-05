(ns paren.serene
  (:refer-clojure
   :exclude [alias compile extend])
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [paren.serene.introspection :as introspection]
   #?(:cljs [cljs.reader :refer [read-string]]))
  #?(:cljs (:require-macros
            [paren.serene])))

(def introspection-query introspection/query)

(defn ^:private conform!
  [spec x]
  (let [conformed (s/conform spec x)]
    (if (s/invalid? conformed)
      (throw (ex-info
               (str "Invalid " spec ": " (s/explain-str spec x))
               {:spec spec
                :invalid x}))
      conformed)))

(defn ^:private nonconforming
  [x]
  `(s/nonconforming ~x))

(defn ^:private map-entry
  ([x]
   {:pre [(sequential? x)
          (= (count x) 2)]}
   (if-not (map-entry? x)
     (apply map-entry x)
     x))
  ([k v]
   #?(:clj (clojure.lang.MapEntry. k v)
      :cljs (MapEntry. k v nil))))

(defn ^:private spec-name
  [obj]
  {:pre [(contains? obj :name)]}
  (let [ks (loop [obj obj
                  ks ()]
             (if obj
               (recur
                 (::parent obj)
                 (cons (:name obj) ks))
               (->> ks
                 (cons (ns-name *ns*))
                 (map name))))]
    (keyword
      (str/join "." (butlast ks))
      (last ks))))

(def ^:private assoc-specs-object-spec
  (s/or
    :EnumValue ::introspection/EnumValue
    :Field ::introspection/Field
    :InputValue ::introspection/InputValue
    :Type ::introspection/Type))

(defmulti ^:private -assoc-specs
  (fn [{:as obj
        :keys [__typename kind]}]
    {:pre [(s/valid? assoc-specs-object-spec obj)]}
    (case __typename
      :__EnumValue :ENUM_VALUE
      :__Field :FIELD
      :__InputValue :INPUT_VALUE
      :__Type kind)))

(defn ^:private assoc-specs
  ([obj]
   (assoc-specs nil obj))
  ([parent obj]
   (cond
     (s/valid? assoc-specs-object-spec obj) (-assoc-specs (assoc obj ::parent parent))
     (s/valid? ::introspection/Directive obj) obj
     (coll? obj) (mapv assoc-specs obj)
     :else obj)))

(defn ^:private assoc-child-specs
  [obj k]
  (->> obj
    (partial assoc-specs)
    (partial mapv)
    (update obj k)))

(defn ^:private type-ref-spec
  [type-ref]
  (->> type-ref
    (walk/postwalk (fn [x]
                     (if (map? x)
                       (let [{:keys [kind name ofType]} x]
                         (if (= kind :NON_NULL)
                           (assoc ofType :non-null? true)
                           x))
                       x)))
    ((fn type-ref->spec [{:as obj
                          :keys [kind non-null? ofType]}]
       (let [spec (if (= kind :LIST)
                    `(s/coll-of ~(type-ref->spec ofType)
                       :kind sequential?)
                    (spec-name obj))]
         (if-not non-null?
           `(s/nilable ~spec)
           spec))))))

(defn ^:private input-values-spec
  [input-values]
  (->> input-values
    (reduce
      (fn [m {:keys [defaultValue type]
              ::keys [spec]}]
        (let [opt? (or
                     defaultValue
                     (not= (:kind type) :NON_NULL))
              k (if opt? :opt-un :req-un)]
          (update m k conj (::name spec))))
      {:opt-un []
       :req-un []})
    (apply concat)
    (cons `s/keys)))

(defmethod -assoc-specs :ENUM [obj]
  (let [obj (assoc-child-specs obj :enumValues)]
    (assoc obj ::spec {::name (spec-name obj)
                       ::form (->> obj
                                :enumValues
                                (mapcat (juxt :name (comp ::name ::spec)))
                                (cons `s/or)
                                nonconforming)})))

(defmethod -assoc-specs :ENUM_VALUE [obj]
  (assoc obj ::spec {::name (spec-name obj)
                     ::form #{(-> obj :name name)}}))

(defmethod -assoc-specs :FIELD [obj]
  (let [obj (assoc-child-specs obj :args)]
    (-> obj
      (assoc ::spec {::name (spec-name obj)
                     ::form (if (= (:name obj) :__typename)
                              #{(-> obj ::parent :name name)}
                              (-> obj :type type-ref-spec))})
      (assoc ::args-spec {::name (spec-name (update obj :name #(-> %
                                                                 name
                                                                 (str "%")
                                                                 keyword)))
                          ::form (-> obj :args input-values-spec)}))))

(defmethod -assoc-specs :INPUT_OBJECT [obj]
  (let [obj (assoc-child-specs obj :inputFields)]
    (assoc obj ::spec {::name (spec-name obj)
                       ::form (->> obj
                                :inputFields
                                input-values-spec)})))

(defmethod -assoc-specs :INPUT_VALUE [obj]
  (assoc obj ::spec {::name (spec-name obj)
                     ::form (-> obj :type type-ref-spec)}))

(defmethod -assoc-specs :INTERFACE [obj]
  (let [obj (assoc-child-specs obj :fields)]
    (assoc obj ::spec {::name (spec-name obj)
                       ::form (->> obj
                                :possibleTypes
                                (mapcat (juxt :name spec-name))
                                (cons `s/or)
                                nonconforming)})))

(defmethod -assoc-specs :OBJECT [obj]
  (let [obj (-> obj
              (update :fields conj {:__typename :__Field
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
              (assoc-child-specs :fields))]
    (assoc obj ::spec {::name (spec-name obj)
                       ::form (->> obj
                                :fields
                                (mapv (comp ::name ::spec))
                                (list :opt-un)
                                (cons `s/keys))})))

(defmethod -assoc-specs :SCALAR [obj]
  (assoc obj ::spec {::name (spec-name obj)
                     ::form (case (:name obj)
                              :Boolean `boolean?
                              :Float `float?
                              :ID `string?
                              :Int `integer?
                              :String `string?
                              `any?)}))

(defmethod -assoc-specs :UNION  [obj]
  (assoc obj ::spec {::name (spec-name obj)
                     ::form (->> obj
                              :possibleTypes
                              (mapcat (juxt :name spec-name))
                              (cons `s/or)
                              nonconforming)}))

(defn ^:private topo-sort-specs
  [spec-entries]
  (let [spec-map (into (sorted-map) spec-entries)
        sort-aliases (fn sort-aliases [sorted unsorted]
                       (let [no-deps (->> unsorted
                                       (remove (fn [[k v]]
                                                 (contains? unsorted v)))
                                       (into (sorted-map)))
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

(s/def ::spec-name qualified-keyword?)

(defn ^:private serializable?
  [form]
  (try
    (= (-> form pr-str read-string) form)
    (catch #?(:clj Throwable :cljs :default) e
      false)))

(s/def ::spec-form (s/nonconforming
                     (s/or
                       :keyword qualified-keyword?
                       :seq (s/and seq? serializable?)
                       :set (s/and set? serializable?)
                       :symbol symbol?)))

(s/def ::spec-entry (s/tuple ::spec-name ::spec-form))

(defn compile
  "Takes a GraphQL introspection query response and an optional transducer.
  The transducer will receive map entries where the keys are qualified keywords and the values are spec forms.
  Returns a topologically sorted vector of `s/def` forms."
  ([introspection-response]
   (compile introspection-response nil))
  ([introspection-response xform]
   (->> introspection-response
     (conform! ::introspection/response)
     assoc-specs
     (tree-seq coll? seq)
     (transduce
       (comp
         (filter map?)
         (map (juxt ::name ::form))
         (filter (partial every? some?))
         (map map-entry)
         (or xform (map identity))
         (map (partial conform! ::spec-entry)))
       (completing
         (fn [m [k v]]
           (when (contains? m k)
             (->
               (str "Duplicate spec name: " k)
               (ex-info {:name k :form k})
               throw))
           (assoc! m k v))
         persistent!)
       (transient {}))
     topo-sort-specs
     (mapv
       (fn [[k v]]
         `(s/def ~k ~v))))))

(defmacro def-specs
  "Same as `compile` except arguments are `eval`ed and it's a macro."
  ([introspection-response]
   `(def-specs ~introspection-response nil))
  ([introspection-response xform]
   (let [resp (eval introspection-response)
         xf (eval xform)]
     (compile resp xf))))

(defn alias
  "Takes a function (or map) that will receive a spec name and should return a spec name, a collection of spec names, or `nil`.
  Returns a transducer that will add aliased spec entries."
  [alias-fn]
  {:pre [(ifn? alias-fn)]}
  (mapcat (fn [[k _ :as entry]]
            (->> k
              alias-fn
              list
              flatten
              (map (comp
                     #(map-entry % k)
                     (partial conform! ::spec-name)))
              (cons entry)))))

(defn extend
  "Takes a function (or map) that will receive a spec name and should return a spec form or `nil`.
  Returns a transducer that combines specs with `s/and`."
  [extend-fn]
  {:pre [(ifn? extend-fn)]}
  (map (fn [[k v :as entry]]
         (if-some [ext (extend-fn k)]
           (map-entry k `(s/and ~v ~ext))
           entry))))

(defn prefix
  "Takes a function (or map) that will receive a spec name prefixed with `*ns*` and should return a new prefx or `nil`.
  Returns a transducer that replaces keywords prefixed with `*ns*`."
  [prefix-fn]
  {:pre [(ifn? prefix-fn)]}
  (let [ns (-> *ns* ns-name name)]
    (comp
      (map (partial
             walk/postwalk
             (fn [x]
               (if-let [prefix (and
                                 (keyword? x)
                                 (namespace x)
                                 (str/starts-with? (namespace x) ns)
                                 (prefix-fn x))]
                 (do
                   (conform! simple-ident? prefix)
                   (keyword
                     (str/replace-first (namespace x) ns (name prefix))
                     (name x)))
                 x))))
      (map map-entry))))

(defn postfix-args
  "Takes a function (or map) that will receive a spec name postfixed with `%` and should return a new postfix or `nil`.
  Returns a transducer that replaces keywords postfixed with `%`."
  [postfix-args-fn]
  {:pre [(ifn? postfix-args-fn)]}
  (comp
    (map (partial
           walk/postwalk
           (fn [x]
             (if-let [postfix (and
                                (keyword? x)
                                (str/ends-with? (name x) "%")
                                (postfix-args-fn x))]
               (do
                 (conform! simple-ident? postfix)
                 (keyword
                   (namespace x)
                   (str/replace-first (name x) #"%$" (name postfix))))
               x))))
    (map map-entry)))
