(ns paren.serene.compiler
  (:refer-clojure :exclude [compile])
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [paren.serene.introspection :as introspection])
  #?(:cljs (:require-macros
            [paren.serene.compiler])))

(defn ^:private namespace?
  [x]
  (instance?
    #?(:clj clojure.lang.Namespace
       :cljs cljs.core.Namespace)
    x))

(def ^:private tmp-ns
  "paren.serene.compiler.tmp")

(defn ^:private tmp-spec-name?
  [k]
  (and
    (keyword? k)
    (= (namespace k) tmp-ns)))

(defn ^:private tmp-spec-name
  [k-or-obj]
  (let [ks (if (keyword? k-or-obj)
             [k-or-obj]
             (loop [obj k-or-obj
                    ks ()]
               (if obj
                 (recur
                   (::parent obj)
                   (cons (:name obj) ks))
                 ks)))]
    (->> ks
      (mapcat (juxt namespace name))
      (remove nil?)
      (str/join ".")
      (keyword tmp-ns))))

(defn ^:private raw-spec-name
  [k]
  {:pre [(tmp-spec-name? k)]}
  (let [segs (str/split (name k) #"\.")
        ns  (when-let [ns-segs (butlast segs)]
              (str/join "." ns-segs))
        n (last segs)]
    (keyword ns n)))

(defn ^:private prefix-spec-name
  [prefix k]
  {:pre [(tmp-spec-name? k)]}
  (let [prefix (name (if (namespace? prefix)
                       (ns-name prefix)
                       prefix))
        segs (str/split (name k) #"\.")
        ns (->> (butlast segs)
             (cons prefix)
             (str/join "."))
        n (last segs)]
    (keyword ns n)))

(defn ^:private maybe-prefix-spec-name
  [prefix k]
  (if (tmp-spec-name? k)
    (prefix-spec-name prefix k)
    k))

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
    ((fn type-ref->spec [{:keys [non-null? kind name ofType]}]
       (let [spec (if (= kind :LIST)
                    `(s/coll-of ~(type-ref->spec ofType)
                       :kind sequential?)
                    (tmp-spec-name name))]
         (if non-null?
           spec
           `(s/or ; Avoid using `s/nilable` due to https://dev.clojure.org/jira/browse/CLJS-2940
              :null nil?
              :non-null ~spec)))))))

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
    (assoc obj ::spec {::name (tmp-spec-name obj)
                       ::form (->> obj
                                :enumValues
                                (mapcat (juxt :name (comp ::name ::spec)))
                                (cons `s/or))})))

(defmethod -assoc-specs :ENUM_VALUE [obj]
  (assoc obj ::spec {::name (tmp-spec-name obj)
                     ::form #{(-> obj :name name)}}))

(defmethod -assoc-specs :FIELD [obj]
  (let [obj (assoc-child-specs obj :args)]
    (-> obj
      (assoc ::spec {::name (tmp-spec-name obj)
                     ::form (if (= (:name obj) :__typename)
                              #{(-> obj ::parent :name name)}
                                (-> obj :type type-ref-spec))})
      (assoc ::args-spec {::name (tmp-spec-name {::parent obj :name :%})
                          ::form (-> obj :args input-values-spec)}))))

(defmethod -assoc-specs :INPUT_OBJECT [obj]
  (let [obj (assoc-child-specs obj :inputFields)]
    (assoc obj ::spec {::name (tmp-spec-name obj)
                       ::form (->> obj
                                :inputFields
                                input-values-spec)})))

(defmethod -assoc-specs :INPUT_VALUE [obj]
  (assoc obj ::spec {::name (tmp-spec-name obj)
                     ::form (-> obj :type type-ref-spec)}))

(defmethod -assoc-specs :INTERFACE [obj]
  (let [obj (assoc-child-specs obj :fields)]
    (assoc obj ::spec {::name (tmp-spec-name obj)
                       ::form (->> obj
                                :possibleTypes
                                (mapcat (juxt :name tmp-spec-name))
                                (cons `s/or)
                                doall)})))

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
    (assoc obj ::spec {::name (tmp-spec-name obj)
                       ::form (->> obj
                                :fields
                                (mapv (comp ::name ::spec))
                                (list :opt-un)
                                (cons `s/keys))})))

(defmethod -assoc-specs :SCALAR [obj]
  (assoc obj ::spec {::name (tmp-spec-name obj)
                     ::form (case (:name obj)
                              :Boolean `boolean?
                              :Float `float?
                              :ID `string?
                              :Int `integer?
                              :String `string?
                              `any?)}))

(defmethod -assoc-specs :UNION  [obj]
  (assoc obj ::spec {::name (tmp-spec-name obj)
                     ::form (->> obj
                              :possibleTypes
                              (mapcat (juxt :name tmp-spec-name))
                              (cons `s/or)
                              doall)}))

(defn ^:private collect-specs [resp]
  (->> resp
    (tree-seq coll? seq)
    (filter map?)
    (map (juxt ::name ::form))
    (filter (partial every? some?))
    (reduce
      (fn [m [k v]]
        (when (contains? m k)
          (throw (ex-info
                   (str "Duplicate schema spec name: " (raw-spec-name k))
                   {:spec (raw-spec-name k)})))
        (assoc m k v))
      {})))

(defn ^:private nonconform-specs [tmp-spec-map]
  (->> tmp-spec-map
    (map (fn [[k v]]
           [k
            (if (seq? v)
              `(s/nonconforming ~v)
              v)]))
    (into {})))

(defn ^:private and-specs [raw-specs tmp-spec-map]
  (reduce
    (fn [spec-map [k v]]
      (let [tmp-k (tmp-spec-name k)]
        (when-not (contains? tmp-spec-map tmp-k)
          (throw (ex-info (str "Invalid spec name: " k) {:spec k})))
        (update spec-map tmp-k (fn [spec]
                                 `(s/and ~spec ~v)))))
    tmp-spec-map
    raw-specs))

(defn ^:private alias-specs [alias-fn tmp-spec-map]
  (let [alias-fn (fn alias-wrapper [kw]
                   (let [ret (alias-fn kw)]
                     (cond
                       (nil? ret) #{}
                       (keyword? ret) #{ret}
                       (set? ret) ret)))]
    (reduce
      (fn [spec-map tmp-k]
        (let [raw-k (raw-spec-name tmp-k)
              aliases (alias-fn raw-k)]
          (reduce
            (fn [spec-map alias]
              (when (contains? spec-map alias)
                (throw (ex-info (str "Alias not unique: " alias) {:alias alias})))
              (assoc spec-map alias tmp-k))
            spec-map
            aliases)))
      tmp-spec-map
      (keys tmp-spec-map))))

(defn ^:private prefix-specs [prefix tmp-spec-map]
  (->> tmp-spec-map
    (map (fn [[k v]]
           [k (walk/postwalk (partial maybe-prefix-spec-name prefix) v)]))
    (into {})
    (reduce
      (fn [m [k v]]
        (let [k (maybe-prefix-spec-name prefix k)]
          (when (contains? m k)
            (throw (ex-info (str "Duplicate prefixed spec name: " k) {:spec k})))
          (assoc m k v)))
      {})))

(defn ^:private topo-sort-specs [prefix-spec-map]
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
        aliases (->> prefix-spec-map
                  (filter #(-> % val keyword?))
                  (into {}))]
    (concat
      (apply dissoc prefix-spec-map (keys aliases))
      (sort-aliases [] aliases))))

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

(s/def ::prefix (s/or
                  :namespace namespace?
                  :ident simple-ident?))

(s/def ::specs (s/map-of keyword? (s/or
                                    :keyword qualified-keyword?
                                    :symbol symbol?
                                    :seq seq?)))

(s/def ::config (s/keys
                  :req-un [::alias ::prefix ::specs]))

(defn compile
  [introspection-response {:as config
                           :keys [alias prefix specs]}]
  {:pre [(s/valid? ::introspection/response introspection-response)
         (s/valid? ::config config)]}
  (let [compiled-specs (->> introspection-response
                         assoc-specs
                         collect-specs
                         nonconform-specs
                         (and-specs specs)
                         (alias-specs alias)
                         (prefix-specs prefix)
                         topo-sort-specs)]
    `(let [undef-specs# (fn undef-specs# []
                          ~(mapv
                             (fn [[k v]]
                               `(s/def ~k nil))
                             compiled-specs))
           def-specs# (fn def-specs# []
                        ~(mapv
                           (fn [[k v]]
                             `(s/def ~k ~v))
                           compiled-specs))]
       {:def-specs def-specs#
        :undef-specs undef-specs#
        :response ~introspection-response})))
